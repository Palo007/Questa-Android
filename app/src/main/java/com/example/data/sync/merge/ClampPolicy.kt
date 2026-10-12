package com.example.data.sync.merge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * WP-8.4 -- sync.js clamp helpers (:1212-1245), ported exactly.
 *
 * Polarity rule (sync.js :1218-1245, PX-35): the CLAMPED helpers ([ua], [ca], [clampFuture])
 * are for arbitration sites only, where a clamped 0 merely LOSES a tiebreak. The inverted-
 * polarity sites -- where 0 would DESTROY data (tombstone overlay, GUARD 1, GUARD 3, the
 * one-sided char guard, mergeChecklist's survivor test, cleanDevices/mergeDevices, and every
 * abs/cAbs watermark) -- read [uaRaw] or raw values and must never call the clamped twins.
 *
 * The ceiling is [MergeContext.futureCeil]: PHYSICAL now + [MAX_FUTURE_SKEW_MS], fixed once
 * per round (sync.js `_skewDiagReset`, :1287), never derived from the HLC.
 */
object ClampPolicy {
    /** sync.js MAX_FUTURE_SKEW_MS (:43): 120 s. */
    const val MAX_FUTURE_SKEW_MS: Long = 120_000L

    /** `_uaRaw` (:1232): `Number(updatedAt) || Number(createdAt) || 0`, never clamped. */
    fun uaRaw(o: JsonObject): Long = toLong(uaRawD(o))

    /** `_ua` (:1225): [uaRaw] clamped to 0 when beyond the round ceiling. */
    fun ua(o: JsonObject, c: MergeContext): Long = toLong(clampD(uaRawD(o), c.futureCeil))

    /** `_ca` (:1226): `Number(createdAt) || 0`, clamped. */
    fun ca(o: JsonObject, c: MergeContext): Long = toLong(clampD(Js.num(o["createdAt"]), c.futureCeil))

    /** `_clampFuture` (:1245): arbitration-only clamp of a raw scalar. */
    fun clampFuture(v: Long, c: MergeContext): Long = if (v > c.futureCeil) 0L else v

    // ---- exact-double twins used by the ported helpers (JS compares doubles) ----

    internal fun uaRawD(o: JsonElement?): Double {
        if (o !is JsonObject) return 0.0
        val u = Js.num(o["updatedAt"])
        return if (u != 0.0) u else Js.num(o["createdAt"])
    }

    /** `_clampFuture` over `Number(v) || 0` with an explicit ceiling. */
    internal fun clampFutureD(v: JsonElement?, futureCeil: Long): Double = clampD(Js.num(v), futureCeil)

    internal fun clampD(v: Double, futureCeil: Long): Double = if (v > futureCeil.toDouble()) 0.0 else v

    private fun toLong(d: Double): Long = when {
        d.isNaN() -> 0L
        d >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        d <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> d.toLong()
    }
}
