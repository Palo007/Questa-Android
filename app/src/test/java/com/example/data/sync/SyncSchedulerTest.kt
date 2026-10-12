package com.example.data.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP-5.1 (spec §7 D10) -- pure-JVM coroutine tests for [SyncScheduler].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    private fun newWritesFlow(): MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Test
    fun localWrite_followedByIdleWindow_triggersExactlyOneSync() = runTest {
        val writes = newWritesFlow()
        var syncCallCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = writes,
            syncFn = { syncCallCount++; SyncResult.Ok }
        )
        scheduler.startDebounceCollector()
        runCurrent()

        writes.tryEmit(Unit)
        runCurrent()
        assertEquals(0, syncCallCount)

        advanceTimeBy(SyncScheduler.SYNC_DEBOUNCE_MS + 500)
        runCurrent()
        assertEquals(1, syncCallCount)
    }

    @Test
    fun twoWritesWithinDebounceWindow_coalesceToExactlyOneSync() = runTest {
        val writes = newWritesFlow()
        var syncCallCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = writes,
            syncFn = { syncCallCount++; SyncResult.Ok }
        )
        scheduler.startDebounceCollector()
        runCurrent()

        writes.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        
        writes.tryEmit(Unit)
        runCurrent()

        advanceTimeBy(4000)
        runCurrent()
        assertEquals(0, syncCallCount)

        advanceTimeBy(1500)
        runCurrent()
        assertEquals(1, syncCallCount)
    }

    @Test
    fun concurrentRequestSyncCalls_runExactlyOneInFlightPlusOneQueuedRerun() = runTest {
        var callCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = newWritesFlow(),
            syncFn = {
                callCount++
                delay(1000)
                SyncResult.Ok
            }
        )

        scheduler.requestSync()
        runCurrent()

        scheduler.requestSync()
        scheduler.requestSync()
        scheduler.requestSync()
        runCurrent()

        advanceTimeBy(2000)
        runCurrent()

        assertEquals(2, callCount)
    }

    @Test
    fun requestSync_whileIdle_runsImmediatelyWithNoQueuedRerun() = runTest {
        var callCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = newWritesFlow(),
            syncFn = { callCount++; SyncResult.Ok }
        )

        scheduler.requestSync()
        runCurrent()

        assertEquals(1, callCount)
    }

    @Test
    fun conflictExhaustionResult_retriesAfterBackoffWindow() = runTest {
        var callCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = newWritesFlow(),
            syncFn = {
                callCount++
                if (callCount == 1) {
                    SyncResult.Error(ErrorKind.CONFLICT,
                        "Dropbox sync conflict: retries exhausted (3); try syncing again shortly"
                    )
                } else {
                    SyncResult.Ok
                }
            }
        )

        scheduler.requestSync()
        runCurrent()
        assertEquals(1, callCount)

        advanceTimeBy(SyncScheduler.SYNC_CONFLICT_BACKOFF_MS + 1000)
        runCurrent()
        
        assertEquals(2, callCount)
    }

    @Test
    fun nonExhaustionError_doesNotScheduleBackoffRetry() = runTest {
        var callCount = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = newWritesFlow(),
            syncFn = { callCount++; SyncResult.Error(ErrorKind.HTTP, "Other error") }
        )

        scheduler.requestSync()
        runCurrent()

        advanceTimeBy(SyncScheduler.SYNC_CONFLICT_BACKOFF_MS + 1000)
        runCurrent()

        assertEquals(1, callCount)
    }

    @Test
    fun successfulSync_invokesOnSyncSuccessHook() = runTest {
        var successHookCalls = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            localWrites = newWritesFlow(),
            onSyncSuccess = { successHookCalls++ },
            syncFn = { SyncResult.Ok }
        )

        scheduler.requestSync()
        runCurrent()

        assertEquals(1, successHookCalls)
    }
}
