package com.example.ui.gestures

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.freshMainDispatcher
import com.example.ui.settings.SettingsTexts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.22-D item 1: the dashboard's start-up error line (hidden unless the start-up failed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StartupFailedBannerTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun `shown with the Settings text only when the start-up failed`() {
        val failed = mutableStateOf(false)
        rule.setContent { StartupFailedBanner(failed.value) }
        rule.onAllNodesWithTag("startup_failed_banner").assertCountEquals(0)
        failed.value = true
        rule.waitForIdle()
        rule.onAllNodesWithTag("startup_failed_banner").assertCountEquals(1)
        rule.onNodeWithText(SettingsTexts.STARTUP_FAILED).assertExists()
    }

    @Test fun `placeholder shows the error and opens Settings (review 46 F1)`() {
        var opened = 0
        rule.setContent { StartupFailedPlaceholder(onOpenSettings = { opened++ }) }
        rule.onNodeWithText(SettingsTexts.STARTUP_FAILED).assertExists()
        rule.onNodeWithTag("startup_failed_settings").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(1, opened)
    }
}
