package com.example.data.sync

import com.example.data.boot.BootGate
import com.example.data.clock.StartupBarrier
import kotlinx.coroutines.sync.Mutex

/**
 * WP-8.6 (PX-44) -- the ONE process-wide sync lock, shared by the scheduler, the worker and every
 * manual round. Port of Opti's `questa-sync` Web Lock (sync.js:2342-2347, :2393).
 *
 * - First line: [awaitBarrier], meant to be [StartupBarrier.await] (R15): a round never reads the
 *   base before the startup migrations have finished; a failed migration leaves the barrier (and
 *   every round) closed. Wired by 8.I at the W3 close with `StartupMigrations.run`.
 * - A Web Lock request queues, so every trigger waits for the lock -- except
 *   [SyncTrigger.WORKER], which returns null at once when a round is already running (the running
 *   round covers it; WP-8.6 guess). null is the "lock-skip".
 * - `finally` -> [BootGate.onFirstRoundSettled] with the result, null on a lock-skip and on the
 *   exception path (§5.0 W1 hooks, D28).
 *
 * Lock hierarchy (§5.0, r14 T-10a): code holding this lock never takes `BootGate.dayMutex`.
 */
object SyncLock {
    private val mutex = Mutex()

    // 8.I (W3 close): switched in the same commit that launches `StartupMigrations.run`.
    private val DEFAULT_AWAIT_BARRIER: suspend () -> Unit = { StartupBarrier.await() }

    /**
     * Seam for [StartupBarrier.await] (the real barrier is a process-wide one-shot).
     *
     * 8.I (W3 close): the default (also [resetForTest]'s) is `{ StartupBarrier.await() }`, switched
     * in the same commit that launches `StartupMigrations.run(...)` from `QuestaApplication.onCreate`,
     * together with MainActivity's `seedFirstLaunch(awaitBarrier = ...)` default (review 26 C2).
     */
    @Volatile internal var awaitBarrier: suspend () -> Unit = DEFAULT_AWAIT_BARRIER

    /** Test seam for the settle hook. */
    @Volatile internal var onSettled: (SyncResult?) -> Unit = { BootGate.onFirstRoundSettled(it) }

    val isHeld: Boolean get() = mutex.isLocked

    suspend fun <T> singleFlight(trigger: SyncTrigger, block: suspend () -> T): T? {
        var result: T? = null
        try {
            awaitBarrier()
            if (trigger == SyncTrigger.WORKER) {
                if (!mutex.tryLock()) return null
            } else {
                mutex.lock()
            }
            try {
                result = block()
            } finally {
                mutex.unlock()
            }
            return result
        } finally {
            // A Settings save is not a sync round, so it must not settle the first round.
            if (trigger != SyncTrigger.SETTINGS) {
                try { onSettled(result as? SyncResult) } catch (e: Exception) { }
            }
        }
    }

    /** Test hook: restores the production seams. */
    internal fun resetForTest() {
        awaitBarrier = DEFAULT_AWAIT_BARRIER
        onSettled = { BootGate.onFirstRoundSettled(it) }
    }
}
