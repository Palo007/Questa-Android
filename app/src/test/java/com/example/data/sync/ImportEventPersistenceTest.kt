package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-5.4 -- backup-import event persistence (Stage 4->5 carry-over fix, WORK_TRACKER.md line 55a).
 *
 * Exercises [SyncManager.importSnapshotFromFile] end-to-end against an in-memory Room DB (per
 * `00_README.md` §4 conventions, mirroring `SyncConflictRetryTest`'s setup): before this WP,
 * `backup.events` was parsed by `SerializationEngine.importBackup` but never written to
 * `event_logs` at all. These tests cover the WP's acceptance criteria: fresh import populates
 * `event_logs` with correct row count and correct `uid`/`dev`/`synthetic`; re-import is idempotent
 * (two-layer dedup, spec §3 addendum rules 16-19); an event whose `dev` differs from this device's
 * own id is excluded from `EventSyncEngine.evtUploadable`'s output after import; a legacy uid-less
 * event is imported as-is per this WP's step-3 decision (option (a) -- see class doc below and the
 * WP's own Log entry).
 *
 * Uses a schema-1 (no `_backup` envelope) fixture for simplicity: `importBackup` treats a file
 * with no `_backup` key as schema-1 and reads `char`/`tasks`/`events`/etc. directly off the root
 * object, with events read verbatim (no detokenization) -- see `importBackup`'s `isSchema2` branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportEventPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncDataStore: SyncDataStore
    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore
        )
        syncManager = SyncManager(context, repository, syncDataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Foreign device id used by every fixture event below -- guaranteed to never equal this
    // test's own local device id, which `SyncDataStore.getOrCreateDeviceId()` mints as a fresh
    // random `"dev-" + 8 hex chars` UUID fragment per in-memory DataStore instance.
    private val foreignDev = "device-A"

    /**
     * Three events, all in the same UTC month bucket (`ts` values are all small epoch-ms, i.e.
     * January 1970) so the month-bucketed dedup path (`EventSyncEngine.evtMonthKey`/
     * `evtMonthRange`) only ever needs to touch a single bucket:
     *  - `t1`/`t2`: normal events with a `uid`/`dev` stamped by their (foreign) origin device.
     *  - `t3`: a legacy pre-uid-era event -- no `uid`, no `dev` key at all.
     */
    private fun backupJson(): String = """
        {
          "char": { "name": "Hero", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
          "tasks": [], "rewards": [], "tags": [], "devices": [],
          "lastCron": 0, "history": [], "charHistory": [], "monthlyBackups": [], "deletions": [],
          "prefs": {},
          "events": [
            { "ts": 1000, "uid": "devA-000-aaaaa", "dev": "$foreignDev", "kind": "complete", "taskId": "t1", "taskTitle": "Task One" },
            { "ts": 2000, "uid": "devA-000-bbbbb", "dev": "$foreignDev", "kind": "complete", "taskId": "t2", "taskTitle": "Task Two" },
            { "ts": 3000, "kind": "complete", "taskId": "t3", "taskTitle": "Legacy Task" }
          ]
        }
    """.trimIndent()

    private suspend fun allLogsSorted() = repository.getLogsInRangeSync(0L, Long.MAX_VALUE)

    // -----------------------------------------------------------------------------------------

    @Test
    fun freshImport_populatesEventLogsWithCorrectRowCountAndFields() = runBlocking {
        val result = syncManager.importSnapshotFromFile(backupJson())
        assertTrue("import must succeed", result.isSuccess)

        val logs = allLogsSorted()
        assertEquals("exactly 3 rows for the fixture's 3 events", 3, logs.size)

        val t1 = logs.single { it.taskId == "t1" }
        assertEquals("devA-000-aaaaa", t1.uid)
        assertEquals(foreignDev, t1.dev)
        assertEquals("complete", t1.kind)
        assertEquals("Task One", t1.taskTitle)
        assertFalse("fixture did not mark this event synthetic", t1.synthetic)

        val t2 = logs.single { it.taskId == "t2" }
        assertEquals("devA-000-bbbbb", t2.uid)
        assertEquals(foreignDev, t2.dev)
    }

    @Test
    fun reimport_isIdempotent_noAdditionalRowsForExistingUids() = runBlocking {
        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)
        assertEquals(3, allLogsSorted().size)

        // Re-importing the SAME backup file a second time must insert ZERO additional rows for
        // events whose uid was already present (acceptance criterion 2).
        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)
        val logs = allLogsSorted()
        assertEquals("idempotent re-import: row count must not double", 3, logs.size)
        assertEquals(1, logs.count { it.uid == "devA-000-aaaaa" })
        assertEquals(1, logs.count { it.uid == "devA-000-bbbbb" })
    }

    @Test
    fun importOverlappingWithAlreadySyncedEvent_doesNotDuplicate() = runBlocking {
        // Simulate an event already present locally from the LIVE /events/*.json pull pipeline
        // (same uid as one of the backup's events, inserted directly via insertImportedLog --
        // i.e. NOT via this import path), before the backup import ever runs.
        repository.insertImportedLog(
            com.example.data.local.entities.EventLogEntity(
                timestamp = 1000L,
                uid = "devA-000-aaaaa",
                dev = foreignDev,
                kind = "complete",
                taskId = "t1",
                taskTitle = "Task One"
            )
        )
        assertEquals(1, allLogsSorted().size)

        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)

        val logs = allLogsSorted()
        // 1 pre-existing (t1) + 1 new (t2) + 1 new legacy uid-less (t3) = 3; t1's backup copy
        // must NOT have been duplicated (acceptance criterion 3).
        assertEquals(3, logs.size)
        assertEquals(1, logs.count { it.uid == "devA-000-aaaaa" })
    }

    @Test
    fun foreignDeviceEvent_excludedFromEvtUploadable_afterImport() = runBlocking {
        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)
        val myDev = syncDataStore.getOrCreateDeviceId()
        assertNotEquals("sanity: fixture's foreign dev must differ from this device's own id", myDev, foreignDev)

        val logs = allLogsSorted()
        val uploadable = EventSyncEngine.evtUploadable(logs, myDev, sinceTs = 0L)

        // Acceptance criterion 4: an imported event whose `dev` differs from the importing
        // device's own id is NEVER selected by evtUploadable on a subsequent push.
        assertTrue(
            "no imported foreign-dev event may be considered uploadable",
            uploadable.none { it.dev == foreignDev }
        )
        assertTrue(uploadable.none { it.uid == "devA-000-aaaaa" || it.uid == "devA-000-bbbbb" })
    }

    @Test
    fun legacyUidLessEvent_isImportedAsIs_uidAndDevStayNull() = runBlocking {
        // Step 3 decision (WP-5.4 Log): option (a) -- imported via insertImportedLog as-is, no
        // re-stamping to the local device's uid/dev, no forced synthetic=true.
        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)

        val myDev = syncDataStore.getOrCreateDeviceId()
        val legacy = allLogsSorted().single { it.taskId == "t3" }

        assertNull("legacy uid-less event must stay uid=null, not deduped/synthesized", legacy.uid)
        assertNull("legacy uid-less event must stay dev=null", legacy.dev)
        assertNotEquals(
            "must NOT be silently reassigned to the importing device (that would be the " +
                "repository.insertLog bug this WP's insertImportedLog avoids)",
            myDev,
            legacy.dev
        )
        assertFalse("option (a): no forced synthetic=true", legacy.synthetic)
    }

    @Test
    fun exportedBackup_reEmitsImportedEvent_withOriginalUidDevIntact() = runBlocking {
        assertTrue(syncManager.importSnapshotFromFile(backupJson()).isSuccess)

        val exportResult = syncManager.exportSnapshotToFile()
        assertTrue("export must succeed", exportResult.isSuccess)

        val reImported = SerializationEngine.importBackup(exportResult.getOrThrow())
        val t1 = reImported.events.single { it.taskId == "t1" }

        // Acceptance criterion 5 (round-trip fidelity): a subsequent exportSnapshotToFile() call
        // re-emits the imported events with their original uid/dev intact.
        assertEquals("devA-000-aaaaa", t1.uid)
        assertEquals(foreignDev, t1.dev)
    }

    /**
     * Regression guard for a second-order gap uncovered while implementing this WP: moving
     * `synthetic` into `importBackup`'s `knownKeys` (step 1) removes it from the opaque
     * `payloadJson` passthrough that was PREVIOUSLY the only thing round-tripping it (since
     * `EventLogEntity.synthetic` is a non-nullable Boolean, unlike `uid`/`dev`, so there is no way
     * to tell "source never had this key" from "explicitly false" once it's in a typed column).
     * `SerializationEngine.exportBackup`'s `eventsJson` builder was updated to emit `synthetic`
     * ONLY when true, to (a) preserve a genuinely synthetic event's flag across a round-trip
     * (correctness-critical -- spec addendum rule 1/17/25 exclude synthetic events from ever being
     * uploaded/ingested) without (b) injecting a spurious `synthetic:false` key into every plain
     * event that never had one (most real events, per `golden_backup.json`'s own fixture).
     */
    @Test
    fun syntheticTrueEvent_survivesRoundTrip_andSyntheticFalseEventGainsNoSpuriousKey() {
        val json = """
            {
              "char": { "name": "Hero", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
              "tasks": [], "rewards": [], "tags": [], "devices": [],
              "lastCron": 0, "history": [], "charHistory": [], "monthlyBackups": [], "deletions": [],
              "prefs": {},
              "events": [
                { "ts": 1000, "uid": "devA-000-syn", "dev": "$foreignDev", "kind": "lifecycle", "synthetic": true, "taskId": "sTrue" },
                { "ts": 2000, "uid": "devA-000-plain", "dev": "$foreignDev", "kind": "complete", "taskId": "sFalse" }
              ]
            }
        """.trimIndent()

        val imported = SerializationEngine.importBackup(json)
        assertTrue("typed synthetic column must be true on import", imported.events.single { it.taskId == "sTrue" }.synthetic)
        assertFalse(imported.events.single { it.taskId == "sFalse" }.synthetic)

        val reExported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = imported.character,
            tasks = imported.tasks,
            rewards = imported.rewards,
            events = imported.events,
            deletions = imported.deletions,
            lastCron = imported.lastCron,
            tags = imported.tags,
            devices = imported.devices,
            charHistory = imported.charHistory,
            monthlyBackups = imported.monthlyBackups,
            globalHistory = imported.globalHistory,
            snapshotExtras = imported.rawSnapshot
        )
        val reImported = SerializationEngine.importBackup(reExported)

        assertTrue(
            "a genuinely synthetic event must not silently lose that flag across export/re-import",
            reImported.events.single { it.taskId == "sTrue" }.synthetic
        )
        assertFalse(
            "a plain event that never carried a synthetic key must not gain one on re-export",
            reImported.events.single { it.taskId == "sFalse" }.synthetic
        )
    }
}
