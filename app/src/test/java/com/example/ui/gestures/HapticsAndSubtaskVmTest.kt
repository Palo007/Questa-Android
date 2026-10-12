package com.example.ui.gestures

import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.ui.MainViewModel
import com.example.ui.RyaTestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP-8.16: Opti's `buzz(ms)` after each action (habit up/log 50, habit down 100, complete 50,
 * subtask done 50, new task 50; app.js:2307, :2486, :2499, :2506, :3087, :6227), and `toggleSub`
 * / `commitSubOrder` stamps from the shared HLC (the §4.4 clock rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsAndSubtaskVmTest : RyaTestBase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun ready(vm: MainViewModel): MutableList<Long> {
        val out = CopyOnWriteArrayList<Long>()
        vm.buzzes.onEach { out += it }.launchIn(scope)
        scope.launch { vm.allTasks.collect { } }
        var n = 0
        while (vm.allTasks.value.isEmpty() && n++ < 60) delay(50)
        return out
    }

    private suspend fun waitFor(cond: suspend () -> Boolean) { var n = 0; while (!cond() && n++ < 60) delay(50) }

    @Test fun buzz_durations_follow_opti() = runBlocking {
        seedChar(lastCron = "20260925")
        repo.insertTask(TaskEntity(id = "h1", type = "habit", title = "H", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        repo.insertTask(TaskEntity(id = "hl", type = "habit", title = "Log", difficulty = "log", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "T", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val vm = newVm()
        val buzzes = ready(vm)

        vm.scoreHabitUp("h1"); waitFor { buzzes.size >= 1 }
        vm.scoreHabitDown("h1"); waitFor { buzzes.size >= 2 }
        vm.scoreHabitDown("hl"); waitFor { buzzes.size >= 3 }
        vm.completeTask("t1"); waitFor { buzzes.size >= 4 }
        vm.uncompleteTask("t1"); delay(300)
        assertEquals(listOf(50L, 100L, 50L, 50L), buzzes.toList())
        scope.cancel()
    }

    @Test fun toggleSubtask_stamps_hlc_logs_and_buzzes_only_when_done() = runBlocking {
        seedChar(lastCron = "20260925")
        repo.insertTask(TaskEntity(id = "d1", type = "daily", title = "D", updatedAt = 1L,
            checklist = listOf(ChecklistItem("s1", "one", false), ChecklistItem("s2", "two", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val vm = newVm()
        val buzzes = ready(vm)
        val hlcBefore = repo.hlcClock.now()

        vm.toggleSubtask("d1", "s2", 1)
        waitFor { task("d1")!!.checklist[1].done && buzzes.isNotEmpty() && repo.allLogs.first().any { it.kind == "subtask" } }
        val t = task("d1")!!
        assertTrue(t.checklist[1].touchedAt!! > hlcBefore)
        assertTrue(t.updatedAt > hlcBefore)
        assertEquals(listOf(50L), buzzes.toList())
        val ev = repo.allLogs.first().single { it.kind == "subtask" }
        assertEquals("d1", ev.taskId)
        assertTrue(ev.payloadJson!!.contains("\"subId\":\"s2\""))
        assertTrue(ev.payloadJson!!.contains("\"done\":true"))

        vm.toggleSubtask("d1", "s2", 1)
        waitFor { !task("d1")!!.checklist[1].done }
        delay(200)
        assertEquals("un-done does not buzz", listOf(50L), buzzes.toList())

        // An unknown id falls back to the index (Opti idxFallback).
        vm.toggleSubtask("d1", "gone", 0)
        waitFor { task("d1")!!.checklist[0].done }
        assertTrue(task("d1")!!.checklist[0].done)
        scope.cancel()
    }

    @Test fun reorderSubtasks_commits_the_order_with_an_hlc_stamp() = runBlocking {
        seedChar(lastCron = "20260925")
        repo.insertTask(TaskEntity(id = "d1", type = "daily", title = "D", updatedAt = 1L,
            checklist = listOf(ChecklistItem("s1", "one", false), ChecklistItem("s2", "two", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val vm = newVm()
        vm.reorderSubtasks("d1", listOf("s2", "s1"))
        waitFor { task("d1")!!.checklist.first().id == "s2" }
        assertEquals(listOf("s2", "s1"), task("d1")!!.checklist.map { it.id })
        assertTrue(task("d1")!!.updatedAt > 1L)
    }

    @Test fun saveEditedTask_stamps_from_the_hlc() = runBlocking {
        seedChar(lastCron = "20260925")
        repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "Old", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val vm = newVm()
        val before = repo.hlcClock.now()
        vm.saveEditedTask(task("t1")!!.copy(title = "New"))
        waitFor { task("t1")!!.title == "New" }
        assertTrue(task("t1")!!.updatedAt > before)
    }
}
