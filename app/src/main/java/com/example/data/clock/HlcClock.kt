package com.example.data.clock

import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WP-8.2 (Wave 1, "Schema v7, clock and persistence foundation") -- D9/D12/D23 physical-time
 * choke point.
 *
 * [PhysicalClock] is the ONLY place in `app/src/main` allowed to hold a bare
 * `System.currentTimeMillis()` call outside the §4.4 clock-stamp-site baseline table. Every entity
 * default (`TaskEntity.createdAt`, `TombstoneEntity.at`, etc.) that used to read
 * `System.currentTimeMillis()` directly now calls [PhysicalClock.nowMs] instead (same real
 * wall-clock value, no behavior change) -- but review r1 major 9 correctly points out that a call
 * to [PhysicalClock.nowMs] is *itself* still a clock-stamp site, just relocated: `ClockStampSiteTest`
 * now matches `PhysicalClock\.` in addition to the raw platform APIs, so hiding a site behind this
 * wrapper no longer erases it from the inventory, it only lets one file (this one) hold the single
 * real `System.currentTimeMillis()` read every other `PhysicalClock.nowMs()` call site delegates to.
 */
object PhysicalClock {
    /** App-logic "now": real time plus the WP-8.12-T1 debug offset (always 0 in release). */
    fun nowMs(): Long = System.currentTimeMillis() + DebugClockOffset.offsetMs() // clock-ok:hlc-physical

    /** Real time, never offset: transport timers only (token expiry, Retry-After, throttles). */
    fun realNowMs(): Long = System.currentTimeMillis() // clock-ok:transport-timer
}

/**
 * D9/D22/D23/R15 -- the logical clock used to stamp `updatedAt` on merge-participating records.
 * Seeded from the persisted `HLC_LAST_ISSUED` DataStore key (§4.3) so a stamp issued by this
 * device is monotonic across process restarts, matching Opti's HLC (app.js:123-165, commit
 * 7d5d4f3). WP-8.5 (merge core) and WP-8.6 (round orchestration) are the consumers; WP-8.2 only
 * builds the clock and its seed/persist plumbing -- HLC clamping/tie-break semantics for MERGE
 * values belong to WP-8.5's `ClampPolicy` (that clamp is a *different* tolerance, sync.js's
 * `MAX_FUTURE_SKEW_MS`, applied to values being merged; [HLC_RATCHET_TOLERANCE_MS] below is the
 * clock's own future-skew tolerance, `app.js`'s `HLC_RATCHET_TOLERANCE_MS`, which
 * `MAX_FUTURE_SKEW_MS` derives from -- see app.js:123-129's comment on why the two were once
 * inconsistent and must not be again).
 */
interface HlcClock {
    /** Issues a new, monotonically increasing HLC stamp and persists it as the new seed. Heals first (see [heal]). */
    fun now(): Long

    /**
     * Ratchets the clock forward to `remoteMaxTs` if it is ahead of the local clock AND within
     * [HLC_RATCHET_TOLERANCE_MS] of physical time. A remote stamp further in the future than that
     * is refused (not just clamped to a lower value -- REFUSED, the local clock does not move) and
     * a `clockSkew` diagnostic fires. Returns true iff the clock actually moved.
     */
    fun ratchet(remoteMaxTs: Long): Boolean

    /**
     * Ports Opti's `_hlcHeal()` (app.js:139-142): if the clock has drifted more than
     * [HLC_RATCHET_TOLERANCE_MS] into the future of physical time (a poisoned clock -- manual
     * change, bad NTP, dual boot, dead RTC), snap it back to physical time, persist that, and emit
     * an `hlcReset` diagnostic. Returns true iff it actually healed. [now] always calls this first,
     * matching Opti calling `_hlcHeal()` at the top of `now()`; it is also exposed standalone so a
     * caller can heal at boot (app.js's own module-load `_hlcHeal()` call) before the first `now()`.
     */
    fun heal(): Boolean

