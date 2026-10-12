package com.example.data.boot

import com.example.data.clock.DayStamp
import com.example.data.clock.StartupBarrier
import com.example.data.sync.SyncResult
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** app.js:2826 `BOOT_ROLLOVER_TIMEOUT_MS`. */
const val BOOT_ROLLOVER_TIMEOUT_MS = 8_000L

/** app.js:2815-2817 (pure; kept as the testable unit, as Opti keeps it). */
fun shouldDeferDayRollover(configured: Boolean, elapsedMs: Long, timeoutMs: Long): Boolean =
    configured && elapsedMs < timeoutMs

/**
 * WP-8.6 -- the boot gate and the day rollover (PARITY_FIX_PLAN §5.0, rules B1-B9; Opti app.js at
 * 7d5d4f3, :2778-2898 and :8603-8639). §5.0 is the only specification; the rule numbers below
 * point into it.
 *
 * Transitions run under one JVM monitor ([lock]); the StateFlow writes happen inside it; nothing
 * suspends inside it. `startDay()` runs on the gate's single-threaded [scope], under [dayMutex].
 *
 * Lock hierarchy (r14, T-10a): code holding [dayMutex] never waits for SyncLock or a round; code
 * holding SyncLock or inside a Room transaction never takes [dayMutex].
 */
object BootGate {
    private val lock = Any()

    // ---- observable state (B1) ----
    private val _pending = MutableStateFlow(false)
    private val _rolloverDay = MutableStateFlow<DayStamp>(0)
    private val _loadDayStamp = MutableStateFlow<DayStamp>(0)

    /** `_bootRolloverPending` (app.js:2783). */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** The day of the latest [runDayRollover] ENTRY (B3); 0 = none yet. */
    val rolloverDay: StateFlow<DayStamp> = _rolloverDay.asStateFlow()

    /** `_loadDayStamp` (app.js:8613); written only by [bootStartDay] (B2). */
    val loadDayStamp: StateFlow<DayStamp> = _loadDayStamp.asStateFlow()

    /** `bootGateBlocksInput()` (app.js:2785). */
    fun blocksInput(): Boolean = _pending.value

    // ---- providers and bindings (B8, B9, B10) ----
    /** The one boot-gate clock site; a test seam. */
    @Volatile var clock: () -> Long = { com.example.data.clock.PhysicalClock.nowMs() } // clock-ok:boot-gate-timer

    /** D27 read; assigned by 8.I (W1 step 2). Any exception reads as false (B9). */
    @Volatile var configuredProvider: () -> Boolean = { false }

    /** The B7 log sink; bound by 8.I at the W3 close (W1 hooks). Default no-op. */
    @Volatile var lifecycleLog: (kind: String, detail: String) -> Unit = { _, _ -> }

