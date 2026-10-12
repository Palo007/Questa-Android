package com.example.data.activity

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.RpgRepository
import com.example.data.attachments.Attachment
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.ui.EditorTitleRules
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * CR-KT-017: the editor Save reads the LIVE row in one transaction and writes back only the
 * fields the user changed (Opti saveTask b7a94cd). The `edit` event still diffs base vs the
 * editor's copy, never the merged row (RE-PWA-017).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrKt017WriteBackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(same).setTransactionExecutor(same).build()
        val store = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            store, database = db
        )
        viewModel = MainViewModel(repository, SyncManager(context, repository, store), store, processLifecycle = null)
    }

    @After
    fun tearDown() {
        db.close()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    private fun logs() = runBlocking { db.eventLogDao().getLogsInRangeSync(Long.MIN_VALUE, Long.MAX_VALUE) }
    /** The `edit` event is written after the row (and after markLocalSaved's DataStore write): wait for it. */
    private fun editLog(): com.example.data.local.entities.EventLogEntity {
        var e: com.example.data.local.entities.EventLogEntity? = null
        awaitAssert { e = logs().single { it.kind == "edit" } }
        return e!!
    }
    private fun stored(id: String) = runBlocking { db.taskDao().getTaskByIdSync(id) }!!
    private fun put(t: TaskEntity) = runBlocking { db.taskDao().insertTask(t) }

    private fun awaitAssert(block: () -> Unit) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            try { block(); return } catch (e: Throwable) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(20)
            }
        }
    }

    private val a = ChecklistItem(id = "a", text = "shop", done = false)
    private val c = ChecklistItem(id = "c", text = "peer", done = false, touchedAt = 50)
    private val todo = TaskEntity(id = "t1", type = "todo", title = "Buy milk", checklist = listOf(a),
        repeat = EditorTitleRules.NEW_TASK_REPEAT, createdAt = 1, updatedAt = 1)
    private val habit = TaskEntity(id = "h1", type = "habit", title = "Push-ups", cUp = 2, value = 1.0,
        repeat = listOf(true, false, true, false, true, false, true), createdAt = 1, updatedAt = 1)
    private val char = CharacterEntity(xp = 0, gold = 0.0, mp = 0, hp = 50.0, maxHp = 50.0, lvl = 1, updatedAt = 1L)

    @Test fun `sync-arrived subtask is kept in the saved task and not logged as added`() {
        put(todo.copy(checklist = listOf(a, c), updatedAt = 40))         // a sync merged c in mid-edit
        viewModel.saveEditedTask(todo.copy(title = "Buy oat milk"), base = todo)
        awaitAssert { assertEquals("Buy oat milk", stored("t1").title) }
        assertEquals(listOf("a", "c"), stored("t1").checklist.map { it.id })
        val e = editLog()
        assertFalse(e.payloadJson!!, e.payloadJson!!.contains("checklist"))
    }

    @Test fun `user ticks a subtask while c syncs in - c kept, only the tick is logged`() {
        put(todo.copy(checklist = listOf(a, c), updatedAt = 40))
        viewModel.saveEditedTask(todo.copy(checklist = listOf(a.copy(done = true))), base = todo)
        awaitAssert { assertEquals(true, stored("t1").checklist.first().done) }
        assertEquals(listOf("a", "c"), stored("t1").checklist.map { it.id })
        // RE-PWA-018: the ticked subtask is stamped from the HLC; the peer's keeps its own touchedAt.
        assertTrue((stored("t1").checklist[0].touchedAt ?: 0L) > 0L)
        assertEquals(50L, stored("t1").checklist[1].touchedAt)
        val p = editLog().payloadJson!!
        assertTrue(p, p.contains("checklist"))
        assertFalse(p, p.contains("peer"))
    }

    @Test fun `peer counter tap during a title edit is kept`() {
        put(habit.copy(cUp = 3, value = 2.0, updatedAt = 40))
        viewModel.saveEditedTask(habit.copy(title = "Push-ups x10"), base = habit, editedCUp = 2, editedCDown = 0)
        awaitAssert { assertEquals("Push-ups x10", stored("h1").title) }
        assertEquals(3, stored("h1").cUp)
        assertEquals(2.0, stored("h1").value, 0.0)
        assertEquals(habit.repeat, stored("h1").repeat)
        assertTrue(editLog().payloadJson!!.let { !it.contains("counter") && !it.contains("schedule") })
    }

    @Test fun `cDown-only edit keeps the peer's cUp and charges only the user's down delta`() {
        runBlocking { repository.insertCharacter(char) }
        put(habit.copy(cUp = 3, value = 2.0, updatedAt = 40))
        viewModel.saveEditedTask(habit, base = habit, editedCUp = 2, editedCDown = 1)
        awaitAssert { assertEquals(1, stored("h1").cDown) }
        assertEquals(3, stored("h1").cUp)
        val ch = runBlocking { repository.getCharacterSync() }
        assertEquals(0, ch.xp)                 // no up loop ran
        assertTrue(ch.hp < 50.0)               // one down loop ran
        assertTrue(editLog().payloadJson!!.contains("counter"))
    }

    @Test fun `counter edit never undoes an image added while the editor was open`() {
        runBlocking { repository.insertCharacter(char) }
        val img = Attachment(id = "i1", sha = "b".repeat(64), w = 1, h = 1, bytes = 1, addedAt = 5)
        put(habit.copy(attachments = listOf(img), updatedAt = 40))
        viewModel.saveEditedTask(habit, base = habit, editedCUp = 3, editedCDown = 0)
        awaitAssert { assertEquals(3, stored("h1").cUp) }
        assertEquals(listOf(img), stored("h1").attachments)
    }

    @Test fun `a task deleted while the editor was open is not written and logs nothing`() {
        viewModel.saveEditedTask(todo.copy(title = "X"), base = todo)
        Thread.sleep(150)
        assertNull(runBlocking { db.taskDao().getTaskByIdSync("t1") })
        assertTrue(logs().none { it.kind == "edit" })
    }

    @Test fun `new todo gets repeat true x7 like the PWA template`() {
        viewModel.createTask(type = "todo", title = "New")
        awaitAssert {
            val t = runBlocking { db.taskDao().getAllTasksSync() }.single { it.title == "New" }
            assertEquals(List(7) { true }, t.repeat)
        }
    }
}
