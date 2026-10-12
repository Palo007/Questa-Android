package com.example.data.sync

import android.content.Context
import com.example.data.RpgRepository
import com.example.data.inbox.DataStoreInboxPrefs
import com.example.data.inbox.InboxSteps
import com.example.data.local.AppDatabase
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.backup.AutoBackupStep
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.TieredBackupEngine
import com.example.data.sync.backup.UiPrefsOverlay
import com.example.data.sync.events.DropboxEventsRemote
import com.example.data.sync.events.EventsRemote
import com.example.data.sync.events.EventsStep
import com.example.data.sync.events.PushOpts
import com.example.data.sync.events.purgeOwnEventFiles
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * WP-8.I (W4 close): the ONE production round-step list, built the same way by
 * `QuestaApplication.onCreate` and `SyncWorker.doWork` (8.7 / 8.8 / 8.9 wiring patches).
 *
 * [steps] replaces BOTH SyncManager built-ins through `roundSteps`:
 *  - 8.7 [EventsStep.forApp] replaces the private EventsStep (shrink guard, self-healing pull);
 *  - 8.9 [AutoBackupStep] replaces the private BackupsStep / legacy `maybeAutoExport`. Both must
 *    never run together: every due backup would upload twice, and the lazy EXPORT_INTERVAL_DAYS
 *    migration in [BackupPrefs] deletes the key the legacy step reads;
 *  - 8.8 [InboxSteps.of] (AFTER_RATCHET, POST_UPLOAD, BACKUPS order 100).
 */
class AppSyncWiring(
    context: Context,
    database: AppDatabase,
    repository: RpgRepository,
    syncDataStore: SyncDataStore,
    eventToast: (String) -> Unit = { android.util.Log.w("Questa.events", it) },
) {
    private val prefs = context.applicationContext.syncDataStore

    val backupPrefs = BackupPrefs(prefs)
    // WP-8.I (W7, 8.15 patch 1, REQUIRED): the auto-backup carries all 9 local UI prefs (S.prefs).
    val uiPrefs = UiPrefsOverlay(prefs)
    val liveBackup = LiveBackupSource(repository, syncDataStore, backupPrefs, uiPrefs)
    val eventsStep: EventsStep = EventsStep.forApp(syncDataStore, prefs, database, toast = eventToast)
    val tieredBackup = TieredBackupEngine(
        prefs = backupPrefs,
        buildBackupText = { liveBackup.backupText() },
        deviceId = { syncDataStore.getOrCreateDeviceId() },
        saveLastExportTs = { syncDataStore.saveLastExportTs(it) },
        lastBackupError = { syncDataStore.lastBackupError.first() },
        saveLastBackupError = { syncDataStore.saveLastBackupError(it) },
        logExport = { title, notes ->
            repository.insertLog(
                EventLogEntity(
                    timestamp = repository.physicalNow(),
                    kind = "export",
                    taskTitle = title,
                    payloadJson = CanonicalJson.canonicalJsonString(buildJsonObject { put("notes", JsonPrimitive(notes)) }),
                )
            )
        },
    )

    val steps: List<SyncRoundStep> = listOf(eventsStep, AutoBackupStep(tieredBackup)) +
        InboxSteps.of(repository, DataStoreEventWatermark(syncDataStore), DataStoreInboxPrefs(prefs))

    // ---- Token-bound event ops (8.9 ForceOps / ResetEngine bindings, 8.7 hand-off) ----
    // [token] is `SyncManager.activeAccessTokenChecked` (D7 first). No token = "not linked", thrown
    // like Opti's syncToken failure; ForceOps / the reset treat it as they treat any failure.

    private suspend fun remote(token: suspend () -> String?): EventsRemote =
        DropboxEventsRemote(token() ?: throw IllegalStateException("not linked"))

    /** `syncEventsPull({force:true})`. */
    fun forcePullEvents(token: suspend () -> String?): suspend () -> Unit = {
        eventsStep.puller.pull(remote(token), force = true)
    }

    /** `syncEventsForcePush()` = `syncEventsPush({force:true, forceFullPush:true})`. */
    fun forcePushEvents(token: suspend () -> String?): suspend () -> Unit = {
        eventsStep.pusher.push(remote(token), PushOpts(force = true, forceFullPush = true))
    }

    /** `syncEventsDeleteDevice(dev)` for a linked reset. */
    fun purgeOwnEvents(token: suspend () -> String?): suspend (String) -> Unit = { dev ->
        purgeOwnEventFiles(remote(token), dev)
    }
}
