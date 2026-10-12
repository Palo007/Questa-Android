package com.example.data.sync

import kotlinx.serialization.json.JsonElement

/**
 * WP-8.3 (PX-21, SY-14): Opti's key-order-independent equality, sync.js:787-798:
 * ```
 * function stableClone(o){ ... Object.keys(o).sort().forEach(k => out[k] = stableClone(o[k])) ... }
 * function stableStringify(o){ return JSON.stringify(stableClone(o)); }
 * function deepEqual(a, b){ return stableStringify(a) === stableStringify(b); }
 * ```
 * The sorted clone is itself serialized by `JSON.stringify`, so array-index keys still come first
 * (ascending by number) and every other key follows in UTF-16 code-unit order. Numbers and strings
 * follow [CanonicalJson]'s rules.
 *
 * Every equality check on sync data (base self-check, nothing-to-push, merge change detection)
 * uses [deepEqual], never `toString()` or [CanonicalJson.stringify].
 */
object StableJson {

    fun stableStringify(e: JsonElement): String = CanonicalJson.stringifyStable(e)

    /**
     * `deepEqual(a, b)`. A Kotlin `null` is JS `undefined`: `stableStringify(undefined)` is
     * `undefined`, so `null == null` is true and `null` vs [kotlinx.serialization.json.JsonNull]
     * is false.
     */
    fun deepEqual(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a === b) return true
        return stableStringify(a) == stableStringify(b)
    }
}
