package com.example.data.local.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * WP-8.2 (Wave 1) -- the PWA `quickLog` per-task launcher-shortcut mode, ported exactly from
 * Opti's `quickLogMode(t)` (app.js:2408-2414, commit 7d5d4f3):
 * ```
 * function quickLogMode(t){
 *   var q=t&&t.quickLog;
 *   if(!q) return 'off';
 *   return (q==='down'||q==='both')?q:'up';
 * }
 * ```
 * The wire value of `t.quickLog` is `false | true | 'down' | 'both'` (Opti never writes the
 * string `'up'`: a plain truthy `true` predates the 2026-09-24 'down'/'both' addition and still
 * means "up", per the comment directly above `quickLogMode` in app.js). [fromWire] reproduces the
 * exact JS-truthiness branch (`!q`) plus the `down`/`both` special case, so every historical shape
 * -- `undefined`, `false`, `true`, `'down'`, `'both'`, or any other truthy JS value -- maps to the
 * same `off`/`up`/`down`/`both` Opti itself would compute.
 *
 * [SerialName] values match Opti's own `'off'|'up'|'down'|'both'` strings (`quickLogDirs`,
 * app.js:2415-2421, and the Android shortcut wiring, app.js:6006-6010, both read the *derived*
 * mode string, not the raw `t.quickLog` wire value) so any future kotlinx.serialization encode of
 * this enum round-trips against Opti-compatible tooling.
 */
@Serializable
enum class QuickLogMode {
    @SerialName("off") OFF,
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("both") BOTH;

    companion object {
        /**
         * Ports `quickLogMode(t)` for the raw `t.quickLog` JSON element (or `null`/absent, which
         * is JS `undefined` -- falsy, same as `false`). Only call this when the caller has already
         * decided the key is worth mapping; MIGRATION_6_7 keeps `TaskEntity.quickLog` at Room
         * `NULL` ("absent", not merely "off") when `extraJson` never carried a `quickLog` key at
         * all -- see the §5 card's "(+) null = absent" rule and AppDatabase.kt's backfill comment.
         */
        fun fromWire(element: JsonElement?): QuickLogMode {
            if (element == null || element is JsonNull) return OFF // JS: q undefined -> !q -> off
            val prim = element as? JsonPrimitive ?: return UP // a non-primitive is JS-truthy -> up
            if (prim.isString) {
                // JS empty string is falsy; any other string is truthy, and only 'down'/'both'
                // take the special branch -- everything else (including a genuinely raw 'up',
                // which Opti itself never writes but must still round-trip if seen) means up.
                if (prim.content.isEmpty()) return OFF
                return when (prim.content) {
                    "down" -> DOWN
                    "both" -> BOTH
                    else -> UP
                }
            }
            // Non-string primitive: boolean or number. JS falsy: false, 0, NaN.
            prim.booleanOrNull?.let { return if (it) UP else OFF }
            val d = prim.doubleOrNull
            if (d != null) return if (d == 0.0 || d.isNaN()) OFF else UP
            return UP
        }
    }
}
