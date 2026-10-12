package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.ui.DailiesTabContent
import com.example.ui.HabitsTabContent
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.16 (PX-93, I1/I2): the card lifts at Opti's `longPressMs()` = 200 ms (app.js:5223) with a
 * 15 ms buzz (:5285); a move of more than 10 px first is a scroll (:5242); a press that starts on a
 * card control (+/-, check, subFrac, subtask) never lifts the card (`skipCardTimer`, :5210-5216).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DragPickupTimingTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val drag = DragActivity()
    private val buzzes = mutableListOf<Long>()
    private val calls = HabitCalls()
    private val orders = mutableListOf<List<String>>()

    private val habits = listOf(
        TaskEntity(id = "h1", type = "habit", title = "One", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        TaskEntity(id = "h2", type = "habit", title = "Two", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
    )

    private fun showHabits(sortMode: String = "manual") {
        rule.setContent {
            CardTestHost(buzz = Buzz { buzzes += it }, drag = drag) {
                androidx.compose.foundation.layout.Box(Modifier.height(600.dp)) {
                    HabitsTabContent(
                        tasks = habits,
                        onScoreUp = { calls.up++ }, onScoreDown = { calls.down++ },
                        onLongPressUp = { calls.longUp++ }, onLongPressDown = { calls.longDown++ },
                        onEdit = { calls.edit++ },
                        onPersistOrder = { orders += it },
                        sortMode = sortMode,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Test fun `199 ms does not lift the card and 200 ms does, with a 15 ms buzz`() {
        showHabits()
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(190)
        assertEquals(0, drag.pickups)
        rule.mainClock.advanceTimeBy(30)
        assertEquals(1, drag.pickups)
        assertEquals(listOf(GestureSpec.BUZZ_PICKUP_MS), buzzes)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        // Review 35 ruling: a still lift released before the long-press time is still a tap
        // (Chrome fires the click); the card drops in place.
        assertEquals(1, calls.edit)
        assertEquals(listOf(listOf("h1", "h2")), orders)
    }

    @Test fun `a lifted card that moved is not a tap, and a still hold past the long-press time is not either`() {
        showHabits()
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(250)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { moveBy(Offset(0f, 12.dp.toPx())); up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, calls.edit)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, calls.edit)
    }

    @Test fun `a lifted card dragged down past the next card persists the new order`() {
        showHabits()
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(250)
        rule.onNodeWithTag("habit_body_h1").performTouchInput {
            for (i in 1..12) moveBy(Offset(0f, 10.dp.toPx()))
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(listOf(listOf("h2", "h1")), orders)
        assertEquals(false, drag.active)
    }

    @Test fun `a move of more than 10 dp before 200 ms is a scroll, not a pickup`() {
        showHabits()
        rule.onNodeWithTag("habit_body_h1").performTouchInput {
            down(center)
            moveBy(Offset(11.dp.toPx(), 0f))
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, drag.pickups)
        assertEquals(0, calls.edit)
    }

    @Test fun `a hold on a habit button opens the rep sheet and never lifts the card`() {
        showHabits()
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, drag.pickups)
        assertEquals(1, calls.longUp)
    }

    @Test fun `a quick body tap opens the editor`() {
        showHabits()
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, calls.edit)
        assertEquals(0, drag.pickups)
    }

    @Test fun `a hold on a subtask row lifts the row, not the card, and the drop commits`() {
        val toggles = mutableListOf<String?>()
        val subOrders = mutableListOf<List<String>>()
        val daily = TaskEntity(
            id = "d1", type = "daily", title = "Daily",
            checklist = listOf(ChecklistItem("s1", "first", false), ChecklistItem("s2", "second", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs(),
        )
        rule.setContent {
            CardTestHost(buzz = Buzz { buzzes += it }, drag = drag) {
                androidx.compose.foundation.layout.Box(Modifier.height(600.dp)) {
                    DailiesTabContent(
                        tasks = listOf(daily),
                        onToggleCheck = { _, _ -> }, onEdit = { calls.edit++ },
                        onToggleSubtask = { _, sub, _ -> toggles += sub },
                        onReorderSubtasks = { _, ids -> subOrders += ids },
                        onPersistOrder = { orders += it },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("checklist_badge_d1").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        // A tap on the row toggles it.
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { down(centerRight - Offset(20f, 0f)) }
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf<String?>("s1"), toggles)
        // A hold lifts the row (one pickup, the row's) and the lift commits its order.
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { down(centerRight - Offset(20f, 0f)) }
        rule.mainClock.advanceTimeBy(250)
        assertEquals(1, drag.pickups)
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { for (i in 1..6) moveBy(Offset(0f, 8.dp.toPx())) }
        rule.mainClock.advanceTimeBy(50)
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf(listOf("s2", "s1")), subOrders)
        assertTrue("the card never lifted", orders.isEmpty())
        assertEquals("a moved row does not toggle", listOf<String?>("s1"), toggles)
        // Review 35 ruling: a 250 ms still hold lifts, drops in place and still toggles.
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { down(centerRight - Offset(20f, 0f)) }
        rule.mainClock.advanceTimeBy(250)
        rule.onNodeWithTag("subtask_row_s1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(2, drag.pickups)
        assertEquals(listOf<String?>("s1", "s1"), toggles)
        assertEquals(2, subOrders.size)
    }
}
