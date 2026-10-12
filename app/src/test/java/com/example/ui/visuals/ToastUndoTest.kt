package com.example.ui.visuals

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.18 (PX-104): Opti `toast(msg)` (app.js:3015-3019) shows a styled `.toastMsg` for 2.4 s,
 * several stack; the `.toastMsg button` action (Undo) runs its callback and closes the toast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ToastUndoTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun `a toast shows and is removed after 2400 ms`() {
        val state = ToastState()
        rule.mainClock.autoAdvance = false
        rule.setContent { QuestaToastHost(state) }
        Snapshot.withMutableSnapshot { state.show("+3 reps · no reward") }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("+3 reps · no reward").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1600)
        rule.onAllNodesWithTag("toast_0").assertCountEquals(0)
        assertEquals(0, state.items.size)
    }

    @Test fun `toasts stack and expire in order`() {
        val state = ToastState()
        rule.mainClock.autoAdvance = false
        rule.setContent { QuestaToastHost(state) }
        Snapshot.withMutableSnapshot { state.show("one") }
        rule.mainClock.advanceTimeBy(1200)
        Snapshot.withMutableSnapshot { state.show("two") }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("one").assertIsDisplayed()
        rule.onNodeWithText("two").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1300)
        rule.onAllNodesWithTag("toast_0").assertCountEquals(0)
        rule.onNodeWithText("two").assertIsDisplayed()
    }

    @Test fun `undo action runs and closes the toast`() {
        val state = ToastState()
        var undone = 0
        rule.setContent { QuestaToastHost(state) }
        Snapshot.withMutableSnapshot { state.show("Task deleted", ToastAction("Undo") { undone++ }) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("toast_action_0").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, undone)
        assertEquals(0, state.items.size)
    }

    @Test fun `a plain toast has no action button`() {
        val state = ToastState()
        rule.setContent { QuestaToastHost(state) }
        Snapshot.withMutableSnapshot { state.show("Avatar image set") }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        rule.onAllNodesWithTag("toast_action_0").assertCountEquals(0)
    }
}
