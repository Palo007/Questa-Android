package com.example.reminders

import com.example.data.local.Converters
import com.example.data.local.entities.ReminderSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §4.5 8.11 "Reminders JSON is byte-identical": a reminder saved by this editor encodes to the
 * same bytes as `JSON.stringify(r)` of Opti's editor object (app.js:6061-6083, key order
 * id, enabled, kind, time, lastFiredKey, date|days); an Opti-made reminder round-trips unchanged;
 * markFired's rewrite changes only the lastFiredKey value. Also covers the WP-8.2 follow-up:
 * a null field clears the stored value.
 */
class ReminderSerializationByteIdentityTest {

    private val c = Converters()
    private val all7 = "[true,true,true,true,true,true,true]"

    private fun encode(list: List<ReminderSpec>) = c.fromReminderSpecList(list)!!

    @Test
    fun `editor output matches Opti's JSON for each kind`() {
        val now = FIXED_NOW_MS
        val weekly = ReminderEditorRules.save(null, ReminderEditorRules.Draft(true, "13:00", "2026-09-24", List(7) { true }), "habit", null, now, UTC) { "rid" }
        assertEquals("""[{"id":"rid","enabled":true,"kind":"weekly","time":"13:00","lastFiredKey":"","days":$all7}]""", encode(weekly))
        val once = ReminderEditorRules.save(null, ReminderEditorRules.Draft(true, "08:00", "2026-09-30", List(7) { true }), "todo", null, now, UTC) { "rid" }
        assertEquals("""[{"id":"rid","enabled":true,"kind":"once","time":"08:00","lastFiredKey":"20260924-08:00","date":"2026-09-30"}]""", encode(once))
        val daily = ReminderEditorRules.save(null, ReminderEditorRules.Draft(true, "07:00", "2026-09-24", List(7) { true }), "daily", null, now, UTC) { "rid" }
        assertEquals("""[{"id":"rid","enabled":true,"kind":"daily","time":"07:00","lastFiredKey":"20260924-07:00","days":$all7}]""", encode(daily))
    }

    @Test
    fun `an Opti reminder round-trips byte-identical and markFired changes only its key`() {
        // fixtures/golden_state.json task lqxh... reminders[0], verbatim.
        val opti = """[{"id":"lqxh8c3d4e5f","enabled":true,"kind":"daily","time":"07:00","lastFiredKey":"","days":$all7}]"""
        val decoded = c.toReminderSpecList(opti)!!
        assertEquals(opti, encode(decoded))
        val fired = decoded.mapIndexed { i, r -> if (i == 0) r.copy(lastFiredKey = "20260924-07:00") else r }
        assertEquals(opti.replace("\"lastFiredKey\":\"\"", "\"lastFiredKey\":\"20260924-07:00\""), encode(fired))
    }

    @Test
    fun `unknown keys and foreign key order survive a markFired rewrite`() {
        val foreign = """[{"time":"07:00","zzz":{"a":1},"id":"x","lastFiredKey":"old","enabled":true,"kind":"weekly"}]"""
        val fired = c.toReminderSpecList(foreign)!!.map { it.copy(lastFiredKey = "new") }
        assertEquals("""[{"time":"07:00","zzz":{"a":1},"id":"x","lastFiredKey":"new","enabled":true,"kind":"weekly"}]""", encode(fired))
    }

    @Test
    fun `a null field clears the stored value but a stored JSON null stays`() {
        val stored = """[{"id":"x","enabled":true,"kind":"once","time":"07:00","lastFiredKey":"","date":"2026-09-24","days":$all7}]"""
        val cleared = c.toReminderSpecList(stored)!!.map { it.copy(days = null) }
        assertEquals("""[{"id":"x","enabled":true,"kind":"once","time":"07:00","lastFiredKey":"","date":"2026-09-24"}]""", encode(cleared))
        val withNull = """[{"id":"x","enabled":true,"kind":"once","time":"07:00","lastFiredKey":"","date":null}]"""
        assertEquals(withNull, encode(c.toReminderSpecList(withNull)!!))
    }
}
