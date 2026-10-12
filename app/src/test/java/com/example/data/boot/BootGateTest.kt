package com.example.data.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.sync.DropboxService
import com.example.data.sync.ErrorKind
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.RoundDevice
import com.example.data.sync.SyncLock
import com.example.data.sync.SyncResult
import com.example.data.sync.SyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

private val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long = LocalDateTime.of(y, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli()

/** Shared: a TestScope-driven BootGate with a movable clock and a counting fake startDay. */
@OptIn(ExperimentalCoroutinesApi::class)
private class GateFixture(val scope: TestScope, start: Long) {
    var now = start
    val startDays = AtomicInteger()
    init {
        com.example.data.clock.StartupBarrier.markDone() // onForeground reads the global barrier
        BootGate.resetForTest(scope, { now }, ZONE)
        BootGate.bindStartDay { startDays.incrementAndGet() }
    }
}

/** §5.0 test table: BootGateOpensTest (WP-8.6, W3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootGateOpensTest {
    @After fun tearDown() { SyncLock.resetForTest(); restoreRealBootClock() }

    @Test fun notLinked_opensAtOnce() = runTest {
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = false, barrierDone = true)
        assertFalse(BootGate.pending.value)
        runCurrent()
        assertEquals(1, f.startDays.get())
    }

    @Test fun linkedOffline_opensOnTheSettledError() = runTest {
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertTrue(BootGate.blocksInput())
        BootGate.onFirstRoundSettled(SyncResult.Error(ErrorKind.HTTP, "download failed: 0"))
        assertFalse(BootGate.blocksInput())
        runCurrent()
        assertEquals(1, f.startDays.get())
    }

    @Test fun linkedFirstConnectOrInterlock_opens() = runTest {
        for (r in listOf(SyncResult.NeedsFirstConnectChoice, SyncResult.InterlockRefused)) {
            GateFixture(this, at(2026, 9, 25, 9))
            BootGate.bootStartDay(configured = true, barrierDone = true)
            BootGate.onFirstRoundSettled(r)
            assertFalse("$r", BootGate.pending.value)
        }
    }

    @Test fun barrierFailure_opensAt8000ms() = runTest {
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = false, barrierDone = false)
        advanceTimeBy(BOOT_ROLLOVER_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(BootGate.pending.value)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(BootGate.pending.value)
        assertEquals(1, f.startDays.get())
    }

    @Test fun notLinkedBarrierPending_opensAtMarkDone() = runTest {
        GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = false, barrierDone = false)
        assertTrue(BootGate.pending.value)
        BootGate.onBarrierDone()
        assertFalse(BootGate.pending.value)
    }

    @Test fun configured_barrierDoneDoesNotOpen_theRoundDoes() = runTest {
        GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = false)
        BootGate.onBarrierDone()
        assertTrue("a configured arm waits for the round or the timer", BootGate.pending.value)
        BootGate.onFirstRoundSettled(null)
        assertFalse(BootGate.pending.value)
    }

    @Test fun lockSkip_opens() = runTest {
        GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = true)
        BootGate.onFirstRoundSettled(null)
        assertFalse(BootGate.pending.value)
    }

    @Test fun upgradeBoot_legacyToken_defersUntilTheFirstRoundSettles() = runTest {
        // W1 step 1: the legacy DROPBOX_REFRESH_TOKEN counts as configured.
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = true)
        advanceTimeBy(3_000); runCurrent()
        assertTrue(BootGate.pending.value)
        assertEquals(0, f.startDays.get())
        BootGate.onFirstRoundSettled(SyncResult.Error(ErrorKind.NOT_LINKED, "x"))
        runCurrent()
        assertEquals(1, f.startDays.get())
    }

    @Test fun bothTriggers_runTheRolloverOnce() = runTest {
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = true)
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        advanceTimeBy(BOOT_ROLLOVER_TIMEOUT_MS * 2); runCurrent()
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        BootGate.onBarrierDone()
        runCurrent()
        assertEquals(1, f.startDays.get())
    }

    @Test fun configured_startDayZeroWhilePending_thenOnce() = runTest {
        val f = GateFixture(this, at(2026, 9, 25, 9))
        BootGate.bootStartDay(configured = true, barrierDone = true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, f.startDays.get())
        BootGate.onFirstRoundSettled(SyncResult.NothingToPush)
        runCurrent()
        assertEquals(1, f.startDays.get())
    }

    /** D28: configured + RECONNECT_REQUIRED still attempts a round, and its settle opens the gate before 8000 ms. */
    @Test fun configuredReconnectRequired_roundIsAttempted_gateOpensBefore8000ms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = FakeDropboxServer()
        val original = DropboxService.client
        DropboxService.client = fake.asClient()
        val d = RoundDevice(context, "dev-rr")
        try {
            runBlocking {
                d.link()
                d.syncDataStore.markReconnectRequired()
                BootGate.resetForTest(kotlinx.coroutines.CoroutineScope(Dispatchers.Default), { at(2026, 9, 25, 9) }, ZONE)
                BootGate.bootStartDay(configured = true, barrierDone = true)
                assertTrue(BootGate.pending.value)
                val r = d.manager.sync(SyncTrigger.FOREGROUND)
                assertTrue("a round ran and settled: $r", r is SyncResult.Error && r.kind == ErrorKind.NOT_LINKED)
                assertFalse("the settle opened the gate", BootGate.pending.value)
            }
        } finally {
            DropboxService.client = original
            FakeDropboxServer.restoreInterlockPolicy()
            d.close()
        }
    }
}

