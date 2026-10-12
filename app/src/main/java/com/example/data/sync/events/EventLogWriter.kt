package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.CanonicalJson
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.EventWatermark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.7 -- Opti `logEvent(ev)` (app.js:982-1041) for the engine's own diagnostic events:
 * `{ts: Date.now(), uid: syncEventUid(), dev: syncDeviceId(), ...ev}`, written fire-and-forget
 * (a failed write never reaches the caller).
 *
 * Two sinks are bound by 8.I:
 *  - [hlcSink] -> `HlcClock.bindEventSink` (`hlcReset` {was, to, driftMs}, `clockSkew`
 *    {remoteTs, localTs}; app.js:33, :38). These are ordinary events, uploaded like any other
 *    (Opti pushes with `includeDiag: true`), so they are NOT marked synthetic.
 *  - [lifecycleSink] -> `BootGate.lifecycleLog` (`logEvent({kind:'lifecycle', detail})`,
 *    app.js:8622). The `lifecycle` kind stays local: [EvtUploadPolicy] never uploads it.
 *
 * [clock] defaults to the physical clock seam ([com.example.data.boot.BootGate.clock], WP-8.23:
 * the entity has no default stamp), so this file adds no clock site; tests pass a fixed clock.
 */
class EventLogWriter(
    private val insert: suspend (EventLogEntity) -> Unit,
    private val deviceId: suspend () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { com.example.data.boot.BootGate.clock() },
) {
    /** Builds and inserts one event now; returns it. */
    suspend fun logEventNow(kind: String, fields: Map<String, Any?>): EventLogEntity {
        val base = EventLogEntity(timestamp = clock(), kind = kind)
        val dev = deviceId()
        val extras = LinkedHashMap<String, JsonElement>()
        for ((k, v) in fields) toJson(v)?.let { extras[k] = it }
        val e = base.copy(
            uid = EventSyncEngine.generateEventUid(dev, base.timestamp),
            dev = dev,
            payloadJson = if (extras.isEmpty()) null else CanonicalJson.canonicalJsonString(JsonObject(extras)),
        )
        insert(e)
        return e
    }

    /** Fire-and-forget, as Opti's `logEvent`. */
    fun logEvent(kind: String, fields: Map<String, Any?>) {
        scope.launch {
            try { logEventNow(kind, fields) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    val hlcSink: (String, Map<String, Any?>) -> Unit = { kind, payload -> logEvent(kind, payload) }

    val lifecycleSink: (String, String) -> Unit = { kind, detail -> logEvent(kind, mapOf("detail" to detail)) }

    private fun toJson(v: Any?): JsonElement? = when (v) {
        null -> null // JS undefined: the key is dropped
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int, is Long -> JsonPrimitive(v as Number)
        is Number -> CanonicalJson.jsonNumber(v.toDouble())
        else -> JsonPrimitive(v.toString())
    }
}

/**
 * WP-8.7 (PX-51) -- Opti's boot self-heal of a poisoned upload watermark (sync.js:3842-3930): a
 * watermark in the future (any amount) is reset to 0, so the next push re-scans everything
 * (the month rebuild is idempotent). A diag entry and the toast text are produced as in Opti.
 * [DEFERRED_OPEN_QUESTION] Opti also keeps the last 10 repairs in `questa.sync.watermarkNotes`
 * for a Settings note; that UI is not ported here (Settings is 8.14's).
 */
class EvtWatermarkRepair(
    private val w: EventWatermark,
    private val store: LocalEventStore,
    private val clock: () -> Long,
    private val diag: DiagRing,
) {
    /** Returns the toast text when a repair happened, else null. */
    suspend fun runIfFuture(): String? {
        val watermark = w.get()
        val now = clock()
        if (watermark <= now) return null
        val rows = store.inRangeStrict(Long.MIN_VALUE, Long.MAX_VALUE)
        w.lowerTo(0L)
        val data = LinkedHashMap<String, JsonElement?>()
        data["was"] = JsonPrimitive(watermark)
        data["now"] = JsonPrimitive(now)
        data["resetTo"] = JsonPrimitive(0)
        data["maxLocalTs"] = JsonPrimitive(rows?.maxOfOrNull { it.timestamp } ?: 0L)
        data["eventCount"] = JsonPrimitive(rows?.size ?: 0)
        if (rows == null) data["error"] = JsonPrimitive("read failed")
        diag.push("evtWatermarkRepaired", data)
        if (rows == null) return null // Opti's IDB-failure arms reset and diagnose, but do not toast
        return "Sync repaired: upload watermark was in the future (" + iso(watermark) + "). Reset to " + iso(0L) +
            ". Events will upload on next sync."
    }

    private fun iso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }
}
