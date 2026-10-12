package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.TaskEntity
import com.example.ui.TodosTabContent
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-8.16 (PX-98, I8): Opti deletes only from the edit sheet; a card long-press is a drag in manual
 * sort and nothing at all in any other sort. The card composables take no delete callback, and
 * no card long-press opens anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoLongPressDeleteTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun `a long-press on a card in a non-manual sort does nothing`() {
        val drag = DragActivity()
        var edits = 0; var toggles = 0; val orders = mutableListOf<List<String>>()
        rule.setContent {
            CardTestHost(drag = drag) {
                Box(Modifier.height(400.dp)) {
                    TodosTabContent(
                        tasks = listOf(TaskEntity(id = "t1", type = "todo", title = "Todo", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())),
                        onToggleCheck = { _, _ -> toggles++ }, onEdit = { edits++ },
                        onToggleSubtask = { _, _, _ -> }, onReorderSubtasks = { _, _ -> },
                        onPersistOrder = { orders += it },
                        sortMode = "created-desc",
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("body_t1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("body_t1").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, edits)
        assertEquals(0, toggles)
        assertEquals(0, drag.pickups)
        assertEquals(emptyList<List<String>>(), orders)
    }

    @Test fun `no card composable wires a long-click or a delete`() {
        val src = listOf("src/main/java/com/example/ui/DashboardScreen.kt", "app/src/main/java/com/example/ui/DashboardScreen.kt")
            .map(::File).first { it.isFile }.readText()
        assertFalse(src.contains("combinedClickable"))
        assertFalse(src.contains("onLongClick"))
        for (card in listOf("HabitCardItem", "DailyCardItem", "TodoCardItem", "RewardCardItem")) {
            val sig = src.substringAfter("fun $card(").substringBefore(") {")
            assertFalse("$card takes a delete callback", sig.contains("onDelete"))
        }
    }
}
