package com.example.data.clock

import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * WP-8.6 (PX-46, SY-24) -- the ONE HLC of the process. Opti has one `lastIssued` per page
 * (app.js:131-165); the sync round ratchets it and every local save stamps `__savedAt = now()`
 * from it. Kotlin had no shared instance (WP-8.2 built only the class), so the round and the
 * repository each get the same clock here.
 *
 * Seeded once from HLC_LAST_ISSUED (a blocking read on first use), healed at creation (Opti's
 * module-load `_hlcHeal()`), and persisted through a CONFLATED channel: stamps are issued
 * synchronously, the latest value is written in order, and a heal (which lowers the value) is
 * written too.
 *
 * [DEFERRED_OPEN_QUESTION] The `hlcReset` / `clockSkew` events stay buffered inside the clock
 * until 8.7's event log binds a sink ([HlcClock.bindEventSink]).
 */
object ProcessHlc {
    @Volatile private var instance: HlcClock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(syncDataStore: SyncDataStore): HlcClock =
        instance ?: synchronized(this) { instance ?: create(syncDataStore).also { instance = it } }

    private fun create(syncDataStore: SyncDataStore): HlcClock =
        build(runBlocking { syncDataStore.getHlcLastIssued() }, scope) { syncDataStore.saveHlcLastIssued(it) }

    /**
     * The clock plus its persist loop. WP-8.I (W7, orchestrator-approved guess): a failed save is
     * logged and skipped, never thrown (an uncaught exception on the IO thread would kill the app).
     * The in-memory clock is unchanged; the next issued value is saved again (CONFLATED: the latest
     * value wins). Cancellation is rethrown.
     */
    internal fun build(seed: Long, scope: CoroutineScope, save: suspend (Long) -> Unit): HlcClock {
        val writes = Channel<Long>(Channel.CONFLATED)
        scope.launch {
            for (v in writes) {
                try {
                    save(v)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    try { android.util.Log.w("Questa.hlc", "HLC save failed; the next save retries", e) } catch (_: Throwable) { }
                }
            }
        }
        val clock = DefaultHlcClock(seed) { writes.trySend(it) }
        clock.heal()
        return clock
    }

    /** Test hook: install a clock (or null to rebuild from the DataStore on next use). */
    internal fun setForTest(clock: HlcClock?) {
        synchronized(this) { instance = clock }
    }
}
