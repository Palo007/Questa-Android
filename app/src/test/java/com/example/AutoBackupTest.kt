package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.AutoBackupEngine
import com.example.data.sync.DropboxService
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * WP-5.2 (spec §7 D9) -- Robolectric, real-Room-backed integration tests for
 * [SyncManager.maybeAutoExport] (the port of PWA's `syncMaybeAutoExport`, `legacy_pwa/sync.js:
 * 1348-1396`). Dropbox itself is faked via [FakeDropboxServer], same convention as
 * [EventSyncPushPullTest]/[SyncConflictRetryTest]. [maybeAutoExport] is called DIRECTLY (it is
 * public) rather than only through `syncNow()`, so each scenario can set up its own
 * exportIntervalDays/lastExportTs/monthlyBackups precondition without needing a full state.json
 * round-trip first -- `AutoBackupWiringTest`-equivalent coverage of the actual `syncNow()` call
 * site is intentionally out of scope here (see [SyncConflictRetryTest]-style tests elsewhere for
 * "does syncNow still return Success" coverage; this file focuses on the auto-backup decision/
 * upload logic itself).
 *
 * See [com.example.data.sync.AutoBackupEngineTest] for the pure-logic (no Robolectric) unit tests
 * of the decision functions this orchestration layer calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoBackupTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncDataStore: SyncDataStore
    private lateinit var syncManager: SyncManager
    private lateinit var fakeServer: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient

    @Before
    fun setUp() = runBlocking {
        AppBootQuiesce.quiesce() // WP-8.10: no background boot write overlaps this test's DataStore writes
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.data.clock.StartupBarrier.markDone() // WP-8.6: rounds await the barrier (SyncLock)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore, db // WP-8.6: the round's transaction and base use this database
        )
        syncManager = SyncManager(context, repository, syncDataStore)

        fakeServer = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fakeServer.asClient()

        // Defensive reset -- this Robolectric Context's DataStore file is NOT guaranteed to reset
        // between test methods (see CharacterUpdatedAtSyncTest's comment on the same subject).
        syncDataStore.saveExportIntervalDays(0)
        syncDataStore.saveLastExportTs(0L)
        syncDataStore.saveMonthlyBackups(emptyList())
        FakeDropboxServer.linkScratchAccount(syncDataStore) // WP-8.1 D7
    }

    @After
    fun tearDown() {
        AppBootQuiesce.quiesce()
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        db.close()
    }

    private fun currentMonthKey(): String = AutoBackupEngine.localMonthKey(Calendar.getInstance())

    private val monthlyFilenamePattern = Regex("""/questa-backup-\d{8}-\d{4}\.json""")

    // -----------------------------------------------------------------------------------------
    // interval-off (0) skips both paths
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_exportIntervalDaysZero_skipsBothMonthlyAndIntervalPaths() = runBlocking {
        syncDataStore.saveExportIntervalDays(0)
        // Both gates would otherwise fire: month never backed up AND lastExportTs is ancient.
        syncDataStore.saveLastExportTs(0L)
        syncDataStore.saveMonthlyBackups(emptyList())

        syncManager.maybeAutoExport("fake-token")

        assertTrue("no /export-backup.json must be uploaded", fakeServer.eventFiles.keys.none { it.endsWith("export-backup.json") })
        assertTrue("no monthly dated backup must be uploaded", fakeServer.eventFiles.keys.none { monthlyFilenamePattern.matches(it) })
        assertEquals("lastExportTs must be untouched", 0L, syncDataStore.lastExportTs.first())
        assertTrue("monthlyBackups must be untouched", syncDataStore.monthlyBackups.first().isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // monthly gate skips a month already in the list
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_monthAlreadyBackedUp_andIntervalNotYetDue_uploadsNothing() = runBlocking {
        val monthKey = currentMonthKey()
        syncDataStore.saveExportIntervalDays(7)
        syncDataStore.saveMonthlyBackups(listOf(monthKey))
        // Interval not due yet: "just synced" a moment ago.
        syncDataStore.saveLastExportTs(System.currentTimeMillis())

        syncManager.maybeAutoExport("fake-token")

        assertTrue("no monthly file must be uploaded for an already-covered month", fakeServer.eventFiles.keys.none { monthlyFilenamePattern.matches(it) })
        assertTrue("no /export-backup.json must be uploaded when the interval isn't due yet", fakeServer.eventFiles.keys.none { it.endsWith("export-backup.json") })
        assertEquals(listOf(monthKey), syncDataStore.monthlyBackups.first())
    }

    // -----------------------------------------------------------------------------------------
    // monthly upload appends correct monthKey and uses correct filename stamp format
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_monthNeverBackedUp_uploadsMonthlyBlob_andAppendsMonthKey() = runBlocking {
        val monthKey = currentMonthKey()
        syncDataStore.saveExportIntervalDays(1)
        syncDataStore.saveMonthlyBackups(emptyList())
        syncDataStore.saveLastExportTs(0L)

        syncManager.maybeAutoExport("fake-token")

        val uploadedMonthlyPath = fakeServer.eventFiles.keys.firstOrNull { monthlyFilenamePattern.matches(it) }
        assertNotNull("a questa-backup-<stamp>.json must be uploaded", uploadedMonthlyPath)
        assertTrue(
            "uploaded monthly blob must be non-empty backup content",
            fakeServer.eventFiles[uploadedMonthlyPath]!!.content.isNotEmpty()
        )

        assertEquals(
            "monthlyBackups must gain exactly this month's key",
            listOf(monthKey), syncDataStore.monthlyBackups.first()
        )
        assertTrue("lastExportTs must be stamped on monthly success too", syncDataStore.lastExportTs.first() > 0L)

        // Monthly path (only) must log an event.
        val logs = repository.allLogs.first()
        val monthlyLog = logs.firstOrNull { it.kind == "export" && it.taskTitle == "Monthly Auto Backup" }
        assertNotNull("monthly success must log a 'Monthly Auto Backup' export event", monthlyLog)
        assertEquals(false, monthlyLog!!.synthetic)
    }

    @Test
    fun maybeAutoExport_monthlyGate_takesPriorityOverInterval_evenWhenIntervalAlsoDue() = runBlocking {
        // Both gates satisfied simultaneously -- monthly must win (mutually exclusive branches).
        syncDataStore.saveExportIntervalDays(1)
        syncDataStore.saveMonthlyBackups(emptyList())
        syncDataStore.saveLastExportTs(0L) // interval hugely overdue too

        syncManager.maybeAutoExport("fake-token")

        assertTrue(
            "monthly path must fire, not the interval path, when both gates are satisfied",
            fakeServer.eventFiles.keys.any { monthlyFilenamePattern.matches(it) }
        )
        assertTrue(
            "/export-backup.json must NOT be uploaded in the same call the monthly path fires",
            fakeServer.eventFiles.keys.none { it.endsWith("export-backup.json") }
        )
    }

    // -----------------------------------------------------------------------------------------
    // interval upload fires only when monthly gate satisfied AND enough days elapsed
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_monthAlreadyCovered_andIntervalElapsed_uploadsExportBackup() = runBlocking {
        val monthKey = currentMonthKey()
        syncDataStore.saveExportIntervalDays(3)
        syncDataStore.saveMonthlyBackups(listOf(monthKey))
        syncDataStore.saveLastExportTs(System.currentTimeMillis() - (5L * 86_400_000L))

        syncManager.maybeAutoExport("fake-token")

        val exportBackupPath = fakeServer.eventFiles.keys.firstOrNull { it.endsWith("export-backup.json") }
        assertNotNull("/export-backup.json must be uploaded once the interval has elapsed", exportBackupPath)
        assertTrue(fakeServer.eventFiles[exportBackupPath]!!.content.isNotEmpty())
        assertTrue("lastExportTs must be refreshed", syncDataStore.lastExportTs.first() > System.currentTimeMillis() - 60_000L)

        // Interval path must NOT log an event (only monthly does).
        val logs = repository.allLogs.first()
        assertTrue(
            "interval-only path must not log a 'Monthly Auto Backup' event",
            logs.none { it.kind == "export" && it.taskTitle == "Monthly Auto Backup" }
        )
        // monthlyBackups must be unchanged by the interval path.
        assertEquals(listOf(monthKey), syncDataStore.monthlyBackups.first())
    }

    @Test
    fun maybeAutoExport_monthAlreadyCovered_andIntervalNotYetElapsed_uploadsNothing() = runBlocking {
        val monthKey = currentMonthKey()
        syncDataStore.saveExportIntervalDays(7)
        syncDataStore.saveMonthlyBackups(listOf(monthKey))
        syncDataStore.saveLastExportTs(System.currentTimeMillis() - (2L * 86_400_000L)) // only 2 of 7 days

        syncManager.maybeAutoExport("fake-token")

        assertTrue(fakeServer.eventFiles.keys.none { it.endsWith("export-backup.json") })
        assertTrue(fakeServer.eventFiles.keys.none { monthlyFilenamePattern.matches(it) })
    }

    // -----------------------------------------------------------------------------------------
    // failed upload leaves state unchanged for retry
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_failedMonthlyUpload_leavesMonthlyBackupsAndLastExportTsUnchanged() = runBlocking {
        fakeServer.forceGenericUploadFailure = true
        syncDataStore.saveExportIntervalDays(1)
        syncDataStore.saveMonthlyBackups(emptyList())
        syncDataStore.saveLastExportTs(0L)

        syncManager.maybeAutoExport("fake-token")

        assertTrue("a failed upload must leave monthlyBackups untouched, so the NEXT sync retries", syncDataStore.monthlyBackups.first().isEmpty())
        assertEquals("a failed upload must leave lastExportTs untouched", 0L, syncDataStore.lastExportTs.first())
        val logs = repository.allLogs.first()
        assertTrue("a failed upload must not log a success event", logs.none { it.kind == "export" && it.taskTitle == "Monthly Auto Backup" })
    }

    @Test
    fun maybeAutoExport_failedIntervalUpload_leavesLastExportTsUnchanged() = runBlocking {
        val monthKey = currentMonthKey()
        fakeServer.forceGenericUploadFailure = true
        syncDataStore.saveExportIntervalDays(1)
        syncDataStore.saveMonthlyBackups(listOf(monthKey)) // monthly already covered -> interval branch
        val staleTs = System.currentTimeMillis() - (5L * 86_400_000L)
        syncDataStore.saveLastExportTs(staleTs)

        syncManager.maybeAutoExport("fake-token")

        assertEquals("a failed /export-backup.json upload must leave lastExportTs untouched for retry", staleTs, syncDataStore.lastExportTs.first())
    }

    // -----------------------------------------------------------------------------------------
    // maybeAutoExport must never throw -- best-effort guard
    // -----------------------------------------------------------------------------------------

    @Test
    fun maybeAutoExport_neverThrows_evenOnUploadFailure() = runBlocking {
        fakeServer.forceGenericUploadFailure = true
        syncDataStore.saveExportIntervalDays(1)
        syncDataStore.saveMonthlyBackups(emptyList())
        syncDataStore.saveLastExportTs(0L)

        // Must not throw.
        syncManager.maybeAutoExport("fake-token")
    }
}
