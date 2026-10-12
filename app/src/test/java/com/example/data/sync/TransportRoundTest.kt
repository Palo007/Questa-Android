package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.FakeTokenEndpoint
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.auth.TokenSet
import com.example.data.sync.SyncManager.SyncResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import com.example.data.sync.auth.DropboxConfigRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.1 acceptance: "6 FakeDropboxServer sequences give 0 uploads with rev=null" (SY-05:
 * a transient/401/restricted download error must never be read as "empty remote"), plus
 * PX-07 refresh-on-401 with the FRESH token and invalid_grant -> RECONNECT_REQUIRED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransportRoundTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ds: SyncDataStore
    private lateinit var db: AppDatabase
    private lateinit var manager: SyncManager
    private lateinit var tokens: InMemorySecureTokenStore
    private lateinit var endpoint: FakeTokenEndpoint
    private lateinit var fake: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient

    // Pins the runtime app key so seeded bases are valid.
    @get:Rule val config = DropboxConfigRule()

    private val sum = { s: String -> "{\"error_summary\":\"$s\",\"error\":{}}" }

    @Before fun setUp() = runBlocking {
        AuthTestSupport.resetSyncPrefs(context)
        ds = SyncDataStore(context)
        val (d, repo) = AuthTestSupport.newRepository(context, ds)
        db = d
        tokens = InMemorySecureTokenStore()
        endpoint = FakeTokenEndpoint()
        manager = SyncManager(context, repo, ds, null, tokens, DropboxOAuth(http = endpoint.client), sleeper = {})
        fake = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fake.asClient()
        FakeDropboxServer.linkScratchAccount(ds)
        ds.markLinked(FakeDropboxServer.SCRATCH_ACCOUNT_ID)
        tokens.write(TokenSet("at-old", "rt-1", Long.MAX_VALUE, FakeDropboxServer.SCRATCH_ACCOUNT_ID))
    }

    @After fun tearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        db.close()
    }

    private fun assertNoAddUpload(result: SyncResult) {
        assertTrue("round must fail, got $result", result is SyncResult.Error)
        assertEquals("uploads with rev=null", 0, fake.stateUploadsWithNullRev())
        assertEquals("no state upload at all", 0, fake.stateUploads().size)
    }

    // ---- the six "never an empty remote" sequences ----

    @Test fun seq1_download409Restricted() = runBlocking {
        fake.scriptNext("/2/files/download", 409, sum("path/restricted_content/.."))
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq2_download500() = runBlocking {
        // WP-8.6: 5xx is retried on the ladder (sync.js:2484); 4 answers = the attempt + 3 retries.
        repeat(4) { fake.scriptNext("/2/files/download", 500, "Internal Server Error") }
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq3_download429WithRetryAfter() = runBlocking {
        repeat(4) { fake.scriptNext("/2/files/download", 429, sum("too_many_requests/.."), mapOf("Retry-After" to "3")) }
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq4_download401_thenRefreshFails() = runBlocking {
        fake.scriptNext("/2/files/download", 401, sum("expired_access_token/.."))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(500, "{}"))
        assertNoAddUpload(manager.syncNow())
        assertEquals(1, fake.requests.count { it.endpoint == "/2/files/download" })
    }

    @Test fun seq5_corruptBody() = runBlocking {
        fake.scriptNext("/2/files/download", 200, "<html>not json</html>")
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq6_stateNull() = runBlocking {
        fake.scriptNext("/2/files/download", 200, """{"schema":1,"savedAt":1,"state":null}""")
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq7_schemaOnly() = runBlocking {
        fake.scriptNext("/2/files/download", 200, """{"schema":1}""")
        assertNoAddUpload(manager.syncNow())
    }

    @Test fun seq8_download401_twice_evenAfterRefresh() = runBlocking {
        fake.scriptNext("/2/files/download", 401, "{}")
        fake.scriptNext("/2/files/download", 401, "{}")
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, """{"access_token":"at-new","expires_in":14400}"""))
        assertNoAddUpload(manager.syncNow())
        assertEquals("exactly one retry", 2, fake.requests.count { it.endpoint == "/2/files/download" })
    }

    // ---- legitimate empty remote ----

    @Test fun notFound_isTheOnlyEmptyRemote() = runBlocking {
        val r = manager.syncNow() // fake has no state.json -> 409 path/not_found
        assertEquals(SyncResult.Success, r)
        assertEquals(1, fake.stateUploadsWithNullRev())
    }

    // ---- PX-07 ----

    @Test fun refreshOn401_retriesWithTheFreshToken_andStoresIt() = runBlocking {
        fake.seedState("""{"schema":1,"savedAt":1,"deviceId":"other","state":{}}""")
        AuthTestSupport.seedValidBase(db, "rev-base", "{}") // WP-8.6 first-connect gate
        fake.scriptNext("/2/files/download", 401, sum("expired_access_token/.."))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, """{"access_token":"at-new","expires_in":14400}"""))
        manager.syncNow()
        val downloads = fake.requests.filter { it.endpoint == "/2/files/download" }
        assertEquals("at-old", downloads[0].bearer)
        assertEquals("at-new", downloads[1].bearer)
        assertEquals("at-new", tokens.read()!!.accessToken)
        assertEquals("refresh keeps the linked account id", FakeDropboxServer.SCRATCH_ACCOUNT_ID, tokens.read()!!.accountId)
        assertEquals(0, fake.stateUploadsWithNullRev())
        // later calls in the same round use the fresh token directly (one refresh per round)
        assertTrue(fake.requests.drop(2).all { it.bearer == "at-new" })
        assertEquals(1, endpoint.tokenCalls())
    }

    @Test fun invalidGrant_on401_marksReconnectRequired() = runBlocking {
        fake.scriptNext("/2/files/download", 401, "{}")
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(400, """{"error":"invalid_grant","error_description":"revoked"}"""))
        assertNoAddUpload(manager.syncNow())
        assertEquals(AuthState.RECONNECT_REQUIRED, ds.authState.first())
        assertFalse(ds.isDropboxEnabled.first())
        assertTrue(manager.syncNow() is SyncResult.Error)
        assertEquals("disabled: no further requests", 1, fake.requests.size)
    }

    @Test fun expiredAccessToken_refreshesBeforeTheFirstCall() = runBlocking {
        tokens.write(TokenSet("at-expired", "rt-1", 0L, FakeDropboxServer.SCRATCH_ACCOUNT_ID))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, """{"access_token":"at-new","expires_in":14400}"""))
        manager.syncNow()
        assertEquals("at-new", fake.requests.first().bearer)
    }

    @Test fun conflictUpload_realSummary_isRetried() = runBlocking {
        fake.seedState("""{"schema":1,"savedAt":1,"deviceId":"other","state":{}}""")
        AuthTestSupport.seedValidBase(db, "rev-base", "{}") // WP-8.6 first-connect gate
        fake.forceStateConflicts = 1 // answered with the real "path/conflict/file/.." summary
        assertEquals(SyncResult.Success, manager.syncNow())
        assertEquals(2, fake.stateUploadAttempts)
    }

    @Test fun interlockRefusal_isAnError_withZeroRequests() = runBlocking {
        ds.saveAccountId("dbid:someone-else")
        val r = manager.syncNow()
        assertEquals(SyncResult.Error(SyncManager.INTERLOCK_REFUSED_MESSAGE), r)
        assertEquals(0, fake.requests.size)
    }

    /** A raw non-ASCII header would make OkHttp throw; the escaped one round-trips to the same path. */
    @Test fun nonAsciiPath_isSentEscaped() = runBlocking {
        DropboxService.downloadFile("at-old", "/events/café.json")
        assertEquals("/events/café.json", fake.requests.single().arg!!["path"].toString().trim('"'))
    }
}
