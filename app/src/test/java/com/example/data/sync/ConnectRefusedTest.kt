package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.FakeTokenEndpoint
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.auth.InterlockPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/**
 * KT-61 (fix plan P9): a connect the interlock refuses must not look like a cancelled login.
 * The refusal is saved as the sync status (the Settings status line) and the app shows it.
 *
 * The expected text is a literal on purpose: it pins the exact words the user reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectRefusedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ds: SyncDataStore
    private lateinit var db: AppDatabase
    private lateinit var manager: SyncManager
    private lateinit var tokens: InMemorySecureTokenStore
    private lateinit var endpoint: FakeTokenEndpoint

    private val scratch = "dbid:scratch-1"
    private val tokenBody = """{"access_token":"sl.AT-secret-1","refresh_token":"RT-secret-1","expires_in":14400,"account_id":"$scratch","token_type":"bearer"}"""
    private val lockedText = "This Dropbox account is locked. It was not connected."

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

    @Test fun interlockRefusal_emitsLockedMessage() = runBlocking {
        manager.getAuthorizationUri()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody.replace(scratch, "dbid:real-other")))
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertEquals(lockedText, ds.lastSyncError.first())
        assertEquals(lockedText, manager.lastConnectRefusal)
    }

    @Test fun cancelledLogin_savesNoLockedMessage() = runBlocking {
        manager.getAuthorizationUri()
        assertFalse(manager.handleRedirectUri("questa-dev://oauth?error=access_denied"))
        assertNull(ds.lastSyncError.first())
        assertNull(manager.lastConnectRefusal)
    }

    @Test fun laterAllowedConnect_clearsTheLockedMessage() = runBlocking {
        ds.saveSyncStatus(lockedText)
        manager.getAuthorizationUri()
        endpoint.tokenAnswers.addLast(FakeTokenEndpoint.Canned(200, tokenBody))
        assertTrue(manager.handleRedirectUri("questa-dev://oauth?code=abc"))
        assertNull(ds.lastSyncError.first())
        assertNull(manager.lastConnectRefusal)
    }
}
