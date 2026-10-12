package com.example.ui.gestures

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.entities.TaskEntity
import com.example.ui.TaskEditSheet
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The habit editor exposes the "+ Positive" / "− Negative" buttons (Opti app.js:6279-6282).
 * null counts as ON (`t.up!==false`); a toggle writes `!(x!==false)`. Both may be off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HabitButtonsEditorTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private fun habit(up: Boolean? = null, down: Boolean? = null) = TaskEntity(
        id = "h1", type = "habit", title = "Water", up = up, down = down,
        createdAt = com.example.data.clock.PhysicalClock.nowMs(),
        updatedAt = com.example.data.clock.PhysicalClock.nowMs(),
    )

    @Test fun `turning plus off saves up false and leaves untouched down as stored`() {
        var savedUp: Boolean? = null
        var savedDown: Boolean? = null
        rule.setContent {
            TaskEditSheet(
                task = habit(),
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _, _, _, _, _, _, u, d -> savedUp = u; savedDown = d },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("habit_up_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, savedUp)
        // Opti keeps EDIT.down as stored (undefined) unless toggled; null still renders ON.
        assertEquals(null, savedDown)
    }

    @Test fun `both buttons may be turned off`() {
        var savedUp: Boolean? = null
        var savedDown: Boolean? = null
        rule.setContent {
            TaskEditSheet(
                task = habit(up = true, down = true),
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _, _, _, _, _, _, u, d -> savedUp = u; savedDown = d },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("habit_up_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("habit_down_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, savedUp)
        assertEquals(false, savedDown)
    }

    @Test fun `a stored false is shown off and toggles back on as true`() {
        var savedDown: Boolean? = null
        rule.setContent {
            TaskEditSheet(
                task = habit(down = false),
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, d -> savedDown = d },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("habit_down_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(true, savedDown)
    }

    @Test fun `a todo shows no habit buttons`() {
        rule.setContent {
            TaskEditSheet(
                task = TaskEntity(id = "t1", type = "todo", title = "T", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            )
        }
        rule.onNodeWithTag("habit_up_toggle").assertDoesNotExist()
        rule.onNodeWithTag("habit_down_toggle").assertDoesNotExist()
    }
}