/** §5.0 test table: BootGateForegroundTest (WP-8.6, W3), rows (1)-(10). */
@OptIn(ExperimentalCoroutinesApi::class)
class BootGateForegroundTest {
    private val day1 = at(2026, 9, 25, 22)
    private val day2 = at(2026, 9, 26, 7)

    @After fun restoreClock() { restoreRealBootClock() }

    @Test fun row1_sameDay_andClockBackwards_areNoOps() = runTest {
        val f = GateFixture(this, day1)
        val logs = AtomicInteger()
        BootGate.lifecycleLog = { _, _ -> logs.incrementAndGet() }
        BootGate.bootStartDay(false, true); runCurrent()
        BootGate.onForeground(at(2026, 9, 25, 23, 59))
        BootGate.onForeground(at(2026, 9, 24, 12)) // clock moved backwards
        runCurrent()
        assertEquals(1, f.startDays.get())
        assertEquals(0, logs.get())
        assertEquals(20260925, BootGate.loadDayStamp.value)
    }

    @Test fun row2_newDay_reArms_configuredGoesPending() = runTest {
        val f = GateFixture(this, day1)
        BootGate.configuredProvider = { true }
        BootGate.bootStartDay(false, true); runCurrent()
        f.now = day2
        BootGate.onForeground(day2)
        assertEquals(20260926, BootGate.loadDayStamp.value)
        assertTrue(BootGate.pending.value)
    }

    @Test fun row3_sheetOpen_defers_nextOnStartReArms() = runTest {
        val f = GateFixture(this, day1)
        var sheet = true
        BootGate.bindSheetOpen { sheet }
        BootGate.bootStartDay(false, true); runCurrent()
        f.now = day2
        BootGate.onForeground(day2)
        assertEquals(20260925, BootGate.loadDayStamp.value)
        sheet = false // closing the sheet is not an event
        runCurrent()
        assertEquals(20260925, BootGate.loadDayStamp.value)
        BootGate.onForeground(day2)
        assertEquals(20260926, BootGate.loadDayStamp.value)
    }

    @Test fun row4_oneLifecycleLogPerReArm() = runTest {
        val f = GateFixture(this, day1)
        val logs = mutableListOf<Pair<String, String>>()
        BootGate.lifecycleLog = { k, d -> logs += k to d }
        BootGate.bootStartDay(false, true); runCurrent()
        BootGate.onForeground(day1) // no-op
        f.now = day2
        BootGate.onForeground(day2)
        BootGate.onForeground(day2) // same (new) day now: no-op
        assertEquals(listOf("lifecycle" to "reload:newDay"), logs)
    }

    @Test fun row5_configuredProviderIsReReadOnEachReArm() = runTest {
        val f = GateFixture(this, day1)
        var configured = false
        val reads = AtomicInteger()
        BootGate.configuredProvider = { reads.incrementAndGet(); configured }
        BootGate.bootStartDay(false, true); runCurrent()
        f.now = day2
        BootGate.onForeground(day2)
        assertFalse("not configured: rolls over at once", BootGate.pending.value)
        configured = true
        val day3 = at(2026, 9, 27, 7)
        f.now = day3
        BootGate.onForeground(day3)
        assertTrue("configured now: defers", BootGate.pending.value)
        assertEquals(2, reads.get())
    }

    @Test fun row6_noLatch_firstSettledRoundAfterReArmOpens() = runTest {
        val f = GateFixture(this, day1)
        BootGate.configuredProvider = { true }
        BootGate.bootStartDay(true, true)
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        runCurrent()
        f.now = day2
        BootGate.onForeground(day2)
        assertTrue(BootGate.pending.value)
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        assertFalse(BootGate.pending.value)
        runCurrent()
        assertEquals(2, f.startDays.get())
    }

