package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.clock.HlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Contract `bulk-select-v1` (data half): repository bulk APIs.
 *
 * Each bulk edit must be wire-indistinguishable from N sequential single edits: every touched
 * row is rewritten with a fresh [HlcClock.now] `updatedAt` and is otherwise byte-identical, and
 * the whole batch lands in ONE Room transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkSelectTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var clock: FakeHlcClock

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
        clock = FakeHlcClock()
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            SyncDataStore(context),
            database = db,
            hlc = clock
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    private fun task(
        id: String,
        tags: List<String> = emptyList(),
        difficulty: String = "easy",
        updatedAt: Long = 1000L,
        createdAt: Long = 1000L
    ) = TaskEntity(
        id = id,
        type = "todo",
        title = "task-$id",
        tags = tags,
        difficulty = difficulty,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun `bulkUpdateTags applies per-task diffs`() = runBlocking {
        db.taskDao().insertTask(task("a", tags = listOf("X")))
        db.taskDao().insertTask(task("b", tags = emptyList()))
        db.taskDao().insertTask(task("c", tags = listOf("X", "Y")))

        repository.bulkUpdateTags(listOf("a", "b", "c"), add = setOf("Z"), remove = setOf("X"))

        assertEquals(listOf("Z"), db.taskDao().getTaskByIdSync("a")!!.tags)
        assertEquals(listOf("Z"), db.taskDao().getTaskByIdSync("b")!!.tags)
        assertEquals(listOf("Y", "Z"), db.taskDao().getTaskByIdSync("c")!!.tags)
    }

    @Test
    fun `bulkUpdateTags bumps only updatedAt on an already-correct task`() = runBlocking {
        val before = task("d", tags = listOf("Z"), updatedAt = 1000L)
        db.taskDao().insertTask(before)

        repository.bulkUpdateTags(listOf("d"), add = setOf("Z"), remove = setOf("X"))

        val after = db.taskDao().getTaskByIdSync("d")!!
        assertEquals(listOf("Z"), after.tags)
        assertTrue("updatedAt must advance", after.updatedAt > before.updatedAt)
        assertEquals("every other field must be byte-identical", before.copy(updatedAt = after.updatedAt), after)
    }

    @Test
    fun `bulkUpdateTags is one transaction - a mid-batch failure rolls everything back`() = runBlocking {
        val a = task("a", tags = listOf("X"), updatedAt = 1000L)
        val b = task("b", tags = emptyList(), updatedAt = 1000L)
        val c = task("c", tags = listOf("X", "Y"), updatedAt = 1000L)
        listOf(a, b, c).forEach { db.taskDao().insertTask(it) }

        // The second HLC stamp (task b's write) throws: with a single-transaction batch the
        // first write must roll back too, leaving every row byte-identical.
        clock.failOnCall = 2
        try {
            repository.bulkUpdateTags(listOf("a", "b", "c"), add = setOf("Z"), remove = setOf("X"))
            fail("expected the injected clock failure to propagate")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(a, db.taskDao().getTaskByIdSync("a"))
        assertEquals(b, db.taskDao().getTaskByIdSync("b"))
        assertEquals(c, db.taskDao().getTaskByIdSync("c"))
    }

    @Test
    fun `bulkDelete writes one tombstone per id`() = runBlocking {
        db.taskDao().insertTask(task("a"))
        db.taskDao().insertTask(task("b"))
        db.taskDao().insertTask(task("keep"))

        repository.bulkDelete(listOf("a", "b"))

        assertNull(db.taskDao().getTaskByIdSync("a"))
        assertNull(db.taskDao().getTaskByIdSync("b"))
        assertNotNull(db.taskDao().getTaskByIdSync("keep"))
        assertNotNull("tombstone for a", db.tombstoneDao().getTombstoneById("a"))
        assertNotNull("tombstone for b", db.tombstoneDao().getTombstoneById("b"))
    }

    @Test
    fun `bulkDelete is one transaction - a mid-batch failure rolls everything back`() = runBlocking {
        val a = task("a", updatedAt = 1000L)
        val b = task("b", updatedAt = 1000L)
        listOf(a, b).forEach { db.taskDao().insertTask(it) }

        clock.failOnCall = 2
        try {
            repository.bulkDelete(listOf("a", "b"))
            fail("expected the injected clock failure to propagate")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(a, db.taskDao().getTaskByIdSync("a"))
        assertEquals(b, db.taskDao().getTaskByIdSync("b"))
        assertNull(db.tombstoneDao().getTombstoneById("a"))
        assertNull(db.tombstoneDao().getTombstoneById("b"))
    }

    @Test
    fun `bulkSetDifficulty rejects values outside the contract set`() = runBlocking {
        db.taskDao().insertTask(task("a"))
        try {
            repository.bulkSetDifficulty(listOf("a"), "legendary")
            fail("expected require() to reject an unknown difficulty")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals("easy", db.taskDao().getTaskByIdSync("a")!!.difficulty)
    }

    @Test
    fun `bulkSetDifficulty applies to every id and stamps updatedAt`() = runBlocking {
        val a = task("a", difficulty = "easy", updatedAt = 1000L)
        val b = task("b", difficulty = "trivial", updatedAt = 1000L)
        listOf(a, b).forEach { db.taskDao().insertTask(it) }

        for (difficulty in listOf("trivial", "easy", "medium", "hard")) {
            repository.bulkSetDifficulty(listOf("a", "b"), difficulty)
            val afterA = db.taskDao().getTaskByIdSync("a")!!
            val afterB = db.taskDao().getTaskByIdSync("b")!!
            assertEquals(difficulty, afterA.difficulty)
            assertEquals(difficulty, afterB.difficulty)
            assertTrue(afterA.updatedAt > 1000L)
            assertTrue(afterB.updatedAt > 1000L)
        }
    }

    /**
     * Deterministic HLC for bulk tests: strictly increasing stamps from a fixed base (above the
     * fixtures' `updatedAt`), with an injectable single-call failure to prove transactionality.
     */
    private class FakeHlcClock : HlcClock {
        private val _lastIssued = MutableStateFlow(0L)
        override val lastIssued: StateFlow<Long> = _lastIssued
        var calls = 0
        var failOnCall = -1
        private var next = 9_000L

        override fun now(): Long {
            calls++
            if (calls == failOnCall) throw IllegalStateException("injected clock failure")
            next += 1
            _lastIssued.value = next
            return next
        }

        override fun ratchet(remoteMaxTs: Long): Boolean = false
        override fun heal(): Boolean = false
        override fun bindEventSink(sink: (kind: String, payload: Map<String, Any?>) -> Unit) {}
    }
}
