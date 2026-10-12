package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kind branches app.js:6059/6076-6083 and the sheet-open defaults app.js:5863-5873. */
class ReminderEditorKindTest {

    private val now = FIXED_NOW_MS
    private val all7 = List(7) { true }
    private val mwf = listOf(false, true, false, true, false, true, false)

    @Test
    fun `kind per task type`() {
        assertEquals("once", ReminderEditorRules.kindFor("todo"))
        assertEquals("daily", ReminderEditorRules.kindFor("daily"))
        assertEquals("weekly", ReminderEditorRules.kindFor("habit"))
    }

    @Test
    fun `once takes the draft date or today, and has no days`() {
        val r = ReminderEditorRules.onSave(null, ReminderSpec(kind = "once", time = "13:00", date = "2026-10-01"), now, UTC) { "id" }
        assertEquals("2026-10-01", r.date)
        assertNull(r.days)
        val d = ReminderEditorRules.onSave(null, ReminderSpec(kind = "once", time = "13:00", date = ""), now, UTC) { "id" }
        assertEquals("2026-09-24", d.date)
    }

    @Test
    fun `weekly takes draft days or all seven`() {
        assertEquals(mwf, ReminderEditorRules.onSave(null, ReminderSpec(kind = "weekly", time = "13:00", days = mwf), now, UTC) { "id" }.days)
        assertEquals(all7, ReminderEditorRules.onSave(null, ReminderSpec(kind = "weekly", time = "13:00"), now, UTC) { "id" }.days)
    }

    @Test
    fun `daily save takes the task repeat or all seven`() {
        val draft = ReminderEditorRules.Draft(true, "13:00", "2026-09-24", all7)
        assertEquals(mwf, ReminderEditorRules.save(null, draft, "daily", mwf, now, UTC) { "id" }.single().days)
        assertEquals(all7, ReminderEditorRules.save(null, draft, "daily", null, now, UTC) { "id" }.single().days)
    }

    @Test
    fun `save stores a single reminder, or none when disabled`() {
        val prev = listOf(
            ReminderSpec(id = "keep", enabled = false, kind = "weekly", time = "07:00", lastFiredKey = "k"),
            ReminderSpec(id = "second", enabled = true, time = "08:00")
        )
        val on = ReminderEditorRules.save(prev, ReminderEditorRules.Draft(true, "07:00", "2026-09-24", mwf), "habit", null, now, UTC) { "new" }
        assertEquals(1, on.size)
        assertEquals("prev = reminders[0] even when disabled", "keep", on[0].id)
        assertEquals("k", on[0].lastFiredKey)
        assertEquals(true, on[0].enabled)
        assertEquals(emptyList<ReminderSpec>(), ReminderEditorRules.save(prev, ReminderEditorRules.Draft(false, "07:00", "", mwf), "habit", null, now, UTC))
    }

    @Test
    fun `empty draft time saves as 09_00`() {
        val r = ReminderEditorRules.save(null, ReminderEditorRules.Draft(true, "", "2026-09-24", all7), "habit", null, now, UTC) { "n" }.single()
        assertEquals("09:00", r.time)
        assertEquals("09:00 < 12:00 so it is burned", "20260924-09:00", r.lastFiredKey)
    }

    @Test
    fun `draft defaults when there is no enabled first reminder`() {
        val d = ReminderEditorRules.draftFrom(listOf(ReminderSpec(enabled = false, time = "06:00")), now, UTC)
        assertEquals(ReminderEditorRules.Draft(false, "09:00", "2026-09-24", all7), d)
        val e = ReminderEditorRules.draftFrom(listOf(ReminderSpec(enabled = true, time = "06:00", days = mwf)), now, UTC)
        assertEquals(ReminderEditorRules.Draft(true, "06:00", "2026-09-24", mwf), e)
    }
}
