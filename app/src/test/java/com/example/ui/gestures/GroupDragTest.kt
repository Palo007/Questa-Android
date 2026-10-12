package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.clock.PhysicalClock
import com.example.data.local.entities.TaskEntity
import com.example.ui.HabitsTabContent
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-KT-011: in selection mode (manual sort) a long press on a SELECTED card lifts it and the
 * other selected cards ride along (collapsed, "+N" on the lifted card); the drop lands them as one
 * block. An unselected card never lifts; a tap still toggles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GroupDragTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val drag = DragActivity()
    private val orders = mutableListOf<List<String>>()
    private val toggles = mutableListOf<String>()

    private fun habit(id: String) = TaskEntity(
        id = id, type = "habit", title = id, createdAt = PhysicalClock.nowMs(), updatedAt = PhysicalClock.nowMs(),
    )

    private fun show(selected: Set<String>, sortMode: String = "manual", ids: List<String> = listOf("h1", "h2", "h3", "h4")) {
        rule.setContent {
            CardTestHost(drag = drag) {
                Box(Modifier.height(600.dp)) {
                    HabitsTabContent(
                        tasks = ids.map(::habit),
                        onScoreUp = {}, onScoreDown = {}, onLongPressUp = {}, onLongPressDown = {},
                        onEdit = {},
                        onPersistOrder = { orders += it },
                        sortMode = sortMode,
                        selectionMode = true,
                        selectedIds = selected,
                        onToggleSelect = { toggles += it },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun hold(tag: String, ms: Long = 250) {
        rule.onNodeWithTag(tag).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(ms)
    }

    /** True when no placed node with [tag] takes any height (collapsed or gone). */
    private fun collapsed(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .none { it.layoutInfo.isPlaced && it.size.height > 0 }

    @Test fun `a selected card lifts and an unselected one does not`() {
        show(selected = setOf("h1"))
        hold("habit_body_h2")
        assertEquals("unselected card never lifts", 0, drag.pickups)
        rule.onNodeWithTag("habit_body_h2").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        hold("habit_body_h1")
        assertEquals("selected card lifts", 1, drag.pickups)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertFalse(drag.active)
    }

    @Test fun `outside manual sort a selected card does not lift`() {
        show(selected = setOf("h1"), sortMode = "alpha")
        hold("habit_body_h1")
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, drag.pickups)
    }

    @Test fun `a quick tap on a selected card still toggles it`() {
        show(selected = setOf("h1"))
        hold("habit_body_h1", ms = 80)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("h1"), toggles)
        assertEquals(0, drag.pickups)
    }

    @Test fun `a still lift released in selection mode does not toggle the card`() {
        show(selected = setOf("h1", "h3"))
        hold("habit_body_h1")
        assertEquals(1, drag.pickups)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertTrue("the lifted card stays selected", toggles.isEmpty())
        assertEquals("a drop in place still persists once", listOf(listOf("h1", "h3", "h2", "h4")), orders)
        // The next quick tap toggles as usual.
        hold("habit_body_h1", ms = 80)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("h1"), toggles)
    }

    @Test fun `the other selected cards collapse into the lifted one and land as one block`() {
        show(selected = setOf("h1", "h3"))
        val cardPx = rule.onNodeWithTag("habit_card_h2").fetchSemanticsNode().size.height.toFloat()
        hold("habit_body_h1")
        rule.mainClock.advanceTimeBy(400)
        assertTrue("h3 rides along, collapsed", collapsed("habit_card_h3"))
        rule.onNodeWithTag("group_badge_h1").assertTextEquals("+1")
        // Drag h1 down past h2 (about one and a third cards).
        rule.onNodeWithTag("habit_body_h1").performTouchInput {
            val step = cardPx * 1.35f / 10
            for (i in 1..10) moveBy(Offset(0f, step))
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(600)
        assertEquals(listOf(listOf("h2", "h1", "h3", "h4")), orders)
        assertFalse(drag.active)
        assertFalse("h3 is back", collapsed("habit_card_h3"))
        assertTrue("selection is untouched by the drag", toggles.isEmpty())
    }
}
