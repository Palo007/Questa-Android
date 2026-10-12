package com.example.data.inbox

import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import com.example.reminders.ReminderEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P10 / CR-PWA-001 (from CR-TWA-004): a "once" reminder with no `date` is not emitted.
 * Golden: fixtures/cr_pwa_001/ (copied unchanged from shared\fixtures\CR-PWA-001\, made by the
 * fixed Opti sync.js `syncInboxWriteReminders`). `updatedAt` is excluded; the local items hash
 * `_inboxHash(JSON.stringify(items))` must be `781f046d:348`.
 */
class CrPwa001OnceNoDateGoldenTest {

    private fun str(o: JsonObject, k: String): String? =
        o[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun reminder(o: JsonObject) = ReminderSpec(
        id = str(o, "id"),
        time = str(o, "time"),
        enabled = o["enabled"]?.jsonPrimitive?.boolean ?: false,
        kind = str(o, "kind") ?: "daily",
        date = str(o, "date"),
        days = (o["days"] as? JsonArray)?.map { it.jsonPrimitive.boolean },
        lastFiredKey = str(o, "lastFiredKey") ?: "",
    )

    /** `S.tasks` of input_state_once_no_date.json, in file order. */
    private fun inputTasks(): List<TaskEntity> =
        fixtureJson("cr_pwa_001/input_state_once_no_date.json").jsonObject["tasks"]!!.jsonArray.map { e ->
            val t = e.jsonObject
            TaskEntity(
                id = str(t, "id")!!,
                type = str(t, "type")!!,
                title = str(t, "title") ?: "",
                done = t["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                reminders = t["reminders"]?.jsonArray?.map { reminder(it.jsonObject) },
                createdAt = 0L,
                updatedAt = 0L,
            )
        }

    private fun withoutUpdatedAt(e: JsonElement): JsonObject =
        JsonObject(e.jsonObject.filterKeys { it != "updatedAt" })

    @Test
    fun `reminders json items match the CR-PWA-001 golden`() {
        val items = ReminderEngine.items(inputTasks())
        val body = InboxReminderMeta.body(items, updatedAt = 0L)

        val expected = fixtureJson("cr_pwa_001/expected_reminders.json")
        assertEquals(withoutUpdatedAt(expected), withoutUpdatedAt(Json.parseToJsonElement(body)))
        assertEquals("781f046d:348", InboxHash.of(InboxReminderMeta.itemsJson(items)))
    }

    @Test
    fun `undated once is skipped and other keys keep their reminder index`() {
        val items = ReminderEngine.items(inputTasks())
        assertFalse(items.any { it.taskId == "t-once-nodate" })
        assertEquals(listOf("t-once-dated#0", "h-weekly#0"), items.map { it.key })
    }

    @Test
    fun `skip keeps the original index for later reminders of the same task`() {
        val t = TaskEntity(
            id = "t", type = "todo", title = "x", createdAt = 0L, updatedAt = 0L,
            reminders = listOf(
                ReminderSpec(enabled = true, kind = "once", time = "09:00"),
                ReminderSpec(enabled = true, kind = "once", time = "09:00", date = ""),
                ReminderSpec(enabled = true, kind = "once", time = "10:00", date = "2026-10-01"),
                ReminderSpec(enabled = true, kind = "daily", time = "11:00"),
            ),
        )
        assertEquals(listOf("t#2", "t#3"), ReminderEngine.items(listOf(t)).map { it.key })
    }
}
