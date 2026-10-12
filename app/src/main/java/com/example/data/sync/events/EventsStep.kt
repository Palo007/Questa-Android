package com.example.data.sync.events

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.data.local.AppDatabase
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.EventWatermark
import com.example.data.sync.RoundContext
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncClocks
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncRoundStep
import kotlinx.coroutines.CancellationException

/**
 * WP-8.7 (PX-56, SY-55) -- Opti `syncEventsDeleteDevice(devId)` (sync.js:3621-3630): list
 * `/events` and delete every file whose parsed device is [deviceId]. A foreign file, a
 * conflict copy (unparsed name) and a device whose id merely starts with [deviceId] are never
 * touched. One failed delete does not stop the rest; a list failure throws (callers ignore it).
 * An empty [deviceId] is a no-op.
 */
suspend fun purgeOwnEventFiles(remote: EventsRemote, deviceId: String) {
    if (deviceId.isEmpty()) return
    for (ent in remote.listEvents()) {
        val parsed = EventSyncEngine.evtParseFileName(ent.name)
        if (parsed != null && parsed.first == deviceId) {
            try {
                remote.delete(EventsDir.file(ent.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort (sync.js:3627)
            }
        }
    }
}

/**
 * WP-8.7 -- the EVENTS round step (replaces SyncManager's built-in one through `roundSteps`).
 *
 * 1. PX-56 consumer: a PENDING_PURGE_DEV left by a reset while disconnected (8.9) is read and
 *    removed, then that device's files are purged, errors ignored. Opti does this on the OAuth
 *    return, before its first round (sync.js:272-279); here it is the first linked round that
 *    reaches EVENTS (guess, see WP-8.7 notes).
 * 2. Opti `syncEventsSync` (sync.js:3635-3645): push, then pull; any failure lands in lastError
 *    as "event sync failed: <message>" and never fails the round.
 */
class EventsStep(
    private val w: EventWatermark,
    private val cfg: EvtCfgStore,
    private val store: LocalEventStore,
    private val deviceId: suspend () -> String,
    private val remoteFor: (accessToken: String) -> EventsRemote = { DropboxEventsRemote(it) },
    private val clock: () -> Long = SyncClocks.transport,
    val diag: DiagRing = DiagRing.process,
    private val saveLastError: suspend (String) -> Unit = {},
    private val toast: (String) -> Unit = {},
    /**
     * WP-8.12-T1 review F3: the pusher's watermark / age / fullPushAt clock. Event `ts` is stamped
     * from the app clock, so these must read the same clock (Opti: one `Date.now()`). Equal to the
     * real time outside a debug clock offset. The puller's throttle and backoff stay on [clock].
     */
    private val pushClock: () -> Long = clock,
) : SyncRoundStep {
    override val slot = StepSlot.EVENTS
    override val order = 0

    /** Exposed for WP-8.9's Force Push Events / force pull (they run it under SyncLock). */
    val pusher = EventPusher(w, cfg, store, deviceId, pushClock, diag)
    val puller = EventPuller(cfg, store, deviceId, clock, diag)

    override suspend fun run(r: RoundContext): StepResult {
        val remote = remoteFor(r.accessToken)
        consumePendingPurge(remote)
        return try {
            val report = pusher.push(remote)
            report.toast?.let { toast(it) }
            puller.pull(remote)
            StepResult.Continue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "event sync failed: " + (e.message ?: e.toString())
            try { saveLastError(msg) } catch (e2: CancellationException) { throw e2 } catch (e2: Exception) { }
            StepResult.Failed(msg)
        }
    }

    /** Read-and-remove, then purge; the purge's own failure is ignored (Opti `.catch(function(){})`). */
    suspend fun consumePendingPurge(remote: EventsRemote) {
        val pend = try { cfg.takePendingPurgeDev() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (pend.isNullOrEmpty()) return
        try {
            purgeOwnEventFiles(remote, pend)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        }
    }

    companion object {
        /**
         * The production step: Room events, the shared sync DataStore, the 8.6 watermark, and
         * lastError through [SyncDataStore.saveSyncStatus] (lastSyncAt untouched).
         */
        fun forApp(
            syncDataStore: SyncDataStore,
            prefs: DataStore<Preferences>,
            database: AppDatabase,
            toast: (String) -> Unit = {},
        ): EventsStep = EventsStep(
            w = DataStoreEventWatermark(syncDataStore),
            cfg = DataStoreEvtCfgStore(prefs),
            store = RoomEventStore(database),
            deviceId = { syncDataStore.getOrCreateDeviceId() },
            saveLastError = { syncDataStore.saveSyncStatus(it) },
            toast = toast,
            pushClock = SyncClocks.mergeNow,
        )
    }
}
