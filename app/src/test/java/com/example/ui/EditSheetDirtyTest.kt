package com.example.ui

import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.QuickLogMode
import com.example.reminders.ReminderEditorRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSheetDirtyTest {

    private val base = EditSheetDraft(
        title = "Run",
        notes = "5k",
        type = "habit",
        difficulty = "easy",
        resetFreq = "daily",
        cUp = 2,
        cDown = 1,
        quickLog = QuickLogMode.BOTH,
        tags = listOf("health"),
        repeatDays = listOf(true, true, true, true, true, true, true),
        checklist = listOf(ChecklistItem(id = "c1", text = "stretch", done = false)),
        reminders = ReminderEditorRules.Draft(enabled = true, time = "07:30", date = "2026-09-27", days = List(7) { true }),
    )

    @Test
    fun `unchanged draft is not dirty`() {
        assertFalse(isEditSheetDirty(base, base.copy()))
    }

    @Test
    fun `title change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(title = "Run more")))
    }

    @Test
    fun `notes change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(notes = "")))
    }

    @Test
    fun `type change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(type = "todo")))
    }

    @Test
    fun `difficulty change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(difficulty = "hard")))
    }

    @Test
    fun `reset frequency change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(resetFreq = "weekly")))
    }

    @Test
    fun `habit counter change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(cUp = 3)))
        assertTrue(isEditSheetDirty(base, base.copy(cDown = 0)))
    }

    @Test
    fun `quick log change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(quickLog = null)))
        assertTrue(isEditSheetDirty(base, base.copy(quickLog = QuickLogMode.UP)))
    }

    @Test
    fun `tag added or removed is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(tags = emptyList())))
        assertTrue(isEditSheetDirty(base, base.copy(tags = base.tags + "work")))
    }

    @Test
    fun `repeat day toggle is dirty`() {
        val toggled = base.repeatDays.toMutableList().also { it[0] = !it[0] }
        assertTrue(isEditSheetDirty(base, base.copy(repeatDays = toggled)))
    }

    @Test
    fun `checklist item added or edited is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(checklist = emptyList())))
        assertTrue(
            isEditSheetDirty(
                base,
                base.copy(checklist = base.checklist + ChecklistItem(id = "c2", text = "new", done = false))
            )
        )
    }

    @Test
    fun `reminder change is dirty`() {
        assertTrue(isEditSheetDirty(base, base.copy(reminders = base.reminders.copy(enabled = false))))
        assertTrue(isEditSheetDirty(base, base.copy(reminders = base.reminders.copy(time = "08:00"))))
    }
}
