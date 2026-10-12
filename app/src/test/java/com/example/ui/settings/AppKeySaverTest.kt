package com.example.ui.settings

import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.SyncLock
import com.example.data.sync.SyncResult
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.TokenSet
import com.example.startup.StartupTestBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saving the Dropbox app key from Settings, including the reset that runs at save time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppKeySaverTest : StartupTestBase() {

    private val settled = mutableListOf<SyncResult?>()

    @Before fun wire() {
        DropboxConfig.store = h.ds
        SyncLock.awaitBarrier = {}
        SyncLock.onSettled = { settled += it }
    }

    @After fun unwire() { SyncLock.resetForTest() }

    private fun saver(timeoutMs: Long = 10_000) =
        AppKeySaver(h.ds, h.tokens, { h.appKeyMigration().runIfNeeded() }, timeoutMs)

    private suspend fun storeKey(k: String) {
        h.ds.saveDropboxAppKey(k)
        DropboxConfig.refresh()
    }

    private suspend fun clearAll() {
        h.prefs.edit { it.remove(SyncKeys.APP_KEY_OF_DATA); it.remove(SyncKeys.DROPBOX_APP_KEY) }
        h.tokens.clear()
        DropboxConfig.refresh()
    }

    @Test fun firstKey_onUnconfiguredInstall_isStored_andSeedsMarker_withoutExport() = runBlocking {
        clearAll()
        assertEquals(SaveAppKeyResult.Saved, saver().save("k"))
        assertEquals("k", h.ds.dropboxAppKey())
        assertEquals("k", DropboxConfig.appKey())
        assertEquals("k", marker())
        assertTrue(h.exports().isEmpty())
    }

    @Test fun newKey_overOldKey_resetsInTheSameProcess() = runBlocking {
        clearAll()
        storeKey("a")
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "a"; it[SyncKeys.AUTH_STATE] = AuthState.LINKED.name }
        h.tokens.write(TokenSet("at", "rt", Long.MAX_VALUE, "acc", "a"))

        assertEquals(SaveAppKeyResult.SavedAndReset, saver().save("b"))

        assertEquals("b", DropboxConfig.appKey())
        assertTrue(h.exports().isNotEmpty())
        assertNull(h.tokens.read())
        assertEquals(AuthState.NOT_LINKED.name, h.prefs.data.first()[SyncKeys.AUTH_STATE])
        assertEquals("b", marker())
    }

    @Test fun sameKey_changesNothing() = runBlocking {
        clearAll()
        storeKey("a")
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "a" }
        h.tokens.write(TokenSet("at", "rt", Long.MAX_VALUE, "acc", "a"))

        assertEquals(SaveAppKeyResult.Saved, saver().save("a"))

        assertEquals("a", h.ds.dropboxAppKey())
        assertNotNull(h.tokens.read())
        assertEquals("a", marker())
        assertTrue(h.exports().isEmpty())
    }

    @Test fun emptyKey_whileNotLinked_removesKey_andLeavesMarker() = runBlocking {
        clearAll()
        storeKey("a")
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "a" }

        assertEquals(SaveAppKeyResult.Saved, saver().save(""))

        assertEquals("", h.ds.dropboxAppKey())
        assertFalse(DropboxConfig.isConfigured)
        assertEquals("a", marker())
    }

    @Test fun emptyKey_whileLinked_isRefused() = runBlocking {
        clearAll()
        storeKey("a")
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = "a" }
        h.tokens.write(TokenSet("at", "rt", Long.MAX_VALUE, "acc", "a"))

        assertEquals(SaveAppKeyResult.DisconnectFirst, saver().save("  "))

        assertEquals("a", h.ds.dropboxAppKey())
        assertEquals("a", DropboxConfig.appKey())
        assertNotNull(h.tokens.read())
    }

    @Test fun save_trimsWhitespace() = runBlocking {
        clearAll()
        saver().save("  k  ")
        assertEquals("k", h.ds.dropboxAppKey())
        assertEquals("k", DropboxConfig.appKey())
    }

    @Test fun save_doesNotSettleTheFirstSyncRound() = runBlocking {
        clearAll()
        saver().save("k")
        assertTrue("a key save is not a sync round", settled.isEmpty())
    }

    @Test fun save_whenStartupNeverFinishes_timesOut_andWritesNothing() = runBlocking {
        clearAll()
        val never = CompletableDeferred<Unit>()
        SyncLock.awaitBarrier = { never.await() }

        assertEquals(SaveAppKeyResult.NotReady, saver(timeoutMs = 50).save("k"))

        assertEquals("", h.ds.dropboxAppKey())
        assertNull(marker())
    }
}
