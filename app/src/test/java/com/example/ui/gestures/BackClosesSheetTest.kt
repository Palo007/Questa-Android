package com.example.ui.gestures

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.data.local.entities.TaskEntity
import com.example.ui.TaskEditSheet
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.16 (PX-96, I16): system Back closes an open sheet and does not leave the app; with no
 * sheet open, Back falls through to the activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackClosesSheetTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createAndroidComposeRule<ComponentActivity>() }

    private var open by mutableStateOf(true)
    private var dismissed = 0

    private fun show() {
        rule.setContent {
            SheetBackHandler(open) { open = false }
            if (open) TaskEditSheet(
                task = TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
                onDismiss = { dismissed++; open = false },
                onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            )
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun `back closes the open editor and the app stays`() {
        show()
        rule.onNodeWithTag("save_task_button").assertExists()
        pressBack()
        assertFalse(open)
        rule.onNodeWithTag("save_task_button").assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun `back with no sheet open falls through to the activity`() {
        open = false
        show()
        pressBack()
        assertTrue(rule.activity.isFinishing)
    }
}
