package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.RpgRepository
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase

/**
 * WP-5.1 (spec §7 D10) -- WorkManager periodic top-up, divergence-approved (this WP's Locked
 * Decisions): the debounce/foreground/connectivity triggers in [SyncScheduler] cover every PWA
 * parity case, but a Kotlin process can be killed by the OS for long stretches with no foreground
 * activity and no local writes to debounce off of; this worker is a coarse safety net (minimum
 * WorkManager allows: 15 minutes) so a device that never comes to the foreground still eventually
 * syncs, rather than drifting indefinitely. Has no PWA equivalent -- a browser tab either is or
 * isn't running JS at all, so PWA never needed this.
 *
 * Constructs its own [RpgRepository]/[SyncManager] from [AppDatabase.getDatabase]'s existing
 * process-wide singleton accessor (per this WP's guidance to prefer whichever needs less new
 * plumbing) rather than requiring a custom `WorkerFactory` -- WorkManager's default factory can
 * instantiate this via its `(Context, WorkerParameters)` constructor with no extra wiring.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // WP-8.22-D item 1: when the startup migrations failed on this process the barrier stays
        // closed forever, so a round would only hang until WorkManager's 10-minute stop, every
        // 15 minutes. Skip at once instead (success: as below, no retry backoff churn).
        if (!awaitStartup()) return Result.success()
        val database = AppDatabase.getDatabase(applicationContext)
        val syncDataStore = SyncDataStore(applicationContext)
        val repository = RpgRepository(
            characterDao = database.characterDao(),
            taskDao = database.taskDao(),
            rewardDao = database.rewardDao(),
            eventLogDao = database.eventLogDao(),
            tagDao = database.tagDao(),
            deviceDao = database.deviceDao(),
            charHistoryDao = database.charHistoryDao(),
            tombstoneDao = database.tombstoneDao(),
            customViewDao = database.customViewDao(),
            snapshotExtrasDao = database.snapshotExtrasDao(),
            metricsExtrasDao = database.metricsExtrasDao(),
            syncDataStore = syncDataStore,
            database = database,
        )
        // WP-8.I (W4): the SAME round steps as QuestaApplication (8.7 events with the shrink guard,
        // 8.9 tiered backups, 8.8 inbox). The legacy built-ins must never run in this process.
        val wiring = AppSyncWiring(applicationContext, database, repository, syncDataStore)
        val syncManager = SyncManager(
            applicationContext, repository, syncDataStore,
            roundSteps = wiring.steps,
            backupSource = wiring.liveBackup,
            clearPendingForcePush = { wiring.backupPrefs.setPendingForcePush(false) },
            blobSync = com.example.data.attachments.AttachmentRepositoryImpl.blobSync(applicationContext), // CR-KT-015
        )

        // WP-8.6 (card): the StartupBarrier wait is SyncLock.singleFlight's first line
        // ([SyncLock.awaitBarrier]; a no-op until 8.I, review 26 C2), not a second call here.
        // WORKER skips when a round already holds the lock (null): that round covers this tick.
        syncManager.sync(SyncTrigger.WORKER)
        // Any outcome is success for WorkManager: a failed periodic top-up (including conflict
        // exhaustion) is never worth its retry backoff churn -- the next tick or one of
        // SyncScheduler's triggers runs again regardless.
        return Result.success()
    }

    internal companion object {
        /** Seam for [StartupBarrier.awaitOutcome] (the real barrier is a process-wide one-shot). */
        @Volatile var awaitStartup: suspend () -> Boolean = { StartupBarrier.awaitOutcome() }
    }
}
