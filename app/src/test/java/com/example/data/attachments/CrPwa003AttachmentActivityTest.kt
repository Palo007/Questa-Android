package com.example.data.attachments

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.ui.MainViewModel
import com.example.ui.activity.EditChangeSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * CR-PWA-003 (Opti 2296612): an image add/remove in the task editor writes the `edit` event item
 * `{field:'attachments', added, removed}`; the feed shows "images +N −M".
 * Golden: fixtures/cr_pwa_003/ (copied unchanged from shared\fixtures\CR-PWA-003\).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrPwa003AttachmentActivityTest {

    private val fx: JsonObject by lazy {
        val f = listOf("fixtures/cr_pwa_003", "../fixtures/cr_pwa_003").map { File(it, "edit_event_attachments.json") }.first { it.exists() }
        Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
    }
    private fun fxEvent(i: Int) = fx.getValue("events").jsonArray[i].jsonObject

    private fun eventFrom(fe: JsonObject, changes: List<JsonObject>) = EventLogEntity(
        timestamp = fe.getValue("ts").jsonPrimitive.long,
        uid = fe.getValue("uid").jsonPrimitive.content,
        dev = fe.getValue("dev").jsonPrimitive.content,
        kind = "edit",
        taskType = fe.getValue("taskType").jsonPrimitive.content,
        taskId = fe.getValue("taskId").jsonPrimitive.content,
        taskTitle = fe.getValue("taskTitle").jsonPrimitive.content,
        payloadJson = AttachmentActivity.editPayload(changes),
    )

    // ---- golden bytes ----

    @Test fun `fixture event 1 - attachments item only - same bytes as the PWA`() {
        val e = eventFrom(fxEvent(0), listOfNotNull(AttachmentActivity.changeItem(AttachmentDelta(1, 0))))
        assertEquals(fxEvent(0).toString(), SerializationEngine.buildEventJson(e).toString())
    }

    @Test fun `fixture event 2 - attachments item after title - same bytes as the PWA`() {
        val title = fxEvent(1).getValue("changes").jsonArray[0].jsonObject // the title item: see CrKt016EditEventTest
        val e = eventFrom(fxEvent(1), listOfNotNull(title, AttachmentActivity.changeItem(AttachmentDelta(2, 1))))
        assertEquals(fxEvent(1).toString(), SerializationEngine.buildEventJson(e).toString())
    }

    @Test fun `no change gives no item`() {
        assertNull(AttachmentActivity.changeItem(AttachmentDelta.NONE))
    }

    // ---- feed text ----

    @Test fun `feed summary matches the fixture`() {
        val expected = fx.getValue("expected_feed_summary").jsonArray.map { it.jsonPrimitive.content }
        val actual = (0..1).map { EditChangeSummary.text(SerializationEngine.buildEventJson(eventFrom(fxEvent(it), fxEvent(it).getValue("changes").jsonArray.map { c -> c.jsonObject })).toString()) }
        assertEquals(expected, actual)
    }

    @Test fun `feed summary - remove only, no changes, unknown field`() {
        assertEquals("images −1", EditChangeSummary.text(AttachmentActivity.editPayload(listOf(AttachmentActivity.changeItem(AttachmentDelta(0, 1))!!))))
        assertEquals("", EditChangeSummary.text("{\"counter\":{\"xp\":1}}"))
        assertEquals("", EditChangeSummary.text(null))
        assertEquals("schedule, notes, subtasks", EditChangeSummary.text("{\"changes\":[{\"field\":\"schedule\"},{\"field\":\"notes\"},{\"field\":\"checklist\",\"items\":[]}]}"))
    }

    // ---- delta (F1-F4 + fixture delta_cases) ----

    @Test fun `fixture delta cases - AttachmentDelta_of and the editor session agree`() {
        fx.getValue("delta_cases").jsonArray.forEach { c ->
            val o = c.jsonObject
            val base = o.getValue("base_ids").jsonArray.map { it.jsonPrimitive.content }
            val edit = o.getValue("edit_ids").jsonArray.map { it.jsonPrimitive.content }
            val exp = o.getValue("expected").jsonObject
            val want = AttachmentDelta(exp.getValue("added").jsonPrimitive.int, exp.getValue("removed").jsonPrimitive.int)
            val name = o.getValue("name").jsonPrimitive.content
            assertEquals(name, want, AttachmentDelta.of(base, edit))
            // The same end state reached through the user's own add/remove calls.
            val s = AttachmentEditSession("t") { error("no late write") }
            base.filter { it !in edit }.forEach(s::onRemoved)
            edit.filter { it !in base }.forEach(s::onAdded)
            assertEquals(name, want, s.close())
        }
    }

    @Test fun `F1-F4 equivalents`() {
        assertEquals(AttachmentDelta(1, 0), AttachmentDelta.of(listOf("A"), listOf("A", "B")))
        assertEquals(AttachmentDelta(0, 1), AttachmentDelta.of(listOf("A", "B"), listOf("B")))
        assertEquals(AttachmentDelta.NONE, AttachmentDelta.of(emptyList(), emptyList()))
        assertEquals(AttachmentDelta(0, 1), AttachmentDelta.of(listOf("A"), emptyList()))
    }

    @Test fun `session - add then remove of the same id nets to zero`() {
        val s = AttachmentEditSession("t") { error("no late write") }
        s.onAdded("X"); s.onRemoved("X")
        assertEquals(AttachmentDelta.NONE, s.close())
    }

    @Test fun `session - close twice returns the delta once`() {
        val s = AttachmentEditSession("t") { error("no late write") }
        s.onAdded("X")
        assertEquals(AttachmentDelta(1, 0), s.close())
        assertEquals(AttachmentDelta.NONE, s.close())
    }

    @Test fun `session - a result after close is written as its own delta`() {
        val late = mutableListOf<AttachmentDelta>()
        val s = AttachmentEditSession("t") { late += it }
        s.onAdded("X")
        s.close()
        s.onAdded("Y"); s.onRemoved("X")
        assertEquals(listOf(AttachmentDelta(1, 0), AttachmentDelta(0, 1)), late)
    }

    // ---- save / close paths (ViewModel + Room) ----

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var viewModel: MainViewModel
    private val task = TaskEntity(id = "task-att-1", type = "todo", title = "Fix the bike", createdAt = 1L, updatedAt = 1L)

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
        runBlocking { db.taskDao().insertTask(task) }
    }

    @After
    fun tearDown() {
        db.close()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    private fun edits() = runBlocking { db.eventLogDao().getLogsInRangeSync(Long.MIN_VALUE, Long.MAX_VALUE) }.filter { it.kind == "edit" }

    private fun awaitAssert(block: () -> Unit) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            try { block(); return } catch (e: Throwable) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(20)
            }
        }
    }

    @Test fun `save with an image delta writes one edit event with the attachments item`() {
        viewModel.saveEditedTask(task.copy(title = "Fix the bike!"), AttachmentDelta(2, 1))
        awaitAssert {
            val e = edits().single()
            assertEquals("todo", e.taskType); assertEquals("task-att-1", e.taskId); assertEquals("Fix the bike!", e.taskTitle)
            // CR-KT-016: the title item now comes first, same as fixture event 2.
            assertEquals("{\"changes\":[{\"field\":\"title\",\"from\":\"Fix the bike\",\"to\":\"Fix the bike!\"},{\"field\":\"attachments\",\"added\":2,\"removed\":1}]}", e.payloadJson)
            assertTrue(e.uid != null && e.dev != null)
        }
    }

    @Test fun `save with no change and no image delta writes no edit event`() {
        viewModel.saveEditedTask(task.copy(notes = ""))
        awaitAssert { assertTrue(runBlocking { db.taskDao().getTaskByIdSync(task.id) }!!.updatedAt > 1L) }
        assertTrue(edits().isEmpty())
    }

    @Test fun `close without Save writes an edit event with only the attachments item`() {
        val s = AttachmentEditSession(task.id) { d -> viewModel.logAttachmentEdit(task.id, d) }
        s.onAdded("A")
        viewModel.logAttachmentEdit(s.taskId, s.close())
        awaitAssert {
            val e = edits().single()
            assertEquals("Fix the bike", e.taskTitle)
            assertEquals("images +1", EditChangeSummary.text(e.payloadJson))
        }
    }

    @Test fun `close without Save and no delta writes nothing, a late add writes its own event`() {
        val s = AttachmentEditSession(task.id) { d -> viewModel.logAttachmentEdit(task.id, d) }
        viewModel.logAttachmentEdit(s.taskId, s.close())
        assertTrue(edits().isEmpty())
        s.onAdded("late")
        awaitAssert { assertEquals("images +1", EditChangeSummary.text(edits().single().payloadJson)) }
    }
}
