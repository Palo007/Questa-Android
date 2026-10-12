package com.example.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.CardPrefs
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class AppearanceActions : SettingsActions {
    val calls = mutableListOf<String>()
    override fun openOption(key: String?) { calls += "option:$key" }
    override fun setWidth(px: Int) { calls += "width:$px" }
    override fun setNotesLines(n: Int) { calls += "notes:$n" }
    override fun setShowStreaks(on: Boolean) { calls += "showStreaks:$on" }
    override fun setHideSyncDiag(on: Boolean) { calls += "hideSyncDiag:$on" }
    override fun setHideConflictDecisions(on: Boolean) { calls += "hideConflicts:$on" }
    override fun setHaptics(on: Boolean) { calls += "haptics:$on" }
    override fun chooseHaptics(on: Boolean) { calls += "chooseHaptics:$on" }
    override fun testVibration(): HapticsDiag { calls += "testVibration"; return HapticsDiag() }
    override fun browseAvatarImage() { calls += "browse" }
    override fun removeAvatarImage() { calls += "remove" }
}

/** WP-8.15: the Appearance / Activity Feed / Haptics / avatar rows with Opti's labels and values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppearanceRowsScreenTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun appearance_category_rows_and_values() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(category = "appearance"), a) }
        rule.onNodeWithText("🎨 Appearance").assertIsDisplayed()
        rule.onNodeWithText("Interface width").assertIsDisplayed()
        rule.onNodeWithText("Custom ›").assertIsDisplayed()
        rule.onNodeWithText("Note lines").assertIsDisplayed()
        rule.onNodeWithText("3 lines ›").assertIsDisplayed()
        rule.onNodeWithText("Card thickness").assertIsDisplayed()
        rule.onNodeWithTag("cardThick_value").assertTextEquals("Default")
        rule.onNodeWithText("Streak badges").assertIsDisplayed()
        rule.onNodeWithTag("multi_width").performClick()
        rule.onNodeWithTag("multi_notes").performClick()
        rule.onNodeWithTag("toggle_showStreaks").performClick()
        assertEquals(listOf("option:width", "option:notes", "showStreaks:false"), a.calls)
    }

    @Test fun slider_value_label_follows_the_pref() {
        rule.setContent { SettingsScreen(SettingsUiState(category = "appearance", card = CardPrefs(cardThick = 14, widthPx = 720, notesLines = 1)), AppearanceActions()) }
        rule.onNodeWithTag("cardThick_value").assertTextEquals("+14 px")
        rule.onNodeWithText("Wide ›").assertIsDisplayed()
        rule.onNodeWithText("1 line ›").assertIsDisplayed()
    }

    @Test fun width_menu_offers_the_four_widths() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(option = "width"), a) }
        rule.onNodeWithText(AppearanceTexts.WIDTH_HINT).assertIsDisplayed()
        listOf("Slim", "Medium", "Wide", "Full").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithTag("width_560").performClick()
        rule.onNodeWithTag("width_3000").performClick()
        assertEquals(listOf("width:560", "width:3000"), a.calls)
    }

    @Test fun notes_menu_offers_off_1_2_3_5() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(option = "notes"), a) }
        rule.onNodeWithText("Note lines on cards").assertIsDisplayed()
        listOf(0, 1, 2, 3, 5).forEach { rule.onNodeWithTag("notes_$it").performClick() }
        assertEquals(listOf("notes:0", "notes:1", "notes:2", "notes:3", "notes:5"), a.calls)
    }

    @Test fun activity_feed_has_the_two_hide_toggles_before_pause() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(category = "activityFeed"), a) }
        rule.onNodeWithText("Hide sync & diagnostic events").assertIsDisplayed()
        rule.onNodeWithText("Hide conflict decisions").assertIsDisplayed()
        rule.onNodeWithText("Pause tracking").assertIsDisplayed()
        val top = { t: String -> rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Hide sync & diagnostic events") < top("Hide conflict decisions"))
        assertTrue(top("Hide conflict decisions") < top("Pause tracking"))
        rule.onNodeWithTag("toggle_hideSyncDiag").performClick()
        rule.onNodeWithTag("toggle_hideConflictDecisions").performClick()
        assertEquals(listOf("hideSyncDiag:false", "hideConflicts:true"), a.calls)
    }

    @Test fun haptics_label_opens_the_menu_with_diagnostics_and_test() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(category = "interaction"), a) }
        rule.onNodeWithTag("toggle_haptics_label").performClick()
        assertEquals(listOf("option:haptics"), a.calls)
    }

    @Test fun haptics_menu() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(option = "haptics", hapticsDiag = HapticsDiag("performHapticFeedback", true, 3)), a) }
        rule.onNodeWithText(AppearanceTexts.HAPTICS_HINT).assertIsDisplayed()
        rule.onNodeWithTag("haptics_diag").assertTextEquals("API: performHapticFeedback · Last: true · Count: 3")
        rule.onNodeWithText("Test vibration").performClick()
        rule.onNodeWithTag("haptics_off").performClick()
        assertEquals(listOf("testVibration", "chooseHaptics:false"), a.calls)
    }

    @Test fun avatar_browse_and_remove() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(hasAvatarImage = false), a) }
        rule.onNodeWithTag("avatar_remove").assertDoesNotExist()
        rule.onNodeWithText("Browse image").performClick()
        assertEquals(listOf("browse"), a.calls)
    }

    @Test fun avatar_remove_shows_with_an_image() {
        val a = AppearanceActions()
        rule.setContent { SettingsScreen(SettingsUiState(hasAvatarImage = true), a) }
        rule.onNodeWithTag("avatar_remove").performClick()
        assertEquals(listOf("remove"), a.calls)
    }
}
