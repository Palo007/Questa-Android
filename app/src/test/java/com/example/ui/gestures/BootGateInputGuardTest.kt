package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.boot.BootGate
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.ui.HabitsTabContent
import com.example.ui.RyaTestBase
import com.example.ui.SYNCING_TOAST
import com.example.ui.freshMainDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.16 (§5.0 B13, D26): while `BootGate.blocksInput()`, every guarded card action changes 0 rows
 * and toasts "Syncing…" (Opti app.js:2464 scoreHabit, :3078 toggleSub, :5828 toggle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootGateInputGuardTest : RyaTestBase() {

    private suspend fun seed() {
        seedChar(lastCron = "20260925")
        repo.insertTask(TaskEntity(id = "h1", type = "habit", title = "Habit", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        repo.insertTask(TaskEntity(id = "d1", type = "daily", title = "Daily", updatedAt = 1L,
            checklist = listOf(ChecklistItem("s1", "sub", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "Todo", done = true, updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
    }

    private suspend fun snapshot() = repo.getAllTasksSync().sortedBy { it.id }.map { it.copy() } to repo.allLogs.first().size

    @Test fun each_guarded_action_changes_nothing_and_toasts_while_pending() = runBlocking {
        seed()
        val vm = newVm()
        val toasts = collectToasts(vm)
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertTrue(BootGate.blocksInput())
        val before = snapshot()

        vm.scoreHabitUp("h1")
        vm.scoreHabitDown("h1")
        vm.completeTask("d1", applyDueTodayGate = true)
        vm.uncompleteTask("t1")
        vm.toggleSubtask("d1", "s1", 0)
        delay(300)

        assertEquals(before, snapshot())
        assertEquals(List(5) { SYNCING_TOAST }, toasts.settled())
    }

    @Test fun the_actions_work_again_once_the_gate_opens() = runBlocking {
        seed()
        val vm = newVm()
        val toasts = collectToasts(vm)
        BootGate.bootStartDay(configured = true, barrierDone = true)
        BootGate.onFirstRoundSettled(null)
        awaitRolled(); awaitDayMutex()
        assertTrue(!BootGate.blocksInput())
        // scoreHabitUp reads the subscribed task list, as the Dashboard does.
        val sub = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { vm.allTasks.collect { } }
        var waits = 0
        while (vm.allTasks.value.none { it.id == "h1" } && waits++ < 50) delay(50)

        vm.scoreHabitUp("h1")
        vm.toggleSubtask("d1", "s1", 0)
        var tries = 0
        while ((task("h1")!!.cUp != 1 || task("d1")!!.checklist.first().done != true) && tries++ < 50) delay(50)
        assertEquals(1, task("h1")!!.cUp)
        assertEquals(true, task("d1")!!.checklist.first().done)
        assertTrue(toasts.settled().none { it == SYNCING_TOAST })
        sub.cancel()
        // Let the taps' DataStore writes (markLocalSaved) finish before the device closes.
        delay(1_000)
    }
}

/**
 * B13 UI: the "syncing…" banner is shown while gated and gone once the gate opens. Layer 2: a
 * gated card ignores tap, long-press and drag and paints at 0.55; the header, nav and Settings
 * (outside the cards) stay live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootGateLayerTwoTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private var gated by mutableStateOf(true)

    @Test fun `banner follows the gate`() {
        rule.setContent { SyncGateBanner(gated) }
        rule.onNodeWithText(SYNC_GATE_BANNER).assertIsDisplayed()
        gated = false
        rule.waitForIdle()
        rule.onNodeWithTag("sync_gate_banner").assertDoesNotExist()
    }

    @Test fun `a gated card ignores tap, long-press and drag, at alpha 0_55, and the header stays live`() {
        val calls = HabitCalls()
        val drag = DragActivity()
        var headerClicks = 0
        val orders = mutableListOf<List<String>>()
        rule.setContent {
            CardTestHost(drag = drag, gated = gated) {
                Column {
                    Box(Modifier.size(100.dp).testTag("header").clickable { headerClicks++ })
                    Box(Modifier.height(400.dp)) {
                        HabitsTabContent(
                            tasks = listOf(TaskEntity(id = "h1", type = "habit", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), TaskEntity(id = "h2", type = "habit", title = "B", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())),
                            onScoreUp = { calls.up++ }, onScoreDown = { calls.down++ },
                            onLongPressUp = { calls.longUp++ }, onLongPressDown = { calls.longDown++ },
                            onEdit = { calls.edit++ }, onPersistOrder = { orders += it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("habit_card_h1").assert(SemanticsMatcher.expectValue(GateAlphaKey, GestureSpec.GATED_CARD_ALPHA))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center); up() }
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center); up() }
        rule.onNodeWithTag("habit_down_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("habit_down_h1").performTouchInput { up() }
        rule.onNodeWithTag("habit_body_h1").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("habit_body_h1").performTouchInput { moveBy(Offset(0f, 200f)); up() }
        rule.mainClock.advanceTimeBy(400)
        assertEquals(0, calls.up + calls.down + calls.longUp + calls.longDown + calls.edit)
        assertEquals(0, drag.pickups)
        assertEquals(emptyList<List<String>>(), orders)

        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("header").performClick()
        assertEquals(1, headerClicks)

        gated = false
        rule.waitForIdle()
        rule.onNodeWithTag("habit_card_h1").assert(SemanticsMatcher.expectValue(GateAlphaKey, 1f))
        rule.onNodeWithTag("habit_up_h1").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(1, calls.up)
    }
}
