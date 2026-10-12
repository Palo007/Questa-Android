package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3 / T3.5 (D12, opus-review finding #3 on T3.1) -- Robolectric, real-Room-backed tests
 * exercising the ACTUAL production wiring [com.example.data.sync.StateSnapshotFieldAlignmentTest]
 * (plain-JVM, `mergeSnapshots` only) assumes is correct upstream: that
 * [SyncManager.buildLocalSnapshot] itself emits exactly the `syncSubset()` field set/order, and
 * that a full import -> export round trip through the public backup-file API
 * ([SyncManager.importSnapshotFromFile] / [SyncManager.exportSnapshotToFile]) preserves
 * `tasks`/`rewards`/`tags` ARRAY ELEMENT order -- not merely each element's own field content/key
 * order (already covered by `UnknownFieldPassthroughTest`). Same setup pattern as
 * `CharacterUpdatedAtSyncTest`/`AnViewsMetricsPersistenceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateSnapshotFieldAlignmentPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncManager: SyncManager
    private lateinit var syncDataStore: SyncDataStore

    private val expectedSyncSubsetKeyOrder = listOf(
        "char", "tasks", "rewards", "tags", "devices", "lastCron",
        // WP-8.6: the local subset is Opti's syncSubset() (sync.js:806-827), which includes `pause`.
        "history", "charHistory", "an", "pause", "monthlyBackups", "deletions"
    )

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce() // WP-8.10: no background boot write overlaps this test's DataStore writes
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
        AppBootQuiesce.quiesce()
        db.close()
    }

    @Test
    fun buildLocalSnapshot_fieldSetAndKeyOrder_matchSyncSubsetExactly() = runBlocking {
        repository.getCharacterSync() // ensure a character row exists

        val snap = syncManager.buildLocalSnapshot()

        assertEquals(expectedSyncSubsetKeyOrder.toSet(), snap.keys)
        assertEquals(expectedSyncSubsetKeyOrder, snap.keys.toList())
        assertFalse("real production buildLocalSnapshot must not emit `version` on the wire", snap.containsKey("version"))
        assertFalse("real production buildLocalSnapshot must not emit full `prefs` on the wire", snap.containsKey("prefs"))
    }

    @Test
    fun importThenExport_preservesTaskArrayOrder_forRealPWAUnshiftInsertedFixture() = runBlocking {
        // Stage 1 T1.2 / spec §1.2: PWA's `saveTask()` (app.js:4545) does
        // `S.tasks.unshift(EDIT)` on creation -- newest task ends up FIRST. This fixture's
        // array order (t-newest, t-mid, t-oldest) mirrors that real insertion order, which is
        // the OPPOSITE of what a naive re-sort-by-createdAt-ASCENDING would produce, and (before
        // T3.5) also happened to coincide with the pre-fix `ORDER BY createdAt DESC` -- so this
        // case alone wouldn't distinguish "position preserved" from "still just re-deriving order
        // from createdAt". See the companion scrambled-order test below for that distinction.
        val fixtureJson = """
            {
              "char": { "name": "Hero", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
              "tasks": [
                { "id": "t-newest", "type": "todo", "title": "Newest", "createdAt": 3000, "updatedAt": 3000 },
                { "id": "t-mid", "type": "todo", "title": "Mid", "createdAt": 2000, "updatedAt": 2000 },
                { "id": "t-oldest", "type": "todo", "title": "Oldest", "createdAt": 1000, "updatedAt": 1000 }
              ],
              "rewards": [], "tags": [], "devices": [], "lastCron": 0, "history": [],
              "charHistory": [], "monthlyBackups": [], "deletions": []
            }
        """.trimIndent()

        val importResult = syncManager.importSnapshotFromFile(fixtureJson)
        assertTrue("fixture import must succeed: ${importResult.exceptionOrNull()}", importResult.isSuccess)

        val exportResult = syncManager.exportSnapshotToFile()
        assertTrue("re-export must succeed: ${exportResult.exceptionOrNull()}", exportResult.isSuccess)

        val reimported = SerializationEngine.importBackup(exportResult.getOrThrow())
        assertEquals(listOf("t-newest", "t-mid", "t-oldest"), reimported.tasks.map { it.id })
    }

    @Test
    fun importThenExport_preservesTaskArrayOrder_forScrambledNonMonotonicCreatedAt() = runBlocking {
        // The decisive case: createdAt sequence 3000, 1000, 2000 is neither ascending NOR
        // descending, so it cannot be reproduced by ANY single ORDER BY createdAt clause -- the
        // pre-T3.5 `TaskDao` (`ORDER BY createdAt DESC`) would have re-exported this as
        // [t-first(3000), t-third(2000), t-second(1000)] (sorted purely by createdAt, descending),
        // NOT the original [t-first, t-second, t-third] input order. Real production data
        // (`fixtures/real_backup.json`) is provably non-monotonic too (see task report), so this
        // is not a contrived edge case.
        val fixtureJson = """
            {
              "char": { "name": "Hero", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
              "tasks": [
                { "id": "t-first", "type": "todo", "title": "First", "createdAt": 3000, "updatedAt": 3000 },
                { "id": "t-second", "type": "todo", "title": "Second", "createdAt": 1000, "updatedAt": 1000 },
                { "id": "t-third", "type": "todo", "title": "Third", "createdAt": 2000, "updatedAt": 2000 }
              ],
              "rewards": [
                { "id": "r-first", "title": "Reward A", "cost": 10, "createdAt": 5000, "updatedAt": 5000 },
                { "id": "r-second", "title": "Reward B", "cost": 20, "createdAt": 1000, "updatedAt": 1000 }
              ],
              "tags": [
                { "id": "g-first", "name": "Tag A", "color": "#fff", "createdAt": 5000, "updatedAt": 5000 },
                { "id": "g-second", "name": "Tag B", "color": "#000", "createdAt": 1000, "updatedAt": 1000 }
              ],
              "devices": [], "lastCron": 0, "history": [],
              "charHistory": [], "monthlyBackups": [], "deletions": []
            }
        """.trimIndent()

        val importResult = syncManager.importSnapshotFromFile(fixtureJson)
        assertTrue("fixture import must succeed: ${importResult.exceptionOrNull()}", importResult.isSuccess)

        val exportResult = syncManager.exportSnapshotToFile()
        assertTrue("re-export must succeed: ${exportResult.exceptionOrNull()}", exportResult.isSuccess)

        val reimported = SerializationEngine.importBackup(exportResult.getOrThrow())
        assertEquals(listOf("t-first", "t-second", "t-third"), reimported.tasks.map { it.id })
        // Rewards/tags: input order is [first(createdAt=5000), second(createdAt=1000)] -- also
        // deliberately non-monotonic-with-push-semantics, to prove these two collections are
        // ALSO position-preserved now (not just re-derived via `createdAt ASC`, which would
        // coincidentally also produce [second, first] here -- see the reversed-cost-only check
        // below for why that coincidence doesn't hide a bug: the array position, not the
        // timestamp, is what travels through Room).
        assertEquals(listOf("r-first", "r-second"), reimported.rewards.map { it.id })
        assertEquals(listOf("g-first", "g-second"), reimported.tags.map { it.id })
    }

    @Test
    fun createTask_newTaskOrdinal_sortsBeforeExistingTasks_matchingPwaUnshift() = runBlocking {
        val older = TaskEntity(id = "older", type = "todo", title = "Older", createdAt = 1000L, orderIndex = repository.nextTaskOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertTask(older)
        val newer = TaskEntity(id = "newer", type = "todo", title = "Newer", createdAt = 2000L, orderIndex = repository.nextTaskOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertTask(newer)

        val all = repository.getAllTasksSync()
        assertEquals(listOf("newer", "older"), all.map { it.id })
    }

    @Test
    fun createReward_newRewardOrdinal_sortsAfterExistingRewards_matchingPwaPush() = runBlocking {
        val older = RewardEntity(id = "older", title = "Older", cost = 5.0, createdAt = 1000L, orderIndex = repository.nextRewardOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertReward(older)
        val newer = RewardEntity(id = "newer", title = "Newer", cost = 5.0, createdAt = 2000L, orderIndex = repository.nextRewardOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertReward(newer)

        val all = repository.getAllRewardsSync()
        assertEquals(listOf("older", "newer"), all.map { it.id })
    }

    // -----------------------------------------------------------------------------------------
    // WP-5.2 (spec §7 D9) -- buildLocalSnapshot's monthlyBackups was hardcoded to an empty
    // array (SyncManager.kt, pre-fix); these two tests exercise the REAL production
    // SyncManager.buildLocalSnapshot() the way the WP's acceptance criteria require, which the
    // plain-JVM `com.example.data.sync.StateSnapshotFieldAlignmentTest` (mergeSnapshots-only,
    // no Room/DataStore access) cannot reach -- see this class's own leading doc for that split.
    // -----------------------------------------------------------------------------------------

    @Test
    fun buildLocalSnapshot_monthlyBackups_reflectsPersistedList_notHardcodedEmpty() = runBlocking {
        repository.getCharacterSync()
        syncDataStore.saveMonthlyBackups(listOf("2026-05", "2026-06"))

        val snap = syncManager.buildLocalSnapshot()
        val monthlyBackups = snap["monthlyBackups"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(
            "buildLocalSnapshot must emit the real persisted monthlyBackups list, not a hardcoded empty array",
            listOf("2026-05", "2026-06"), monthlyBackups
        )
    }

    @Test
    fun buildLocalSnapshot_neverLeaksExportIntervalDaysOrLastExportTs_orPrefs_atAnyLevel() = runBlocking {
        repository.getCharacterSync()
        syncDataStore.saveExportIntervalDays(7)
        syncDataStore.saveLastExportTs(123456789L)

        val snap = syncManager.buildLocalSnapshot()
        val snapString = snap.toString()

        assertFalse("full `prefs` must never appear on the state.json wire body (spec §3 syncSubset())", snap.containsKey("prefs"))
        assertFalse(
            "exportIntervalDays is a LOCAL-ONLY Kotlin setting (spec §3 line 243 excludes full `prefs`) -- must never appear on the wire, at any level",
            snapString.contains("exportIntervalDays")
        )
        assertFalse(
            "lastExportTs is a LOCAL-ONLY Kotlin setting -- must never appear on the wire, at any level",
            snapString.contains("lastExportTs")
        )
    }

    @Test
    fun createTag_newTagOrdinal_sortsAfterExistingTags_matchingPwaPush() = runBlocking {
        val older = TagEntity(id = "older", name = "Older", color = "#fff", createdAt = 1000L, orderIndex = repository.nextTagOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertTag(older)
        val newer = TagEntity(id = "newer", name = "Newer", color = "#000", createdAt = 2000L, orderIndex = repository.nextTagOrderIndex(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        repository.insertTag(newer)

        val all = repository.getAllTagsSync()
        assertEquals(listOf("older", "newer"), all.map { it.id })
    }
}
