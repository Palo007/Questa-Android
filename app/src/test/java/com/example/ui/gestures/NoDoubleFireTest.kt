package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.TaskEntity
import com.example.ui.HabitCardItem
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.16 (PX-94, I6): Opti's `_suppressHabitClick` (app.js:2466, :8540). A long-press on +/-
 * opens the rep sheet ONCE and the trailing lift never also scores; a quick tap scores once and
 * never opens the sheet; the body never fires with a button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoDoubleFireTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val calls = HabitCalls()

    private fun show() {
        rule.setContent {
            CardTestHost {
                HabitCardItem(
                    task = TaskEntity(id = "h1", type = "habit", title = "Water", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
                    onScoreUp = { calls.up++ }, onScoreDown = { calls.down++ },
                    onLongPressUp = { calls.longUp++ }, onLongPressDown = { calls.longDown++ },
                    onEdit = { calls.edit++ }, sortMode = "manual",
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    @Test fun `long-press then release fires the sheet once and no score`() {
        show()
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, calls.longUp)
        assertEquals(0, calls.up)
        assertEquals(0, calls.edit)
    }

    @Test fun `a quick tap scores once and opens no sheet`() {
        show()
        rule.onNodeWithTag("habit_down_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("habit_down_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, calls.down)
        assertEquals(0, calls.longDown)
        assertEquals(0, calls.edit)
    }

    @Test fun `two taps score twice`() {
        show()
        repeat(2) {
            rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(60)
            rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
            rule.mainClock.advanceTimeBy(60)
        }
        assertEquals(2, calls.up)
        assertEquals(0, calls.longUp)
    }

    @Test fun `a body tap opens the editor and scores nothing`() {
        show()
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, calls.edit)
        assertEquals(0, calls.up + calls.down + calls.longUp + calls.longDown)
    }
}
