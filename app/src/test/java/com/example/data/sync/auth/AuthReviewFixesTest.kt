package com.example.data.sync.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.sync.DropboxService
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.1 opus review (24_review_wp81.md) items 1-3 and the Pkce redaction:
 * 1. the D7 interlock runs before the pre-round token refresh;
 * 2. the legacy plaintext token fallback is never used while ACCOUNT_ID is set, and a connect
 *    clears the legacy keys;
 * 3. a token minted under the legacy app key is revoked with that key and is reported as
 *    RECONNECT_REQUIRED without any network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthReviewFixesTest {

    @get:Rule val config = DropboxConfigRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ds: SyncDataStore
    private lateinit var db: AppDatabase
    private lateinit var manager: SyncManager
    private lateinit var tokens: InMemorySecureTokenStore
    private lateinit var endpoint: FakeTokenEndpoint
    private lateinit var fake: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient

    private val scratch = FakeDropboxServer.SCRATCH_ACCOUNT_ID
    private val refreshOk = """{"access_token":"sl.AT-new","expires_in":14400,"token_type":"bearer"}"""

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
        fake = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fake.asClient()
    }

    @After fun tearDown() {
        DropboxService.client = originalClient
        InterlockPolicy.resetToBuildConfig()
        db.close()
    }

    private fun totalRequests() = endpoint.seen.size + fake.requests.size

    // ---- item 1: interlock before the pre-round refresh ------------------------------------

    @Test fun legacyPlaintextToken_noAccountId_expired_zeroRequests() = runBlocking {
        ds.saveTokens("legacy-at", "legacy-rt", -100L) // expired, enabled=true, no ACCOUNT_ID
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        val r = manager.syncNow()
        assertEquals(SyncManager.SyncResult.Error(SyncManager.INTERLOCK_REFUSED_MESSAGE), r)
        assertEquals("no refresh, no file call", 0, totalRequests())
    }

    @Test fun migratedToken_noAccountId_expired_zeroRequests() = runBlocking {
        ds.setDropboxEnabled(true)
        tokens.write(TokenSet("legacy-at", "legacy-rt", 0L, null, DropboxConfig.LEGACY_SENTINEL))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        assertEquals(SyncManager.SyncResult.Error(SyncManager.INTERLOCK_REFUSED_MESSAGE), manager.syncNow())
        assertEquals(0, totalRequests())
    }

    @Test fun nonAllowlistedLinkedAccount_expired_zeroRequests() = runBlocking {
        ds.markLinked("dbid:real-other")
        tokens.write(TokenSet("at", "rt", 0L, "dbid:real-other"))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        assertEquals(SyncManager.SyncResult.Error(SyncManager.INTERLOCK_REFUSED_MESSAGE), manager.syncNow())
        assertEquals(0, totalRequests())
    }

    // ---- item 2: no legacy fallback while ACCOUNT_ID is set --------------------------------

    @Test fun accountIdSet_secureStoreEmpty_legacyKeysIgnored_zeroRequests() = runBlocking {
        // SecureTokenStore.read() returns null on a decrypt error; the old plaintext tokens
        // (maybe another account's) must not be used in its place.
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        ds.markLinked(scratch)
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        val r = manager.syncNow()
        assertTrue("round must fail, got $r", r is SyncManager.SyncResult.Error)
        assertEquals(0, totalRequests())
    }

    @Test fun accountIdSet_secureStoreEmpty_expiredLegacyKeys_neverRefreshed() = runBlocking {
        ds.saveTokens("legacy-at", "legacy-rt", -100L)
        ds.markLinked(scratch)
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        assertTrue(manager.syncNow() is SyncManager.SyncResult.Error)
        assertEquals(0, endpoint.tokenCalls())
        assertEquals(0, fake.requests.size)
    }

    @Test fun tokenOfAnotherAccount_isNotUsed() = runBlocking {
        ds.markLinked(scratch)
        tokens.write(TokenSet("at-other", "rt-other", Long.MAX_VALUE, "dbid:real-other"))
        assertTrue(manager.syncNow() is SyncManager.SyncResult.Error)
        assertEquals(0, totalRequests())
    }

    @Test fun successfulConnect_clearsLegacyTokenKeys() = runBlocking {
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        manager.getAuthorizationUri()
        endpoint.tokenAnswers.addLast(
            FakeTokenEndpoint.Canned(200, """{"access_token":"sl.AT-1","refresh_token":"RT-1","expires_in":14400,"account_id":"$scratch"}""")
        )
        assertTrue(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNull(ds.dropboxAccessToken.first())
        assertNull(ds.dropboxRefreshToken.first())
        assertFalse(AuthTestSupport.dataStoreDump(context).contains("legacy-rt"))
        assertEquals(DropboxConfigRule.TEST_APP_KEY, tokens.read()!!.appKey)
    }

    @Test fun refusedConnect_keepsLegacyTokenKeys() = runBlocking {
        // Only a successful connect replaces them; a refused one saves nothing at all.
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        manager.getAuthorizationUri()
        endpoint.tokenAnswers.addLast(
            FakeTokenEndpoint.Canned(200, """{"access_token":"sl.AT-1","refresh_token":"RT-1","account_id":"dbid:real-other"}""")
        )
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals("legacy-rt", ds.dropboxRefreshToken.first())
    }

    // ---- item 3: legacy app key ------------------------------------------------------------

    /**
     * A key guaranteed to differ from [DropboxConfigRule.TEST_APP_KEY], which is what "this token was
     * minted under the previous Dropbox app" means. A literal would be wrong: the real keys are
     * supplied at build time and are blank in a clone with no local.properties, which would make
     * LEGACY == APP_KEY and collapse the very distinction these tests exist to check.
     */
    private val legacyKey = "test-legacy-app-key-never-equals-APP_KEY"

    @Test fun legacyKeyToken_isReconnectRequired_withoutNetwork() = runBlocking {
        InterlockPolicy.unlocked = true // get past D7 so the app-key rule is what stops the round
        ds.setDropboxEnabled(true)
        tokens.write(TokenSet("legacy-at", "legacy-rt", 0L, null, legacyKey))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        assertTrue(manager.syncNow() is SyncManager.SyncResult.Error)
        assertEquals(AuthState.RECONNECT_REQUIRED, ds.authState.first())
        assertFalse(ds.isDropboxEnabled.first())
        assertEquals(0, totalRequests())
    }

    @Test fun legacyKeyToken_freshAccessToken_stillReconnectRequired() = runBlocking {
        // A legacy-key access token points at the old app's Apps/ folder (D16): never use it.
        InterlockPolicy.unlocked = true
        ds.setDropboxEnabled(true)
        tokens.write(TokenSet("legacy-at", "legacy-rt", Long.MAX_VALUE, null, legacyKey))
        assertTrue(manager.syncNow() is SyncManager.SyncResult.Error)
        assertEquals(AuthState.RECONNECT_REQUIRED, ds.authState.first())
        assertEquals(0, totalRequests())
    }

    @Test fun legacyPlaintextFallback_unlocked_isReconnectRequired_withoutNetwork() = runBlocking {
        InterlockPolicy.unlocked = true
        ds.saveTokens("legacy-at", "legacy-rt", -100L)
        assertTrue(manager.syncNow() is SyncManager.SyncResult.Error)
        assertEquals(AuthState.RECONNECT_REQUIRED, ds.authState.first())
        assertEquals(0, totalRequests())
    }

    @Test fun disconnect_legacyKeyToken_refreshesWithTheLegacyKey_thenRevokes() = runBlocking {
        ds.setDropboxEnabled(true)
        tokens.write(TokenSet("legacy-at", "legacy-rt", 0L, null, legacyKey))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        assertTrue(manager.disconnect())
        val form = endpoint.seen.first { it.url.toString() == DropboxOAuth.TOKEN_URL }.body as FormBody
        val map = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(legacyKey, map["client_id"])
        assertEquals("Bearer sl.AT-new", endpoint.revokeCalls().single().header("Authorization"))
    }

    @Test fun oauthRefresh_usesTheGivenAppKey_andCarriesItOnTheResult() = runBlocking {
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, refreshOk))
        val r = DropboxOAuth(http = endpoint.client).refresh("rt", legacyKey) as RefreshResult.Ok
        val form = endpoint.seen.single().body as FormBody
        val map = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(legacyKey, map["client_id"])
        assertEquals(legacyKey, r.tokens.appKey)
    }

    @Test fun tokenMigration_marksTheLegacyAppKey() = runBlocking {
        ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        TokenMigration.moveFromDataStore(ds, tokens)
        assertEquals(DropboxConfig.LEGACY_SENTINEL, tokens.read()!!.appKey)
    }

    @Test fun tokenSet_defaultAppKey_isThePwaKey() {
        assertEquals(DropboxConfigRule.TEST_APP_KEY, TokenSet("a", "r", 1L, scratch).appKey)
    }

    @Test fun secureTokenStore_roundTripsAppKey_andDefaultsOldBlobs() {
        val legacy = TokenSet("a", "r", 1L, null, DropboxConfig.LEGACY_SENTINEL)
        assertEquals(legacy, AndroidKeystoreTokenStore.decode(AndroidKeystoreTokenStore.encode(legacy)))
        val old = AndroidKeystoreTokenStore.decode("""{"accessToken":"a","refreshToken":"r","expiresAtMs":1,"accountId":"$scratch"}""")!!
        assertEquals(DropboxConfigRule.TEST_APP_KEY, old.appKey)
    }

    // ---- guess 17: no automatic sync right after connect in the debug build ----------------

    @Test fun postConnectAutoSync_debugSkips_releaseKeeps() {
        assertFalse(DropboxConfig.autoSyncAfterConnect(debug = true))
        assertTrue(DropboxConfig.autoSyncAfterConnect(debug = false))
        // This JVM run is the debug variant: the dev app must not merge into the real state.json.
        assertFalse(DropboxConfig.autoSyncAfterConnect())
    }

    @Test fun mainActivity_gatesThePostConnectSync() {
        val src = repoFile("app/src/main/java/com/example/MainActivity.kt").readText()
        assertTrue(src.contains("DropboxConfig.autoSyncAfterConnect()"))
    }

    // ---- integrator patch 1: per-build-type OAuth scheme ----------------------------------

    @Test fun manifest_usesTheOauthSchemePlaceholder_setForBothBuildTypes() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:scheme=\"\${oauthScheme}\""))
        assertFalse(manifest.contains("android:scheme=\"questa\""))
        val gradle = repoFile("app/build.gradle.kts").readText()
        assertTrue(gradle.contains("manifestPlaceholders[\"oauthScheme\"] = \"questa\""))
        assertTrue(gradle.contains("manifestPlaceholders[\"oauthScheme\"] = \"questa-dev\""))
    }

    private fun repoFile(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("$rel not found")
    }

    // ---- minor: Pkce redaction -------------------------------------------------------------

    @Test fun pkceToString_neverPrintsTheVerifier() {
        val p = DropboxOAuth().newPkce()
        val s = p.toString()
        assertFalse(s.contains(p.verifier))
        assertTrue(s.contains("<redacted>"))
    }
}
