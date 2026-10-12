package com.example.data.sync.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.SyncManager
import com.example.data.sync.syncDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.1 -- the SyncManager auth block: connect (D7 "also at connect"), token storage (PX-09),
 * disconnect key set + revoke (PX-10), and TokenMigration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFlowTest {

    @get:Rule val config = DropboxConfigRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ds: SyncDataStore
    private lateinit var db: AppDatabase
    private lateinit var manager: SyncManager
    private lateinit var tokens: InMemorySecureTokenStore
    private lateinit var endpoint: FakeTokenEndpoint

    private val scratch = "dbid:scratch-1"
    private val tokenBody = """{"access_token":"sl.AT-secret-1","refresh_token":"RT-secret-1","expires_in":14400,"account_id":"$scratch","token_type":"bearer"}"""

    @Before fun setUp() = runBlocking {
        AuthTestSupport.resetSyncPrefs(context)
        ds = SyncDataStore(context)
        val (d, repo) = AuthTestSupport.newRepository(context, ds)
        db = d
        tokens = InMemorySecureTokenStore()
        endpoint = FakeTokenEndpoint()
        InterlockPolicy.unlocked = false
        InterlockPolicy.allowlist = setOf(scratch)
        manager = SyncManager(context, repo, ds, null, tokens, DropboxOAuth(http = endpoint.client))
    }

    @After fun tearDown() {
        InterlockPolicy.resetToBuildConfig()
        db.close()
    }

    private suspend fun startConnect(): String {
        val url = manager.getAuthorizationUri()
        assertTrue(url.startsWith("https://www.dropbox.com/oauth2/authorize?"))
        return ds.getOAuthVerifier()!!
    }

    @Test fun connect_allowlisted_linksAndStoresTokensOnlyInSecureStore() = runBlocking {
        val verifier = startConnect()
        assertEquals(86, verifier.length)
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        assertTrue(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals("RT-secret-1", tokens.read()!!.refreshToken)
        assertEquals(scratch, tokens.read()!!.accountId)
        assertEquals(scratch, ds.getAccountId())
        assertEquals(AuthState.LINKED, ds.authState.first())
        assertTrue(ds.isDropboxEnabled.first())
        assertNull(ds.getOAuthVerifier())
        val dump = AuthTestSupport.dataStoreDump(context)
        assertFalse("DataStore holds a refresh token", dump.contains("RT-secret-1"))
        assertFalse("DataStore holds an access token", dump.contains("AT-secret-1"))
        assertFalse(dump.contains("dropbox_refresh_token"))
    }

    @Test fun connect_sendsTheStoredVerifier() = runBlocking {
        val verifier = startConnect()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        manager.handleRedirectUri("questa-dev://oauth?code=abc")
        val form = endpoint.seen.single().body as okhttp3.FormBody
        val map = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(verifier, map["code_verifier"])
        assertEquals("abc", map["code"])
        assertEquals(DropboxConfigRule.TEST_APP_KEY, map["client_id"])
    }

    @Test fun connect_refusedAccount_savesNothing_andRevokesTheNewGrant() = runBlocking {
        startConnect()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody.replace(scratch, "dbid:real-other")))
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNull(tokens.read())
        assertNull(ds.getAccountId())
        assertEquals(AuthState.NOT_LINKED, ds.authState.first())
        assertNull(ds.getOAuthVerifier())
        assertEquals("Bearer sl.AT-secret-1", endpoint.revokeCalls().single().header("Authorization"))
    }

    @Test fun connect_tokenResponseWithoutAccountId_isRefused() = runBlocking {
        startConnect()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, """{"access_token":"a","refresh_token":"r","uid":"1"}"""))
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNull(tokens.read())
    }

    @Test fun connect_wrongSchemeOrNoCode() = runBlocking {
        startConnect()
        assertFalse(manager.handleRedirectUri("questa://oauth?code=abc")) // release scheme on a debug build
        assertFalse(manager.handleRedirectUri("questa-dev://other?code=abc"))
        assertTrue("not an OAuth return: verifier kept", ds.getOAuthVerifier() != null)
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?error=access_denied"))
        assertNull("Cancel on the consent screen clears the verifier", ds.getOAuthVerifier())
        assertEquals(0, endpoint.seen.size)
    }

    @Test fun connect_exchangeFails_clearsVerifier() = runBlocking {
        startConnect()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(400, """{"error":"invalid_grant"}"""))
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNull(ds.getOAuthVerifier())
        assertNull(tokens.read())
    }

    @Test fun disconnect_optiKeepSet_plusBaseAndMarker_andRevokes() = runBlocking {
        startConnect()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        assertTrue(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        ds.saveLocalDeviceId("dev-keep")
        ds.saveSyncMetadata("rev-base", "{\"base\":1}")
        ds.saveEvtFileRevs(mapOf("f.json" to "r1"))
        ds.saveEvtLastUploadTs(123L)
        // An arbitrary value standing in for the app key the data was written under: these tests
        // only need a marker that survives, not a real Dropbox app key (never committed).
        AuthTestSupport.setMarker(context, "legacy-app-key-under-test")
        context.syncDataStore.edit {
            it[SyncKeys.EVT_FILE_COUNTS] = "{\"f.json\":{}}"
            it[SyncKeys.EVT_PUSH_BLOCKED] = true
            it[SyncKeys.EVT_BAD_REVS] = "{}"
        }

        assertTrue(manager.disconnect())

        assertNull(tokens.read())
        assertEquals(AuthState.NOT_LINKED, ds.authState.first())
        assertFalse(ds.isDropboxEnabled.first())
        assertNull(ds.getAccountId())
        val prefs = context.syncDataStore.data.first()
        // kept
        assertEquals("dev-keep", ds.getOrCreateDeviceId())
        assertEquals("rev-base", ds.dropboxLastRev.first())
        assertEquals("{\"base\":1}", ds.dropboxBaseSnapshot.first())
        assertEquals("legacy-app-key-under-test", AuthTestSupport.marker(context))
        assertEquals("{\"f.json\":{}}", prefs[SyncKeys.EVT_FILE_COUNTS])
        assertEquals(true, prefs[SyncKeys.EVT_PUSH_BLOCKED])
        // reset to Opti defaults
        assertNull(prefs[SyncKeys.EVT_FILE_REVS])
        assertNull(prefs[SyncKeys.EVT_LAST_UPLOAD_TS])
        assertNull(prefs[SyncKeys.EVT_BAD_REVS])
        // revoke used the still-fresh access token
        assertEquals("Bearer sl.AT-secret-1", endpoint.revokeCalls().single().header("Authorization"))
    }

    @Test fun disconnect_offline_stillDisconnectsLocally() = runBlocking {
        tokens.write(TokenSet("at", "rt", 0L, scratch))
        ds.markLinked(scratch)
        endpoint.revokeCode = 500
        assertFalse(manager.disconnect())
        assertNull(tokens.read())
        assertEquals(AuthState.NOT_LINKED, ds.authState.first())
    }

    @Test fun tokenMigration_movesLegacyTokens_keepsMarkerAndEnabled() = runBlocking {
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        AuthTestSupport.setMarker(context, DropboxConfig.LEGACY_SENTINEL)
        TokenMigration.moveFromDataStore(ds, tokens)
        val moved = tokens.read()!!
        assertEquals("legacy-rt", moved.refreshToken)
        assertEquals("legacy-at", moved.accessToken)
        assertNull("legacy code never stored an account id", moved.accountId)
        assertNull(ds.dropboxRefreshToken.first())
        assertNull(ds.dropboxAccessToken.first())
        assertTrue(ds.isDropboxEnabled.first())
        assertEquals(DropboxConfig.LEGACY_SENTINEL, AuthTestSupport.marker(context))
        assertFalse(AuthTestSupport.dataStoreDump(context).contains("legacy-rt"))
        // idempotent
        TokenMigration.moveFromDataStore(ds, tokens)
        assertEquals(moved, tokens.read())
    }

    @Test fun tokenMigration_neverOverwritesNewerSecureTokens() = runBlocking {
        val newer = TokenSet("new-at", "new-rt", 5L, scratch)
        tokens.write(newer)
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        TokenMigration.moveFromDataStore(ds, tokens)
        assertEquals(newer, tokens.read())
        assertNull(ds.dropboxRefreshToken.first())
    }

    @Test fun secureTokenStoreClear_neverTouchesMarker() = runBlocking {
        AuthTestSupport.setMarker(context, "legacy-app-key-under-test")
        tokens.write(TokenSet("a", "r", 1L, scratch))
        tokens.clear()
        manager.disconnect()
        assertEquals("legacy-app-key-under-test", AuthTestSupport.marker(context))
    }
}
