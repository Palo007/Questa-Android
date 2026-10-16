package com.example

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.LocalTapQueue
import com.example.data.RpgRepository
import com.example.data.boot.BootGate
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase
import com.example.data.clock.ProcessHlc
import com.example.data.sync.AppSyncWiring
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.DefaultResetSteps
import com.example.data.sync.FirstConnect
import com.example.data.sync.ForceOpsEngine
import com.example.data.sync.LocalContent
import com.example.data.sync.ResetEngine
import com.example.data.sync.SectionImporter
import com.example.data.sync.SyncClocks
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncScheduler
import com.example.data.sync.SyncTrigger
import com.example.data.sync.SyncWorker
import com.example.data.sync.backup.TieredBackupEngine
import com.example.data.sync.events.DiagRing
import com.example.data.sync.events.EventLogWriter
import com.example.data.sync.events.EvtWatermarkRepair
import com.example.data.sync.events.RoomEventStore
import com.example.data.sync.syncDataStore
import com.example.reminders.NotificationChannels
import com.example.reminders.ReminderForegroundObserver
import com.example.reminders.ReminderObserver
import com.example.reminders.ReminderRuntime
import com.example.startup.StartupMigrations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * WP-5.1 (spec §7 D10) -- process-wide [Application] subclass. Did not previously exist; needed so
 * [SyncScheduler]'s foreground ([androidx.lifecycle.ProcessLifecycleOwner]) and connectivity
 * ([android.net.ConnectivityManager]) observers register exactly once per process, rather than
 * once per [MainActivity] recreation (configuration changes, task-switch-back, etc.) -- registering
 * them from `MainActivity.onCreate()` would re-register on every recreation with no matching
 * teardown.
 *
 * [repository]/[syncManager]/[syncScheduler]/[syncDataStore] are constructed once here and reused
 * by every consumer (currently only [MainActivity]) instead of each constructing its own -- two
 * independent [RpgRepository] instances would each hold their own private write-signal
 * (`localWrites`) [kotlinx.coroutines.flow.MutableSharedFlow], so only the instance actually
 * wired into [SyncScheduler] must ever be used for real work.
 */
open class QuestaApplication : Application() { // open: TestQuestaApplication (8.I W7, test isolation)

    lateinit var syncDataStore: SyncDataStore
        private set
    lateinit var repository: RpgRepository
        private set
    lateinit var syncManager: SyncManager
        private set
    lateinit var syncScheduler: SyncScheduler
        private set

    // WP-8.9 engines (W4 close). UI consumers are 8.14's; the OAuth return uses [forceOps].
    lateinit var forceOps: ForceOpsEngine
        private set
    lateinit var firstConnect: FirstConnect
        private set
    lateinit var resetEngine: ResetEngine
        private set
    lateinit var sectionImporter: SectionImporter
        private set
    lateinit var tieredBackup: TieredBackupEngine
        private set

    // Process-lifetime scope for the scheduler's debounce/backoff/single-flight coroutines --
    // deliberately not tied to any single Activity's lifecycle (see this class's own doc).
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runs the app key switch check; Settings calls it right after the user saves a key. */
    val appKeyMigration: suspend () -> Boolean by lazy {
        val m = StartupMigrations.appKeyMigration(this, tokens = syncManager.tokenStore)
        val run: suspend () -> Boolean = { m.runIfNeeded() }
        run
    }

    /** The W1 step 5 launch. Robolectric runs this onCreate for every test; tests join it first. */
    @Volatile internal var startupMigrationsJob: Job? = null
        private set

    /**
     * The build-time Dropbox app key, stored once at first start when no key is stored.
     * Empty when the build supplies none. Open so the test app can keep it empty.
     */
    open val appKeySeed: String get() = BuildConfig.DROPBOX_APP_KEY_SEED

    /** CR-KT-013: once-per-process guard for the local event prune (Opti `_idbPruned`). */
    private val eventPruneTrigger = com.example.data.local.EventPruneTrigger()