    /** The last stamp issued or ratcheted to, observable for diagnostics/tests. */
    val lastIssued: StateFlow<Long>

    /**
     * review r2 minor 4: binds the durable `hlcReset`/`clockSkew` event sink. Any event emitted
     * before a sink is bound (e.g. [createHlcClock]'s boot-time [heal] call, which runs before DI
     * wiring can exist -- Opti's own `_hlcHeal()` runs at module load, app.js:132, before any
     * event-log consumer could possibly be listening) is buffered, not dropped, and is delivered
     * to the sink the moment it binds, in the order it was emitted.
     */
    fun bindEventSink(sink: (kind: String, payload: Map<String, Any?>) -> Unit)

    companion object {
        /**
         * Ported from Opti's `HLC_RATCHET_TOLERANCE_MS` (app.js:123-129, commit 7d5d4f3): the one
         * future-skew tolerance for the whole app. Above `physical + this`, no peer device will
         * ever honour a stamp this device issues (WP-8.5's merge clamp zeroes anything past that
         * ceiling too), so there is no ordering authority left in a value beyond it worth
         * preserving -- [heal] drops it, [ratchet] refuses it.
         */
        const val HLC_RATCHET_TOLERANCE_MS = 120_000L
    }
}

/**
 * Default [HlcClock]. Physical component from [PhysicalClock]; the logical component ports Opti's
 * `_hlcHeal`/`now`/`ratchetHlc` (app.js:139-165) line for line -- see each override's KDoc.
 *
 * [seed] is read synchronously by the caller (see [seedFrom]) before this class is constructed, so
 * `now()` never needs to suspend (D23: physical taps and merge stamps must not suspend mid-transaction).
 *
 * The `logEvent({kind:'hlcReset', ...})` / `logEvent({kind:'clockSkew', ...})` equivalent
 * (app.js:141, :165) is [bindEventSink]/[emit] below: a plain callback, not a suspend `EventLogDao`
 * write, because [heal]/[now]/[ratchet] are all synchronous by contract. The DI wiring that gives
 * later waves (WP-8.6/8.7's real event-log pipeline) a durable sink is out of WP-8.2's W1 file
 * list, so events emitted before [bindEventSink] is called are buffered (review r2 minor 4), not
 * dropped, and flushed to the sink in order once it binds -- this class is also fully testable
 * standalone via a sink bound immediately in a test.
 */
