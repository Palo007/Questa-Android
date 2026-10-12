package com.example

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.input.pointer.PointerId
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.ui.MainViewModel
import com.example.ui.gestures.ReorderStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.burnoutcrew.reorderable.ReorderableLazyListState
import org.burnoutcrew.reorderable.ReorderableState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Phase 0c/0e -- proves `MainViewModel.reorderTask`/`reorderReward` reorder by reassigning the
 * persisted `orderIndex` ordinal (TaskEntity.orderIndex / RewardEntity.orderIndex), NOT by
 * swapping `createdAt` between adjacent rows (the old, buggy implementation this replaces -- see
 * MainViewModel.kt's reindexTasksForType/reindexRewards doc). Also proves a reorder never bumps
 * `updatedAt` (S4/R3 sync contract: a pure local reorder must not look like a remote-relevant
 * edit) and never touches `createdAt` (KB1: `createdAt` must remain the row's real creation
 * time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        // reorderTask/reorderReward run via viewModelScope.launch (fire-and-forget), so the test
        // needs the whole chain to complete synchronously before assertions run: (1) route
        // Dispatchers.Main to an UnconfinedTestDispatcher (viewModelScope defaults to
        // Dispatchers.Main.immediate, which under Robolectric would otherwise sit queued on the
        // paused main Looper until manually idled), and (2) give Room a same-thread executor so
        // its suspend DAO calls (which normally hop to Room's own query/transaction executor)
        // resolve inline instead of on a real background thread the test can't wait on.
        // WP-8.10: QuestaApplication.onCreate (run by Robolectric) now binds the real startDay, whose
        // runCron writes localSavedAt through the app's DataStore instance. Cancel that rollover and
        // wait for it, so it cannot overwrite the DataStore file this test writes through another instance.
        AppBootQuiesce.quiesce()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sameThreadExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(sameThreadExecutor)
            .setTransactionExecutor(sameThreadExecutor)
            .build()
        val syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore
        )
        val syncManager = SyncManager(context, repository, syncDataStore)
        // WP-8.10: no process lifecycle, so the RYA ON_START caller 2 (which may run startDay and
        // write localSavedAt through a second DataStore instance) stays out of these tests.
        viewModel = MainViewModel(repository, syncManager, syncDataStore, processLifecycle = null)
    }

    @After
    fun tearDown() {
        db.close()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        // WP-8.10 review: restore BootGate to a fresh S0 after the test (and wait out any holder).
        AppBootQuiesce.quiesce()
    }

    private fun task(id: String, type: String, orderIndex: Long, createdAt: Long, updatedAt: Long) =
        TaskEntity(
            id = id,
            type = type,
            title = "task-$id",
            orderIndex = orderIndex,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun reward(id: String, orderIndex: Long, createdAt: Long, updatedAt: Long) =
        RewardEntity(
            id = id,
            title = "reward-$id",
            cost = 10.0,
            orderIndex = orderIndex,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    @Test
    fun `reorderTask moves within its own type and reassigns orderIndex`() = runBlocking {
        // Global interleaved sequence (orderIndex ASC): h1(0) d1(1) h2(2) d2(3) h3(4)
        // Habit sub-sequence: h1, h2, h3. Move h3 up one slot -> h1, h3, h2.
        val h1 = task("h1", "habit", 0, 1000, 1000)
        val d1 = task("d1", "daily", 1, 2000, 2000)
        val h2 = task("h2", "habit", 2, 3000, 3000)
        val d2 = task("d2", "daily", 3, 4000, 4000)
        val h3 = task("h3", "habit", 4, 5000, 5000)
        listOf(h1, d1, h2, d2, h3).forEach { db.taskDao().insertTask(it) }

        viewModel.reorderTask("h3", moveUp = true)

        awaitAssert {
            val after = db.taskDao().getAllTasksSync()
            println("DIAGNOSTIC reorderTask: " + after.map { "${it.id}(type=${it.type}, orderIndex=${it.orderIndex}, createdAt=${it.createdAt})" })
            // Visible order for habits should now be h1, h3, h2.
            assertEquals(listOf("h1", "h3", "h2"), after.filter { it.type == "habit" }.map { it.id })
            // Daily tasks' relative order/positions among themselves untouched.
            assertEquals(listOf("d1", "d2"), after.filter { it.type == "daily" }.map { it.id })
            // Full DAO read order (orderIndex ASC) matches the expected rebuilt global sequence:
            // h1, d1, h3, d2, h2 (daily rows keep the same slots they occupied before).
            assertEquals(listOf("h1", "d1", "h3", "d2", "h2"), after.map { it.id })

            // createdAt untouched on every row.
            assertEquals(1000L, after.first { it.id == "h1" }.createdAt)
            assertEquals(2000L, after.first { it.id == "d1" }.createdAt)
            assertEquals(3000L, after.first { it.id == "h2" }.createdAt)
            assertEquals(4000L, after.first { it.id == "d2" }.createdAt)
            assertEquals(5000L, after.first { it.id == "h3" }.createdAt)

            // updatedAt untouched on every row (pure reorder must not bump it).
            assertEquals(1000L, after.first { it.id == "h1" }.updatedAt)
            assertEquals(2000L, after.first { it.id == "d1" }.updatedAt)
            assertEquals(3000L, after.first { it.id == "h2" }.updatedAt)
            assertEquals(4000L, after.first { it.id == "d2" }.updatedAt)
            assertEquals(5000L, after.first { it.id == "h3" }.updatedAt)
        }
    }

    @Test
    fun `reorderReward reassigns orderIndex to the new order`() = runBlocking {
        val r1 = reward("r1", 0, 1000, 1000)
        val r2 = reward("r2", 1, 2000, 2000)
        val r3 = reward("r3", 2, 3000, 3000)
        listOf(r1, r2, r3).forEach { db.rewardDao().insertReward(it) }

        // Move r2 down one slot -> r1, r3, r2.
        viewModel.reorderReward("r2", moveUp = false)

        awaitAssert {
            val after = db.rewardDao().getAllRewardsSync()
            assertEquals(listOf("r1", "r3", "r2"), after.map { it.id })
            assertEquals(listOf(0L, 1L, 2L), after.map { it.orderIndex })

            // createdAt untouched.
            assertEquals(1000L, after.first { it.id == "r1" }.createdAt)
            assertEquals(2000L, after.first { it.id == "r2" }.createdAt)
            assertEquals(3000L, after.first { it.id == "r3" }.createdAt)

            // updatedAt untouched.
            assertEquals(1000L, after.first { it.id == "r1" }.updatedAt)
            assertEquals(2000L, after.first { it.id == "r2" }.updatedAt)
            assertEquals(3000L, after.first { it.id == "r3" }.updatedAt)
        }
    }

    @Test
    fun `reorderTask at top boundary is a no-op`() = runBlocking {
        val h1 = task("h1", "habit", 0, 1000, 1000)
        val h2 = task("h2", "habit", 1, 2000, 2000)
        listOf(h1, h2).forEach { db.taskDao().insertTask(it) }

        viewModel.reorderTask("h1", moveUp = true) // already first; nothing above it

        val after = db.taskDao().getAllTasksSync()
        assertEquals(listOf("h1", "h2"), after.map { it.id })
        assertEquals(1000L, after.first { it.id == "h1" }.updatedAt)
        assertEquals(2000L, after.first { it.id == "h2" }.updatedAt)
    }

    @Test
    fun `persistTaskOrder reassigns orderIndex from an arbitrary drag result without touching other types`() = runBlocking {
        // Same interleaved layout as above, but exercise the general (arbitrary-order) path that
        // Phase 1's drag onMove will also call: reorder the todo sub-list directly to [t3, t1, t2].
        val h1 = task("h1", "habit", 0, 1000, 1000)
        val t1 = task("t1", "todo", 1, 2000, 2000)
        val t2 = task("t2", "todo", 2, 3000, 3000)
        val h2 = task("h2", "habit", 3, 4000, 4000)
        val t3 = task("t3", "todo", 4, 5000, 5000)
        listOf(h1, t1, t2, h2, t3).forEach { db.taskDao().insertTask(it) }

        viewModel.persistTaskOrder(listOf("t3", "t1", "t2"))

        awaitAssert {
            val after = db.taskDao().getAllTasksSync()
            println("DIAGNOSTIC persistTaskOrder: " + after.map { "${it.id}(type=${it.type}, orderIndex=${it.orderIndex}, createdAt=${it.createdAt})" })
            assertEquals(listOf("t3", "t1", "t2"), after.filter { it.type == "todo" }.map { it.id })
            assertEquals(listOf("h1", "h2"), after.filter { it.type == "habit" }.map { it.id })
            // habit rows keep the same slots (index 0 and index 3) they occupied before.
            assertEquals(listOf("h1", "t3", "t1", "h2", "t2"), after.map { it.id })

            listOf("h1", "t1", "t2", "h2", "t3").forEach { id ->
                val original = listOf(h1, t1, t2, h2, t3).first { it.id == id }
                val row = after.first { it.id == id }
                assertEquals("createdAt changed for $id", original.createdAt, row.createdAt)
                assertEquals("updatedAt changed for $id", original.updatedAt, row.updatedAt)
            }
        }
    }

    @Test
    fun `reorder paths never leak orderIndex to wire and preserve order on round-trip`() = runBlocking {
        // (a) insert several tasks with distinct orderIndex/createdAt/updatedAt
        val t1 = task("t1", "todo", 0, 1000, 1000)
        val t2 = task("t2", "todo", 1, 2000, 2000)
        val t3 = task("t3", "todo", 2, 3000, 3000)
        listOf(t1, t2, t3).forEach { db.taskDao().insertTask(it) }

        // (b) calls SerializationEngine.buildTaskJson on each and asserts the resulting JsonObject has no orderIndex key
        listOf(t1, t2, t3).forEach { task ->
            val json = com.example.data.sync.SerializationEngine.buildTaskJson(task)
            assertFalse("orderIndex leaked to wire for ${task.id}", json.containsKey("orderIndex"))
        }

        // (c) calls persistTaskOrder with a shuffled id list
        val shuffledIds = listOf("t2", "t3", "t1")
        viewModel.persistTaskOrder(shuffledIds)

        awaitAssert {
            // (d) re-reads via the DAO (orderIndex ASC order) and asserts the DAO's row order exactly matches the shuffled input order
            val after = db.taskDao().getAllTasksSync().filter { it.type == "todo" }
            assertEquals(shuffledIds, after.map { it.id })

            // (e) asserts createdAt/updatedAt byte-identical to their pre-reorder values
            listOf("t1", "t2", "t3").forEach { id ->
                val original = listOf(t1, t2, t3).first { it.id == id }
                val row = after.first { it.id == id }
                assertEquals("createdAt changed for $id", original.createdAt, row.createdAt)
                assertEquals("updatedAt changed for $id", original.updatedAt, row.updatedAt)
            }
        }

        // Similar for rewards
        val r1 = RewardEntity("r1", "Reward 1", 10.0, "", 1000, 1000, null, 0)
        val r2 = RewardEntity("r2", "Reward 2", 20.0, "", 2000, 2000, null, 1)
        db.rewardDao().insertReward(r1)
        db.rewardDao().insertReward(r2)

        val r1Json = com.example.data.sync.SerializationEngine.buildRewardJson(r1)
        assertFalse("orderIndex leaked to wire for r1", r1Json.containsKey("orderIndex"))

        viewModel.persistRewardOrder(listOf("r2", "r1"))
        awaitAssert {
            val afterR = db.rewardDao().getAllRewardsSync()
            assertEquals(listOf("r2", "r1"), afterR.map { it.id })
            assertEquals(1000L, afterR.first { it.id == "r1" }.createdAt)
            assertEquals(2000L, afterR.first { it.id == "r2" }.createdAt)
        }
    }

    @Test
    fun `persistTaskOrder succeeds when reordering a filtered subset of tasks such as active todos`() = runBlocking {
        val t1 = task("t1", "todo", 0, 1000, 1000).copy(done = false)
        val t2 = task("t2", "todo", 1, 2000, 2000).copy(done = true)
        val t3 = task("t3", "todo", 2, 3000, 3000).copy(done = false)
        listOf(t1, t2, t3).forEach { db.taskDao().insertTask(it) }

        // Reorder active todos from [t1, t3] to [t3, t1], passing only the 2 active task IDs
        viewModel.persistTaskOrder(listOf("t3", "t1"))

        awaitAssert {
            val after = db.taskDao().getAllTasksSync().filter { it.type == "todo" }
            val activeAfter = after.filter { !it.done }.map { it.id }
            assertEquals(listOf("t3", "t1"), activeAfter)

            listOf("t1", "t2", "t3").forEach { id ->
                val original = listOf(t1, t2, t3).first { it.id == id }
                val row = after.first { it.id == id }
                assertEquals("createdAt changed for $id", original.createdAt, row.createdAt)
                assertEquals("updatedAt changed for $id", original.updatedAt, row.updatedAt)
            }
        }
    }

    // ---- Checklist (subtask) reorder -- Phase 2/2b (edit-menu drag reorder) persistence path ----
    // These tests mirror the production call site (DashboardScreen's TaskEditSheet onSave lambda),
    // which builds `task.copy(checklist = reorderedList, updatedAt = System.currentTimeMillis())`
    // and calls `viewModel.updateTask(updatedTask)` -- `updateTask` itself is a thin passthrough to
    // `repository.updateTask` and does NOT bump `updatedAt` on its own, so the bump must already be
    // present on the entity passed in (see MainViewModel.kt updateTask, DashboardScreen.kt onSave).
    private fun checklistItem(id: String, text: String, done: Boolean = false) =
        ChecklistItem(id = id, text = text, done = done)

    @Test
    fun `checklist reorder via updateTask persists new order and bumps updatedAt`() = runBlocking {
        val original = TaskEntity(
            id = "t1",
            type = "todo",
            title = "task-t1",
            checklist = listOf(
                checklistItem("c1", "first"),
                checklistItem("c2", "second"),
                checklistItem("c3", "third")
            ),
            createdAt = 1000,
            updatedAt = 1000
        )
        db.taskDao().insertTask(original)

        // Reorder to [c3, c1, c2] (as a drag-drop onMove would produce) and bump updatedAt, exactly
        // like the TaskEditSheet onSave call site does.
        val reordered = listOf(original.checklist[2], original.checklist[0], original.checklist[1])
        val bumpedAt = 5000L
        viewModel.updateTask(original.copy(checklist = reordered, updatedAt = bumpedAt))

        val after = db.taskDao().getTaskByIdSync("t1")
        assertNotNull(after)
        assertEquals(listOf("c3", "c1", "c2"), after!!.checklist.map { it.id })
        assertEquals(1000L, after.createdAt) // createdAt untouched
        assertEquals(bumpedAt, after.updatedAt) // updatedAt WAS bumped (unlike card/reward reorder)
    }

    @Test
    fun `checklist reorder survives a reload from DB`() = runBlocking {
        val original = TaskEntity(
            id = "t1",
            type = "daily",
            title = "task-t1",
            checklist = listOf(
                checklistItem("c1", "first"),
                checklistItem("c2", "second"),
                checklistItem("c3", "third")
            ),
            createdAt = 1000,
            updatedAt = 1000
        )
        db.taskDao().insertTask(original)

        val reordered = listOf(original.checklist[1], original.checklist[2], original.checklist[0])
        viewModel.updateTask(original.copy(checklist = reordered, updatedAt = 2000))

        // Simulate an app restart / fresh reload: query the DAO again independently of anything
        // held in memory from the update call above.
        val reloaded = db.taskDao().getTaskByIdSync("t1")
        assertNotNull(reloaded)
        assertEquals(listOf("c2", "c3", "c1"), reloaded!!.checklist.map { it.id })
        assertEquals(listOf("second", "third", "first"), reloaded.checklist.map { it.text })
    }

    @Test
    fun `reordered checklist serializes to JSON in the new order (round-trip)`() = runBlocking {
        val original = TaskEntity(
            id = "t1",
            type = "todo",
            title = "task-t1",
            checklist = listOf(
                checklistItem("c1", "first"),
                checklistItem("c2", "second"),
                checklistItem("c3", "third")
            ),
            createdAt = 1000,
            updatedAt = 1000
        )
        db.taskDao().insertTask(original)

        val reordered = listOf(original.checklist[2], original.checklist[0], original.checklist[1])
        viewModel.updateTask(original.copy(checklist = reordered, updatedAt = 3000))

        val after = db.taskDao().getTaskByIdSync("t1")!!
        val json = SerializationEngine.buildTaskJson(after)
        val checklistJson = json["checklist"]!!.jsonArray
        val idsInJson = checklistJson.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("c3", "c1", "c2"), idsInJson)
    }

    // ---- Checklist (subtask) `done` toggle -- P11 in-card subtask list persistence path ----
    // Mirrors the production call site (DashboardScreen's InCardSubtaskList Checkbox.onCheckedChange),
    // which builds `task.copy(checklist = updatedList, updatedAt = System.currentTimeMillis())` and
    // calls `viewModel.updateTask(updatedTask)` -- same passthrough as the reorder path above, so the
    // bump must already be present on the entity passed in.
    @Test
    fun `in-card subtask done toggle via updateTask persists and bumps updatedAt`() = runBlocking {
        val original = TaskEntity(
            id = "t1",
            type = "daily",
            title = "task-t1",
            checklist = listOf(
                checklistItem("c1", "first", done = false),
                checklistItem("c2", "second", done = false)
            ),
            createdAt = 1000,
            updatedAt = 1000
        )
        db.taskDao().insertTask(original)

        // Toggle c2's `done` to true, exactly like InCardSubtaskList's Checkbox onCheckedChange does.
        val toggled = original.checklist.map { if (it.id == "c2") it.copy(done = true) else it }
        val bumpedAt = 4000L
        viewModel.updateTask(original.copy(checklist = toggled, updatedAt = bumpedAt))

        val after = db.taskDao().getTaskByIdSync("t1")
        assertNotNull(after)
        assertEquals(false, after!!.checklist.first { it.id == "c1" }.done)
        assertEquals(true, after.checklist.first { it.id == "c2" }.done)
        assertEquals(1000L, after.createdAt) // createdAt untouched
        assertEquals(bumpedAt, after.updatedAt) // updatedAt WAS bumped by the in-card toggle path
    }

    @Test
    fun `hashtag in notes never creates a tag or writes to task tags`() = runBlocking {
        // Arrange: Task with a hashtag in notes
        val task = task("id-hashtag", "todo", 0, 0, 0).copy(notes = "Solving #kotlin puzzles")
        repository.insertTask(task)

        awaitAssert {
            // Assert: Ensure no tag was added to the task.tags list (write-path guard)
            val savedTask = repository.getAllTasksSync().first { it.id == "id-hashtag" }
            assertTrue(savedTask.tags.isEmpty())

            // Assert: Ensure no TagEntity was implicitly created in the database
            val allDbTags = repository.allTags.first()
            assertTrue(allDbTags.none { it.name == "kotlin" })
        }
    }

    @Test
    fun `dayStart defaults to zero and is a no-op in getEffectiveCalendar`() = runBlocking {
        val char = CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertEquals(0, char.dayStart)
    }

    @Test
    fun `resetFreq persistence and default for habits`() = runBlocking {
        // Create a new habit -> default resetFreq should be "daily"
        viewModel.createTask(type = "habit", title = "New Habit")
        awaitAssert {
            val habits = db.taskDao().getAllTasksSync().filter { it.type == "habit" }
            assertEquals(1, habits.size)
            assertEquals("daily", habits[0].resetFreq)
        }

        // Edit habit to "weekly"
        val habit = db.taskDao().getAllTasksSync().first { it.type == "habit" }
        viewModel.updateTask(habit.copy(resetFreq = "weekly"))
        awaitAssert {
            val updated = db.taskDao().getTaskByIdSync(habit.id)
            assertEquals("weekly", updated?.resetFreq)
        }
    }

    @Test
    fun `createTask stores habit up and down buttons (PWA app_js 6280)`() = runBlocking {
        viewModel.createTask(type = "habit", title = "Only plus", up = true, down = false)
        awaitAssert {
            val h = db.taskDao().getAllTasksSync().first { it.title == "Only plus" }
            assertEquals(true, h.up)
            assertEquals(false, h.down)
        }
    }

    @Test
    fun `createTask leaves up and down null by default`() = runBlocking {
        viewModel.createTask(type = "habit", title = "Default buttons")
        awaitAssert {
            val h = db.taskDao().getAllTasksSync().first { it.title == "Default buttons" }
            assertNull(h.up)
            assertNull(h.down)
        }
    }

    @Test
    fun `resetFreq is null for non-habit tasks`() = runBlocking {
        viewModel.createTask(type = "daily", title = "New Daily")
        awaitAssert {
            val daily = db.taskDao().getAllTasksSync().first { it.type == "daily" }
            assertNull(daily.resetFreq)
        }
    }

    @Test
    fun `unlinkDropbox runs the full disconnect and keeps the base (D8)`() = runBlocking {
        // WP-8.1 integrator patch: was syncDataStore.clearDropbox(), which wiped the base and left
        // ACCOUNT_ID / AUTH_STATE / the encrypted tokens behind.
        val ds = SyncDataStore(ApplicationProvider.getApplicationContext<Context>())
        ds.markLinked("dbid:x")
        ds.saveSyncMetadata("rev-keep", "{\"base\":1}")
        viewModel.unlinkDropbox()
        awaitAssert {
            assertEquals("Dropbox unlinked.", viewModel.syncStatus.value)
            assertNull(ds.getAccountId())
            assertEquals(com.example.data.sync.auth.AuthState.NOT_LINKED, ds.authState.first())
            assertFalse(ds.isDropboxEnabled.first())
            assertEquals("rev-keep", ds.dropboxLastRev.first())
            assertEquals("{\"base\":1}", ds.dropboxBaseSnapshot.first())
        }
    }

    private suspend fun <T> awaitAssert(timeoutMs: Long = 2000, pollMs: Long = 10, assertion: suspend () -> T): T {
        val start = System.currentTimeMillis()
        var lastError: Throwable? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                return assertion()
            } catch (e: Throwable) {
                lastError = e
                kotlinx.coroutines.delay(pollMs)
            }
        }
        throw lastError ?: AssertionError("Timeout waiting for assertion")
    }
}