    @Test fun row7_reArmCancelsThePreviousTimer_oneRolloverPerArm() = runTest {
        val f = GateFixture(this, day1)
        BootGate.configuredProvider = { true }
        BootGate.bootStartDay(true, true)
        advanceTimeBy(5_000); runCurrent()
        f.now = day2
        BootGate.onForeground(day2) // re-arm while pending: the old 8 s timer is cancelled
        advanceTimeBy(4_000); runCurrent()
        assertTrue("the old timer would have fired at 8 s", BootGate.pending.value)
        assertEquals(0, f.startDays.get())
        advanceTimeBy(4_001); runCurrent()
        assertFalse(BootGate.pending.value)
        assertEquals(1, f.startDays.get())
    }

    @Test fun row8_rolloverDaySetAtEntry_beforePendingClears_andBeforeStartDayReturns() = runTest {
        GateFixture(this, day1)
        val release = CompletableDeferred<Unit>()
        var dayWhenStarted = -1
        val order = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            BootGate.rolloverDay.collect { if (it != 0) order += "rolloverDay" }
        }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            BootGate.pending.collect { if (!it && order.isNotEmpty()) order += "pendingFalse" }
        }
        BootGate.bindStartDay { dayWhenStarted = BootGate.rolloverDay.value; release.await() }
        BootGate.bootStartDay(true, true)
        BootGate.onFirstRoundSettled(SyncResult.Ok)
        runCurrent()
        assertEquals(20260925, dayWhenStarted)
        assertEquals(listOf("rolloverDay", "pendingFalse"), order)
        release.complete(Unit)
        runCurrent()
    }

    @Test fun row9_reArmCancelsAndJoinsTheRunningStartDay_neverOverlaps() = runTest {
        val f = GateFixture(this, day1)
        val inside = AtomicInteger()
        var maxInside = 0
        var cancelled = 0
        BootGate.bindStartDay {
            val n = inside.incrementAndGet(); if (n > maxInside) maxInside = n
            try { delay(60_000) } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e } finally { inside.decrementAndGet() }
        }
        BootGate.bootStartDay(false, true)
        runCurrent()
        assertEquals(1, inside.get())
        f.now = day2
        BootGate.onForeground(day2) // not configured -> runs the new rollover at once
        runCurrent()
        assertEquals("previous startDay cancelled", 1, cancelled)
        assertEquals(1, maxInside)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(0, inside.get())
        assertEquals(0, f.startDays.get()) // the counting body was replaced above
    }

    @Test fun row10_startDayWaitsForDayMutex_runsOnceAfterUnlock_cancelReleasesIt() = runTest {
        val f = GateFixture(this, day1)
        BootGate.dayMutex.lock()
        BootGate.bootStartDay(false, true)
        runCurrent()
        assertFalse("pending cleared at entry", BootGate.pending.value)
        assertEquals("blocked on dayMutex", 0, f.startDays.get())
        BootGate.dayMutex.unlock()
        runCurrent()
        assertEquals(1, f.startDays.get())
        assertFalse(BootGate.dayMutex.isLocked)
        // a cancelled startDay releases the lock (withLock)
        BootGate.bindStartDay { delay(60_000) }
        f.now = day2
        BootGate.onForeground(day2); runCurrent()
        assertTrue(BootGate.dayMutex.isLocked)
        val day3 = at(2026, 9, 27, 7)
        f.now = day3
        BootGate.onForeground(day3); runCurrent() // cancels the running one, then runs again
        BootGate.bindStartDay { }
        advanceTimeBy(60_001); runCurrent()
        assertFalse(BootGate.dayMutex.isLocked)
        BootGate.dayMutex.withLock { } // not deadlocked
    }

    @Test fun shouldDefer_isThePurePredicate() {
        assertTrue(shouldDeferDayRollover(true, 0, BOOT_ROLLOVER_TIMEOUT_MS))
        assertFalse(shouldDeferDayRollover(true, BOOT_ROLLOVER_TIMEOUT_MS, BOOT_ROLLOVER_TIMEOUT_MS))
        assertFalse(shouldDeferDayRollover(false, 0, BOOT_ROLLOVER_TIMEOUT_MS))
    }

    @Test fun tapDrainRunsAfterStartDay() = runTest {
        val f = GateFixture(this, day1)
        val order = mutableListOf<String>()
        BootGate.bindStartDay { order += "startDay" }
        BootGate.bindTapDrain { order += "drain@$it" }
        BootGate.bootStartDay(false, true); runCurrent()
        assertEquals(listOf("startDay", "drain@${f.now}"), order)
    }
}
