package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** sync.js:688-707 `syncInboxWriteReminders` item rules. */
class ReminderItemsFilterTest {

    private val days = listOf(true, false, true, false, true, false, true)

    @Test
    fun `filters and shapes items like Opti`() {
        val tasks = listOf(
            TaskEntity(id = "done-todo", type = "todo", title = "x", done = true,
                reminders = listOf(ReminderSpec(enabled = true, kind = "once", time = "09:00", date = "2026-09-24")), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "h", type = "habit", title = "Water", reminders = listOf(
                ReminderSpec(enabled = false, time = "08:00"),          // idx 0 disabled
                ReminderSpec(enabled = true, time = ""),                // idx 1 no time
                ReminderSpec(enabled = true, kind = "weekly", time = "10:00", days = days) // idx 2
            ), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "d", type = "daily", title = "Gym", repeat = days.asReversed(),
                reminders = listOf(ReminderSpec(enabled = true, kind = "daily", time = "07:00", days = days)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "o", type = "todo", title = "Ticket",
                reminders = listOf(ReminderSpec(enabled = true, kind = "once", time = "18:30", date = "", days = days)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "short", type = "habit", title = "S",
                reminders = listOf(ReminderSpec(enabled = true, kind = "weekly", time = "06:00", days = listOf(true))), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "none", type = "habit", title = "N", reminders = null, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        )
        val items = ReminderEngine.items(tasks)
        assertEquals("CR-PWA-001: once with date '' is skipped", listOf("h#2", "d#0", "short#0"), items.map { it.key })
        assertEquals(ReminderItem("h#2", "h", "habit", "Water", "Time to score your habit!", "10:00", null, days), items[0])
        assertEquals("daily takes t.repeat", days.asReversed(), items[1].days)
        assertEquals("days of length != 7 -> null", null, items[2].days)
    }
}
