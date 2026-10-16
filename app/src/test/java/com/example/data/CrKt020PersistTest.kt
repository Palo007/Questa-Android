package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.boot.BootGate
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * CR-KT-020 persistence: the completion state the engine computes (the day's `history` point, the
 * REALIZED-delta `_gr`, the daily-only `_histCreated`, the todo `completedAt`) must survive the
 * Room row — a fresh read, a wire export and a parse round trip — exactly like the values the
 * golden fixture pins. Drives the real `RpgRepository` paths with the frozen physical clock, then
 * reads the row back through the DAO (no in-memory residue from the engine call).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrKt020PersistTest {

    /** The fixture generator's `now_ms` (2026-10-16T00:00:00Z; TZ=UTC, so day %20261016). */
    private val nowMs = 1_792_144_800_000L

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var savedClock: () -> Long
    private lateinit var savedZone: () -> ZoneId

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        savedClock = BootGate.clock
        savedZone = BootGate.zoneProvider
        BootGate.clock = { nowMs }
        BootGate.zoneProvider = { ZoneId.of("UTC") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(same).setTransactionExecutor(same).build()
        val store = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            store, database = db, hlc = DefaultHlcClock(1_000L) { }
        )
        runBlocking {
            repository.insertCharacter(
                CharacterEntity(lvl = 2, xp = 100, gold = 50.0, mp = 5, hp = 50.0, maxHp = 50.0, updatedAt = 0L)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        BootGate.clock = savedClock
        BootGate.zoneProvider = savedZone
        AppBootQuiesce.quiesce()
    }

    private fun daily(id: String = "d1", value: Double = 1.0, history: List<TaskHistoryPoint> = emptyList()) =
        TaskEntity(
            id = id, type = "daily", title = "D", difficulty = "easy", value = value,
            streak = 4, repeat = List(7) { true }, history = history, createdAt = 1, updatedAt = 1
        )

    private fun stored(id: String): TaskEntity = runBlocking { db.taskDao().getTaskByIdSync(id) }!!

    private fun histKey(t: TaskEntity) = SerializationEngine.buildTaskJson(t)["_histCreated"]

    @Test
    fun `daily completion persists the day point, realized _gr and _histCreated - un-tick pops them`() {
        runBlocking {
            repository.insertTask(daily())
            assertTrue(repository.completeTask("d1"))
        }
        val t = stored("d1")
        assertEquals(true, t.done)
        assertEquals(true, t.histCreated)
        assertEquals(nowMs, t.doneAt)
        assertEquals(20261016, t.doneDay)
        val gr = t.gr!!
        // The realized delta is `(value + delta) - value` (app.js:2491-2493), so it carries the
        // same IEEE-754 rounding the PWA computes -- not the raw `valueDelta` literal.
        assertEquals(t.value - 1.0, gr.delta, 0.0)
        assertEquals(1.0 + RpgEngine.valueDelta(1.0), t.value, 0.0)
        val p = t.history.single()
        assertEquals(nowMs, p.date)
        assertEquals(true, p.completed)
        assertEquals(true, p.isDue)
        assertEquals(t.value, p.value!!, 0.0)
        assertEquals(gr, p.reward)
        assertEquals(List(7) { true }, p.repeat)

        // The character write went through the same repository call.
        val ch = runBlocking { repository.getCharacterSync() }
        assertEquals(100 + gr.xp, ch.xp)
        assertEquals(Math.round((50.0 + gr.gold) * 100.0) / 100.0, ch.gold, 0.0)

        // The wire carries the PWA keys, and a parse round trip reads the stamp back.
        val wire = SerializationEngine.buildTaskJson(t)
        assertEquals(true, wire["_histCreated"]?.jsonPrimitive?.booleanOrNull)
        assertNotNull(wire["_gr"])
        assertEquals(true, SerializationEngine.parseTaskEntity(wire).histCreated)

        // Un-tick: the point THIS completion created is popped; doneAt/doneDay retracted.
        runBlocking { assertTrue(repository.uncompleteTask("d1")) }
        val u = stored("d1")
        assertNull(u.histCreated)
        assertTrue(u.history.isEmpty())
        assertFalse(u.done)
        assertNull(u.doneAt)
        assertNull(u.doneDay)
        assertFalse(SerializationEngine.buildTaskJson(u).containsKey("_histCreated"))
    }

    @Test
    fun `merged daily keeps _histCreated false - un-tick keeps the merged point and clears the stamp`() {
        val miss = TaskHistoryPoint(date = nowMs - 3_600_000, value = 1.4, completed = false, isDue = true, repeat = List(7) { true })
        runBlocking {
            repository.insertTask(daily(id = "d2", value = 2.0, history = listOf(miss)))
            assertTrue(repository.completeTask("d2"))
        }
        val t = stored("d2")
        assertEquals(false, t.histCreated)
        assertEquals(1, t.history.size)
        assertEquals(true, t.history.single().completed)
        assertEquals(false, histKey(t)?.jsonPrimitive?.booleanOrNull)

        runBlocking { assertTrue(repository.uncompleteTask("d2")) }
        val u = stored("d2")
        assertNull(u.histCreated)
        assertEquals("the merged point survives", 1, u.history.size)
        assertEquals(2.0, u.history.single().value!!, 1e-12)
        assertNull(u.history.single().reward)
        assertEquals(false, u.history.single().completed)
        assertFalse(SerializationEngine.buildTaskJson(u).containsKey("_histCreated"))
    }

    @Test
    fun `todo completion persists completedAt and its point - un-tick deletes completedAt (CR-PWA-004)`() {
        val todo = TaskEntity(id = "t1", type = "todo", title = "Buy milk", difficulty = "easy", value = 1.0, createdAt = 1, updatedAt = 1)
        runBlocking {
            repository.insertTask(todo)
            assertTrue(repository.completeTask("t1"))
        }
        val t = stored("t1")
        assertEquals(nowMs, t.completedAt)
        assertEquals(nowMs, t.doneAt)
        assertNull("todos are never stamped with _histCreated", t.histCreated)
        assertEquals(1, t.history.size)
        val wire = SerializationEngine.buildTaskJson(t)
        assertFalse(wire.containsKey("_histCreated"))
        assertNull(SerializationEngine.parseTaskEntity(wire).histCreated)

        runBlocking { assertTrue(repository.uncompleteTask("t1")) }
        val u = stored("t1")
        // CR-PWA-004 (app.js:2572 at Opti 338d430): the fixed un-tick DELETES `completedAt` -- the
        // analytics todo fallback (`!any && t.completedAt`, app.js:4428) must not count a reverted
        // todo. (Pre-fix this kept the stamp; that old behavior is held in the afdae43 fixture.)
        assertNull(u.completedAt)
        assertEquals("the point is kept -- _histCreated was never true for a todo", 1, u.history.size)
        assertEquals(false, u.history.single().completed)
        assertEquals(1.0, u.history.single().value!!, 1e-12)
        assertNull(u.history.single().reward)
        assertNull(u.doneAt)
        assertFalse(SerializationEngine.buildTaskJson(u).containsKey("_histCreated"))
        assertFalse("the deleted stamp is not emitted", SerializationEngine.buildTaskJson(u).containsKey("completedAt"))
    }
}
