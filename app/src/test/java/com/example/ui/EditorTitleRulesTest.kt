package com.example.ui

import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WP-8.22-A: Opti's blank-title rules per editor (app.js @7d5d4f3). */
class EditorTitleRulesTest {

    // ---- task: saveTask, app.js:6049 `value.trim()||'Untitled'` ----

    @Test fun `blank task title saves as Untitled`() {
        assertEquals("Untitled", EditorTitleRules.task(""))
        assertEquals("Untitled", EditorTitleRules.task("   "))
        assertEquals("Untitled", EditorTitleRules.task("\t\n  　﻿"))
    }

    @Test fun `task title is trimmed like JS trim`() {
        assertEquals("Buy milk", EditorTitleRules.task("  Buy milk \n"))
        assertEquals("a  b", EditorTitleRules.task("﻿a  b "))
    }

    @Test fun `zero width space is not JS whitespace so it stays`() {
        assertEquals("​", EditorTitleRules.task("​"))
    }

    // ---- reward: saveReward, app.js:6431 `value.trim()||'Reward'` ----

    @Test fun `blank reward title saves as Reward`() {
        assertEquals("Reward", EditorTitleRules.reward(""))
        assertEquals("Reward", EditorTitleRules.reward("  ﻿ "))
    }

    @Test fun `reward title is trimmed`() {
        assertEquals("Coffee", EditorTitleRules.reward(" Coffee  "))
    }

    // ---- reward cost: saveReward, app.js:6432 `Math.max(0, parseFloat(v)||0)` ----

    @Test fun `reward cost follows JS parseFloat prefix parsing`() {
        val cases = mapOf(
            "12abc" to 12.0, "abc" to 0.0, "" to 0.0, "-5" to 0.0, " 7.5" to 7.5,
            " \t3" to 3.0, "1e2x" to 100.0, "1e" to 1.0, "1." to 1.0, ".5" to 0.5,
            "." to 0.0, "+4" to 4.0, "0x10" to 0.0, "1,5" to 1.0, "--1" to 0.0, "-0" to 0.0,
            "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to 0.0, "1e400" to Double.POSITIVE_INFINITY,
            // review 45 finding 4
            "+Infinity" to Double.POSITIVE_INFINITY, "Infinityx" to Double.POSITIVE_INFINITY,
            "infinity" to 0.0, " 8" to 8.0, "　9.25" to 9.25, "1.e5" to 1e5,
        )
        cases.forEach { (raw, want) -> assertEquals("\"$raw\"", want, EditorTitleRules.rewardCost(raw), 0.0) }
    }

    // ---- subtask: saveTask, app.js:6057 drops rows whose text trims empty ----

    @Test fun `blank subtasks are dropped on save and the rest keep their text untrimmed`() {
        val rows = listOf(
            ChecklistItem("a", "Milk", false),
            ChecklistItem("b", "   ", true),
            ChecklistItem("c", "", false),
            ChecklistItem("d", " Eggs ", true),
        )
        assertEquals(listOf("a", "d"), EditorTitleRules.checklist(rows).map { it.id })
        assertEquals(" Eggs ", EditorTitleRules.checklist(rows).last().text)
    }

    // ---- tag: addTag, app.js:3258 trims and refuses an empty name ----

    @Test fun `blank tag name is refused and a real one is trimmed`() {
        assertNull(EditorTitleRules.tagName("  "))
        assertNull(EditorTitleRules.tagName("﻿"))
        assertEquals("Work", EditorTitleRules.tagName(" Work "))
    }

    // ---- tag: addTag, app.js:3259 reuses a tag whose name matches ignoring case ----

    @Test fun `existing tag matches by lower case name`() {
        val tags = listOf(TagEntity("t1", "Work", "#fff", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), TagEntity("t2", "ÄRGER", "#000", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals("t1", EditorTitleRules.existingTag("work", tags)?.id)
        assertEquals("t1", EditorTitleRules.existingTag("WORK", tags)?.id)
        assertEquals("t2", EditorTitleRules.existingTag("ärger", tags)?.id)
        assertNull(EditorTitleRules.existingTag("Works", tags))
    }

    // ---- CR-KT-017: repeat / resetFreq are never dropped by the editor (Opti b7a94cd) ----

    private val seven = List(7) { true }
    private val grid = listOf(false, true, true, true, true, true, false)

    @Test fun `repeat - daily saves the grid, other saved types keep the stored repeat`() {
        val habit = TaskEntity(id = "h", type = "habit", title = "h", repeat = grid, createdAt = 1, updatedAt = 1)
        assertEquals(grid, EditorTitleRules.repeat("habit", habit, seven))
        assertNull(EditorTitleRules.repeat("todo", habit.copy(type = "todo", repeat = null), seven))
        assertEquals(grid, EditorTitleRules.repeat("daily", habit.copy(type = "daily", repeat = seven), grid))
    }

    @Test fun `repeat - a new task of any type saves the template true x7`() {
        assertEquals(seven, EditorTitleRules.repeat("todo", null, seven))
        assertEquals(seven, EditorTitleRules.repeat("habit", null, seven))
        assertEquals(seven, EditorTitleRules.NEW_TASK_REPEAT)
    }

    @Test fun `resetFreq - habit saves the picker, other types keep the stored value`() {
        val todo = TaskEntity(id = "t", type = "todo", title = "t", resetFreq = "daily", createdAt = 1, updatedAt = 1)
        assertEquals("daily", EditorTitleRules.resetFreq("todo", todo, "weekly"))
        assertEquals("weekly", EditorTitleRules.resetFreq("habit", todo.copy(type = "habit"), "weekly"))
    }
}
