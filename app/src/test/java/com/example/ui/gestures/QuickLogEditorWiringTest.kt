package com.example.ui.gestures

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.TaskEntity
import com.example.ui.TaskEditSheet
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.13 patch D-1: the editor shows the Android shortcut setting for a habit and saves it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogEditorWiringTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun `a habit saves the chosen shortcut mode`() {
        var saved: QuickLogMode? = null
        var saves = 0
        rule.setContent {
            TaskEditSheet(
                task = TaskEntity(id = "h1", type = "habit", title = "Water", quickLog = QuickLogMode.UP, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _, _, _, _, _, q, _, _ -> saved = q; saves++ },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("quicklog_editor").performScrollTo()
        rule.onNode(hasText("+1 and −1")).performClick()
        rule.onNodeWithTag("save_task_button").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, saves)
        assertEquals(QuickLogMode.BOTH, saved)
    }

    @Test fun `a todo shows no shortcut setting`() {
        rule.setContent {
            TaskEditSheet(task = TaskEntity(id = "t1", type = "todo", title = "T", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), onDismiss = {}, onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> })
        }
        rule.onNodeWithTag("quicklog_editor").assertDoesNotExist()
    }
}
