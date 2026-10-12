package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * G11 (WP-8.0 -> 8.11): fixtures/opti/G11/reminders.json, produced by running Opti's own
 * reminder helpers (app.js:2069-2145 at 7d5d4f3) at 2026-09-24T12:00Z in UTC. The inputs are the
 * generator's cases (tools/gen_opti_fixtures.mjs genG11), rebuilt here as TaskEntity/ReminderSpec.
 */
class G11ReminderGoldenTest {

    private val all7 = List(7) { true }

    private data class Case(val t: TaskEntity, val r: ReminderSpec)

    private fun task(type: String, done: Boolean = false, repeat: List<Boolean>? = null) =
        TaskEntity(id = "fx-1", type = type, title = "Fixture Task", done = done, repeat = repeat, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    private val cases = mapOf(
        "daily_due_now" to Case(task("daily", repeat = all7), ReminderSpec(enabled = true, kind = "daily", time = "12:00", lastFiredKey = "")),
        // Opti passes lastFiredKey:null; Converters decodes a null to the "" default.
        "daily_already_fired" to Case(task("daily", repeat = all7), ReminderSpec(enabled = true, kind = "daily", time = "12:00")),
        "habit_missed_past_time" to Case(task("habit"), ReminderSpec(enabled = true, kind = "daily", time = "00:01")),
        "todo_done_never_due" to Case(task("todo", done = true), ReminderSpec(enabled = true, kind = "once", date = "2026-09-24", time = "12:00")),
        "once_wrong_date" to Case(task("todo"), ReminderSpec(enabled = true, kind = "once", date = "2026-09-25", time = "12:00")),
        "disabled_reminder" to Case(task("habit"), ReminderSpec(enabled = false, kind = "daily", time = "12:00"))
    )

    private fun fixture(): JsonArray {
        val f = File("../fixtures/opti/G11/reminders.json").takeIf { it.isFile } ?: File("fixtures/opti/G11/reminders.json")
        return Json.parseToJsonElement(f.readText()) as JsonArray
    }

    @Test
    fun `every G11 row matches Opti`() {
        val rows = fixture()
        assertEquals(6, rows.size)
        for (row in rows) {
            val o = row.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val c = cases.getValue(name)
            assertEquals("$name fireKey", o.getValue("fireKey").jsonPrimitive.content, ReminderEngine.fireKey(c.r, FIXED_NOW_MS, UTC))
            assertEquals("$name due", o.getValue("due").jsonPrimitive.boolean, ReminderEngine.isDue(c.t, c.r, FIXED_NOW_MS, UTC))
            val missed = ReminderEngine.isMissed(c.t, c.r, FIXED_NOW_MS, UTC)
            assertEquals("$name missed", o.getValue("missed").jsonPrimitive.boolean, missed)
            val p = o.getValue("payload").jsonObject
            val actual = ReminderEngine.payload(c.t, c.r, missed)
            assertEquals("$name title", p.getValue("title").jsonPrimitive.content, actual.title)
            assertEquals("$name body", p.getValue("body").jsonPrimitive.content, actual.body)
            assertEquals("$name tag", p.getValue("tag").jsonPrimitive.content, actual.tag)
        }
    }
}
