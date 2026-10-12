package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.FakeTokenEndpoint
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.auth.InterlockPolicy
import com.example.data.sync.auth.SecureTokenStore
import com.example.data.sync.auth.TokenSet
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A connect that got a code but could not finish must say so ([SyncManager.lastConnectFailure]),
 * not return silently to Settings. Regression: with no INTERNET permission the exchange failed at
 * once with "network error" and the user saw nothing.
 *
 * The expected texts are literals on purpose: they pin the exact words the user reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectFailedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ds: SyncDataStore
    private lateinit var db: AppDatabase
    private lateinit var repo: RpgRepository

    private val scratch = "dbid:scratch-1"
    private val tokenBody = """{"access_token":"sl.AT-secret-1","refresh_token":"RT-secret-1","expires_in":14400,"account_id":"$scratch","token_type":"bearer"}"""

    @Before fun setUp() = runBlocking {
        AuthTestSupport.resetSyncPrefs(context)
        ds = SyncDataStore(context)
        val (d, r) = AuthTestSupport.newRepository(context, ds)
        db = d
        repo = r
        InterlockPolicy.unlocked = false
        InterlockPolicy.allowlist = setOf(scratch)
    }

    @After fun tearDown() {
        InterlockPolicy.resetToBuildConfig()
        db.close()
    }

    private fun manager(http: OkHttpClient, tokens: SecureTokenStore = InMemorySecureTokenStore()) =
        SyncManager(context, repo, ds, null, tokens, DropboxOAuth(http = http))

    @Test fun networkError_setsFailureMessage() = runBlocking {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val m = manager(offline)
        m.getAuthorizationUri()
        assertFalse(m.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals("Dropbox connect failed (token exchange failed: network error). Try again.", m.lastConnectFailure)
        assertNull(m.lastConnectRefusal)
    }

    @Test fun rejectedCode_setsFailureMessageWithDropboxReason() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(400, """{"error":"invalid_grant","error_description":"code doesn't exist or has expired"}"""))
        val m = manager(endpoint.client)
        m.getAuthorizationUri()
        assertFalse(m.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals("Dropbox connect failed (code doesn't exist or has expired). Try again.", m.lastConnectFailure)
    }

    @Test fun tokenSaveFails_setsFailureMessage() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        val broken = object : SecureTokenStore by InMemorySecureTokenStore() {
            override suspend fun write(t: TokenSet) { throw IllegalStateException("disk full") }
        }
        val m = manager(endpoint.client, broken)
        m.getAuthorizationUri()
        assertFalse(m.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals("Dropbox connect failed (could not save the login). Try again.", m.lastConnectFailure)
    }

    @Test fun cancelledLogin_setsNoFailureMessage() = runBlocking {
        val m = manager(FakeTokenEndpoint().client)
        m.getAuthorizationUri()
        assertFalse(m.handleRedirectUri("questa-dev://oauth?error=access_denied"))
        assertNull(m.lastConnectFailure)
    }

    @Test fun successAfterFailure_clearsFailureMessage() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(500, "{}"))
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        val m = manager(endpoint.client)
        m.getAuthorizationUri()
        assertFalse(m.handleRedirectUri("questa-dev://oauth?code=abc"))
        m.getAuthorizationUri()
        assertTrue(m.handleRedirectUri("questa-dev://oauth?code=def"))
        assertNull(m.lastConnectFailure)
    }
}
