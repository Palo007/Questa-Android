package com.example.data.sync.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.4 -- sync.js `_mergeInner` `pause` (:2178-2191), ported exactly.
 *
 * - winner: LWW by the CLAMPED `at` (base ignored), tie -> local (F1, 2026-08-18);
 * - `pausedDays`: union of base + local + remote, finite numbers only, de-duplicated,
 *   ascending, last 7;
 * - `at`: `Math.max` of the RAW local/remote values (never clamped, so never deleted).
 *
 * Arguments are the three snapshots' `pause` members; `null` stands for an absent (or
 * non-object) member, i.e. sync.js's `base.pause || {}`. Opti always yields an object, so this
 * never returns null; the nullable return type is the WP-8.4 contract's.
 */
object PauseMerge {
    fun merge(base: JsonObject?, local: JsonObject?, remote: JsonObject?, futureCeil: Long): JsonObject? {
        val baseP = base ?: EMPTY
        val localP = local ?: EMPTY
        val remoteP = remote ?: EMPTY
        val lAt = Js.num(localP["at"])
        val rAt = Js.num(remoteP["at"])
        val lAtc = ClampPolicy.clampD(lAt, futureCeil)
        val rAtc = ClampPolicy.clampD(rAt, futureCeil)
        val src = if (lAtc >= rAtc) localP else remoteP

        // [].concat(a || [], b || [], c || []).filter(v => typeof v === "number" && isFinite(v))
        val union = ArrayList<Double>()
        for (p in listOf(baseP, localP, remoteP)) {
            for (v in concatArg(p["pausedDays"])) {
                if (!Js.isNumber(v)) continue
                val d = (v as JsonPrimitive).content.toDoubleOrNull() ?: continue
                if (d.isNaN() || d.isInfinite()) continue
                union += d
            }
        }
        // Array.from(new Set(...)) (SameValueZero: 0 === -0) .sort((a, b) => a - b).slice(-7)
        val seen = LinkedHashSet<Double>()
        for (d in union) seen += if (d == 0.0) 0.0 else d
        val days = seen.sorted().takeLast(7)

        val out = LinkedHashMap<String, JsonElement>()
        out["paused"] = JsonPrimitive(Js.truthy(src["paused"]))
        out["pausedDays"] = JsonArray(days.map { Js.jsonNum(it) })
        out["at"] = Js.jsonNum(maxOf(lAt, rAt))
        return JsonObject(out)
    }

    private val EMPTY = JsonObject(emptyMap())

    /** The elements `[].concat(x || [])` contributes: an array spreads, a truthy scalar is one element. */
    private fun concatArg(x: JsonElement?): List<JsonElement> = when {
        !Js.truthy(x) -> emptyList()
        x is JsonArray -> x
        else -> listOf(x!!)
    }
}