    override fun onCreate() {
        super.onCreate()
        // WP-8.12-T1: the debug clock offset (no-op in release) before any clock read.
        com.example.data.clock.DebugClockOffset.load(this)
        // §5.0 W1 step 0 (WP-8.21): file checks only, before any DB or DataStore access.
        val probe = StartupMigrations.captureStep0(this)

        val database = AppDatabase.getDatabase(applicationContext)
        syncDataStore = SyncDataStore(applicationContext)
        repository = RpgRepository(
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
            database = database // WP-8.6 patch 1: atomic rounds, pause sync
        )
        // WP-8.I (W4): 8.7 EventsStep + 8.9 AutoBackupStep replace both built-ins; 8.8 inbox steps.
        // SyncWorker builds the same list (AppSyncWiring), so no process runs the legacy engine.
        val wiring = AppSyncWiring(applicationContext, database, repository, syncDataStore)
        syncManager = SyncManager(
            applicationContext, repository, syncDataStore,
            roundSteps = wiring.steps,
            backupSource = wiring.liveBackup,
            clearPendingForcePush = { wiring.backupPrefs.setPendingForcePush(false) },
            blobSync = com.example.data.attachments.AttachmentRepositoryImpl.blobSync(applicationContext), // CR-KT-015
        )
        syncScheduler = SyncScheduler(
            context = applicationContext,
            syncManager = syncManager,
            scope = applicationScope,
            localWrites = repository.localWrites
        )

        // WP-8.6 patch 2: the habit-tap queue, drained by the round and by BootGate B3.
        val tapQueue = LocalTapQueue(syncDataStore, repository)
        syncManager.tapQueue = tapQueue
        BootGate.bindTapDrain { tapQueue.drain(it) }
        // WP-8.I (W7, 8.13 patch I-1/I-2): launcher taps share this queue (one drain lock).
        com.example.launcher.QuickLogRuntime.bindTapQueue(tapQueue)
        // WP-8.7 patches 2-3 / §5.0 W1 hooks (r13 minor 7): 8.7's event-log writer. Rows are
        // written once the StartupBarrier is open (never under a running startup migration).
        val eventWriter = EventLogWriter(
            insert = { StartupBarrier.await(); database.eventLogDao().insertLog(it) },
            deviceId = { syncDataStore.getOrCreateDeviceId() },
            scope = applicationScope,
        )
        ProcessHlc.get(syncDataStore).bindEventSink(eventWriter.hlcSink)
        BootGate.lifecycleLog = eventWriter.lifecycleSink

        // Opti `cfg.enabled && cfg.refreshToken` (steps 1-2 and WP-8.9's isConfigured).
        val isConfiguredNow: suspend () -> Boolean = {
            syncDataStore.isDropboxEnabled.first() &&
                (syncManager.tokenStore.read()?.refreshToken?.isNotEmpty() == true ||
                    !syncDataStore.dropboxRefreshToken.first().isNullOrEmpty())
        }
        // §5.0 W1 step 1 (D22, D27): blocking read; any exception means false.
        val readConfigured: () -> Boolean = {
            try {
                runBlocking { isConfiguredNow() }
            } catch (e: Exception) {
                false
            }
        }

        // WP-8.9 patches 2-5. The event ops take the token through the D7-gated accessor.
        val token: suspend () -> String? = { syncManager.activeAccessTokenChecked() }
        // CR-KT-015: attachment downloads use the same D7-gated token.
        com.example.data.attachments.AttachmentRepositoryImpl.bindTokenProvider(token)
        forceOps = ForceOpsEngine(
            internals = syncManager,
            syncDataStore = syncDataStore,
            localSubset = { syncManager.buildLocalSnapshot() },
            isConfigured = { try { isConfiguredNow() } catch (e: Exception) { false } },
            ratchet = { repository.hlcClock.ratchet(it) },
            eventsForcePull = wiring.forcePullEvents(token),
            eventsForcePush = wiring.forcePushEvents(token),
            normalSync = { syncManager.sync(SyncTrigger.CONNECT) },
            backupPrefs = wiring.backupPrefs,
        )
        firstConnect = FirstConnect(
            syncManager, forceOps, { syncManager.buildLocalSnapshot() },
            LocalContent(repository)::isEffectivelyEmpty, // REQUIRED (8.9 review fix 1)
        )
        resetEngine = ResetEngine(
            DefaultResetSteps(
                database, repository, syncDataStore, applicationContext.syncDataStore, wiring.backupPrefs,
                linked = { try { isConfiguredNow() } catch (e: Exception) { false } },
                disconnectFn = { syncManager.disconnect() },
                purge = wiring.purgeOwnEvents(token),
            )
        )
        // WP-8.I (W7, 8.15 patch 2, REQUIRED): an import restores the 9 local UI prefs too.
        sectionImporter = SectionImporter(repository, syncDataStore, wiring.backupPrefs, wiring.uiPrefs)
        tieredBackup = wiring.tieredBackup
        val configured = readConfigured()
        // Step 2 (B9).
        BootGate.configuredProvider = { try { readConfigured() } catch (e: Exception) { false } }
        // Step 3 (W4, WP-8.10 B10 body): ryaOpener if bound, else the real startDay. The shared
        // repository instance, so MainViewModel's opener is visible here (r14, T-10c).
        BootGate.bindStartDay(repository.startDayBinding())
        // Step 4: before step 5, so onBarrierDone can never fire before pending is set.
        BootGate.bootStartDay(configured, StartupBarrier.done.value)
        // Step 5 (WP-8.21): markDone opens the barrier only on success; then B5. On failure the
        // barrier stays closed (§5.0) and the B2 timer opens the gate.
        startupMigrationsJob = applicationScope.launch {
            val ok = try {
                StartupMigrations.run(this@QuestaApplication, probe.dbExisted, probe.dsHadKeys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StartupMigrations.publish(StartupMigrations.STATUS_FAILED)
                false
            }
            if (ok) {
                BootGate.onBarrierDone()
                // CR-KT-013: prune old local events once per session after the DB is ready
                // (Opti schedulePrune right after the event DB opens). Async, best effort.
                // CR-KT-014: gated by the prune warning (warn first, prune 14 days later).
                eventPruneTrigger.scheduleOnce(applicationScope) {
                    val pruneNow = { com.example.data.clock.PhysicalClock.nowMs() } // clock-ok:event-prune
                    val pruneOffset = { com.example.data.clock.DebugClockOffset.offsetMs() }
                    val warnStore = com.example.data.local.DataStorePruneWarnStore.forApp(applicationContext)
                    com.example.data.local.WarnedEventPrune(
                        dao = database.eventLogDao(),
                        store = warnStore,
                        warner = com.example.data.local.AndroidPruneWarner(applicationContext),
                        now = pruneNow,
                        prune = { ageCutoff, capMaxId ->
                            com.example.data.local.EventPruner(database, now = pruneNow, clockOffsetMs = pruneOffset)
                                .prune(ageCutoff, capMaxId)
                        },
                        clockOffsetMs = pruneOffset,
                    ).run()
                }
                // WP-8.7 patch 4 (REQUIRED, PX-51): boot self-heal of a future event watermark.
                try {
                    EvtWatermarkRepair(
                        DataStoreEventWatermark(syncDataStore), RoomEventStore(database),
                        SyncClocks.transport, DiagRing.process,
                    ).runIfFuture()?.let { android.util.Log.w("Questa.events", it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort, as Opti's boot repair.
                }
                // CR-KT-022: one-shot repair of the completion history points pre-1.3 never
                // wrote -- replay the local complete/uncomplete event rows
                // (HistoryBackfill through RpgRepository.backfillCompletionHistory), behind a
                // device-only flag so the scan runs once per install. It runs INLINE on this
                // launch, LAST in the `ok` block, so it starts only after the barrier work above
                // and the watermark self-heal -- a long scan delays nothing but this job's own
                // completion, which nothing on the boot path awaits.
                //
                // The flag is stamped only when the scan SAW completion rows (report.hadEvents):
                // an empty log means nothing pre-1.3 can exist to repair, and re-checking an
                // empty table on later boots is free -- the flag's saving (stop re-reading a log
                // that only grows) starts to matter exactly once the log has content. The repair
                // is idempotent (UTC-day dedup, add-only), so a lost flag or the Diagnostics
                // re-run is harmless; a failure leaves the flag unset and retries on the next
                // boot.
                //
                // Test-suite safety (the guard at the end of this method): Robolectric runs this
                // onCreate for every test, and the repair's extra background DataStore + Room
                // work perturbs the boot window of unrelated composition tests on the dev host
                // (DataStore 1.1's file store commits with Files.move, which the host file
                // system can fail when read and write handles overlap -- extra activity widens
                // that window). The repair itself is fully driven by CrKt022RunnerTest, so the
                // boot trigger is skipped there; on device this is a trivial string compare.
                if (android.os.Build.FINGERPRINT != "robolectric") {
                    try {
                        val flag = com.example.data.local.DataStoreCompletionBackfillStore.forApp(applicationContext)
                        if (!flag.hasRun()) {
                            val report = repository.backfillCompletionHistory()
                            if (report.hadEvents) flag.markRun()
                            if (report.restored > 0) android.util.Log.i("Questa.rpg", "CR-KT-022 backfill restored ${report.restored} completion day(s)")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort, as the prune above; retried on the next boot.
                    }
                }
            }
        }

        // WP-8.11: reminders (best effort, after the database exists).
        try {
            NotificationChannels.ensure(this)
            val rt = ReminderRuntime.provider(this)
            ReminderObserver(database.taskDao(), rt.scheduler).start(applicationScope)
            ProcessLifecycleOwner.get().lifecycle.addObserver(ReminderForegroundObserver(rt.checker, applicationScope))
        } catch (e: Exception) {
            // Best effort, as the scheduler wiring below.
        }

        // WP-8.I (W7, 8.13 patch I-2): the launcher long-press menu. Returns at once; the coroutine
        // awaits StartupBarrier first (R15), then republishes on every habit or auth change.
        try {
            com.example.launcher.QuickLogBootstrap.start(this, applicationScope)
        } catch (e: Exception) {
            // Best effort, as Bubblewrap QuickLogBootstrap (never breaks the launch).
        }

        // Step 6: registered before step 7's own ON_START observer (dispatch order).
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    BootGate.onForeground(BootGate.clock())
                }
            })
        } catch (e: Exception) {
            // Best effort (Robolectric), as the scheduler wiring below.
        }

        // Deviation (test-suite safety): AndroidManifest now declares this class as the app's
        // `Application`, which means Robolectric instantiates and runs THIS onCreate() for every
        // existing `@RunWith(RobolectricTestRunner::class)` test in the suite by default (none of
        // them override `@Config(application = ...)`), not just new WP-5.1 tests. Registering
        // ProcessLifecycleOwner/ConnectivityManager observers and enqueuing WorkManager periodic
        // work are best-effort background wiring, not correctness-critical for any single test run
        // -- an environment where one of them throws (e.g. WorkManager's ContentProvider-based
        // auto-init not running the same way under Robolectric as on a real device) must not crash
        // Application startup and cascade a failure into every unrelated pre-existing test. Real
        // on-device startup failures here would be equally undesirable (a crashed cold start), so
        // this guard is sound production behavior too, not just a test workaround.
        // CR-KT-018: stamp localEditAt (the header sync badge's "not synced" side) on each user
        // write. Same signal as the debounce below; a merge apply emits nothing.
        com.example.data.sync.LocalEditStamper(repository.localWrites, syncDataStore::stampLocalEdit).start(applicationScope)
        try {
            syncScheduler.start()
        } catch (e: Exception) {
            // Best-effort: the WorkManager periodic top-up below (also best-effort) and any
            // manual "sync now" action remain available even if these observers failed to
            // register.
        }

        // WP-5.1 step 6 (divergence-approved): coarse periodic top-up, see [enqueuePeriodicSync].
        try {
            enqueuePeriodicSync(WorkManager.getInstance(applicationContext))
        } catch (e: Exception) {
            // Best-effort -- see the try/catch above this one for the full rationale.
        }
    }
}

/** The unique name of the periodic sync work. */
internal const val PERIODIC_SYNC_WORK_NAME = "questa-periodic-sync"

/**
 * WP-5.1 step 6: the periodic top-up, at the floor interval WorkManager allows (15 minutes).
 * KT-62 (fix plan P9): it runs only with a network (an offline phone no longer wakes for a round
 * that must fail). Policy is always UPDATE: onCreate() runs on every process start, and UPDATE
 * keeps the existing work id and enqueue time, so the interval clock is not reset (the reason
 * KEEP was used before). UPDATE also moves installs that already hold the old KEEP work (no
 * network rule) onto the new rule, with no stored flag.
 */
internal fun enqueuePeriodicSync(wm: WorkManager): Operation {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()
    return wm.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
}
