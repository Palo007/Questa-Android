package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/** Counts every callback a habit card fires. */
internal class HabitCalls {
    var up = 0; var down = 0; var longUp = 0; var longDown = 0; var edit = 0
}

/**
 * WP-8.16 (PX-94, I4): a hold on a habit +/- opens the rep sheet at Opti's REP_LONGPRESS_MS = 350
 * (app.js:2517), a move of more than 10 px first cancels it (:8549), and a disabled (`.hbtn.off`)
 * button does nothing (:8528).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepSheetTimingTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val calls = HabitCalls()
    private val buzzes = mutableListOf<Long>()

    private fun show(task: TaskEntity = TaskEntity(id = "h1", type = "habit", title = "Water", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())) {
        rule.setContent {
            CardTestHost(buzz = Buzz { buzzes += it }) {
                    HabitCardItem(
                        task = task,
                        onScoreUp = { calls.up++ }, onScoreDown = { calls.down++ },
                        onLongPressUp = { calls.longUp++ }, onLongPressDown = { calls.longDown++ },
                        onEdit = { calls.edit++ }, sortMode = "manual",
                    )
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    // The test clock moves in 16 ms frames, so the checks sit one frame either side of 350 ms.
    @Test fun `a hold opens the sheet at 350 ms, not before, while the finger is still down`() {
        show()
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(330)
        assertEquals(0, calls.longUp)
        rule.mainClock.advanceTimeBy(40)
        assertEquals(1, calls.longUp)
        rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, calls.up)
    }

    @Test fun `the minus button opens the minus sheet`() {
        show()
        rule.onNodeWithTag("habit_down_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("habit_down_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, calls.longDown)
        assertEquals(0, calls.down)
        assertEquals(0, calls.longUp)
    }

    @Test fun `a move of more than 10 dp before 350 ms cancels the long-press and the tap`() {
        show()
        rule.onNodeWithTag("habit_up_h1").performTouchInput {
            down(center)
            moveBy(Offset(0f, 11.dp.toPx()))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, calls.longUp)
        assertEquals(0, calls.up)
    }

    @Test fun `a move of 9 dp keeps both the long-press and the tap alive`() {
        show()
        rule.onNodeWithTag("habit_up_h1").performTouchInput {
            down(center)
            moveBy(Offset(9.dp.toPx(), 0f))
            up()
        }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, calls.up)
    }

    @Test fun `a disabled button neither scores nor opens the sheet`() {
        show(TaskEntity(id = "h1", type = "habit", title = "Water", up = false, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("habit_up_h1").performTouchInput { up() }
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(0, calls.longUp)
        assertEquals(0, calls.up)
    }
}
