package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-KT-009: Opti `eventImportSummary` / `eventImportSummaryText` (app.js:1543-1564) and the
 * import event note (app.js:8575-8576). Fixture `fixtures/CR-KT-009/import_summary.json` was made
 * by running those PWA functions in Node on the live app.js.
 */
class ImportSummaryTest {

    private fun ev(kind: String, taskType: String? = null): JsonObject = JsonObject(
        buildMap {
            put("ts", JsonPrimitive(1759900000000L))
            put("kind", JsonPrimitive(kind))
            if (taskType != null) put("taskType", JsonPrimitive(taskType))
        },
    )

    private val mixed: List<JsonElement> = listOf(
        ev("habitTap", "habit"), ev("habitReps"), ev("complete", "daily"), ev("miss"),
        ev("complete", "todo"), ev("create", "todo"), ev("import"), ev("lifecycle"), ev("clockSkew"),
        ev("conflictResolved"), ev("subtask", "todo"), ev("subtask", "daily"),
    )

    private val fixture: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("fixtures/CR-KT-009/import_summary.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `mixed list - diagnostics are not in system, conflictResolved is`() {
        val s = ImportSummary.of(mixed)
        assertEquals(12, s.total)
        assertEquals(2, s.diagnostic)
        assertEquals(10, s.visible)
        assertEquals(mapOf("habit" to 2, "daily" to 3, "todo" to 3, "system" to 2), s.byCat)
    }

    @Test
    fun `empty list has zeros and all four keys`() {
        val s = ImportSummary.of(emptyList())
        assertEquals(0, s.total)
        assertEquals(0, s.diagnostic)
        assertEquals(0, s.visible)
        assertEquals(listOf("habit", "daily", "todo", "system"), s.byCat.keys.toList())
        assertEquals(listOf(0, 0, 0, 0), s.byCat.values.toList())
        assertEquals(s, ImportSummary.EMPTY)
    }

    @Test
    fun `non-objects are skipped, arrays count as system like a JS object`() {
        // Opti: `if(!e || typeof e!=='object') continue;` -- a JS array IS typeof 'object'.
        val s = ImportSummary.of(listOf(JsonNull, JsonPrimitive(3), JsonPrimitive("x"), JsonArray(emptyList()), ev("lifecycle")))
        assertEquals(2, s.total)
        assertEquals(1, s.diagnostic)
        assertEquals(1, s.byCat["system"])
    }

    @Test
    fun `text is byte-exact`() {
        assertEquals(
            "12 stored total · 10 visible in feed · 2 system/diagnostic hidden · Habits 2, Dailies 3, To-dos 3, System 2",
            ImportSummary.of(mixed).text(),
        )
    }

    @Test
    fun `import note for a merge with two keys and for a full replace`() {
        val s = ImportSummary.of(mixed)
        assertEquals(
            "Restored 9 events (12 stored total · 10 visible in feed · 2 system/diagnostic hidden · " +
                "Habits 2, Dailies 3, To-dos 3, System 2; 3 already present, skipped) [char+events; merge]",
            ImportSummary.importNote(9, s, 3, full = false, keys = listOf("char", "events"), replacing = false),
        )
        assertEquals(
            "Restored 12 events (12 stored total · 10 visible in feed · 2 system/diagnostic hidden · " +
                "Habits 2, Dailies 3, To-dos 3, System 2; 0 already present, skipped) [all sections; replace]",
            ImportSummary.importNote(12, s, 0, full = true, keys = listOf("char", "events"), replacing = true),
        )
    }

    @Test
    fun `golden fixture from the PWA functions`() {
        val events = (fixture["events"] as JsonArray).toList()
        val s = ImportSummary.of(events)
        val exp = fixture["expected_summary"]!!.jsonObject
        assertEquals(exp["total"]!!.jsonPrimitive.int, s.total)
        assertEquals(exp["diagnostic"]!!.jsonPrimitive.int, s.diagnostic)
        assertEquals(exp["visible"]!!.jsonPrimitive.int, s.visible)
        assertEquals(exp["byCat"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }, s.byCat)
        assertEquals(fixture["expected_text"]!!.jsonPrimitive.content, s.text())
        assertEquals(
            fixture["expected_note_merge_char_events_added9_skipped3"]!!.jsonPrimitive.content,
            ImportSummary.importNote(9, s, 3, false, listOf("char", "events"), false),
        )
        assertEquals(
            fixture["expected_note_full_replace_added12_skipped0"]!!.jsonPrimitive.content,
            ImportSummary.importNote(12, s, 0, true, emptyList(), true),
        )
        assertEquals(fixture["empty_text"]!!.jsonPrimitive.content, ImportSummary.of(emptyList()).text())
    }

    @Test
    fun `shared kind list and category equal Opti`() {
        assertEquals(
            listOf("lifecycle", "storagePersist", "webLocksUnavailable", "clockSkew", "hlcReset", "multiTabClobberAvoided", "quotaError"),
            EventKinds.DIAGNOSTIC_KINDS,
        )
        assertEquals("conflict", EventKinds.category("conflictResolved", null))
        assertEquals("habit", EventKinds.category("complete", "habit"))
        assertEquals("system", EventKinds.category("export", "todo"))
        assertEquals("daily", EventKinds.category("subtask", null))
        assertEquals("system", EventKinds.category(null, null))
    }
}
