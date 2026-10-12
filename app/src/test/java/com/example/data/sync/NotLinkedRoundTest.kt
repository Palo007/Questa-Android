package com.example.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * REWORK R-3: a round while not linked is silent (Opti `syncNow`, sync.js:2355-2358) and makes
 * no Dropbox request; a linked, non-allowlisted account is still refused by the D7 interlock;
 * disconnect and a fresh link clear the stored round error (Opti `syncDisconnect`, sync.js:757-770).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotLinkedRoundTest : RoundTestBase() {

    @Test fun afterDisconnect_roundIsSilentNotLinked_zeroRequests_noStoredError() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.clearForDisconnect() // removes ACCOUNT_ID: the interlock alone would refuse
        val r = d.manager.sync()
        assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.NOT_LINKED)
        assertEquals("0 HTTP requests", 0, fake.requests.size)
        assertNull(d.syncDataStore.lastSyncError.first())
    }

    @Test fun linkedButDisabled_isSilent_zeroRequests() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.setDropboxEnabled(false)
        val r = d.manager.sync()
        assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.NOT_LINKED)
        assertEquals(0, fake.requests.size)
        assertNull(d.syncDataStore.lastSyncError.first())
    }

    @Test fun linkedUnknownAccount_isStillRefused_zeroRequests_errorStored() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.saveAccountId("dbid:someone-else")
        assertEquals(SyncResult.InterlockRefused, d.manager.sync())
        assertEquals(0, fake.requests.size)
        assertEquals(SyncManager.INTERLOCK_REFUSED_MESSAGE, d.syncDataStore.lastSyncError.first())
    }

    @Test fun linkedAllowlistedAccount_roundRuns() = runBlocking {
        val d = device()
        d.link()
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertTrue("the round reached Dropbox", fake.requests.isNotEmpty())
        assertNull(d.syncDataStore.lastSyncError.first())
    }

    @Test fun clearForDisconnect_clearsStoredError_keepsLastSyncAt() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.saveSyncStatus(SyncManager.INTERLOCK_REFUSED_MESSAGE, lastSyncAt = 1234L)
        d.syncDataStore.clearForDisconnect()
        assertNull(d.syncDataStore.lastSyncError.first())
        assertEquals(1234L, d.syncDataStore.lastSyncAt.first())
    }

    @Test fun markLinked_clearsStoredError() = runBlocking {
        val d = device()
        d.syncDataStore.saveSyncStatus(SyncManager.INTERLOCK_REFUSED_MESSAGE)
        d.syncDataStore.markLinked(FakeDropboxServer.SCRATCH_ACCOUNT_ID)
        assertNull(d.syncDataStore.lastSyncError.first())
    }
}
