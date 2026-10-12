package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.clock.PhysicalClock
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.gestures.CardTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * bulk-select-v1: the selection-mode UI over the frozen VM APIs. sdk 34 because the
 * project targets SDK 36 and the Robolectric sandbox on this box is a known
 * pre-existing failure (not this feature).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BulkSelectUiTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private fun habit(id: String) = TaskEntity(
        id = id, type = "habit", title = id,
        createdAt = PhysicalClock.nowMs(), updatedAt = PhysicalClock.nowMs(),
    )

    private fun tag(id: String, name: String, color: String) = TagEntity(
        id = id, name = name, color = color,
        createdAt = PhysicalClock.nowMs(), updatedAt = PhysicalClock.nowMs(),
    )

    // CR-KT-008: candidates are tag IDS (what task.tags must hold); labels resolve to names.
    @Test fun `bulkTagModel splits add and remove candidates`() {
        val known = listOf(tag("t1", "health", "#4CAF50"), tag("t2", "work", "#FF0000"))
        val model = bulkTagModel(listOf(listOf("t1"), listOf("t1", "t2")), known)
        // health is on every selected task (so not addable); work is missing from the first.
        assertEquals(listOf("t2"), model.addCandidates)
        assertEquals(listOf("t1", "t2"), model.removeCandidates)
    }

    @Test fun `bulkTagModel canonicalizes legacy names to tag ids`() {
        val known = listOf(tag("t1", "health", "#4CAF50"), tag("t2", "work", "#FF0000"))
        // One PWA-style task (id) and one legacy Kotlin task (name) of the same tag, plus an orphan.
        val model = bulkTagModel(listOf(listOf("t1"), listOf("health", "orphan")), known)
        assertEquals(listOf("t2"), model.addCandidates)
        assertEquals(listOf("t1", "orphan"), model.removeCandidates)
    }

    @Test fun `bulkTagModel empty selection is empty`() {
        val model = bulkTagModel(emptyList(), listOf(tag("t1", "health", "#4CAF50")))
        assertEquals(emptyList<String>(), model.addCandidates)
        assertEquals(emptyList<String>(), model.removeCandidates)
    }

    @Test fun `context bar shows count and close exits`() {
        var closed = false
        rule.setContent {
            SelectionContextBar(
                selectedCount = 3,
                allVisibleSelected = false,
                onToggleSelectAll = {},
                onClose = { closed = true },
            )
        }
        rule.onNodeWithTag("selection_title").assertTextEquals("3 selected")
        rule.onNodeWithTag("selection_close").performClick()
        assertTrue(closed)
    }

    @Test fun `bulk bar disabled with nothing selected`() {
        rule.setContent { BulkActionBar(selectedCount = 0, onTags = {}, onDifficulty = {}, onDelete = {}) }
        rule.onNodeWithTag("bulk_tags").assertIsNotEnabled()
        rule.onNodeWithTag("bulk_difficulty").assertIsNotEnabled()
        rule.onNodeWithTag("bulk_delete").assertIsNotEnabled()
    }

    @Test fun `bulk bar enabled with a selection`() {
        var deleted = false
        rule.setContent { BulkActionBar(selectedCount = 2, onTags = {}, onDifficulty = {}, onDelete = { deleted = true }) }
        rule.onNodeWithTag("bulk_tags").assertIsEnabled()
        rule.onNodeWithTag("bulk_difficulty").assertIsEnabled()
        rule.onNodeWithTag("bulk_delete").performClick()
        assertTrue(deleted)
    }

    @Test fun `difficulty sheet picks a value`() {
        var picked: String? = null
        var dismissed = false
        rule.setContent { BulkDifficultySheet(onPick = { picked = it }, onDismiss = { dismissed = true }) }
        rule.onNodeWithTag("bulk_diff_hard").performClick()
        assertEquals("hard", picked)
        assertFalse(dismissed)
    }

    @Test fun `delete dialog confirms`() {
        var confirmed = false
        rule.setContent { BulkDeleteDialog(count = 2, onConfirm = { confirmed = true }, onDismiss = {}) }
        rule.onNodeWithTag("bulk_delete_confirm").performClick()
        assertTrue(confirmed)
    }

    @Test fun `delete dialog cancels`() {
        var dismissed = false
        rule.setContent { BulkDeleteDialog(count = 2, onConfirm = {}, onDismiss = { dismissed = true }) }
        rule.onNodeWithTag("bulk_delete_cancel").performClick()
        assertTrue(dismissed)
    }

    @Test fun `tag sheet applies toggled add and remove`() {
        var applied: Pair<Set<String>, Set<String>>? = null
        rule.setContent {
            BulkTagSheet(
                knownTags = listOf(tag("t1", "health", "#4CAF50"), tag("t2", "work", "#FF0000")),
                selectedTagLists = listOf(listOf("t1")),
                onApply = { add, remove -> applied = add to remove },
                onDismiss = {},
            )
        }
        rule.onNodeWithTag("bulk_tag_add_work").performClick()
        rule.onNodeWithTag("bulk_tag_remove_health").performClick()
        rule.onNodeWithTag("bulk_tag_apply").performClick()
        // CR-KT-008: Apply hands the tag IDS to bulkEditTags.
        assertEquals(setOf("t2") to setOf("t1"), applied)
    }

    @Test fun `in selection mode card body toggles selection instead of editing`() {
        var toggled: String? = null
        var edited = false
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    HabitsTabContent(
                        tasks = listOf(habit("h1")),
                        onScoreUp = {}, onScoreDown = {},
                        onLongPressUp = {}, onLongPressDown = {},
                        onEdit = { edited = true },
                        onPersistOrder = {}, onMove = { _, _ -> },
                        selectionMode = true, selectedIds = emptySet(), onToggleSelect = { toggled = it },
                    )
                }
            }
        }
        rule.onNodeWithTag("habit_body_h1").performClick()
        assertEquals("h1", toggled)
        assertFalse(edited)
    }

    @Test fun `in selection mode score control does not fire`() {
        var scored = 0
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    HabitsTabContent(
                        tasks = listOf(habit("h1")),
                        onScoreUp = { scored++ }, onScoreDown = { scored++ },
                        onLongPressUp = {}, onLongPressDown = {},
                        onEdit = {},
                        onPersistOrder = {}, onMove = { _, _ -> },
                        selectionMode = true, selectedIds = emptySet(), onToggleSelect = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("habit_up_h1").performClick()
        rule.onNodeWithTag("habit_down_h1").performClick()
        assertEquals(0, scored)
    }

    @Test fun `in selection mode the leading checkbox reflects selection`() {
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    HabitsTabContent(
                        tasks = listOf(habit("h1")),
                        onScoreUp = {}, onScoreDown = {},
                        onLongPressUp = {}, onLongPressDown = {},
                        onEdit = {},
                        onPersistOrder = {}, onMove = { _, _ -> },
                        selectionMode = true, selectedIds = setOf("h1"), onToggleSelect = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("select_h1").assertExists()
    }

    @Test fun `outside selection mode the body still opens edit`() {
        var edited = false
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    HabitsTabContent(
                        tasks = listOf(habit("h1")),
                        onScoreUp = {}, onScoreDown = {},
                        onLongPressUp = {}, onLongPressDown = {},
                        onEdit = { edited = true },
                        onPersistOrder = {}, onMove = { _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithTag("habit_body_h1").performClick()
        assertTrue(edited)
    }
}
