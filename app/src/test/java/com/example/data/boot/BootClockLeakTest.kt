package com.example.data.boot

import com.example.data.TapTestBase
import com.example.data.clock.PhysicalClock
import com.example.data.inbox.InboxTestBase
import com.example.launcher.GatedTapQueuedTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * KT-97: [BootGate.clock] is a process-wide static. Robolectric keeps it across test classes in
 * one JVM, so a test base that pins it to 2026-09-25 and never restores it makes every later
 * class (TapEventPushRegressionTest) stamp taps in September. Each case runs one fixture's real
 * setUp + tearDown and checks the clock is back on real time afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootClockLeakTest {
    private fun assertRealClock(who: String) {
        val off = abs(BootGate.clock() - PhysicalClock.nowMs())
        assertTrue("$who left BootGate.clock off real time by $off ms", off < 60_000L)
    }

    private fun pinPast() =
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { 1_790_330_400_000L })

    @Test fun tapTestBase_restoresRealClock() {
        val t = object : TapTestBase() {}
        t.tapSetUp()
        t.tapTearDown()
        assertRealClock("TapTestBase")
    }

    @Test fun inboxTestBase_restoresRealClock() {
        val t = object : InboxTestBase() {}
        t.inboxSetUp()
        t.inboxTearDown()
        assertRealClock("InboxTestBase")
    }

    @Test fun gatedTapQueuedTest_restoresRealClock() {
        val t = GatedTapQueuedTest()
        t.setUp()
        t.tearDown()
        assertRealClock("GatedTapQueuedTest")
    }

    @Test fun bootGateOpensTest_restoresRealClock() {
        pinPast()
        BootGateOpensTest().tearDown()
        assertRealClock("BootGateOpensTest")
    }
}
