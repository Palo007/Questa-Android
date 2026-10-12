package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.ui.DailiesTabContent
import com.example.ui.HabitsTabContent
import com.example.ui.MOVE_DOWN_LABEL
import com.example.ui.MOVE_UP_LABEL
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review 35 item 2: every card action is reachable through accessibility actions (TalkBack), and
 * they call the same callbacks as touch (so the same guarded ViewModel functions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardAccessibilityTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private fun customAction(tag: String, label: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == label }
        rule.runOnIdle { action.action() }
    }

    @Test fun `habit buttons, body and move actions`() {
        val calls = HabitCalls()
        val moves = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    HabitsTabContent(
                        tasks = listOf(TaskEntity(id = "h1", type = "habit", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), TaskEntity(id = "h2", type = "habit", title = "B", down = false, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())),
                        onScoreUp = { calls.up++ }, onScoreDown = { calls.down++ },
                        onLongPressUp = { calls.longUp++ }, onLongPressDown = { calls.longDown++ },
                        onEdit = { calls.edit++ }, onPersistOrder = { },
                        onMove = { id, up -> moves += id to up },
                    )
                }
            }
        }
        rule.onNodeWithTag("habit_up_h1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("habit_up_h1").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag("habit_down_h1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("habit_down_h1").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.onNodeWithTag("habit_body_h1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(calls.up, calls.longUp, calls.down, calls.longDown, calls.edit))

        // A disabled (.hbtn.off) button is marked disabled and does nothing.
        rule.onNodeWithTag("habit_down_h2").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        rule.onNodeWithTag("habit_down_h2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, calls.down)

        customAction("habit_card_h2", MOVE_UP_LABEL)
        customAction("habit_card_h1", MOVE_DOWN_LABEL)
        assertEquals(listOf("h2" to true, "h1" to false), moves)
    }

    @Test fun `no move actions outside manual sort`() {
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(300.dp)) {
                    HabitsTabContent(
                        tasks = listOf(TaskEntity(id = "h1", type = "habit", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())),
                        onScoreUp = {}, onScoreDown = {}, onLongPressUp = {}, onLongPressDown = {},
                        onEdit = {}, onPersistOrder = {}, sortMode = "created-desc",
                    )
                }
            }
        }
        assertNull(rule.onNodeWithTag("habit_card_h1").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions))
    }

    @Test fun `daily check, badge, subtask box, subtask row and subtask moves`() {
        var checks = 0
        val toggles = mutableListOf<String?>()
        val subOrders = mutableListOf<List<String>>()
        val daily = TaskEntity(
            id = "d1", type = "daily", title = "D",
            checklist = listOf(ChecklistItem("s1", "one", false), ChecklistItem("s2", "two", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs(),
        )
        rule.setContent {
            CardTestHost {
                Box(Modifier.height(500.dp)) {
                    DailiesTabContent(
                        tasks = listOf(daily), onToggleCheck = { _, _ -> checks++ }, onEdit = {},
                        onToggleSubtask = { _, sub, _ -> toggles += sub },
                        onReorderSubtasks = { _, ids -> subOrders += ids }, onPersistOrder = {},
                    )
                }
            }
        }
        rule.onNodeWithTag("check_d1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, checks)
        rule.onNodeWithTag("checklist_badge_d1").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("subtask_checkbox_s1").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("subtask_row_s2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<String?>("s1", "s2"), toggles)
        customAction("subtask_row_s2", MOVE_UP_LABEL)
        assertEquals(listOf(listOf("s2", "s1")), subOrders)
    }
}
