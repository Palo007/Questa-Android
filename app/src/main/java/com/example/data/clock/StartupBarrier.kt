package com.example.data.clock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * WP-8.2 (Wave 1) -- D8/D22/R15: "The base is never missing when a merge runs." A round must never
 * read [com.example.data.sync.BaseStore] before the startup migrations (WP-8.21, `StartupMigrations`)
 * have finished moving the legacy `sync_base` data into place. [StartupBarrier] is the single gate
 * every merge-adjacent caller awaits before touching the base: `SyncLock.singleFlight` (WP-8.6),
 * `SyncWorker.doWork` (WP-8.6) and `QuickLogBootstrap.onAppCreate` (WP-8.6) all call [await] as
 * their first suspending step (§4.2/§5.0 W1; R15 `StartupBarrierGateTest`).
 *
 * WP-8.2 owns only this object -- the object that later waves await. It does not itself decide
 * *when* migrations are done; WP-8.21's `StartupMigrations.run` calls [markDone] as its last
 * action, ONLY on success (review r1 minor 11: §5.0 is authoritative here -- a failure, status
 * `startup_migration_failed`, must leave the barrier closed forever on that process, not call
 * [markDone]). An explicit design point, not an omission: a process that cannot safely establish
 * its base must never let a round proceed.
 */
object StartupBarrier {
    private val state = BarrierState()

    /** True once [markDone] has been called. Read synchronously by BootGate (WP-8.6, §5.0 W1 step 4). */
    val done: StateFlow<Boolean> get() = state.done

    /**
     * WP-8.22-D item 1: true once the startup migrations reported `startup_migration_failed` on this
     * process. The barrier itself stays closed ([await] never returns); this only lets callers that
     * can give up (SyncWorker, the OAuth redirect) stop waiting instead of hanging.
     */
    val failed: StateFlow<Boolean> get() = state.failed

    /** Suspends until [markDone] has been called. Returns immediately if it already has. */
    suspend fun await() = state.await()

    /** WP-8.22-D: suspends until the barrier opens (true) or is known failed (false). No timeout. */
    suspend fun awaitOutcome(): Boolean = state.awaitOutcome()

    /**
     * Opens the barrier. Idempotent: a second call is a no-op (startup migrations run at most once
     * per process). `internal` because only WP-8.21's `StartupMigrations` (same Gradle module) may
     * call it -- every other caller only ever awaits.
     */
    internal fun markDone() = state.markDone()

    /** WP-8.22-D: called by `StartupMigrations.publish(STATUS_FAILED)`. Never opens the barrier. */
    internal fun markFailed() = state.markFailed()

    /** Test hook (review 46 F6): undoes [markFailed] only; never closes an open barrier. */
    internal fun clearFailedForTest() = state.clearFailed()
}

/** The logic of [StartupBarrier], as a class so tests can use a fresh one (the object is process-wide). */
internal class BarrierState {
    private val gate = CompletableDeferred<Unit>()
    private val _done = MutableStateFlow(false)
    private val _failed = MutableStateFlow(false)

    val done: StateFlow<Boolean> = _done
    val failed: StateFlow<Boolean> = _failed

    suspend fun await() {
        gate.await()
    }

    suspend fun awaitOutcome(): Boolean {
        if (_done.value) return true
        combine(_done, _failed) { d, f -> d || f }.first { it }
        return _done.value
    }

    fun markDone() {
        if (_done.value) return
        _done.value = true
        if (!gate.isCompleted) {
            gate.complete(Unit)
        }
    }

    fun markFailed() {
        if (_done.value) return
        _failed.value = true
    }

    fun clearFailed() {
        _failed.value = false
    }
}
