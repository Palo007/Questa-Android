package com.example.data.sync

import com.example.data.clock.StartupBarrier
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.6 review 26 C2, inverted by 8.I at the W3 close: `StartupMigrations.run(...)` is now
 * launched from `QuestaApplication.onCreate`, so the PRODUCTION default of [SyncLock.awaitBarrier]
 * waits on [StartupBarrier] (R15: no round reads the base before the migrations). The barrier is
 * held closed here with MockK, whatever other tests in this JVM did to the real one-shot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncLockDefaultBarrierTest : RoundTestBase() {

    @After fun unmock() = unmockkObject(StartupBarrier)

    @Test fun defaultSeam_waitsForTheBarrier_manual() = runBlocking {
        val d = device()
        d.link()
        mockkObject(StartupBarrier)
        coEvery { StartupBarrier.await() } coAnswers { awaitCancellation() }
        SyncLock.resetForTest() // the production seams
        val result = withTimeoutOrNull(1_000) { d.manager.sync(SyncTrigger.MANUAL) }
        assertNull("a round must not run while the barrier is closed", result)
        assertEquals(false, SyncLock.isHeld)
    }

    @Test fun defaultSeam_waitsForTheBarrier_worker() = runBlocking {
        val d = device()
        d.link()
        mockkObject(StartupBarrier)
        coEvery { StartupBarrier.await() } coAnswers { awaitCancellation() }
        SyncLock.resetForTest() // the production seams
        val result = withTimeoutOrNull(1_000) { d.manager.sync(SyncTrigger.WORKER) }
        assertNull("a round must not run while the barrier is closed", result)
        assertEquals(false, SyncLock.isHeld)
    }
}
