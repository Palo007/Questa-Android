@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.ui

import com.example.data.PauseState
import com.example.data.RpgEngine
import com.example.data.boot.BootGate
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** §5.0 test table, ColdStartRolloverOnceTest (WP-8.10). "runCron N×" = successful runs; "RYA N×" = opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartRolloverOnceTest : RyaTestBase() {
    @Test fun c1_noMissed_cron0BeforeGate_then1() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertTrue(BootGate.pending.value)
        assertEquals(0, cronRuns())
        BootGate.onFirstRoundSettled(null)
        awaitRolled(); awaitDayMutex()
        assertEquals(1, cronRuns())
        assertEquals(0, opens.settled())
        assertEquals("20260925", char().lastCron)
    }

    @Test fun c2_missed_ryaOnceAfterGate_cronOnlyAfterConfirm() = runBlocking {
        seedChar(); seedDaily("d1")
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertNull(vm.rya.value)
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        awaitRolled()
        assertEquals(listOf(RyaItem("d1", "Daily d1")), vm.rya.value!!.items)
        assertEquals(0, cronRuns())
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(1, cronRuns())
        assertNull(vm.rya.value)
        assertEquals(1, opens.settled())
    }

    @Test fun c3_secondColdStartSameDay_nothing() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        newVm()
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        assertEquals(1, cronRuns())
        assertFalse(task("d1")!!.done) // cron reset it: a naive "missed" check would now fire
        // A new process on the same day.
        clearVm(0)
        BootGate.resetForTest(kotlinx.coroutines.CoroutineScope(Dispatchers.Default.limitedParallelism(1)), { now }, ZONE)
        bindRealStartDay()
        val vm2 = newVm(); val opens = countOpens(vm2)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        assertEquals(0, opens.settled())
        assertEquals(0, lateCredits())
        assertEquals(1, cronRuns())
    }

    @Test fun c4_newAccount_lastCron0_optiBranch() = runBlocking {
        seedDaily("d1") // no character row at all: lastCron = 0
        val vm = newVm()
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled()
        assertEquals(listOf("d1"), vm.rya.value!!.items.map { it.id })
        assertEquals(0, cronRuns())
    }

    @Test fun c4b_newAccount_noDailies_cronOnce() = runBlocking {
        newVm()
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        assertEquals(1, cronRuns())
        assertEquals("20260925", char().lastCron)
    }
}

