package com.example.ui.activity

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.TaskEditSheet
import com.example.ui.freshMainDispatcher
import com.example.ui.settings.DiagnosticsDialog
import com.example.ui.settings.DiagnosticsView
import com.example.ui.settings.FeedFilters
import com.example.ui.settings.SettingsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** CR-KT-012: the Activity tab, its detail sheet, the task "History" row and the moved rollover button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActivityScreenTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val zone = ZoneId.of("Europe/Bratislava")
    private fun at(d: Int, h: Int) = ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(8, 12)

    private val logs = listOf(
        EventLogEntity(id = 1, timestamp = at(8, 9), kind = "habitTap", taskType = "habit", taskId = "h1", taskTitle = "Water", direction = 1, dev = "pixel"),
        EventLogEntity(id = 2, timestamp = at(7, 9), kind = "complete", taskType = "daily", taskId = "d1", taskTitle = "Stretch"),
        EventLogEntity(id = 3, timestamp = at(8, 7), kind = "clockSkew", synthetic = true),
    )

    @Test fun `day headers, rows and the default All range`() {
        rule.setContent { ActivityScreen(logs, FeedFilters(), ActivityNav(), {}, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_screen").assertExists()
        rule.onNodeWithText("Today").assertExists()
        rule.onNodeWithText("Yesterday").assertExists()
        rule.onNodeWithTag("activity_range_all").assertIsSelected()
        rule.onNodeWithTag("activity_row_1").assertExists()
        rule.onAllNodesWithText("09:00:00").assertCountEquals(2) // one row per day
    }

    @Test fun `hidden diagnostics footer shows the count and Show flips the pref`() {
        val calls = mutableListOf<Boolean>()
        rule.setContent { ActivityScreen(logs, FeedFilters(), ActivityNav(), {}, { calls += it }, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_row_3").assertDoesNotExist()
        rule.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("activity_hidden_footer"))
        rule.onNodeWithText("1 hidden (diagnostics)", substring = true).assertExists()
        rule.onNodeWithTag("activity_show_hidden").performClick()
        assertEquals(listOf(false), calls)
    }

    // Opti evDiagFooterHtml (app.js:4962-4966): "Diagnostics shown · Hide" when the pref is off.
    @Test fun `shown diagnostics footer offers Hide, which turns the pref back on`() {
        val calls = mutableListOf<Boolean>()
        rule.setContent { ActivityScreen(logs, FeedFilters(hideSyncDiag = false), ActivityNav(), {}, { calls += it }, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("activity_shown_footer"))
        rule.onNodeWithText("Diagnostics shown", substring = true).assertExists()
        rule.onNodeWithTag("activity_hidden_footer").assertDoesNotExist()
        rule.onNodeWithTag("activity_hide_shown").performClick()
        assertEquals(listOf(true), calls)
    }

    @Test fun `no diagnostic rows, no footer`() {
        rule.setContent { ActivityScreen(logs.take(2), FeedFilters(), ActivityNav(), {}, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_hidden_footer").assertDoesNotExist()
        rule.onNodeWithTag("activity_shown_footer").assertDoesNotExist()
    }

    @Test fun `task filter chip shows the title and clearing it removes the filter`() {
        var nav by mutableStateOf(ActivityNav().forTask("h1", "Water"))
        rule.setContent { ActivityScreen(logs, FeedFilters(), nav, { nav = it }, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_task_chip").assertExists()
        rule.onNodeWithText("Task: Water", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("activity_row_2").assertDoesNotExist()
        rule.onNodeWithTag("activity_task_clear").performClick()
        rule.waitForIdle()
        assertEquals(null, nav.task)
        rule.onNodeWithTag("activity_task_chip").assertDoesNotExist()
        rule.onNodeWithTag("activity_row_2").assertExists()
    }

    @Test fun `an Analytics window shows as the selected custom range`() {
        val nav = ActivityNav().forWindow(at(8, 0), now)
        rule.setContent { ActivityScreen(logs, FeedFilters(), nav, {}, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_range_custom").assertIsSelected()
        rule.onNodeWithTag("activity_row_1").assertExists()
        rule.onNodeWithTag("activity_row_2").assertDoesNotExist()
    }

    @Test fun `picking a range chip replaces the Analytics window`() {
        var nav by mutableStateOf(ActivityNav().forWindow(at(8, 0), now))
        rule.setContent { ActivityScreen(logs, FeedFilters(), nav, { nav = it }, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_range_7d").performClick()
        rule.waitForIdle()
        assertEquals(ActivityRange(snap = "7d"), nav.range)
    }

    @Test fun `row tap opens the event detail sheet`() {
        rule.setContent { ActivityScreen(logs, FeedFilters(), ActivityNav(), {}, {}, zone, { now }) }
        rule.waitForIdle()
        // the row's own click (a touch at its centre can land on the title, which searches instead)
        rule.onNodeWithTag("activity_row_1").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Event detail").assertExists()
        rule.onNodeWithTag("event_detail_Device").assertExists()
    }

    @Test fun `detail content shows non-null fields only and copies the payload`() {
        val e = EventLogEntity(id = 5, timestamp = at(8, 7), kind = "purchase", taskTitle = "Coffee", taskType = "reward", cost = 10.0, payloadJson = "{\"a\":1}")
        var copied: String? = null
        rule.setContent { EventDetailContent(e, zone) { copied = it } }
        rule.waitForIdle()
        listOf("Kind", "Task", "Time", "Cost", "Payload").forEach { rule.onNodeWithTag("event_detail_$it").assertExists() }
        listOf("Damage", "Reps", "Value", "Streak", "Direction", "Device", "UID", "Imported", "Republish", "Synthetic")
            .forEach { rule.onNodeWithTag("event_detail_$it").assertDoesNotExist() }
        rule.onNodeWithTag("event_detail_copy").performClick()
        assertEquals("{\n    \"a\": 1\n}", copied)
    }

    @Test fun `task edit sheet History row calls back for a saved task`() {
        var opened = 0
        val t = TaskEntity(id = "h1", type = "habit", title = "Water", createdAt = 1L, updatedAt = 1L)
        rule.setContent { TaskEditSheet(task = t, onDismiss = {}, onHistory = { opened++ }, onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }) }
        rule.waitForIdle()
        rule.onNodeWithTag("edit_history_row").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun `History with unsaved edits, then Save, saves and still opens Activity`() {
        var opened = 0
        var savedTitle: String? = null
        val t = TaskEntity(id = "h1", type = "habit", title = "Water", createdAt = 1L, updatedAt = 1L)
        rule.setContent { TaskEditSheet(task = t, onDismiss = {}, onHistory = { opened++ }, onSave = { _, title, _, _, _, _, _, _, _, _, _, _, _, _ -> savedTitle = title }) }
        rule.waitForIdle()
        rule.onNodeWithTag("task_title_input").performTextReplacement("Water more")
        rule.onNodeWithTag("edit_history_row").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("discard_dialog_save_button").performClick()
        rule.waitForIdle()
        assertEquals("Water more", savedTitle)
        assertEquals(1, opened)
    }

    @Test fun `the page resets when the feed filters change`() {
        val many = (1..30).map { i -> EventLogEntity(id = i.toLong(), timestamp = at(8, 9) - i * 60_000L, kind = "habitTap", taskType = "habit", taskId = "h1", taskTitle = "Water") } +
            EventLogEntity(id = 99, timestamp = at(8, 1), kind = "clockSkew")
        var ff by mutableStateOf(FeedFilters())
        rule.setContent { ActivityScreen(many, ff, ActivityNav(), {}, {}, zone, { now }) }
        rule.waitForIdle()
        rule.onNodeWithTag("activity_list").performScrollToNode(hasText("Older →"))
        rule.onNodeWithText("Older →").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("activity_list").performScrollToNode(hasText("Page 2 / 2", substring = true))
        ff = FeedFilters(hideSyncDiag = false)
        rule.waitForIdle()
        rule.onNodeWithTag("activity_list").performScrollToNode(hasText("Page 1 / 2", substring = true))
        rule.onNodeWithText("Page 1 / 2 · 31 events").assertExists()
    }

    @Test fun `task edit sheet has no History row for a new task`() {
        rule.setContent { TaskEditSheet(task = null, onDismiss = {}, onHistory = {}, onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }) }
        rule.waitForIdle()
        rule.onNodeWithTag("edit_history_row").assertDoesNotExist()
    }

    @Test fun `diagnostics holds the midnight rollover button`() {
        var ran = false
        rule.setContent { DiagnosticsDialog(DiagnosticsView(emptyList(), emptyList(), "d.json"), object : SettingsActions {}, onForceCron = { ran = true }) }
        rule.waitForIdle()
        rule.onNodeWithTag("force_cron_button").performClick()
        assertTrue(ran)
    }
}
