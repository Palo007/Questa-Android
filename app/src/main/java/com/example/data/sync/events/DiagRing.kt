package com.example.data.sync.events

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.7 (PX-57, SY-58) -- Opti's `_qDiagPush` ring (app.js:4-11): an in-memory list of the last
 * [max] entries, each `Object.assign({t: Date.now(), kind}, data)`, oldest evicted first. It is
 * not persisted and not synced (Opti keeps it in `window.__qDiag`, read by the diagnostic export).
 * Same kinds as Opti: evtPullSkip (reason), evtPullOk, evtFullScan, evtPullGuardReadFailed,
 * evtPullInsertFailed, evtPushShrinkBlocked, evtWatermarkRepaired.
 */
class DiagRing(private val clock: () -> Long, val max: Int = 50) {
    private val entries = ArrayDeque<JsonObject>()

    @Synchronized
    fun push(kind: String, data: Map<String, JsonElement?> = emptyMap()) {
        val o = LinkedHashMap<String, JsonElement>()
        o["t"] = JsonPrimitive(clock())
        o["kind"] = JsonPrimitive(kind)
        // JSON.stringify drops undefined; a Kotlin null here is JS undefined.
        for ((k, v) in data) if (v != null) o[k] = v
        entries.addLast(JsonObject(o))
        while (entries.size > max) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<JsonObject> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** The process ring (Opti: one per page). */
        val process: DiagRing = DiagRing(com.example.data.sync.SyncClocks.transport)

        fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
        fun num(n: Number): JsonElement = JsonPrimitive(n)
    }
}
