package com.example.data.inbox

import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import com.example.reminders.ReminderEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

internal fun fixtureFile(rel: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        val f = File(dir, "fixtures/$rel")
        if (f.isFile) return f
        dir = dir.parentFile
    }
    error("fixtures/$rel not found")
}

internal fun fixtureJson(rel: String): JsonElement = Json.parseToJsonElement(fixtureFile(rel).readText(Charsets.UTF_8))

/** The Opti object for a parsed record (sync.js:557-559), for an exact comparison. */
internal fun InboxRecord.asOptiJson(): JsonObject = JsonObject(linkedMapOf(
    "v" to JsonPrimitive(1), "id" to JsonPrimitive(id), "kind" to JsonPrimitive("habit"),
    "habitId" to JsonPrimitive(habitId), "dir" to JsonPrimitive(dir), "ts" to JsonPrimitive(ts),
    "tzOffsetMin" to (tzOffsetMin?.let { JsonPrimitive(it) } ?: JsonNull), "src" to JsonPrimitive(src),
))

/** Numbers compared as JS numbers (1 == 1.0). */
private fun sameJs(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { sameJs(a[it]!!, b[it]!!) }
    a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.doubleOrNull != null -> a.doubleOrNull == b.doubleOrNull
    else -> a == b
}

/** G6: the tasks of fixtures/wp88/g6_inbox_golden.json `tasks` (the Opti `S.tasks` of the generator). */
internal fun g6Tasks(): List<TaskEntity> {
    val all7 = List(7) { true }
    return listOf(
        TaskEntity(id = "h1", type = "habit", title = "Drink water", quickLog = QuickLogMode.UP, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h2", type = "habit", title = "Both but no down", quickLog = QuickLogMode.BOTH, down = false, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h3", type = "habit", title = "Down only", quickLog = QuickLogMode.DOWN, notes = "less sugar", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h4", type = "habit", title = "No shortcut", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h5", type = "habit", title = "\u010c\"a\\j\n\u2028 \u0001 \u00e9\ud83d\ude00", quickLog = QuickLogMode.BOTH, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h6", type = "habit", title = "", quickLog = QuickLogMode.DOWN, down = false, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "d1", type = "daily", title = "Stretch", quickLog = QuickLogMode.UP,
            repeat = listOf(true, false, true, false, true, false, true),
            reminders = listOf(ReminderSpec(time = "08:00", enabled = true, kind = "daily", days = all7), ReminderSpec(time = "09:00", enabled = false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "t1", type = "todo", title = "Done todo", done = true, reminders = listOf(ReminderSpec(time = "10:00", enabled = true)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "t2", type = "todo", title = "Open todo", notes = "call mom", reminders = listOf(
            ReminderSpec(time = "", enabled = true), ReminderSpec(time = "11:30", enabled = true, kind = "once", date = "2026-09-30", days = all7)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h7", type = "habit", title = "Weekly", reminders = listOf(
            ReminderSpec(time = "07:15", enabled = true, kind = "weekly", days = listOf(false, true, false, true, false, true, false)),
            ReminderSpec(time = "07:20", enabled = true, days = listOf(true, true))), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
    ).mapIndexed { i, t -> t.copy(orderIndex = i.toLong(), updatedAt = 1L, createdAt = 1L) }
}

/** G6 (PX-59, PX-60): inbox parse, hash and meta bytes against Opti 7d5d4f3 (sync.js:547-714). */
class G6InboxGoldenTest {
    private val golden = fixtureJson("wp88/g6_inbox_golden.json").jsonObject

    @Test fun wp80ParseTable_everyRowIsNull() {
        // fixtures/opti/G6/parse_table.json: WP-8.0's inputs lack kind:"habit" and use 2-char ids.
        val table = fixtureJson("opti/G6/parse_table.json").jsonObject
        assertEquals(8, table.size)
        val inputs = mapOf(
            "valid_habit_tap" to ("r1.json" to """{"v":1,"id":"r1","dir":1,"ts":1700000000000,"habitId":"h1"}"""),
            "bad_id_missing" to ("x.json" to """{"v":1,"dir":1,"ts":1700000000000,"habitId":"h1"}"""),
            "filename_mismatch" to ("r2.json" to """{"v":1,"id":"r1","dir":1,"ts":1700000000000,"habitId":"h1"}"""),
            "dir_zero" to ("r3.json" to """{"v":1,"id":"r3","dir":0,"ts":1700000000000,"habitId":"h1"}"""),
            "ts_zero" to ("r4.json" to """{"v":1,"id":"r4","dir":1,"ts":0,"habitId":"h1"}"""),
            "wrong_version" to ("r5.json" to """{"v":2,"id":"r5","dir":1,"ts":1700000000000,"habitId":"h1"}"""),
            "long_src_field" to ("r6.json" to """{"v":1,"id":"r6","dir":1,"ts":1700000000000,"habitId":"h1","src":"${"x".repeat(500)}"}"""),
            "not_json" to ("r7.json" to "not-json{{{"),
        )
        for ((name, expected) in table) {
            assertEquals(name, JsonNull, expected)
            val (file, body) = inputs.getValue(name)
            assertTrue(name, InboxValidator.parse(file, body).isFailure)
        }
    }

    @Test fun wp88ParseTable_matchesOpti() {
        val recs = golden["recs"]!!.jsonObject
        val parsed = golden["parsed"]!!.jsonObject
        assertTrue(recs.size >= 29)
        for ((name, pair) in recs) {
            val body = pair.jsonArray[0].jsonPrimitive.content
            val file = pair.jsonArray[1].jsonPrimitive.content
            val want = parsed.getValue(name)
            val got = InboxValidator.parse(file, body).getOrNull()
            if (want is JsonNull) assertNull(name, got)
            else assertTrue("$name: $got vs $want", got != null && sameJs(got.asOptiJson(), want))
        }
    }

    @Test fun hash_matchesBothFixtures() {
        for (f in listOf(fixtureJson("opti/G6/inbox_hash.json"), golden["hashes"]!!)) {
            for (row in f.jsonArray) {
                val o = row.jsonObject
                assertEquals(o["hash"]!!.jsonPrimitive.content, InboxHash.of(o["input"]!!.jsonPrimitive.content))
            }
        }
    }

    @Test fun metaAndReminderBytes_matchOpti() {
        val up = golden["up"]!!.jsonArray.map { it.jsonObject }
        val tasks = g6Tasks()
        assertEquals("/inbox-meta/habits.json", up[0]["path"]!!.jsonPrimitive.content)
        assertEquals(up[0]["text"]!!.jsonPrimitive.content, QuickLogMeta.body(QuickLogMeta.habits(tasks), 1790000000000L))
        assertEquals("/inbox-meta/reminders.json", up[1]["path"]!!.jsonPrimitive.content)
        assertEquals(up[1]["text"]!!.jsonPrimitive.content, InboxReminderMeta.body(ReminderEngine.items(tasks), 1790000000000L))
        val cfg = golden["cfg"]!!.jsonObject
        assertEquals(cfg["inboxMetaHash"]!!.jsonPrimitive.content, InboxHash.of(QuickLogMeta.habitsJson(QuickLogMeta.habits(tasks))))
        assertEquals(cfg["inboxRemindersHash"]!!.jsonPrimitive.content, InboxHash.of(InboxReminderMeta.itemsJson(ReminderEngine.items(tasks))))
    }

    @Test fun dirs_perQuickLogDirs() {
        val t = TaskEntity(id = "x", type = "habit", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertEquals(emptyList<Int>(), QuickLogMeta.dirs(t))
        assertEquals(listOf(1), QuickLogMeta.dirs(t.copy(quickLog = QuickLogMode.UP)))
        assertEquals(emptyList<Int>(), QuickLogMeta.dirs(t.copy(quickLog = QuickLogMode.UP, up = false)))
        assertEquals(listOf(1, -1), QuickLogMeta.dirs(t.copy(quickLog = QuickLogMode.BOTH, up = true, down = true)))
        assertEquals(listOf(-1), QuickLogMeta.dirs(t.copy(quickLog = QuickLogMode.BOTH, up = false)))
        assertEquals(emptyList<Int>(), QuickLogMeta.dirs(t.copy(quickLog = QuickLogMode.OFF)))
    }
}

/** InboxMetaWriter through Room: `S.tasks` order, overwrite only on change, hash saved after the upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxMetaWriterTest : InboxTestBase() {
    private val golden = fixtureJson("wp88/g6_inbox_golden.json").jsonObject

    private suspend fun seedG6() {
        dev.repository.deleteTaskRowOnly("h1")
        g6Tasks().asReversed().forEach { dev.repository.insertTask(it) } // insertion order must not matter
    }

    @Test fun firstRun_uploadsBothFiles_withOptiBytes_thenSecondRunIsSilent() = runBlocking {
        seedG6()
        val dbx = DropboxServiceInbox("access-dev-a")
        assertTrue(meta.writeHabits(dbx))
        assertTrue(meta.writeReminders(dbx))
        val up = golden["up"]!!.jsonArray.map { it.jsonObject }
        assertEquals(up[0]["text"]!!.jsonPrimitive.content, inbox.files[InboxPaths.INBOX_META_PATH])
        assertEquals(up[1]["text"]!!.jsonPrimitive.content, inbox.files[InboxPaths.INBOX_REMINDERS_PATH])
        assertEquals(golden["cfg"]!!.jsonObject["inboxMetaHash"]!!.jsonPrimitive.content, prefs.habits)
        assertEquals(golden["cfg"]!!.jsonObject["inboxRemindersHash"]!!.jsonPrimitive.content, prefs.reminders)
        val uploads = inbox.count("files/upload")
        assertFalse(meta.writeHabits(dbx))
        assertFalse(meta.writeReminders(dbx))
        assertEquals("unchanged lists upload nothing", uploads, inbox.count("files/upload"))
    }

    @Test fun changedTitle_overwrites() = runBlocking {
        val dbx = DropboxServiceInbox("access-dev-a")
        assertTrue(meta.writeHabits(dbx))
        dev.repository.updateTask(h1().copy(title = "Tea"))
        assertTrue(meta.writeHabits(dbx))
        assertTrue(inbox.files[InboxPaths.INBOX_META_PATH]!!.contains("\"title\":\"Tea\""))
        val arg = inbox.calls.filter { it.endpoint == "files/upload" }
        assertEquals(2, arg.size)
    }

    @Test fun failedUpload_keepsOldHash_andRetriesNextTime() = runBlocking {
        val dbx = DropboxServiceInbox("access-dev-a")
        inbox.failNext["files/upload ${InboxPaths.INBOX_META_PATH}"] = 500
        val threw = try { meta.writeHabits(dbx); false } catch (e: InboxIoException) { true }
        assertTrue(threw)
        assertNull(prefs.habits)
        assertTrue(meta.writeHabits(dbx))
    }

    @Test fun oneWriterFailing_doesNotStopTheOther() = runBlocking {
        inbox.failNext["files/upload ${InboxPaths.INBOX_META_PATH}"] = 500
        meta.run(round())
        assertTrue("inboxMetaFailed" in diagKinds())
        assertTrue(inbox.files.containsKey(InboxPaths.INBOX_REMINDERS_PATH))
    }
}
