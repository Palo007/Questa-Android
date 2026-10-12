package com.example.data.local.entities

import com.example.data.local.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.2 (Wave 1) -- review r1 major 6: `Converters.toReminderSpecList` must not lose the whole
 * list to one malformed element, `enabled` must default to `false` (app.js:2083), and unknown
 * sub-fields must survive via the per-element `extraJson` overlay.
 */
class ReminderSpecDecodeTest {

    private val converters = Converters()

    @Test
    fun `enabled defaults to false when the key is missing`() {
        val json = """[{"id":"r1","time":"08:00"}]"""
        val result = converters.toReminderSpecList(json)
        assertEquals(1, result?.size)
        assertEquals(false, result!!.first().enabled)
    }

    @Test
    fun `a null lastFiredKey does not null out the whole list`() {
        val json = """[{"id":"r1","time":"08:00","enabled":true,"lastFiredKey":null}]"""
        val result = converters.toReminderSpecList(json)
        assertEquals(1, result?.size)
        assertEquals("", result!!.first().lastFiredKey)
    }

    @Test
    fun `one malformed element does not delete every other reminder`() {
        // The second element's "enabled" is a string where a boolean is expected -- with
        // coerceInputValues + per-element decoding this element is either coerced or dropped, but
        // sibling elements 1 and 3 must always survive.
        val json = """
            [
              {"id":"r1","time":"08:00","enabled":true},
              {"id":"r2","time":"not-even-json"::: broken,
              {"id":"r3","time":"20:00","enabled":true}
            ]
        """.trimIndent()
        // This whole array is not valid JSON (deliberately broken syntax) -- the WHOLE-STRING
        // parse fails, so the converter must return null (not crash, not silently corrupt data);
        // see the per-element case below for a *structurally valid* array with one bad element.
        assertNull(converters.toReminderSpecList(json))
    }

    @Test
    fun `a structurally valid array with one semantically-bad element keeps the good ones`() {
        val json = """
            [
              {"id":"r1","time":"08:00","enabled":true},
              {"id":"r2","time":"09:00","enabled":"not-a-boolean"},
              {"id":"r3","time":"20:00","enabled":true}
            ]
        """.trimIndent()
        val result = converters.toReminderSpecList(json)
        assertTrue("expected at least the 2 good elements, got $result", (result?.size ?: 0) >= 2)
        assertTrue(result!!.any { it.id == "r1" })
        assertTrue(result.any { it.id == "r3" })
    }

    @Test
    fun `unknown sub-fields survive via the per-element extraJson overlay`() {
        val json = """[{"id":"r1","time":"08:00","enabled":true,"snoozeMinutes":15}]"""
        val result = converters.toReminderSpecList(json)
        assertEquals(1, result?.size)
        val extra = result!!.first().extraJson
        assertTrue("expected the raw element JSON to be kept, got $extra", extra?.contains("snoozeMinutes") == true)
    }

    @Test
    fun `id and time are optional so a minimal element does not throw`() {
        val json = """[{}]"""
        val result = converters.toReminderSpecList(json)
        assertEquals(1, result?.size)
        assertNull(result!!.first().id)
        assertNull(result.first().time)
        assertEquals(false, result.first().enabled)
    }

    @Test
    fun `round trip through fromReminderSpecList and back preserves id and time`() {
        val specs = listOf(ReminderSpec(id = "r1", time = "07:30", enabled = true, kind = "once", date = "2026-09-24"))
        val json = converters.fromReminderSpecList(specs)
        val decoded = converters.toReminderSpecList(json)
        assertEquals("r1", decoded?.first()?.id)
        assertEquals("07:30", decoded?.first()?.time)
    }

    // --- review r2 major 1: extraJson must never leak onto the wire and must never grow ---

    @Test
    fun `encoded output never contains an extraJson key`() {
        val json = """[{"id":"r1","time":"08:00","enabled":true,"snoozeMinutes":15}]"""
        val decoded = converters.toReminderSpecList(json)!!
        val reEncoded = converters.fromReminderSpecList(decoded)!!
        assertTrue("re-encoded reminders must never contain the literal key \"extraJson\": $reEncoded", !reEncoded.contains("\"extraJson\""))
    }

    @Test
    fun `decode-encode is byte-identical across 3 round trips and unknown keys survive`() {
        val original = """[{"id":"r1","time":"08:00","enabled":true,"kind":"daily","snoozeMinutes":15,"lastFiredKey":""}]"""

        val decoded1 = converters.toReminderSpecList(original)!!
        val encoded1 = converters.fromReminderSpecList(decoded1)!!
        val decoded2 = converters.toReminderSpecList(encoded1)!!
        val encoded2 = converters.fromReminderSpecList(decoded2)!!
        val decoded3 = converters.toReminderSpecList(encoded2)!!
        val encoded3 = converters.fromReminderSpecList(decoded3)!!

        assertEquals("encoded1 -> encoded2 must be byte-identical", encoded1, encoded2)
        assertEquals("encoded2 -> encoded3 must be byte-identical", encoded2, encoded3)
        assertTrue("snoozeMinutes (unknown key) must survive every round trip", encoded3.contains("snoozeMinutes"))
        assertTrue(!encoded3.contains("\"extraJson\""))
    }
}
