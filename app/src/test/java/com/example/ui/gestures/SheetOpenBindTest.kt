package com.example.ui.gestures

import com.example.data.boot.BootGate
import com.example.ui.RyaTestBase
import com.example.ui.at
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP-8.16 (r13, minor 4; the former BootWiringOrderTest row (3)): `MainViewModel.init` binds
 * BootGate's `sheetOpen` to `modalOpen`, and `onCleared` restores `{ false }`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetOpenBindTest : RyaTestBase() {

    private val logs = CopyOnWriteArrayList<String>()

    private suspend fun armToday() {
        BootGate.lifecycleLog = { _, d -> logs += d }
        seedChar(lastCron = "20260925")
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
    }

    @Test fun init_binds_sheetOpen_to_modalOpen() = runBlocking {
        val vm = newVm()
        armToday()
        vm.setModalOpen(ModalKeys.SETTINGS, true)
        now = at(26, 9)
        BootGate.onForeground(now)
        assertEquals(emptyList<String>(), logs.toList())
    }

    @Test fun onCleared_restores_false() = runBlocking {
        val vm = newVm()
        armToday()
        vm.setModalOpen(ModalKeys.TASK_EDITOR, true)
        clearVm(0)
        now = at(26, 9)
        BootGate.onForeground(now)
        assertEquals(listOf("reload:newDay"), logs.toList())
        awaitRolled()
    }

    // Review 35 item 3: an old ViewModel's onCleared must not wipe a newer one's bind.
    @Test fun an_old_vm_cleared_after_a_new_one_bound_keeps_the_new_bind() = runBlocking {
        newVm()
        val fresh = newVm()
        armToday()
        fresh.setModalOpen(ModalKeys.TASK_EDITOR, true)
        clearVm(0)
        now = at(26, 9)
        BootGate.onForeground(now)
        assertEquals("the new VM's open editor still defers", emptyList<String>(), logs.toList())
    }
}