    /** Zone for `dayStamp` (local calendar day, app.js:2025). A test seam. */
    @Volatile var zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }

    /** (r13, T-1) Serialises every startDay, RYA open and confirmRya. NOT reentrant. */
    val dayMutex: Mutex = Mutex()

    @Volatile private var startDay: suspend () -> Unit = {}
    @Volatile private var sheetOpen: () -> Boolean = { false }
    @Volatile private var tapDrain: suspend (nowMs: Long) -> Unit = {}

    /** Binding defaults to a no-op until the W4 bind (W1 step 3). */
    fun bindStartDay(startDay: suspend () -> Unit) { this.startDay = startDay }

    /** Binding defaults to `{ false }` until the W7 bind (B8). */
    fun bindSheetOpen(sheetOpen: () -> Boolean) { this.sheetOpen = sheetOpen }

    /**
     * B3 ends with `LocalTapQueue.drain(clock())`. LocalTapQueue is an instance (DataStore +
     * repository), so 8.I binds it here (WP-8.6 guess; see WP-8.6_NOTES.md). Default no-op.
     */
    fun bindTapDrain(drain: suspend (nowMs: Long) -> Unit) { this.tapDrain = drain }

    // ---- private state (guarded by lock) ----
    private var done = false // _dayRolloverDone (app.js:2827)
    private var armedConfigured = false
    private var timerJob: Job? = null
    private var startDayJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** (r12, minor 6) Single-threaded, so two startDay bodies never interleave. Tests inject a TestScope. */
    @Volatile internal var scope: CoroutineScope = newScope()

    private fun dayStamp(ms: Long): DayStamp = OptiSnapshotMerger.dayStampOf(ms, zoneProvider())

    // ---- events (B2-B7) ----

    /** B2 -- app.js:2849-2891. Cold start and re-arm. */
    fun bootStartDay(configured: Boolean, barrierDone: Boolean) {
        synchronized(lock) { bootStartDayLocked(configured, barrierDone) }
    }

    private fun bootStartDayLocked(configured: Boolean, barrierDone: Boolean) {
        _loadDayStamp.value = dayStamp(clock())
        timerJob?.cancel()
        timerJob = null
        armedConfigured = configured
        if (configured || !barrierDone) {
            _pending.value = true
            timerJob = scope.launch {
                delay(BOOT_ROLLOVER_TIMEOUT_MS)
                onTimer()
            }
            return
        }
        runDayRolloverLocked()
    }

    /** B5 -- app.js:2886. A configured arm waits for the round or the timer. */
    fun onBarrierDone() {
        synchronized(lock) {
            if (_pending.value && !armedConfigured) runDayRolloverLocked()
        }
    }

    /** B4 -- app.js:2846 `onQuestaFirstSyncRound`. Any result, including null (lock-skip). No latch. */
    @Suppress("UNUSED_PARAMETER")
    fun onFirstRoundSettled(result: SyncResult?) {
        synchronized(lock) {
            if (_pending.value) runDayRolloverLocked()
        }
    }

    /**
     * B7 -- the reload equivalent (app.js:8613-8614, 8622-8623, 8635-8639). `>`, not `>=`, so a
     * clock moved backwards is not a new day. Applies in every state, including while pending.
     */
    fun onForeground(nowMs: Long) {
        synchronized(lock) {
            if (!(dayStamp(nowMs) > _loadDayStamp.value)) return
            val sheet = try { sheetOpen() } catch (e: Exception) { false }
            if (sheet) return // the next ON_START retries (app.js:8603-8612)
            try { lifecycleLog("lifecycle", "reload:newDay") } catch (e: Exception) { }
            done = false
            val configured = try { configuredProvider() } catch (e: Exception) { false }
            bootStartDayLocked(configured, StartupBarrier.done.value)
        }
    }

    /** B6 -- `setTimeout(_runDayRollover, 8000)` (app.js:2865, :2885). */
    internal fun onTimer() {
        synchronized(lock) {
            if (_pending.value) runDayRolloverLocked()
        }
    }

    /** B3 -- `_runDayRollover` (app.js:2838-2843). */
    internal fun runDayRollover() {
        synchronized(lock) { runDayRolloverLocked() }
    }

    private fun runDayRolloverLocked() {
        if (done) return
        done = true
        timerJob?.cancel()
        timerJob = null
        // Set at ENTRY and emitted before pending changes (B3; BootGateForegroundTest row 8).
        _rolloverDay.value = dayStamp(clock())
        _pending.value = false
        val prev = startDayJob
        val body = startDay
        val drain = tapDrain
        startDayJob = scope.launch {
            prev?.cancelAndJoin()
            dayMutex.withLock { body() }
            drain(clock())
        }
    }

    /** Test hook: back to S0 with fresh bindings. Never called by production code. */
    internal fun resetForTest(
        scope: CoroutineScope,
        clock: () -> Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        synchronized(lock) {
            timerJob?.cancel()
            startDayJob?.cancel()
            timerJob = null
            startDayJob = null
            done = false
            armedConfigured = false
            _pending.value = false
            _rolloverDay.value = 0
            _loadDayStamp.value = 0
            this.scope = scope
            this.clock = clock
            this.zoneProvider = { zone }
            configuredProvider = { false }
            lifecycleLog = { _, _ -> }
            startDay = {}
            sheetOpen = { false }
            tapDrain = {}
        }
    }
}
