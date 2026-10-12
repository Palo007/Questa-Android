package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** app.js:2069-2145 branches not covered by the six G11 rows (PX-71). */
class ReminderEngineTest {

    private val thu = FIXED_NOW_MS // Thursday 12:00 UTC, getDay() == 4
    private fun daysOnly(vararg on: Int) = List(7) { it in on }

    @Test
    fun `normalize maps a null list to empty`() {
        assertEquals(emptyList<ReminderSpec>(), ReminderEngine.normalize(TaskEntity(id = "a", type = "habit", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())))
    }

    @Test
    fun `key is taskId hash idx`() {
        assertEquals("t1#0", ReminderEngine.key("t1", 0))
    }

    @Test
    fun `fireKey uses the local day of now`() {
        val r = ReminderSpec(time = "07:00")
        // 2026-09-24T23:30Z is already 2026-09-25 in Auckland.
        val late = utcMs(2026, 9, 24, 23, 30)
        assertEquals("20260924-07:00", ReminderEngine.fireKey(r, late, UTC))
        assertEquals("20260925-07:00", ReminderEngine.fireKey(r, late, TimeZone.getTimeZone("Pacific/Auckland")))
        assertEquals("20260924-undefined", ReminderEngine.fireKey(ReminderSpec(), thu, UTC))
    }

    @Test
    fun `daily uses task repeat, not r days`() {
        val r = ReminderSpec(enabled = true, kind = "daily", time = "12:00", days = daysOnly(4))
        val off = TaskEntity(id = "d", type = "daily", title = "x", repeat = daysOnly(1), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val on = TaskEntity(id = "d", type = "daily", title = "x", repeat = daysOnly(4), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val none = TaskEntity(id = "d", type = "daily", title = "x", repeat = null, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertFalse(ReminderEngine.isDue(off, r, thu, UTC))
        assertTrue(ReminderEngine.isDue(on, r, thu, UTC))
        assertTrue("null repeat = every day", ReminderEngine.isDue(none, r, thu, UTC))
        val empty = TaskEntity(id = "d", type = "daily", title = "x", repeat = emptyList(), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertFalse("JS [] is truthy and [][4] is undefined", ReminderEngine.isDue(empty, r, thu, UTC))
    }

    @Test
    fun `habit weekly uses r days`() {
        val t = TaskEntity(id = "h", type = "habit", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertFalse(ReminderEngine.isDue(t, ReminderSpec(enabled = true, kind = "weekly", time = "12:00", days = daysOnly(0)), thu, UTC))
        assertTrue(ReminderEngine.isDue(t, ReminderSpec(enabled = true, kind = "weekly", time = "12:00", days = daysOnly(4)), thu, UTC))
        assertTrue(ReminderEngine.isDue(t, ReminderSpec(enabled = true, kind = "weekly", time = "12:00", days = null), thu, UTC))
    }

    @Test
    fun `already fired today blocks both due and missed`() {
        val t = TaskEntity(id = "h", type = "habit", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val r = ReminderSpec(enabled = true, kind = "weekly", time = "09:00", lastFiredKey = "20260924-09:00")
        assertFalse(ReminderEngine.isMissed(t, r, thu, UTC))
        assertTrue(ReminderEngine.isMissed(t, r.copy(lastFiredKey = "20260923-09:00"), thu, UTC))
    }

    @Test
    fun `missed is a string compare and a future time is neither`() {
        val t = TaskEntity(id = "h", type = "habit", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val r = ReminderSpec(enabled = true, kind = "weekly", time = "13:00")
        assertFalse(ReminderEngine.isDue(t, r, thu, UTC))
        assertFalse(ReminderEngine.isMissed(t, r, thu, UTC))
        assertFalse("empty time never applies", ReminderEngine.appliesToday(t, r.copy(time = ""), thu, UTC))
    }

    @Test
    fun `payload bodies per type with notes and missed prefix`() {
        val r = ReminderSpec(time = "08:15")
        assertEquals("Nudge: n", ReminderEngine.payload(TaskEntity(id = "a", type = "habit", title = "T", notes = "n", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), r, false).body)
        assertEquals("Daily reminder: n", ReminderEngine.payload(TaskEntity(id = "a", type = "daily", title = "T", notes = "n", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), r, false).body)
        assertEquals("To-Do due: n", ReminderEngine.payload(TaskEntity(id = "a", type = "todo", title = "T", notes = "n", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), r, false).body)
        val p = ReminderEngine.payload(TaskEntity(id = "a", type = "daily", title = "", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), r, true)
        assertEquals("Questa Reminder", p.title)
        assertEquals("Missed at 08:15 - Check off your daily task!", p.body)
        assertEquals("questa-a", p.tag)
    }

    @Test
    fun `nextFireMs recurring picks the next allowed day strictly after now`() {
        val item = ReminderItem("k", "t", "habit", "T", "B", "12:00", null, null)
        assertEquals("slot == now is not strictly after", utcMs(2026, 9, 25, 12, 0), ReminderEngine.nextFireMs(item, thu, UTC))
        assertEquals(utcMs(2026, 9, 24, 13, 0), ReminderEngine.nextFireMs(item.copy(time = "13:00"), thu, UTC))
        // Only Monday (1): Thu 24 -> Mon 28.
        assertEquals(utcMs(2026, 9, 28, 9, 0), ReminderEngine.nextFireMs(item.copy(time = "09:00", days = daysOnly(1)), thu, UTC))
        assertNull(ReminderEngine.nextFireMs(item.copy(days = List(7) { false }), thu, UTC))
        assertNull(ReminderEngine.nextFireMs(item.copy(time = "25:00"), thu, UTC))
    }

    @Test
    fun `nextFireMs once is null when the slot is past`() {
        val item = ReminderItem("k", "t", "todo", "T", "B", "13:00", "2026-09-24", null)
        assertEquals(utcMs(2026, 9, 24, 13, 0), ReminderEngine.nextFireMs(item, thu, UTC))
        assertNull(ReminderEngine.nextFireMs(item.copy(time = "11:00"), thu, UTC))
        assertNull(ReminderEngine.nextFireMs(item.copy(date = "bad"), thu, UTC))
    }
}
