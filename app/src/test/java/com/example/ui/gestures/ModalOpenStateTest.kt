package com.example.ui.gestures

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.boot.BootGate
import com.example.ui.RyaTestBase
import com.example.ui.at
import com.example.ui.freshMainDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP-8.16 (§5.0 B8, r11): `modalOpen` is true for each editor sheet, full-screen editor and
 * FirstConnectDialog (the Kotlin surfaces of Opti's `#scrim`), false when only RYA shows, and
 * BootGate's `sheetOpen()` reads it after the W7 bind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModalOpenStateTest : RyaTestBase() {

    private val keys = listOf(ModalKeys.TASK_EDITOR, ModalKeys.REP_SHEET, ModalKeys.REWARD_EDITOR, ModalKeys.SETTINGS, ModalKeys.FIRST_CONNECT)

    @Test fun each_surface_opens_modalOpen_and_all_closed_is_false() {
        val vm = newVm()
        assertFalse(vm.modalOpen.value)
        for (k in keys) {
            vm.setModalOpen(k, true)
            assertTrue(k, vm.modalOpen.value)
            vm.setModalOpen(k, false)
            assertFalse(k, vm.modalOpen.value)
        }
        vm.setModalOpen(ModalKeys.SETTINGS, true)
        vm.setModalOpen(ModalKeys.FIRST_CONNECT, true)
        vm.setModalOpen(ModalKeys.SETTINGS, false)
        assertTrue("one surface still open", vm.modalOpen.value)
        vm.setModalOpen(ModalKeys.FIRST_CONNECT, false)
        assertFalse(vm.modalOpen.value)
    }

    @Test fun rya_alone_is_not_a_modal() = runBlocking {
        val vm = newVm()
        assertNotNull(openSheet(vm, "d1"))
        assertFalse(vm.modalOpen.value)
    }

    @Test fun bootgate_sheetOpen_reads_modalOpen() = runBlocking {
        val vm = newVm()
        val logs = CopyOnWriteArrayList<String>()
        BootGate.lifecycleLog = { _, d -> logs += d }
        seedChar(lastCron = "20260925")
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        vm.setModalOpen(ModalKeys.TASK_EDITOR, true)
        now = at(26, 9)
        BootGate.onForeground(now)
        assertEquals("an open editor defers the new-day re-arm", emptyList<String>(), logs.toList())
        vm.setModalOpen(ModalKeys.TASK_EDITOR, false)
        BootGate.onForeground(now)
        assertEquals(listOf("reload:newDay"), logs.toList())
        awaitRolled()
    }

    @Test fun dashboard_reports_every_scrim_surface_and_not_rya() {
        val src = src("ui/DashboardScreen.kt")
        for (k in listOf("TASK_EDITOR", "REP_SHEET", "REWARD_EDITOR", "SETTINGS", "FIRST_CONNECT")) {
            assertTrue(k, src.contains("ReportModal(ModalKeys.$k,"))
        }
        assertFalse(src.contains("ReportModal(ModalKeys.RYA"))
        assertFalse(File("src/main/java/com/example/ui/gestures/ModalSurface.kt").let { if (it.isFile) it else File("app/" + it.path) }
            .readText().contains("RYA ="))
    }
}

/** `ReportModal` reports open, then closed on hide and on leaving the composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReportModalTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @Test fun `open, hide and dispose each report`() {
        val events = mutableListOf<Pair<String, Boolean>>()
        var open by mutableStateOf(false)
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) ReportModal("k", open) { k, o -> events += k to o } }
        rule.waitForIdle()
        open = true; rule.waitForIdle()
        open = false; rule.waitForIdle()
        open = true; rule.waitForIdle()
        shown = false; rule.waitForIdle()
        val states = events.map { it.second }
        val collapsed = states.filterIndexed { i, v -> i == 0 || v != states[i - 1] }
        assertEquals(listOf(false, true, false, true, false), collapsed)
        assertTrue(events.all { it.first == "k" })
    }
}
