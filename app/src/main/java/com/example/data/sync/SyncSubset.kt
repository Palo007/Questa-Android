package com.example.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * WP-8.3: port of Opti `syncSubset()` (sync.js:806-824), the state object uploaded as
 * `/state.json`. Serialize the result with [CanonicalJson.stringify] (upload body and base are
 * one frozen string); compare with [StableJson.deepEqual].
 */
object SyncSubset {

    val KEY_ORDER = listOf(
        "char", "tasks", "rewards", "tags", "devices", "lastCron",
        "history", "charHistory", "an", "pause", "monthlyBackups", "deletions"
    )

    fun build(s: LocalWireState): JsonObject = buildJsonObject {
        put("char", s.char)
        put("tasks", s.tasks)
        put("rewards", s.rewards)
        put("tags", s.tags)
        put("devices", cleanDevices(s.devices))
        put("lastCron", JsonPrimitive(s.lastCron))
        put("history", s.history)
        put("charHistory", s.charHistory)
        put("an", buildJsonObject {
            put("views", s.anViews)
            put("metrics", s.anMetrics)
        })
        put("pause", normalizePause(s.pause))
        put("monthlyBackups", s.monthlyBackups)
        put("deletions", s.deletions)
    }

    /** sync.js:808, applied to an already-built pause object (idempotent). */
    fun normalizePause(p: JsonObject): JsonObject = buildJsonObject {
        put("paused", JsonPrimitive(jsTruthy(p["paused"])))
        put("pausedDays", p["pausedDays"] as? JsonArray ?: JsonArray(emptyList()))
        val at = p["at"]
        put("at", if (at != null && jsTruthy(at)) at else JsonPrimitive(0))
    }

    /**
     * Port of `cleanDevices(arr)` (sync.js:1571-1593): drop entries without a truthy `id`; for a
     * duplicate id keep the higher score, where a blank name with `updatedAt` 0 is junk (-1) and
     * otherwise score = `updatedAt` + 0.5 for a non-blank name. The first occurrence keeps its
     * position (JS Map insertion order). The skew diagnostic note is not ported (telemetry only).
     */
    fun cleanDevices(arr: JsonArray): JsonArray {
        val byId = LinkedHashMap<JsonElement, JsonObject>()
        for (el in arr) {
            val d = el as? JsonObject ?: continue
            val id = d["id"]
            if (id == null || !jsTruthy(id)) continue
            val prev = byId[id]
            if (prev == null) { byId[id] = d; continue }
            if (score(d) > score(prev)) byId[id] = d
        }
        return JsonArray(byId.values.toList())
    }

    private fun nameOf(x: JsonObject): String {
        val n = x["name"] as? JsonPrimitive ?: return ""
        return if (n.isString) n.content.trim() else ""
    }

    private fun updatedAtNum(x: JsonObject): Double {
        // Opti stamps updatedAt as a number; a non-number is treated as absent (0).
        val p = x["updatedAt"] as? JsonPrimitive ?: return 0.0
        if (p is JsonNull || p.isString) return 0.0
        return p.doubleOrNull?.takeIf { !it.isNaN() } ?: 0.0
    }

    private fun score(x: JsonObject): Double {
        val name = nameOf(x)
        val ua = updatedAtNum(x)
        val junk = name.isEmpty() && ua == 0.0
        return if (junk) -1.0 else ua + (if (name.isNotEmpty()) 0.5 else 0.0)
    }

    /** JS truthiness of a JSON value (absent = undefined = false). */
    internal fun jsTruthy(e: JsonElement?): Boolean = when (e) {
        null, JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            e.isString -> e.content.isNotEmpty()
            else -> e.booleanOrNull ?: e.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
        }
    }
}

/** Opti `wrap(subset)` (sync.js:2326-2328). [savedAtPhysical] is the physical clock, not HLC (PX-26). */
fun wrap(state: JsonObject, deviceId: String, savedAtPhysical: Long): JsonObject = buildJsonObject {
    put("schema", JsonPrimitive(1))
    put("savedAt", JsonPrimitive(savedAtPhysical))
    put("deviceId", JsonPrimitive(deviceId))
    put("state", state)
}
