package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WP-5.1 (spec §7 D10; PWA `legacy_pwa/sync.js`) -- ports the PWA's continuous/invisible sync
 * cadence into Kotlin. Kotlin previously only synced on a manual button press or once after
 * completing OAuth ([com.example.MainActivity.handleOAuthRedirect]); this class adds the four
 * always-on trigger sources PWA relies on, plus the single-flight + conflict-backoff plumbing that
 * makes them safe to fire concurrently:
 *
 * - **Debounce** (PWA `scheduleSync()`, sync.js:1624-1630 / `save()` calling it, app.js:277): a
 *   local write followed by [SYNC_DEBOUNCE_MS] of idle time triggers one sync.
 * - **Foreground** (PWA's `visibilitychange`/resume, app.js:5651-5659): app-to-foreground
 *   triggers one immediately, via a [DefaultLifecycleObserver] on the process-wide lifecycle.
 * - **Connectivity** (PWA's `online` event, app.js:5651-5659): regaining network triggers one
 *   immediately, via [ConnectivityManager.NetworkCallback.onAvailable].
 * - **Conflict backoff** (PWA's `_pushWithConflictRetry` exhaustion, sync.js:1158-1163): a
 *   conflict-retry-exhausted sync attempt re-fires [SYNC_CONFLICT_BACKOFF_MS] later instead of
 *   dead-ending -- see [scheduleConflictBackoff].
 *
 * All four (plus any other direct caller of [requestSync]) funnel through the SAME single-flight
 * gate (PWA's `_syncInFlight`/`_syncRerunQueued`, sync.js:1009-1020, 1017-1020, 1037-1040): at most
 * one [syncFn] invocation runs at a time, and at most one rerun is queued behind it (a boolean
 * flag, not a counter -- extra requests that arrive while one is already queued are coalesced).
 */
class SyncScheduler(
    // Nullable (deviation, see `syncFn`'s doc below): only actually dereferenced by
    // `registerConnectivityTrigger()`, which no-ops if this is null. Always non-null in production
    // (QuestaApplication passes `applicationContext`); JVM tests that never call `start()`/
    // `registerConnectivityTrigger()` can omit it entirely.
    private val context: Context? = null,
    // Nullable (deviation, see `syncFn`'s doc below): production always supplies this (so the
    // default `syncFn` below has something to delegate to); a test that supplies its own `syncFn`
    // can omit it.
    private val syncManager: SyncManager? = null,
    private val scope: CoroutineScope,
    // Deviation from the WP's literal 3-arg constructor: the debounce trigger (step 3) needs a
    // write-signal source. Rather than taking the whole `RpgRepository` (which would couple this
    // class to Room/repository internals and make it harder to unit-test without Robolectric),
    // this accepts the write-signal Flow directly -- QuestaApplication wires it with
    // `repository.localWrites` in production; SyncSchedulerTest injects a bare
    // `MutableSharedFlow<Unit>` instead.
    private val localWrites: Flow<Unit>,
    // WP-5.1 "Interconnections" -- WP-5.2 needs a "sync just completed" signal to decide whether
    // to run auto-backup. Invoked after a successful [syncFn] call that went through the
    // single-flight gate. Coordinate this signature with WP-5.2 rather than modifying this class.
    private val onSyncSuccess: (suspend () -> Unit)? = null,
    // Test seam (deviation, documented per this WP's "Additional implementation notes" -- design
    // SyncScheduler so its syncNow dependency is injectable/fakeable): production code always uses
    // the default, which delegates to `syncManager.syncNow()`. SyncSchedulerTest injects a fake
    // suspend function instead, so debounce-coalescing / single-flight / backoff timing can be
    // exercised with kotlinx.coroutines.test's virtual clock on a bare JVM, with no Robolectric,
    // real SyncManager, or FakeDropboxServer needed for those cases. Because `context` and
    // `syncManager` are both nullable (see above), a test never needs to construct either just to
    // satisfy this class's constructor.
    //
    // WP-8.6: the round goes through `SyncManager.sync(trigger)`, i.e. SyncLock (StartupBarrier
    // first, BootGate settle in its finally). null = lock-skip.
    private val syncFn: suspend (SyncTrigger) -> SyncResult? = { trigger ->
        requireNotNull(syncManager) {
            "SyncScheduler requires either a non-null `syncManager` or an explicit `syncFn`"
        }.sync(trigger)
    }
) {

    companion object {
        /** PWA `legacy_pwa/sync.js:21` -- `SYNC_DEBOUNCE_MS = 5000`. */
        const val SYNC_DEBOUNCE_MS = 5000L

        /**
         * PWA `legacy_pwa/sync.js:24` -- `SYNC_CONFLICT_BACKOFF_MS = 30000` ("30s backoff after
         * conflict retry exhaustion"). Matches [SyncManager]'s own `SYNC_CONFLICT_RETRY_LIMIT`
         * naming convention (`SyncManager.kt:36`).
         */
        const val SYNC_CONFLICT_BACKOFF_MS = 30000L

    }

    // Single-flight gate state (mirrors PWA's `_syncInFlight` / `_syncRerunQueued`). `gate` guards
    // both fields so "is a sync running" and "should one more run afterward" are checked/mutated
    // atomically regardless of which trigger source is calling in.
    private val gate = Mutex()
    private var syncInFlight = false
    private var rerunRequested = false
    private var rerunTrigger = SyncTrigger.FOREGROUND

    private var debounceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // D28 (m-R7-4): never short-circuits on AuthState; the round itself settles the gate.
            requestSync(SyncTrigger.FOREGROUND)
        }

        // WP-8.6 (PX-44): Opti syncs on EVERY visibilitychange (sync.js:3948), hidden included.
        override fun onStop(owner: LifecycleOwner) {
            requestSync(SyncTrigger.BACKGROUND)
        }
    }

    /**
     * Registers the foreground + connectivity triggers and starts collecting [localWrites] for
     * the debounce trigger. Call once per process (QuestaApplication.onCreate()) -- registering
     * twice would double-register the lifecycle observer / network callback.
     *
     * Split into three independently-callable pieces (below) so a pure-JVM test can exercise just
     * [startDebounceCollector] (no [ProcessLifecycleOwner]/[ConnectivityManager] involved, so no
     * Robolectric/Android environment needed) without pulling in the other two.
     */
    fun start() {
        registerForegroundTrigger()
        registerConnectivityTrigger()
        startDebounceCollector()
    }

    private fun registerForegroundTrigger() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    private fun registerConnectivityTrigger() {
        val connectivityManager = context?.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestSync(SyncTrigger.NETWORK)
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    /** The debounce trigger (PWA `scheduleSync()`, sync.js:1624-1630): collects [localWrites] and,
     * on each emission, cancels any pending debounce [Job] and starts a fresh [SYNC_DEBOUNCE_MS]
     * timer. Public (not just called from [start]) so tests can exercise it directly. */
    fun startDebounceCollector() {
        scope.launch {
            localWrites.collect {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(SYNC_DEBOUNCE_MS)
                    requestSync(SyncTrigger.DEBOUNCE)
                }
            }
        }
    }

    /** Unregisters the foreground/connectivity observers. Not used by QuestaApplication (the
     * process owns this scheduler for its whole lifetime) but provided for test hygiene. */
    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        networkCallback?.let { cb ->
            context?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        }
        debounceJob?.cancel()
    }

    /**
     * PWA's `_pushWithConflictRetry` exhaustion self-heal (`sync.js:1158-1163`,
     * `setTimeout(syncNow, SYNC_CONFLICT_BACKOFF_MS)`): schedules exactly one more sync attempt
     * [SYNC_CONFLICT_BACKOFF_MS] from now, through the same [requestSync] single-flight gate as
     * every other trigger. Public so [SyncManager]'s `onConflictExhausted` callback (or a test) can
     * invoke it directly, in addition to [runOnce]'s own result-based detection below.
     */
    fun scheduleConflictBackoff() {
        scope.launch {
            delay(SYNC_CONFLICT_BACKOFF_MS)
            requestSync(SyncTrigger.BACKOFF)
        }
    }

    /**
     * Single funnel for every trigger source (debounce, foreground, connectivity, backoff, and any
     * direct/manual caller such as a UI "sync now" button routed through this scheduler instead of
     * calling [SyncManager.syncNow] directly). Mirrors PWA's `syncNow()` single-flight guard
     * exactly: if a sync is already running, this just sets [rerunRequested] and returns -- the
     * in-flight call's own loop (see [runOnce]) starts exactly one more run afterward, then clears
     * the flag. Never queues more than one rerun (PWA's flag is boolean, not a counter).
     */
    fun requestSync(trigger: SyncTrigger = SyncTrigger.MANUAL) {
        scope.launch {
            var shouldRun = false
            gate.withLock {
                if (syncInFlight) {
                    rerunRequested = true
                    rerunTrigger = trigger
                } else {
                    syncInFlight = true
                    shouldRun = true
                }
            }
            if (shouldRun) {
                runOnce(trigger)
            }
        }
    }

    /**
     * Runs [syncFn] to completion, then -- while still holding "in flight" status -- checks
     * whether a rerun was requested while it ran; if so, loops and runs again immediately (still
     * exactly one queued rerun, per [requestSync]'s contract) instead of releasing and
     * re-acquiring the gate, which would let a third concurrent caller sneak in an extra run.
     */
    private suspend fun runOnce(trigger: SyncTrigger) {
        val result = try {
            syncFn(trigger)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Defensive: a background trigger source must never crash `scope`. `null` falls
            // through `handleResult`'s `else` branch below (no success/backoff signal), same as an
            // unrecognized SyncResult.Error would.
            null
        }
        handleResult(result)

        var again = false
        var next = trigger
        gate.withLock {
            if (rerunRequested) {
                rerunRequested = false
                again = true
                next = rerunTrigger
            } else {
                syncInFlight = false
            }
        }
        if (again) runOnce(next)
    }

    private suspend fun handleResult(result: SyncResult?) {
        when {
            result == SyncResult.Ok || result == SyncResult.NothingToPush -> onSyncSuccess?.invoke()
            result is SyncResult.Error && result.kind == ErrorKind.CONFLICT -> {
                // Mirrors PWA: a conflict-retry-exhausted attempt is NOT a terminal failure to this
                // scheduler -- it self-schedules a backoff retry instead (see
                // scheduleConflictBackoff's doc). This inspects syncFn()'s own return value, so it
                // fires regardless of whether the underlying SyncManager was additionally wired
                // with the `onConflictExhausted` constructor callback.
                scheduleConflictBackoff()
            }
            else -> Unit
        }
    }
}
