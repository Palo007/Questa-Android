package com.example.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CR-KT-018: Opti `_noteSaveCommitted` -- each user write stamps localEditAt from the clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalEditStamperTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))

    @Test fun eachWrite_stampsTheClockValue() {
        val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val stamps = mutableListOf<Long>()
        var now = 100L
        val job = LocalEditStamper(writes, { stamps += it }, { now }).start(scope)
        scheduler.runCurrent()
        assertTrue(writes.tryEmit(Unit)); scheduler.runCurrent()
        now = 250L
        assertTrue(writes.tryEmit(Unit)); scheduler.runCurrent()
        assertEquals(listOf(100L, 250L), stamps)
        job.cancel()
    }

    @Test fun noWrite_noStamp() {
        val stamps = mutableListOf<Long>()
        val job = LocalEditStamper(MutableSharedFlow(), { stamps += it }, { 1L }).start(scope)
        scheduler.runCurrent()
        assertEquals(emptyList<Long>(), stamps)
        job.cancel()
    }

    @Test fun aFailedStamp_keepsCollecting() {
        val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val stamps = mutableListOf<Long>()
        var fail = true
        val job = LocalEditStamper(writes, { if (fail) { fail = false; throw IllegalStateException("disk") }; stamps += it }, { 7L }).start(scope)
        scheduler.runCurrent()
        writes.tryEmit(Unit); scheduler.runCurrent()
        writes.tryEmit(Unit); scheduler.runCurrent()
        assertEquals(listOf(7L), stamps)
        job.cancel()
    }
}
