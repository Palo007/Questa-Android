package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Contract `bulk-select-v1` (ViewModel half): selection state and bulk commands.
 *
 * Selection is process-scoped UI state (never persisted): the VM takes the currently-VISIBLE
 * (filtered) ids as a parameter -- it never selects DB-wide on its own -- and a successful bulk
 * action clears the selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkSelectionViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncDataStore: SyncDataStore
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sameThreadExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(sameThreadExecutor)
            .setTransactionExecutor(sameThreadExecutor)
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore,
            database = db
        )
        val syncManager = SyncManager(context, repository, syncDataStore)
        viewModel = MainViewModel(repository, syncManager, syncDataStore, processLifecycle = null)
    }

    @After
    fun tearDown() {
        db.close()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    private fun task(id: String, tags: List<String> = emptyList(), updatedAt: Long = 1000L) =
        TaskEntity(
            id = id, type = "todo", title = "task-$id",
            tags = tags, createdAt = 1000L, updatedAt = updatedAt
        )

    @Test
    fun `selection state transitions`() {
        assertFalse(viewModel.selectionMode.value)
        assertTrue(viewModel.selectedIds.value.isEmpty())

        viewModel.enterSelectionMode()
        assertTrue(viewModel.selectionMode.value)

        viewModel.toggleSelected("a")
        viewModel.toggleSelected("b")
        assertEquals(setOf("a", "b"), viewModel.selectedIds.value)

        viewModel.toggleSelected("a")
        assertEquals(setOf("b"), viewModel.selectedIds.value)

        viewModel.selectAll(listOf("x", "y"))
        assertEquals(setOf("x", "y"), viewModel.selectedIds.value)

        viewModel.exitSelectionMode()
        assertFalse(viewModel.selectionMode.value)
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun `selectAll takes exactly the caller-supplied visible ids - never a DB-wide select`() = runBlocking {
        listOf("t1", "t2", "t3", "t4").forEach { db.taskDao().insertTask(task(it)) }

        // The UI passes the currently-VISIBLE (filtered) ids; the VM must not widen them.
        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf("t1", "t3"))

        assertEquals(setOf("t1", "t3"), viewModel.selectedIds.value)
    }

    @Test
    fun `exitSelectionMode performs zero DB writes`() = runBlocking {
        db.taskDao().insertTask(task("t1", tags = listOf("X")))
        db.taskDao().insertTask(task("t2"))
        val tasksBefore = db.taskDao().getAllTasksSync()
        val tombstonesBefore = db.tombstoneDao().getAllTombstonesSync()

        viewModel.enterSelectionMode()
        viewModel.toggleSelected("t1")
        viewModel.selectAll(listOf("t1", "t2"))
        viewModel.exitSelectionMode()

        assertEquals(tasksBefore, db.taskDao().getAllTasksSync())
        assertEquals(tombstonesBefore, db.tombstoneDao().getAllTombstonesSync())
    }

    @Test
    fun `bulkDeleteSelected deletes the selected ids, tombstones them and clears selection`() = runBlocking {
        db.taskDao().insertTask(task("t1"))
        db.taskDao().insertTask(task("t2"))
        db.taskDao().insertTask(task("t3"))

        viewModel.enterSelectionMode()
        viewModel.toggleSelected("t1")
        viewModel.toggleSelected("t3")
        viewModel.bulkDeleteSelected()

        awaitAssert {
            assertNull(db.taskDao().getTaskByIdSync("t1"))
            assertNull(db.taskDao().getTaskByIdSync("t3"))
            assertNotNull(db.taskDao().getTaskByIdSync("t2"))
            assertNotNull(db.tombstoneDao().getTombstoneById("t1"))
            assertNotNull(db.tombstoneDao().getTombstoneById("t3"))
            val deletions = syncDataStore.localDeletions.first().map { it.first }.toSet()
            assertTrue(deletions.contains("t1"))
            assertTrue(deletions.contains("t3"))
            assertFalse(viewModel.selectionMode.value)
            assertTrue(viewModel.selectedIds.value.isEmpty())
        }
    }

    @Test
    fun `bulkEditTags applies the diff and auto-exits selection`() = runBlocking {
        db.taskDao().insertTask(task("t1", tags = listOf("X")))
        db.taskDao().insertTask(task("t2"))

        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf("t1", "t2"))
        viewModel.bulkEditTags(add = setOf("Z"), remove = setOf("X"))

        awaitAssert {
            assertEquals(listOf("Z"), db.taskDao().getTaskByIdSync("t1")!!.tags)
            assertEquals(listOf("Z"), db.taskDao().getTaskByIdSync("t2")!!.tags)
            assertFalse(viewModel.selectionMode.value)
            assertTrue(viewModel.selectedIds.value.isEmpty())
        }
    }

    @Test
    fun `bulkSetDifficulty applies and auto-exits selection`() = runBlocking {
        db.taskDao().insertTask(task("t1"))
        db.taskDao().insertTask(task("t2"))

        viewModel.enterSelectionMode()
        viewModel.selectAll(listOf("t1", "t2"))
        viewModel.bulkSetDifficulty("hard")

        awaitAssert {
            assertEquals("hard", db.taskDao().getTaskByIdSync("t1")!!.difficulty)
            assertEquals("hard", db.taskDao().getTaskByIdSync("t2")!!.difficulty)
            assertFalse(viewModel.selectionMode.value)
            assertTrue(viewModel.selectedIds.value.isEmpty())
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
