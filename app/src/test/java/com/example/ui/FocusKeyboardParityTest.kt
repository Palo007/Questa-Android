package com.example.ui

import android.app.Application
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.settings.SettingsActions
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsUiState
import com.example.ui.visuals.RepSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.16-R1: focus and keyboard parity with Opti 7d5d4f3 (see plans/stage8/WP-8.16_NOTES.md,
 * "R1 focus/keyboard parity").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FocusKeyboardParityTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createAndroidComposeRule<ComponentActivity>() }

    private data class Saved(val title: String, val checklist: List<ChecklistItem>)

    private val saves = mutableListOf<Saved>()
    private val tagsCreated = mutableListOf<String>()
    private val createdIds = mutableListOf<String>()

    private val savedTags = mutableListOf<List<String>>()

    private fun editor(task: TaskEntity?, tags: List<TagEntity> = emptyList()) {
        rule.setContent {
            TaskEditSheet(
                task = task,
                availableTags = tags,
                onCreateTag = { id, name, _ -> tagsCreated += name; createdIds += id },
                newTagId = { "new-tag-id-1" },
                onDismiss = {},
                onSave = { _, title, _, _, _, checklist, t, _, _, _, _, _, _, _ -> saves += Saved(title, checklist); savedTags += t },
            )
        }
        rule.waitForIdle()
    }

    /** The EditorInfo the focused text field would hand the soft keyboard. */
    private fun focusedEditorInfo(): EditorInfo {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val roots = (field.get(inst) as List<View>).toList()
        fun find(v: View): View? {
            if (v.hasFocus() && v.onCheckIsTextEditor()) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val view = roots.asReversed().firstNotNullOfOrNull { find(it) } ?: error("no focused text editor")
        val info = EditorInfo()
        rule.runOnUiThread { view.onCreateInputConnection(info) }
        return info
    }

    private fun EditorInfo.cls() = inputType and InputType.TYPE_MASK_CLASS
    private fun EditorInfo.has(flag: Int) = inputType and flag == flag
    private fun EditorInfo.action() = imeOptions and EditorInfo.IME_MASK_ACTION

    // ---- task editor: focus on open (Opti openEdit, app.js:5876-5886) ----

    @Test fun `new task opens with the title focused`() {
        editor(null)
        rule.onNodeWithTag("task_title_input").assertIsFocused()
    }

    @Test fun `edit task opens with the title focused and all of it selected`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Buy milk", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val n = rule.onNodeWithTag("task_title_input").assertIsFocused().fetchSemanticsNode()
        assertEquals(TextRange(0, 8), n.config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun `typing over the selected title replaces it`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Old", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("task_title_input").performTextInput("New")
        rule.onNodeWithTag("task_title_input").performImeAction()
        assertEquals("New", saves.single().title)
    }

    // ---- Enter (Opti onSheetKeydown, app.js:5961-5973) ----

    @Test fun `Enter in the title saves the task`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("task_title_input").performImeAction()
        assertEquals(listOf(Saved("Todo", emptyList())), saves)
    }

    // WP-8.22-A: Opti saveTask stores `value.trim()||'Untitled'` (app.js:6049), on new and edit.
    @Test fun `Enter with an empty title saves it as Untitled`() {
        editor(null)
        rule.onNodeWithTag("task_title_input").performImeAction()
        assertEquals(listOf(Saved("Untitled", emptyList())), saves)
    }

    @Test fun `Save with a blank title on an existing task saves Untitled`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Old", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("task_title_input").performTextClearance()
        rule.onNodeWithTag("task_title_input").performTextInput("   ")
        rule.onNodeWithTag("save_task_button").performClick()
        assertEquals(listOf(Saved("Untitled", emptyList())), saves)
    }

    @Test fun `Save trims the title`() {
        editor(null)
        rule.onNodeWithTag("task_title_input").performTextInput("  Walk  ")
        rule.onNodeWithTag("save_task_button").performClick()
        assertEquals("Walk", saves.single().title)
    }

    @Test fun `Save drops subtasks whose text is blank`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", checklist = listOf(
            ChecklistItem("a", "Milk", false), ChecklistItem("b", "  ", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("save_task_button").performClick()
        assertEquals(listOf("Milk"), saves.single().checklist.map { it.text })
    }

    @Test fun `reward Create with a blank name saves Reward`() {
        val made = mutableListOf<String>()
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { t, _, _ -> made += t }) }
        rule.onNodeWithTag("reward_title_input").performTextInput("  ")
        rule.onNodeWithTag("save_reward_button").performClick()
        assertEquals(listOf("Reward"), made)
    }

    // Opti saveReward: `Math.max(0, parseFloat(rCost.value)||0)` (app.js:6432).
    @Test fun `reward Create parses the cost like JS parseFloat`() {
        val costs = mutableListOf<Double>()
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { _, c, _ -> costs += c }) }
        rule.onNodeWithTag("reward_title_input").performTextInput("Tea")
        rule.onNodeWithTag("reward_cost_input").performTextClearance()
        rule.onNodeWithTag("reward_cost_input").performTextInput("12abc")
        rule.onNodeWithTag("save_reward_button").performClick()
        assertEquals(listOf(12.0), costs)
    }

    @Test fun `reward Create with an empty cost saves 0`() {
        val costs = mutableListOf<Double>()
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { _, c, _ -> costs += c }) }
        rule.onNodeWithTag("reward_cost_input").performTextClearance()
        rule.onNodeWithTag("save_reward_button").performClick()
        assertEquals(listOf(0.0), costs)
    }

    @Test fun `reward Create trims the name`() {
        val made = mutableListOf<String>()
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { t, _, _ -> made += t }) }
        rule.onNodeWithTag("reward_title_input").performTextInput(" Coffee ")
        rule.onNodeWithTag("save_reward_button").performClick()
        assertEquals(listOf("Coffee"), made)
    }

    // Opti openReward(id) (app.js:7171): an existing reward opens with its own values; Save keeps edits.
    @Test fun `reward editor starts from the existing reward and saves the edit`() {
        val made = mutableListOf<Triple<String, Double, String>>()
        val r = RewardEntity(id = "r1", title = "Tea", cost = 15.0, notes = "green", createdAt = 1L, updatedAt = 1L)
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { t, c, n -> made += Triple(t, c, n) }, existing = r, onDelete = {}) }
        rule.onNodeWithText("Edit Reward").assertExists()
        rule.onNodeWithTag("reward_cost_input").performTextReplacement("20")
        rule.onNodeWithTag("save_reward_button").performClick()
        assertEquals(listOf(Triple("Tea", 20.0, "green")), made)
    }

    @Test fun `reward editor Delete calls onDelete`() {
        var deleted = 0
        val r = RewardEntity(id = "r1", title = "Tea", cost = 15.0, createdAt = 1L, updatedAt = 1L)
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { _, _, _ -> }, existing = r, onDelete = { deleted++ }) }
        rule.onNodeWithTag("delete_reward_button").performClick()
        assertEquals(1, deleted)
    }

    // Opti drawChecklist (app.js:6035): every saved subtask row is a text <input> bound to its text.
    @Test fun `a saved subtask text can be edited and the edit is saved`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", checklist = listOf(
            ChecklistItem("a", "Milk", true), ChecklistItem("b", "Eggs", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("subtask_text_a").performTextReplacement("Oat milk")
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        val saved = saves.single().checklist
        assertEquals(listOf("Oat milk", "Eggs"), saved.map { it.text })
        assertEquals(listOf("a", "b"), saved.map { it.id })
        assertEquals(listOf(true, false), saved.map { it.done })
    }

    @Test fun `a subtask added in this sheet can be edited before save`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("checklist_subtask_input").performTextInput("Mlk")
        rule.onNodeWithTag("add_subtask_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Mlk").performTextReplacement("Milk")
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf("Milk"), saves.single().checklist.map { it.text })
    }

    @Test fun `Enter in a saved subtask row saves the task`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", checklist = listOf(
            ChecklistItem("a", "Milk", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("subtask_text_a").performTextReplacement("Bread")
        rule.onNodeWithTag("subtask_text_a").performImeAction()
        assertEquals(listOf("Bread"), saves.single().checklist.map { it.text })
    }

    @Test fun `a saved subtask cleared to blank is dropped at save`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", checklist = listOf(
            ChecklistItem("a", "Milk", false), ChecklistItem("b", "Eggs", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("subtask_text_a").performTextClearance()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf("Eggs"), saves.single().checklist.map { it.text })
    }

    @Test fun `Enter in the subtask input adds the typed row and saves`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("checklist_subtask_input").performClick()
        rule.onNodeWithTag("checklist_subtask_input").performTextInput("Milk")
        rule.onNodeWithTag("checklist_subtask_input").performImeAction()
        assertEquals(listOf("Milk"), saves.single().checklist.map { it.text })
    }

    @Test fun `add subtask keeps focus on the subtask input for the next row`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Shop", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("checklist_subtask_input").performClick()
        rule.onNodeWithTag("checklist_subtask_input").performTextInput("Milk")
        rule.onNodeWithTag("add_subtask_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("checklist_subtask_input").assertIsFocused()
        rule.onNodeWithTag("task_title_input").performImeAction()
        assertEquals(listOf("Milk"), saves.single().checklist.map { it.text })
    }

    @Test fun `Enter in the new tag name adds the tag and does not save the task`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("toggle_new_tag_input_button").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performTextInput("work")
        rule.onNodeWithTag("new_tag_name_input").performImeAction()
        assertEquals(listOf("work"), tagsCreated)
        assertTrue(saves.isEmpty())
    }

    // Opti addTagToEdit clears #eTagInput even when addTag refused the name (app.js:3299-3301).
    @Test fun `a blank tag name is refused and the input is cleared`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("toggle_new_tag_input_button").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performTextInput("   ")
        rule.onNodeWithTag("new_tag_name_input").performImeAction()
        assertTrue(tagsCreated.isEmpty())
        val text = rule.onNodeWithTag("new_tag_name_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("", text)
    }

    // Opti addTag reuses a tag whose name matches ignoring case and attaches it (app.js:3259, 3300).
    @Test fun `a tag name matching an existing tag in another case reuses and attaches it`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), listOf(TagEntity("g1", "Work", "#4488ff", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())))
        rule.onNodeWithTag("toggle_new_tag_input_button").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performTextInput("work")
        rule.onNodeWithTag("new_tag_name_input").performImeAction()
        assertTrue(tagsCreated.isEmpty())
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        // CR-KT-008: task.tags holds the tag ID (Opti addTagToEdit pushes addTag's id, app.js:3300).
        assertEquals(listOf(listOf("g1")), savedTags)
    }

    @Test fun `a new tag is attached to the task being edited`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("toggle_new_tag_input_button").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performScrollTo().performClick()
        rule.onNodeWithTag("new_tag_name_input").performTextInput(" Home ")
        rule.onNodeWithTag("new_tag_name_input").performImeAction()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf("Home"), tagsCreated)
        // CR-KT-008: the new tag's id (known before save) is attached, not its name.
        assertEquals(listOf("new-tag-id-1"), createdIds)
        assertEquals(listOf(listOf("new-tag-id-1")), savedTags)
    }

    private fun now() = com.example.data.clock.PhysicalClock.nowMs()

    // CR-KT-008: toggling an existing tag chip stores the tag id (Opti toggleEditTag, app.js:3302).
    @Test fun `toggling an existing tag chip saves its id`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = now(), updatedAt = now()),
            listOf(TagEntity("g1", "Work", "#4488ff", createdAt = now(), updatedAt = now())))
        clickTagChip("Work")
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf(listOf("g1")), savedTags)
    }

    /**
     * Clicks the editor's tag chip through its OnClick semantics action (the chip's real
     * `clickable` handler). A pointer click misses here: the chip row sits at y=472 in the
     * 470 px Robolectric sheet, and `performScrollTo` only scrolls the chip's innermost scroll
     * container (the horizontal tag row), not the sheet's vertical scroll, so the chip stays
     * clipped to zero bounds and the injected tap lands outside it.
     */
    private fun clickTagChip(name: String) {
        rule.onNodeWithTag("task_tag_$name").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    // CR-KT-008: a legacy NAME token shows its chip as selected; toggling it off removes the name.
    @Test fun `a legacy name token is selected and toggling it off removes it`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", tags = listOf("Work", "orphan"), createdAt = now(), updatedAt = now()),
            listOf(TagEntity("g1", "Work", "#4488ff", createdAt = now(), updatedAt = now())))
        clickTagChip("Work")
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf(listOf("orphan")), savedTags)
    }

    // CR-KT-008: on an editor save, name tokens of known tags become ids; unknown tokens stay.
    @Test fun `editor save converts known name tokens to ids and keeps unknown tokens`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", tags = listOf("Work", "orphan"), createdAt = now(), updatedAt = now()),
            listOf(TagEntity("g1", "Work", "#4488ff", createdAt = now(), updatedAt = now())))
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        assertEquals(listOf(listOf("g1", "orphan")), savedTags)
    }

    // ---- keyboard type per field ----

    @Test fun `title keyboard is one line text with Done and sentence caps`() {
        editor(null)
        rule.onNodeWithTag("task_title_input").assertIsFocused()
        val ei = focusedEditorInfo()
        assertEquals(InputType.TYPE_CLASS_TEXT, ei.cls())
        assertTrue(ei.has(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertTrue(!ei.has(InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(EditorInfo.IME_ACTION_DONE, ei.action())
    }

    @Test fun `notes keyboard is multi line so Enter types a newline`() {
        editor(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("task_notes_input").performClick()
        rule.onNodeWithTag("task_notes_input").assertIsFocused()
        val ei = focusedEditorInfo()
        assertTrue(ei.has(InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(ei.has(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test fun `reward cost keyboard accepts a decimal point`() {
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { _, _, _ -> }) }
        rule.onNodeWithTag("reward_cost_input").performClick()
        val ei = focusedEditorInfo()
        assertEquals(InputType.TYPE_CLASS_NUMBER, ei.cls())
        assertTrue(ei.has(InputType.TYPE_NUMBER_FLAG_DECIMAL))
    }

    @Test fun `reward name keyboard is one line text with sentence caps`() {
        rule.setContent { RewardCreatorDialog(onDismiss = {}, onCreate = { _, _, _ -> }) }
        rule.onNodeWithTag("reward_title_input").performClick()
        val ei = focusedEditorInfo()
        assertEquals(InputType.TYPE_CLASS_TEXT, ei.cls())
        assertTrue(ei.has(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertTrue(!ei.has(InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun `rep sheet keyboard is numeric`() {
        rule.setContent { RepSheet("Push-ups", positive = true, onClose = {}, onCommit = {}) }
        rule.onNodeWithTag("bulk_reps_input").performClick()
        assertEquals(InputType.TYPE_CLASS_NUMBER, focusedEditorInfo().cls())
    }

    // ---- Settings text fields (Opti onchange commit) ----

    @Test fun `Done in the character name commits once and closes the keyboard`() {
        val commits = mutableListOf<String>()
        rule.setContent {
            Box(Modifier.width(392.dp)) {
                SettingsScreen(SettingsUiState(charName = "Adventurer"), object : SettingsActions {
                    override fun setCharName(v: String) { commits += v }
                })
            }
        }
        rule.onNodeWithTag("set_name").performClick()
        rule.onNodeWithTag("set_name").performTextClearance()
        rule.onNodeWithTag("set_name").performTextInput("Bob")
        rule.onNodeWithTag("set_name").performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("Bob"), commits)
        rule.onNodeWithTag("set_name").assertIsNotFocused()
    }
}