class DefaultHlcClock(
    seed: Long,
    private val persist: (Long) -> Unit
) : HlcClock {
    private val lock = Any()
    private val _lastIssued = MutableStateFlow(seed)
    override val lastIssued: StateFlow<Long> = _lastIssued

    private var eventSink: ((String, Map<String, Any?>) -> Unit)? = null
    // WP-8.22-E: no size cap, on purpose. Opti has no pre-sink buffer: its `logEvent`
    // (app.js:982-1041) writes every `hlcReset`/`clockSkew` straight to the IndexedDB event store,
    // which has no cap, and those rows can upload (EvtUploadPolicy). A cap here would drop events
    // Opti keeps, so it could change sync output. In practice the buffer holds only the events
    // emitted before QuestaApplication.onCreate binds the sink (the boot heal: at most one).
    // [DEFERRED_OPEN_QUESTION] unbounded if a process never binds a sink (e.g. a test or a
    // component that builds its own clock and keeps ratcheting refused stamps).
    private val bufferedEvents = mutableListOf<Pair<String, Map<String, Any?>>>()

    /** Callers must hold [lock]. */
    private fun emit(kind: String, payload: Map<String, Any?>) {
        val sink = eventSink
        if (sink != null) sink(kind, payload) else bufferedEvents.add(kind to payload)
    }

    override fun bindEventSink(sink: (kind: String, payload: Map<String, Any?>) -> Unit) {
        synchronized(lock) {
            eventSink = sink
            if (bufferedEvents.isNotEmpty()) {
                val toFlush = bufferedEvents.toList()
                bufferedEvents.clear()
                toFlush.forEach { (kind, payload) -> sink(kind, payload) }
            }
        }
    }

    /** app.js:139-142 `_hlcHeal()`. Callers must hold [lock]. */
    private fun healLocked(): Boolean {
        val physical = PhysicalClock.nowMs() // clock-ok:hlc-physical
        if (!(_lastIssued.value > physical + HlcClock.HLC_RATCHET_TOLERANCE_MS)) return false
        val was = _lastIssued.value
        _lastIssued.value = physical
        persist(physical)
        emit("hlcReset", mapOf("was" to was, "to" to physical, "driftMs" to (was - physical)))
        return true
    }

    override fun heal(): Boolean = synchronized(lock) { healLocked() }

    override fun now(): Long {
        synchronized(lock) {
            healLocked() // app.js:148: now() heals first, every call.
            val physical = PhysicalClock.nowMs() // clock-ok:hlc-physical
            val next = maxOf(physical, _lastIssued.value + 1)
            _lastIssued.value = next
            persist(next)
            return next
        }
    }

    override fun ratchet(remoteMaxTs: Long): Boolean {
        synchronized(lock) {
            val physical = PhysicalClock.nowMs() // clock-ok:hlc-physical
            // app.js:150 ratchetHlc: a remote stamp beyond tolerance is REFUSED, not clamped down
            // to a lower value -- the local clock does not move, only a diagnostic fires. No heal()
            // call here (Opti's ratchetHlc does not call _hlcHeal either).
            if (remoteMaxTs > physical + HlcClock.HLC_RATCHET_TOLERANCE_MS) {
                emit("clockSkew", mapOf("remoteTs" to remoteMaxTs, "localTs" to physical))
                return false
            }
            val moved = remoteMaxTs > _lastIssued.value
            // app.js:150 always writes lastIssued = max(lastIssued, maxRemoteTs) and persists,
            // even when it doesn't change -- an idempotent write, ported as-is for fidelity.
            val next = maxOf(_lastIssued.value, remoteMaxTs)
            _lastIssued.value = next
            persist(next)
            return moved
        }
    }

    companion object {
        /**
         * Synchronously reads the persisted seed. Callers on the main/startup path must do this
         * read before any merge can run (D8/R15), which is why it is a suspend function callable
         * once during app or test startup rather than something [DefaultHlcClock] does lazily.
         */
        suspend fun seedFrom(syncDataStore: SyncDataStore): Long = syncDataStore.getHlcLastIssued()
    }
}

/**
 * Convenience factory used by DI wiring (owned by later waves) and by tests: builds a
 * [DefaultHlcClock] seeded from [SyncDataStore.getHlcLastIssued] and wires persistence back to
 * [SyncDataStore.saveHlcLastIssued]. `persistBlocking` is a suspend-to-blocking bridge supplied by
 * the caller (e.g. `runBlocking` in a background dispatcher) because [HlcClock.now] is intentionally
 * synchronous (D23: physical taps and merge stamps must not suspend mid-transaction).
 *
 * review r2 minor 4: calls [HlcClock.heal] immediately, matching Opti's own module-load
 * `_hlcHeal()` (app.js:132, right after `lastIssued = S.__hlcLast`) -- a poisoned clock is healed
 * at boot, before the first `now()`/`ratchet()` call, not lazily on first use. This runs before any
 * caller has had a chance to call [HlcClock.bindEventSink] (DI wiring happens after construction),
 * so a boot-time `hlcReset` is buffered and delivered once a sink binds, never dropped.
 */
suspend fun createHlcClock(
    syncDataStore: SyncDataStore,
    persistBlocking: (Long) -> Unit
): HlcClock {
    val seed = DefaultHlcClock.seedFrom(syncDataStore)
    val clock = DefaultHlcClock(seed, persistBlocking)
    clock.heal()
    return clock
}
