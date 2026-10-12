package com.example.data.sync

import androidx.datastore.preferences.core.edit
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.auth.DropboxConfigRule
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.BkTier
import com.example.data.sync.backup.TierState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records the step order of a reset (app.js:6641-6660). */
private class RecordingSteps(private val linked: Boolean, private val purgeFails: Boolean = false) : ResetSteps {
    val calls = mutableListOf<String>()
    override suspend fun isLinked() = linked
    override suspend fun deviceId() = "dev-keep"
    override suspend fun purgeOwnEventFiles(deviceId: String) { calls += "purge:$deviceId"; if (purgeFails) throw IllegalStateException("offline") }
    override suspend fun setPendingPurgeDev(deviceId: String) { calls += "pending:$deviceId" }
    override suspend fun wipeLocalEventLog() { calls += "events" }
    override suspend fun resetEventWatermarks() { calls += "watermarks" }
    override suspend fun disconnect() { calls += "disconnect" }
    override suspend fun wipeBase() { calls += "base" }
    override suspend fun wipeData() { calls += "data" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetEngineTest : Wp89TestBase() {

    @Test fun linked_purgesFirst_thenTheOptiOrder() = runBlocking {
        val s = RecordingSteps(linked = true)
        ResetEngine(s).resetEverything()
        assertEquals(listOf("purge:dev-keep", "events", "watermarks", "disconnect", "base", "data"), s.calls)
    }

    @Test fun notLinked_stashesPendingPurgeDev_instead() = runBlocking {
        val s = RecordingSteps(linked = false)
        ResetEngine(s).resetEverything()
        assertEquals(listOf("pending:dev-keep", "events", "watermarks", "disconnect", "base", "data"), s.calls)
    }

    @Test fun aPurgeError_isIgnored() = runBlocking {
        val s = RecordingSteps(linked = true, purgeFails = true)
        ResetEngine(s).resetEverything()
        assertEquals(6, s.calls.size)
    }

    @Test fun runsUnderTheSyncLock() = runBlocking {
        var held = false
        val s = object : ResetSteps by RecordingSteps(false) {
            override suspend fun wipeData() { held = SyncLock.isHeld }
        }
        ResetEngine(s).resetEverything()
        assertTrue(held)
    }

    /** The keep/clear set of the production steps: DEVICE_ID and APP_KEY_OF_DATA kept. */
    @Test fun defaultSteps_keepAndClearSet() = runBlocking {
        val d = device89("dev-reset")
        d.link()
        d.prefsStore.edit {
            it[SyncKeys.APP_KEY_OF_DATA] = DropboxConfigRule.TEST_APP_KEY
            it[SyncKeys.EVT_LAST_UPLOAD_TS] = 123L
            it[SyncKeys.EVT_FILE_REVS] = "{\"dev-reset-202607.json\":\"r1\"}"
            it[SyncKeys.LAST_EXPORT_TS] = 55L
        }
        d.backupPrefs.setAutoBackupEnabled(AutoBackupEnabled(daily = true))
        d.backupPrefs.saveTierStates(mapOf(BkTier.DAILY to TierState(1, 9L, 3)))
        d.syncDataStore.saveMonthlyBackups(listOf("2026-07"))
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "T", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.repository.insertTombstone(TombstoneEntity(id = "gone", at = 5L))
        d.repository.insertLog(EventLogEntity(kind = "habit", taskTitle = "x", timestamp = com.example.data.clock.PhysicalClock.nowMs()))
        d.db.syncBaseDao().writeAtomic(DropboxConfigRule.TEST_APP_KEY, FakeDropboxServer.SCRATCH_ACCOUNT_ID, "rev-1", "{}")
        var purged: String? = null
        var disconnected = 0
        val steps = DefaultResetSteps(
            database = d.db, repository = d.repository, syncDataStore = d.syncDataStore, prefs = d.prefsStore,
            backupPrefs = d.backupPrefs, linked = { true },
            disconnectFn = { disconnected++; d.tokens.clear(); d.syncDataStore.clearForDisconnect() }, // no revoke call (no network)
            purge = { purged = it },
        )
        ResetEngine(steps).resetEverything()

        assertEquals("dev-reset", purged)
        assertEquals(1, disconnected)
        assertNull("tokens cleared", d.tokens.read())
        assertEquals("DEVICE_ID kept", "dev-reset", d.syncDataStore.getOrCreateDeviceId())
        val p = d.prefsStore.data.first()
        assertEquals("APP_KEY_OF_DATA kept", DropboxConfigRule.TEST_APP_KEY, p[SyncKeys.APP_KEY_OF_DATA])
        assertNull(d.base())
        assertTrue(d.repository.getAllTasksSync().isEmpty())
        assertTrue(d.repository.getAllTombstonesSync().isEmpty())
        assertTrue(d.repository.allLogs.first().isEmpty())
        assertEquals(0L, d.syncDataStore.evtLastUploadTs.first())
        assertTrue(p[SyncKeys.EVT_FILE_REVS] == null || p[SyncKeys.EVT_FILE_REVS] == "{}")
        assertEquals(AutoBackupEnabled(), d.backupPrefs.autoBackupEnabled())
        assertNull(p[SyncKeys.LAST_EXPORT_TS])
        assertTrue(d.syncDataStore.monthlyBackups.first().isEmpty())
        assertEquals("tier bookkeeping kept (Opti keeps its localStorage)", TierState(1, 9L, 3), d.backupPrefs.tierStates()[BkTier.DAILY])
        assertTrue(d.syncDataStore.localSavedAt.first() > 0L)
        assertEquals("default character after reset", 1, d.repository.getCharacterSync().lvl)
        assertNull(p[SyncKeys.PENDING_PURGE_DEV])
    }

    @Test fun defaultSteps_notLinked_setsPendingPurgeDev() = runBlocking {
        val d = device89("dev-off")
        d.syncDataStore.saveLocalDeviceId("dev-off")
        val steps = DefaultResetSteps(d.db, d.repository, d.syncDataStore, d.prefsStore, d.backupPrefs,
            linked = { false }, disconnectFn = { d.tokens.clear(); d.syncDataStore.clearForDisconnect() })
        ResetEngine(steps).resetEverything()
        assertEquals("dev-off", d.prefsStore.data.first()[SyncKeys.PENDING_PURGE_DEV])
        assertEquals(0, fake.requests.size)
    }

    @Test fun backupPrefsResetToFresh_isAllOff() = runBlocking {
        val d = device89("dev-x")
        d.backupPrefs.setAutoBackupEnabled(AutoBackupEnabled(fourHour = true))
        d.backupPrefs.resetToFresh()
        assertEquals(AutoBackupEnabled(), d.backupPrefs.autoBackupEnabled())
        assertFalse(d.prefsStore.data.first().contains(BackupPrefs.MIGRATED_TOAST))
    }
}
