package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 5 burn-rule cases of app.js:6068-6075 (§4.5 8.11 row). Now = Thu 2026-09-24 12:00 UTC. */
class ReminderEditorBurnRuleTest {

    private val now = FIXED_NOW_MS
    private fun edited(time: String) = ReminderSpec(kind = "weekly", time = time)
    private val prev = ReminderSpec(id = "r1", enabled = true, kind = "weekly", time = "08:00", lastFiredKey = "20260923-08:00")

    @Test
    fun `new reminder with a past time burns today's key`() {
        assertEquals("20260924-09:30", ReminderEditorRules.onSave(null, edited("09:30"), now, UTC) { "new" }.lastFiredKey)
    }

    @Test
    fun `new reminder with a future time gets empty key`() {
        assertEquals("", ReminderEditorRules.onSave(null, edited("12:01"), now, UTC) { "new" }.lastFiredKey)
    }

    @Test
    fun `time changed to a past time burns today's key`() {
        val r = ReminderEditorRules.onSave(prev, edited("11:59"), now, UTC)
        assertEquals("20260924-11:59", r.lastFiredKey)
        assertEquals("r1", r.id)
    }

    @Test
    fun `time changed to a future time gets empty key`() {
        assertEquals("", ReminderEditorRules.onSave(prev, edited("23:00"), now, UTC).lastFiredKey)
    }

    @Test
    fun `unchanged time keeps the previous key`() {
        assertEquals("20260923-08:00", ReminderEditorRules.onSave(prev, edited("08:00"), now, UTC).lastFiredKey)
    }

    @Test
    fun `the current minute is not past so it gets empty key`() {
        // "12:00" < "12:00" is false in JS.
        assertEquals("", ReminderEditorRules.onSave(null, edited("12:00"), now, UTC) { "n" }.lastFiredKey)
    }
}
