package com.example.data.sync.merge

import kotlinx.serialization.json.JsonObject

/**
 * WP-8.4 -- sync.js char arbitration, ported exactly (plan D-row O2: the K4 tiebreak follows
 * current Opti).
 *
 * The caller composes it as sync.js :2201-2321 does:
 * ```
 * when (oneSided(b, l, r)) { BASE -> b; LOCAL -> l; REMOTE -> r
 *   BOTH_CHANGED -> { val acc = CharAccumulate.accumulate(b, l, r, localDeviceId, remoteDeviceId) // RAW (:2248)
 *                     val w = if (bothChanged(l, r, ld, rd, ceil) == REMOTE) r else l         // RESOLVED
 *                     if (acc != null) CharAccumulate.overlay(w, acc) else w } }
 * ```
 * `b`, `l`, `r` are `base.char || {}`, `local.char || {}`, `remote.char || {}`.
 *
 * The two device-id pairs DIFFER. `localDeviceId`/`remoteDeviceId` are merge()'s raw
 * parameters (`remoteDeviceId = remote.deviceId || null`, sync.js :2453, :2537), so a remote
 * with no deviceId gets NO accumulation. `ld`/`rd` are sync.js `_ld`/`_rd` (:2283-2286):
 * `_ld = localDeviceId ?? syncDeviceId()`, `_rd = remoteDeviceId ?? the first
 * remote.devices id that is not _ld ?? null`.
 */
object CharTiebreak {

    /**
     * sync.js :2222-2240: the F5 stale-default guard (an UNTOUCHED default character always
     * yields to a real one), the changed-since-base test, and the one-sided recency guard.
     * The one-sided guard reads RAW `updatedAt` ("Site 4 of 4 -- never clamp"): clamping would
     * discard remote's only real edit.
     *
     * @return [Side.BASE], [Side.LOCAL] or [Side.REMOTE]; [Side.BOTH_CHANGED] when both sides
     *   changed, in which case the caller must use [bothChanged] (and [CharAccumulate]).
     */
    fun oneSided(base: JsonObject, local: JsonObject, remote: JsonObject): Side {
        val lU = untouched(local)
        val rU = untouched(remote)
        if (lU && !rU) return Side.REMOTE
        if (rU && !lU) return Side.LOCAL
        val localChanged = !Js.deepEqual(local, base)
        val remoteChanged = !Js.deepEqual(remote, base)
        if (!localChanged && !remoteChanged) return Side.BASE
        if (localChanged && !remoteChanged) return Side.LOCAL
        if (!localChanged && remoteChanged) {
            // GUARD (recency): accept remote only if it is not OLDER than local. Raw operands.
            return if (Js.num(remote["updatedAt"]) >= Js.num(local["updatedAt"])) Side.REMOTE else Side.LOCAL
        }
        return Side.BOTH_CHANGED
    }

    /**
     * sync.js :2266-2320 (K4): clamp each side ONCE; remote wins only if strictly newer; the
     * device-id tiebreak (higher id string wins, on both devices) fires ONLY on an exact
     * clamped tie with a usable, distinct remote id; otherwise local (strictly newer, or the F2
     * local bias on an unresolved tie).
     *
     * @param lDev sync.js `_ld` (merge's localDeviceId, else syncDeviceId()).
     * @param rDev sync.js `_rd` (merge's remoteDeviceId, else the first remote.devices id that
     *   is not `_ld`, else null). Resolving that fallback is the merge core's job.
     */
    fun bothChanged(l: JsonObject, r: JsonObject, lDev: String, rDev: String?, futureCeil: Long): Side {
        val lUa = ClampPolicy.clampFutureD(l["updatedAt"], futureCeil)
        val rUa = ClampPolicy.clampFutureD(r["updatedAt"], futureCeil)
        if (rUa > lUa) return Side.REMOTE
        if (lUa == rUa && rDev != null && lDev != rDev) return if (rDev > lDev) Side.REMOTE else Side.LOCAL
        return Side.LOCAL
    }

    /** sync.js :2222-2223 `untouched`. */
    private fun untouched(c: JsonObject): Boolean =
        Js.num(c["lvl"]) <= 1 && Js.num(c["xp"]) <= 0 && Js.num(c["gold"]) <= 0 &&
            (Js.num(c["maxHp"]).takeIf { it != 0.0 } ?: 50.0) <= 50
}