/** §5.0 test table, NoInitCollectorTest (WP-8.10): source-level guarantees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoInitCollectorTest : RyaTestBase() {
    private fun body(src: String, header: String): String {
        val i = src.indexOf(header).also { assertTrue("missing $header", it >= 0) }
        var depth = 0; var j = src.indexOf('{', i); val start = j
        while (true) { val c = src[j]; if (c == '{') depth++; if (c == '}') { depth--; if (depth == 0) break }; j++ }
        return src.substring(start, j + 1)
    }

    @Test fun todayDow_usesBootGateClockAndZone() {
        val vm = newVm(lifecycle = null)
        now = at(26, 3) // Sat 03:00 in Bratislava = Fri 21:00 in New York
        assertEquals(6, vm.todayDow())
        BootGate.zoneProvider = { java.time.ZoneId.of("America/New_York") }
        assertEquals(5, vm.todayDow())
        assertFalse(src("ui/MainViewModel.kt").contains("Calendar.getInstance()"))
    }

    @Test fun noCollector_runCronOnlyInConfirm_ryaWritersOnly() {
        val vm = src("ui/MainViewModel.kt")
        assertFalse(vm.contains("repository.character.collect"))
        val confirm = body(vm, "fun confirmRya(")
        assertEquals(1, Regex("runCron\\(").findAll(vm).count())
        assertTrue(confirm.contains("repository.runCron(nowMs)"))
        val writers = listOf("private suspend fun openRyaLocked(", "suspend fun openRyaIfNeeded(", "fun toggleRyaTick(", "fun confirmRya(")
            .joinToString("\n") { body(vm, it) }
        val writes = Regex("_rya\\.(value =|update)").findAll(vm).count()
        assertEquals(writes, Regex("_rya\\.(value =|update)").findAll(writers).count())
        assertFalse(Regex("rya\\s*=\\s*.*(combine|stateIn)").containsMatchIn(vm))
        for (f in listOf("ui/MainViewModel.kt", "ui/DashboardScreen.kt", "ui/RyaSheet.kt")) {
            val s = src(f)
            for (bad in listOf("dismissRya", "showRyaModal", "startMyDay", "ryaPendingDailies", "creditForYesterday = true")) {
                assertFalse("$bad in $f", s.contains(bad))
            }
        }
    }
}

/** §5.0 test table, PostBootRolloverTest (WP-8.10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostBootRolloverTest : RyaTestBase() {
    @Test fun a_aliveAcrossMidnight_onStart_rerunsTheDay_missed() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        assertEquals(1, cronRuns())
        now = at(26, 9)
        onStart()
        awaitRolled(); awaitDayMutex()
        assertEquals(1, opens.settled()) // d1 (reset by the D cron) was missed on D
        assertEquals(1, cronRuns())
    }

    @Test fun a2_aliveAcrossMidnight_nothingMissed_cronTwice() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        repo.updateTask(task("d1")!!.copy(done = true))
        now = at(26, 9)
        onStart()
        awaitRolled(); awaitDayMutex()
        assertEquals(0, opens.settled())
        assertEquals(2, cronRuns())
        assertEquals("20260926", char().lastCron)
    }

    @Test fun b_sameDayOnStart_keepsTheFrozenSheetAndTicks() = runBlocking {
        val vm = newVm(); val opens = countOpens(vm)
        val s = openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val ticked = vm.rya.value!!
        onStart(); awaitDayMutex()
        assertSame(ticked, vm.rya.value)
        assertEquals(setOf("d1"), vm.rya.value!!.ticked)
        assertEquals(1, opens.settled())
        assertEquals(0, cronRuns())
        assertEquals(s.items, ticked.items)
    }

    @Test fun e_backgroundRollover_noVm_thenForegroundOpens() = runBlocking {
        seedChar(); seedDaily("d1")
        assertNull(repo.ryaOpener)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled()
        assertEquals(0, cronRuns()) // missed: startDay returned the list, no sheet exists
        val vm = newVm() // started process: ON_START delivered at once (caller 2)
        awaitDayMutex()
        assertEquals(listOf("d1"), vm.rya.value!!.items.map { it.id })
    }

    @Test fun e2_backgroundRollover_nothingMissed_cronOnce() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled()
        assertEquals(1, cronRuns())
    }

    @Test fun f_configured_remoteLastCronToday_noRya() = runBlocking {
        seedChar(); seedDaily("d1")
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = true, barrierDone = true)
        repo.insertCharacter(char().copy(lastCron = "20260925")) // the round merged another device's cron
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        awaitRolled(); awaitDayMutex()
        assertEquals(0, opens.settled())
        assertEquals(0, cronRuns())
    }

    @Test fun g_pastMidnightBeforeReArm_roomEmissions_noRya() = runBlocking {
        seedChar(); seedDaily("d1", done = true)
        val vm = newVm(); val opens = countOpens(vm)
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled(); awaitDayMutex()
        now = at(26, 0, 30)
        repo.updateTask(task("d1")!!.copy(title = "renamed by a merge"))
        repo.insertCharacter(char().copy(gold = 5.0))
        delay(200)
        assertEquals(0, opens.settled())
        assertEquals(1, cronRuns())
    }

    @Test fun h_newDayReArm_closesTheSheet_noSheetWhilePending_thenReopens() = runBlocking {
        val vm = newVm()
        val s1 = openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        now = at(26, 9)
        BootGate.configuredProvider = { true }
        onStart(); awaitDayMutex()
        assertTrue(BootGate.pending.value)
        assertNull(vm.rya.value) // caller 2(a): the re-arm closed it, ticks lost
        BootGate.onFirstRoundSettled(null)
        awaitRolled()
        val s2 = vm.rya.value!!
        assertNotSame(s1, s2)
        assertTrue(s2.ticked.isEmpty())
        assertEquals(20260926, s2.armDay)
    }

    @Test fun i_roundMergesWhileOpen_sheetUnchanged_confirmCredits() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val s = vm.rya.value!!
        repo.updateTask(task("d1")!!.copy(title = "renamed elsewhere"))
        seedDaily("d9", orderIndex = 9)
        assertSame(s, vm.rya.value)
        vm.confirmRya(s).join()
        val d1 = task("d1")!!
        assertEquals(1, d1.streak)
        assertNull(d1.missedOn)
        assertEquals(at(24, 12), d1.history.last().date)
        assertEquals(listOf("Credited 1 daily from yesterday"), toasts.settled())
    }

    @Test fun j_confirmAfterMidnight_commitClock_optiResult() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        now = at(26, 10) // no ON_START in between
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(1, lateCredits())
        assertEquals(at(25, 12), task("d1")!!.history.last().date) // yesterday relative to D+1 = D [DEFERRED_OPEN_QUESTION]
        assertEquals(1, cronRuns())
        assertEquals("20260926", char().lastCron)
        assertEquals(listOf("Credited 1 daily from yesterday"), toasts.settled())
    }

    @Test fun k_decisionEqualsStartDay_100Randomised() = runBlocking {
        val vm = newVm()
        seedChar()
        for (i in 0 until 3) seedDaily("d$i", orderIndex = i.toLong())
        val rnd = kotlin.random.Random(810)
        repeat(100) { round ->
            for (i in 0 until 3) repo.updateTask(task("d$i")!!.copy(done = rnd.nextBoolean()))
            repo.insertCharacter(char().copy(lastCron = "20260924"))
            if (rnd.nextBoolean()) delay(rnd.nextLong(0, 4))
            val inputs = repo.getAllTasksSync()
            val expected = RpgEngine.missedYesterdayDailies(inputs, PauseState.NONE, 20260924, now, ZONE).map { it.id }
            BootGate.dayMutex.lock()
            try { vm.opener(now) } finally { BootGate.dayMutex.unlock() }
            val s = vm.rya.value
            assertEquals("round $round", expected, s?.items?.map { it.id } ?: emptyList<String>())
            if (s != null) vm.confirmRya(s).join()
            assertNull(vm.rya.value)
            assertEquals("20260925", char().lastCron)
        }
    }
}

/** §5.0 test table, RyaConfirmActionTest rows (1)-(13) (WP-8.10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RyaConfirmActionTest : RyaTestBase() {
    @Test fun r1_startDayReturnsWithoutWaiting() = runBlocking {
        seedChar(); seedDaily("d1")
        val missed = withTimeout(2_000) { repo.startDay(now) }
        assertEquals(listOf("d1"), missed.map { it.id })
        assertEquals(0, cronRuns())
    }

    @Test fun r2_oneCredit_toastSingular_cronOnce_closed() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1", "d2")
        vm.toggleRyaTick("d1")
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(listOf("Credited 1 daily from yesterday"), toasts.settled())
        assertEquals(1, cronRuns())
        assertEquals(1, missLogs()) // d2 unticked -> damage
        assertNull(vm.rya.value)
    }

    @Test fun r2b_twoCredits_toastDailys() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1", "d2")
        vm.toggleRyaTick("d1"); vm.toggleRyaTick("d2")
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(listOf("Credited 2 dailys from yesterday"), toasts.settled())
        assertEquals(0, missLogs())
        assertEquals("Credited 2 dailys from yesterday", ryaToastText(2))
    }

    @Test fun r3_lastCronMergedToToday_creditsPersist_cronNoOp() = runBlocking {
        val vm = newVm()
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        repo.insertCharacter(char().copy(lastCron = "20260925"))
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(1, task("d1")!!.streak)
        assertTrue(task("d1")!!.done) // cron did not run, so it stays done
        assertTrue(char().xp > 0)
        assertEquals(0, cronRuns())
    }

    @Test fun r4_tombstonedWhileOpen_skippedNoThrow() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1", "d2")
        vm.toggleRyaTick("d1"); vm.toggleRyaTick("d2")
        repo.inTransaction { repo.deleteTaskRowOnly("d1"); repo.insertTombstone(TombstoneEntity("d1", at(25, 10))) }
        assertEquals("Daily d1", vm.rya.value!!.items[0].title)
        vm.confirmRya(vm.rya.value!!).join()
        assertEquals(listOf("Credited 1 daily from yesterday"), toasts.settled())
        assertEquals(1, lateCredits())
    }

    @Test fun r5_alreadyDone_notCounted() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        repo.updateTask(task("d1")!!.copy(done = true))
        vm.confirmRya(vm.rya.value!!).join()
        assertTrue(toasts.settled().isEmpty())
        assertEquals(0, lateCredits())
        assertEquals(1, cronRuns())
    }

    @Test fun r6_zeroTicks_noToast_cronOnce() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.confirmRya(vm.rya.value!!).join()
        assertTrue(toasts.settled().isEmpty())
        assertEquals(1, cronRuns())
        assertEquals(1, missLogs())
    }

    @Test fun r7_noSheet_noOp_andDoubleTap_once() = runBlocking {
        val vm = newVm()
        vm.confirmRya(RyaSheetState(emptyList(), emptySet(), 0)).join()
        assertEquals(0, cronRuns())
        val s = openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val t = vm.rya.value!!
        assertNotSame(s, t)
        val j1 = vm.confirmRya(t); val j2 = vm.confirmRya(t)
        j1.join(); j2.join()
        assertEquals(1, lateCredits())
        assertEquals(1, cronRuns())
    }

    @Test fun r8_sheetIsNotDismissable() {
        val sheet = src("ui/RyaSheet.kt")
        assertTrue(sheet.contains("onDismissRequest = { }"))
        // WP-8.18: the sheet is full-window now (usePlatformDefaultWidth = false); both dismiss flags stay off.
        assertTrue(sheet.contains("DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false"))
        assertFalse(src("ui/MainViewModel.kt").contains("dismissRya"))
    }

    @Test fun r9_order_sheetShowingAndLockHeldWhileCronRuns_saveFirst() = runBlocking {
        val vm = newVm()
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val seen = CopyOnWriteArrayList<String>()
        repo.testHookInCron = {
            seen += "rya=" + (vm.rya.value != null)
            seen += "locked=" + BootGate.dayMutex.isLocked
            seen += "creditSaved=" + (repo.getTaskByIdSync("d1")!!.done)
        }
        vm.confirmRya(vm.rya.value!!).join()
        repo.testHookInCron = null
        assertEquals(listOf("rya=true", "locked=true", "creditSaved=true"), seen)
        assertNull(vm.rya.value)
    }

    @Test fun r10_oneCommitClock_readAfterTheLock() = runBlocking {
        val vm = newVm()
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val ticks = AtomicLong(at(25, 10))
        BootGate.clock = { ticks.incrementAndGet() }
        val before = ticks.get()
        vm.confirmRya(vm.rya.value!!).join()
        val logs = repo.allLogs.first()
        val credit = logs.single { it.kind == "complete" }.timestamp
        val cron = logs.single { it.kind == "cron" }.timestamp
        assertEquals(credit, cron)
        assertTrue(credit > before)
        assertEquals(credit, repo.getAllCharHistorySync().single().date)
    }

    @Test fun r11_titlesAndCountFromTheSnapshot() = runBlocking {
        val vm = newVm()
        openSheet(vm, "d1", "d2")
        repo.updateTask(task("d1")!!.copy(title = "New name"))
        repo.deleteTaskById("d2")
        val s = vm.rya.value!!
        assertEquals(listOf("Daily d1", "Daily d2"), s.items.map { it.title })
        assertEquals(RyaText.sub(2), RyaText.sub(s.items.size))
    }

    @Test fun r12_tombstoneMergedBetweenResolveAndSave_false() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        repo.testHookBeforeCredit = { id ->
            repo.inTransaction { repo.deleteTaskRowOnly(id); repo.insertTombstone(TombstoneEntity(id, at(25, 10))) }
        }
        vm.confirmRya(vm.rya.value!!).join()
        repo.testHookBeforeCredit = null
        assertTrue(toasts.settled().isEmpty())
        assertEquals(0, lateCredits())
        assertEquals(1, cronRuns())
    }

    @Test fun r13_lastCronMergedBetweenResolveAndSave_creditCommits_cronNoOp() = runBlocking {
        val vm = newVm(); val toasts = collectToasts(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        repo.testHookBeforeCredit = { repo.insertCharacter(repo.getCharacterSync().copy(lastCron = "20260925")) }
        vm.confirmRya(vm.rya.value!!).join()
        repo.testHookBeforeCredit = null
        assertEquals(listOf("Credited 1 daily from yesterday"), toasts.settled())
        assertEquals(0, cronRuns())
        assertEquals(1, task("d1")!!.streak)
    }
}

/** §5.0 test table, DayMutexRaceTest rows (1)-(7) (WP-8.10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayMutexRaceTest : RyaTestBase() {
    private suspend fun row1(missed: Boolean) {
        seedChar(); seedDaily("d1", done = !missed)
        val entered = CompletableDeferred<Unit>(); val latch = CompletableDeferred<Unit>()
        BootGate.bindStartDay {
            val opener = repo.ryaOpener
            entered.complete(Unit); latch.await()
            if (opener != null) opener(now) else repo.startDay(now)
            rolled.trySend(Unit)
        }
        BootGate.bootStartDay(configured = false, barrierDone = true) // B3 in onCreate, no VM yet
        entered.await()
        val vm = newVm(); val opens = countOpens(vm) // its ON_START caller 2 queues on dayMutex
        assertNull(vm.rya.value)
        latch.complete(Unit)
        awaitRolled(); awaitDayMutex()
        if (missed) {
            assertEquals(1, opens.settled()); assertEquals(0, cronRuns())
        } else {
            assertEquals(0, opens.settled()); assertEquals(1, cronRuns()); assertEquals(0, missLogs())
        }
    }

    @Test fun m1_coldStartRace_nothingMissed() = runBlocking { row1(missed = false) }
    @Test fun m1b_coldStartRace_missed() = runBlocking { row1(missed = true) }

    @Test fun m2_onStartDuringConfirm_waits_noReopen() = runBlocking {
        val vm = newVm(); val opens = countOpens(vm)
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val inCredit = CompletableDeferred<Unit>(); val latch = CompletableDeferred<Unit>()
        repo.testHookBeforeCredit = { inCredit.complete(Unit); latch.await() }
        val job = vm.confirmRya(vm.rya.value!!)
        inCredit.await()
        onStart()
        assertNotNull(vm.rya.value)
        latch.complete(Unit)
        job.join(); awaitDayMutex()
        repo.testHookBeforeCredit = null
        assertNull(vm.rya.value)
        assertEquals(1, opens.settled())
        assertEquals(1, cronRuns())
    }

    @Test fun m3_reArmDuringConfirm_startsAfterTheClose() = runBlocking {
        val vm = newVm()
        openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val order = CopyOnWriteArrayList<String>()
        val body = repo.startDayBinding()
        BootGate.bindStartDay { order += "rollover:sheetClosed=" + (vm.rya.value == null); body(); rolled.trySend(Unit) }
        val inCredit = CompletableDeferred<Unit>(); val latch = CompletableDeferred<Unit>()
        repo.testHookBeforeCredit = { inCredit.complete(Unit); latch.await() }
        val job = vm.confirmRya(vm.rya.value!!)
        inCredit.await()
        now = at(26, 9)
        BootGate.onForeground(now)
        delay(200)
        assertTrue(order.isEmpty())
        latch.complete(Unit)
        job.join(); awaitRolled()
        repo.testHookBeforeCredit = null
        assertEquals(listOf("rollover:sheetClosed=true"), order)
        assertEquals(1, cronRuns())
    }

    @Test fun m4_twoConcurrentRunCron_onceWritten() = runBlocking {
        seedChar(); seedDaily("d1"); seedDaily("d2", orderIndex = 1)
        val results = (1..2).map { async(Dispatchers.Default) { repo.runCron(now) } }.awaitAll()
        assertEquals(listOf(false, true), results.sorted())
        assertEquals(1, cronRuns())
        assertEquals(2, missLogs())
    }

    @Test fun m5_twoViewModels_olderClearKeepsNewer_onlyBoundOpens() = runBlocking {
        seedChar(); seedDaily("d1")
        BootGate.bootStartDay(configured = false, barrierDone = true)
        awaitRolled()
        val vm1 = newVm(lifecycle = null); val vm2 = newVm(lifecycle = null)
        assertSame(vm2.opener, repo.ryaOpener)
        clearVm(0)
        assertSame(vm2.opener, repo.ryaOpener)
        vm1.openRyaIfNeeded(now)
        assertNull(vm1.rya.value)
        vm2.openRyaIfNeeded(now)
        assertNotNull(vm2.rya.value)
        clearVm(1)
        assertNull(repo.ryaOpener)
    }

    @Test fun m6_tapQueuedAcrossASheetReplacement_noOp() = runBlocking {
        val vm = newVm()
        val s1 = openSheet(vm, "d1")
        vm.toggleRyaTick("d1")
        val tapped = vm.rya.value!!
        BootGate.dayMutex.lock()
        now = at(26, 9)
        BootGate.onForeground(now) // re-arm: its B3 queues on dayMutex first
        delay(300)
        val job = vm.confirmRya(tapped) // then the tap
        BootGate.dayMutex.unlock()
        awaitRolled(); job.join()
        val s2 = vm.rya.value!!
        assertNotSame(tapped, s2)
        assertNotSame(s1, s2)
        assertEquals(20260926, s2.armDay)
        assertEquals(0, lateCredits())
        assertEquals(0, cronRuns())
    }

    @Test fun m7_realBoundViewModel_realMutex_callerOneFinishes() = runBlocking {
        seedChar(); seedDaily("d1")
        val vm = newVm()
        BootGate.bootStartDay(configured = false, barrierDone = true)
        withTimeout(5_000) { awaitRolled() }
        assertSame(vm.opener, repo.ryaOpener)
        assertNotNull(vm.rya.value)
    }
}

/** PX-65 through the repository: setPause, the row lifecycle, and cron/credit while paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PauseRepositoryTest : RyaTestBase() {
    @Test fun setPause_rowLifecycle_unpauseKeepsRow() = runBlocking {
        repo.setPause(true, hlcNow = 111L)
        val p1 = repo.readSyncPrefs()!!
        assertEquals(true, p1.paused); assertEquals(emptyList<Int>(), p1.pausedDays); assertEquals(111L, p1.pausedAt)
        repo.inTransaction { repo.writeSyncPrefs(p1.copy(pausedDays = listOf(20260924))) }
        repo.setPause(false, hlcNow = 222L)
        val p2 = repo.readSyncPrefs()!!
        assertEquals(false, p2.paused); assertEquals(listOf(20260924), p2.pausedDays); assertEquals(222L, p2.pausedAt)
    }

    @Test fun pausedCron_noDamage_recordsDay_onceADay() = runBlocking {
        seedChar(); seedDaily("d1")
        repo.updateTask(task("d1")!!.copy(streak = 3))
        repo.setPause(true, hlcNow = 1L)
        assertTrue(repo.runCron(now))
        assertFalse(repo.runCron(now))
        assertEquals(50.0, char().hp, 0.0)
        assertEquals(3, task("d1")!!.streak)
        assertEquals(listOf(20260925), repo.readSyncPrefs()!!.pausedDays)
        assertEquals(0, missLogs())
        assertTrue(repo.startDay(now).isEmpty())
    }

    @Test fun pausedCompleteAndCredit_streakFrozen() = runBlocking {
        seedChar(); seedDaily("d1"); seedDaily("d2", orderIndex = 1)
        repo.setPause(true, hlcNow = 1L)
        assertTrue(repo.completeTask("d1"))
        assertEquals(0, task("d1")!!.streak)
        assertEquals(20260925, task("d1")!!.doneDay)
        assertTrue(repo.creditYesterday("d2", now))
        assertEquals(0, task("d2")!!.streak)
    }
}
