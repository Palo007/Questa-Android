package com.example.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.backup.BackupPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WP-8.9 (PX-56, PX-87) -- the six steps of Opti `resetEverything()` (app.js:6641-6660), in
 * this order. A this-device-only reset: no tombstones, nothing is pushed to other devices.
 */
interface ResetSteps {
    /** Opti `cfgAtReset.refreshToken` (still connected at reset time). */
    suspend fun isLinked(): Boolean
    /** `syncDeviceId()`; the id is KEPT by the reset. */
    suspend fun deviceId(): String
    /** 1a (linked): delete this device's `/events` files now (8.7's `purgeOwnEventFiles`). */
    suspend fun purgeOwnEventFiles(deviceId: String)
    /** 1b (not linked): `pendingPurgeDev := deviceId`, consumed by 8.7 on the next linked round. */
    suspend fun setPendingPurgeDev(deviceId: String)
    /** 2: wipe the local event log (`clearAllEvents()`). */
    suspend fun wipeLocalEventLog()
    /** 3: `evtLastUploadTs := 0`, `evtFileRevs := {}`. */
    suspend fun resetEventWatermarks()
    /** 4: `syncDisconnect()` -- tokens cleared, DEVICE_ID kept (8.1's disconnect keep set). */
    suspend fun disconnect()
    /** 5: `syncBasePut(null)` -- the sync base wiped. */
    suspend fun wipeBase()
    /** 6: `S = freshState(); save()` -- Room data wiped (and `__seq` with it); APP_KEY_OF_DATA kept. */
    suspend fun wipeData()
}

/** WP-8.9 -- runs [ResetSteps] in Opti order. Errors of step 1a are ignored (`.catch(function(){})`). */
class ResetEngine(private val steps: ResetSteps) {
    /**
     * The whole reset runs under [SyncLock] (WP-8.9 guess): a round that started before the
     * reset cannot re-upload or re-apply data between the steps.
     */
    suspend fun resetEverything() {
        SyncLock.singleFlight(SyncTrigger.MANUAL) { resetLocked() }
    }

    internal suspend fun resetLocked() {
        val dev = steps.deviceId()
        if (dev.isNotEmpty()) {
            if (steps.isLinked()) {
                try { steps.purgeOwnEventFiles(dev) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* ignored, as Opti */ }
            } else {
                steps.setPendingPurgeDev(dev)
            }
        }
        steps.wipeLocalEventLog()
        steps.resetEventWatermarks()
        steps.disconnect()
        steps.wipeBase()
        steps.wipeData()
    }
}

/**
 * Production [ResetSteps]. [purgeOwnEventFiles] is 8.7's (`purgeOwnEventFiles`, sync.js:3621-3630);
 * until 8.I binds it, a linked reset stashes PENDING_PURGE_DEV instead, so the purge still runs
 * on the next linked round (8.7's consumer).
 */
class DefaultResetSteps(
    private val database: AppDatabase,
    private val repository: RpgRepository,
    private val syncDataStore: SyncDataStore,
    private val prefs: DataStore<Preferences>,
    private val backupPrefs: BackupPrefs,
    private val linked: suspend () -> Boolean,
    private val disconnectFn: suspend () -> Unit,
    private val purge: (suspend (String) -> Unit)? = null,
) : ResetSteps {
    override suspend fun isLinked(): Boolean = linked()

    override suspend fun deviceId(): String = syncDataStore.getOrCreateDeviceId()

    override suspend fun purgeOwnEventFiles(deviceId: String) {
        val p = purge
        if (p == null) setPendingPurgeDev(deviceId) else p(deviceId)
    }

    override suspend fun setPendingPurgeDev(deviceId: String) {
        prefs.edit { it[SyncKeys.PENDING_PURGE_DEV] = deviceId }
    }

    override suspend fun wipeLocalEventLog() = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL("DELETE FROM event_logs")
    }

    override suspend fun resetEventWatermarks() {
        prefs.edit {
            it[SyncKeys.EVT_LAST_UPLOAD_TS] = 0L
            it[SyncKeys.EVT_FILE_REVS] = "{}"
        }
    }

    override suspend fun disconnect() = disconnectFn()

    override suspend fun wipeBase() = database.syncBaseDao().wipe()

    /**
     * Every Room table (tasks, rewards, tags, devices, char history, tombstones, views, metrics,
     * snapshot extras incl. `__seq`, sync_prefs, the event log). DataStore keeps DEVICE_ID,
     * APP_KEY_OF_DATA, the HLC and the backup tier bookkeeping (Opti keeps its localStorage keys
     * other than the state). `S.monthlyBackups` and the WP-8.9 prefs go back to freshState; then
     * one save stamp (`save()`), and the default character is seeded by the next read.
     */
    override suspend fun wipeData() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        syncDataStore.saveMonthlyBackups(emptyList())
        backupPrefs.resetToFresh()
        repository.stampLocalSaved()
    }
}
