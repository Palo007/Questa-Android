package com.example.launcher

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.entities.QuickLogMode
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.13: the Opti "Android shortcut" segment (app.js:6006-6011). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogEditorTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun labels_hint_andEachOptionSetsItsMode() {
        var mode by mutableStateOf<QuickLogMode?>(null)
        rule.setContent { QuickLogShortcutEditor(mode, { mode = it }) }
        rule.onNodeWithText("Android shortcut").assertIsDisplayed()
        rule.onNodeWithText(QuickLogEditorText.HINT).assertIsDisplayed()
        for (label in listOf("Off", "+1", "−1", "+1 and −1")) rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNodeWithTag("ql_both").performClick()
        assertEquals(QuickLogMode.BOTH, mode)
        rule.onNodeWithTag("ql_down").performClick()
        assertEquals(QuickLogMode.DOWN, mode)
        rule.onNodeWithTag("ql_up").performClick()
        assertEquals(QuickLogMode.UP, mode)
        rule.onNodeWithTag("ql_off").performClick()
        assertEquals(QuickLogMode.OFF, mode)
    }
}
