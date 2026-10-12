package com.example.data.sync.merge

import com.example.data.sync.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.4 -- the JavaScript value semantics the ported Opti merge helpers depend on.
 *
 * sync.js reads every operand through `Number(x)`, `x || 0`, `!!x`, `typeof x === "number"`
 * and `Object.keys`, so the port has to reproduce those rules on a kotlinx [JsonElement]
 * exactly, including for malformed input (a string timestamp, a boolean, an array). A missing
 * key is modelled as Kotlin `null` (= JS `undefined`); [JsonNull] is JS `null`.
 */
internal object Js {

    /** JS `Number(v)`. May return NaN or +/-Infinity, exactly as JS does. */
    fun number(v: JsonElement?): Double = when (v) {
        null -> Double.NaN // undefined
        is JsonNull -> 0.0
        is JsonPrimitive -> when {
            v.isString -> stringToNumber(v.content)
            v.content == "true" -> 1.0
            v.content == "false" -> 0.0
            else -> v.content.toDoubleOrNull() ?: Double.NaN
        }
        // Number([]) === 0; Number([x]) === Number(String(x)); anything longer is NaN.
        is JsonArray -> when (v.size) {
            0 -> 0.0
            1 -> stringToNumber(toJsString(v[0]))
            else -> Double.NaN
        }
        is JsonObject -> Double.NaN // "[object Object]"
    }

    /** JS `Number(v) || 0` (sync.js `_num`): NaN and 0 both collapse to 0. */
    fun num(v: JsonElement?): Double = orZero(number(v))

    /** JS `x || 0` on an already-numeric value. */
    fun orZero(d: Double): Double = if (d.isNaN() || d == 0.0) 0.0 else d

    /** JS truthiness (`!!v`). */
    fun truthy(v: JsonElement?): Boolean = when (v) {
        null, is JsonNull -> false
        is JsonPrimitive -> when {
            v.isString -> v.content.isNotEmpty()
            v.content == "true" -> true
            v.content == "false" -> false
            else -> {
                val d = v.content.toDoubleOrNull() ?: Double.NaN
                !(d.isNaN() || d == 0.0)
            }
        }
        is JsonArray, is JsonObject -> true
    }

    /** JS `typeof v === "number"`. */
    fun isNumber(v: JsonElement?): Boolean =
        v is JsonPrimitive && v !is JsonNull && !v.isString && v.content != "true" && v.content != "false"

    /** JS `typeof v === "object"` for a truthy value (object or array). */
    fun isObjectLike(v: JsonElement?): Boolean = v is JsonObject || v is JsonArray

    /** JS `o[key]` on an object-like value; `undefined` (null) for primitives and misses. */
    fun get(o: JsonElement?, key: String): JsonElement? = when (o) {
        is JsonObject -> o[key]
        is JsonArray -> arrayIndexOf(key)?.let { if (it < o.size) o[it.toInt()] else null }
        else -> null
    }

    /** JS `Object.keys(o)` in JS property order (array-index keys first, ascending). */
    fun keys(o: JsonElement?): List<String> = when (o) {
        is JsonObject -> jsKeyOrder(o.keys)
        is JsonArray -> o.indices.map { it.toString() }
        else -> emptyList()
    }

    /** Orders keys the way a JS object enumerates them: integer-index keys ascending, then the rest in insertion order. */
    fun jsKeyOrder(keys: Collection<String>): List<String> {
        val idx = keys.mapNotNull { k -> arrayIndexOf(k)?.let { k to it } }.sortedBy { it.second }.map { it.first }
        if (idx.isEmpty()) return keys.toList()
        return idx + keys.filter { arrayIndexOf(it) == null }
    }

    /** A JS object literal built by assignment, enumerated in JS property order. */
    fun jsObject(entries: LinkedHashMap<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in jsKeyOrder(entries.keys)) out[k] = entries.getValue(k)
        return JsonObject(out)
    }

    /** A JSON number as JSON.stringify would write it; non-finite values become `null`. */
    fun jsonNum(d: Double): JsonElement =
        if (d.isNaN() || d.isInfinite()) JsonNull else CanonicalJson.jsonNumber(d)

    /**
     * sync.js `stableStringify` (:787-797): JSON.stringify of a clone whose object keys were
     * inserted in sorted order. JS then enumerates integer-index keys first, so those lead.
     */
    fun stableStringify(v: JsonElement?): String =
        if (v == null) "undefined" else CanonicalJson.canonicalJsonString(stableClone(v))

    /** sync.js `deepEqual` (:798). */
    fun deepEqual(a: JsonElement?, b: JsonElement?): Boolean = stableStringify(a) == stableStringify(b)

    private fun stableClone(v: JsonElement): JsonElement = when (v) {
        is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>()
            for (k in v.keys.sorted()) out[k] = stableClone(v.getValue(k))
            jsObject(out)
        }
        is JsonArray -> JsonArray(v.map { stableClone(it) })
        is JsonNull -> JsonNull
        is JsonPrimitive -> if (isNumber(v)) jsonNum(v.content.toDoubleOrNull() ?: Double.NaN) else v
    }

    /** JS canonical array index: "0" or a non-zero-led decimal < 2^32 - 1. */
    private fun arrayIndexOf(k: String): Long? {
        if (k.isEmpty() || k.length > 10) return null
        if (k != "0" && (k[0] == '0' || !k.all { it in '0'..'9' })) return null
        if (k == "0") return 0L
        val n = k.toLong()
        return if (n < 4294967295L) n else null
    }

    private fun toJsString(v: JsonElement): String = when (v) {
        is JsonNull -> ""
        is JsonPrimitive -> if (v.isString || !isNumber(v)) v.content else jsNumberText(v)
        is JsonArray -> v.joinToString(",") { toJsString(it) } // Array.prototype.toString
        is JsonObject -> "[object Object]"
    }

    private fun jsNumberText(v: JsonPrimitive): String {
        val d = v.content.toDoubleOrNull() ?: return "NaN"
        return if (d.isNaN() || d.isInfinite()) d.toString() else CanonicalJson.jsNumberToString(d)
    }

    // ---- WP-8.5 additions used by the merge core -------------------------------------------

    /** JS `String(v)`; a missing value (Kotlin null) is `"undefined"`. */
    fun jsString(v: JsonElement?): String = if (v == null) "undefined" else toJsString(v).let { if (v is JsonNull) "null" else it }

    /** JS `x || 0` kept as a JSON value: a falsy value becomes the number 0. */
    fun orZeroEl(v: JsonElement?): JsonElement = if (truthy(v)) v!! else ZERO

    /** JS `x || []` kept as a JSON value. */
    fun orEmptyArr(v: JsonElement?): JsonElement = if (truthy(v)) v!! else EMPTY_ARR

    /** The elements `[].concat(x || [])` contributes: an array spreads, a truthy scalar is one element. */
    fun concatArg(x: JsonElement?): List<JsonElement> = when {
        !truthy(x) -> emptyList()
        x is JsonArray -> x
        else -> listOf(x!!)
    }

    /**
     * `(x || [])` where the caller then calls an Array method on it: absent/falsy -> empty, an
     * array -> itself, anything else throws, as the JS TypeError ("x.map is not a function") does.
     */
    fun arrayOrThrow(x: JsonElement?, what: String): List<JsonElement> = when {
        !truthy(x) -> emptyList()
        x is JsonArray -> x
        else -> throw OptiMergeTypeError("$what is not an array")
    }

    /** JS `x.prop` where `x` null/undefined throws (TypeError), and any other primitive gives undefined. */
    fun getOrThrow(o: JsonElement?, key: String): JsonElement? {
        if (o == null || o is JsonNull) throw OptiMergeTypeError("Cannot read properties of ${if (o == null) "undefined" else "null"} (reading '$key')")
        return get(o, key)
    }

    /** JS `a > b` for two JSON values (both strings compare by UTF-16 units, otherwise ToNumber; NaN is false). */
    fun gt(a: JsonElement?, b: JsonElement?): Boolean =
        if (isStr(a) && isStr(b)) (a as JsonPrimitive).content > (b as JsonPrimitive).content
        else number(a) > number(b)

    /** JS `a >= b` (see [gt]). */
    fun ge(a: JsonElement?, b: JsonElement?): Boolean =
        if (isStr(a) && isStr(b)) (a as JsonPrimitive).content >= (b as JsonPrimitive).content
        else number(a) >= number(b)

    /** JS `a === b` for JSON values (undefined is Kotlin null; numbers compare by value). */
    fun strictEq(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
        isNumber(a) && isNumber(b) -> number(a) == number(b)
        a is JsonPrimitive && b is JsonPrimitive -> a.isString == b.isString && a.content == b.content
        else -> a === b // objects: identity
    }

    /** JS `Math.max(a, b)`: NaN if either operand is NaN. */
    fun max(a: Double, b: Double): Double = if (a.isNaN() || b.isNaN()) Double.NaN else maxOf(a, b)

    /** JS `String.prototype.trim` (WhiteSpace + LineTerminator). */
    fun jsTrim(s: String): String = s.trim { it in JS_WS }

    private val FLOAT_PREFIX = Regex("^[+-]?(?:Infinity|(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")

    /** JS global `parseFloat`: skip leading whitespace, read the longest decimal prefix, else NaN. */
    fun parseFloat(s: String): Double {
        val m = FLOAT_PREFIX.find(s.trimStart { it in JS_WS }) ?: return Double.NaN
        return m.value.toDouble()
    }

    /** The own enumerable entries `Object.assign` copies from a source, in JS order. */
    fun ownEntries(e: JsonElement?): List<Pair<String, JsonElement>> = when (e) {
        is JsonObject -> jsKeyOrder(e.keys).map { it to e.getValue(it) }
        is JsonArray -> e.mapIndexed { i, v -> i.toString() to v }
        is JsonPrimitive -> if (e.isString) e.content.mapIndexed { i, c -> i.toString() to JsonPrimitive(c.toString()) } else emptyList()
        else -> emptyList()
    }

    /**
     * `Object.assign({}, src, {k1: v1, ...})`. A null value models `undefined`: JSON.stringify
     * drops such a key, so it is removed here.
     */
    fun assign(src: JsonElement?, vararg kv: Pair<String, JsonElement?>): JsonObject {
        val out = LinkedHashMap<String, JsonElement?>()
        for ((k, v) in ownEntries(src)) out[k] = v
        for ((k, v) in kv) out[k] = v
        val clean = LinkedHashMap<String, JsonElement>()
        for ((k, v) in out) if (v != null) clean[k] = v
        return jsObject(clean)
    }

    /**
     * A key with JS `Map` (SameValueZero) identity for a JSON value: strings and numbers are
     * distinct, numbers compare by value, objects by identity, undefined is [UNDEFINED_KEY].
     */
    fun mapKey(e: JsonElement?): Any = when {
        e == null -> UNDEFINED_KEY
        e is JsonNull -> JsonNull
        e is JsonPrimitive && e.isString -> "s:" + e.content
        isNumber(e) -> number(e).let { if (it == 0.0) 0.0 else it } // -0 === +0
        e is JsonPrimitive -> "b:" + e.content
        else -> IdentityKey(e)
    }

    private object UNDEFINED_KEY
    private class IdentityKey(val e: JsonElement) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.e === e
        override fun hashCode(): Int = System.identityHashCode(e)
    }

    private fun isStr(v: JsonElement?): Boolean = v is JsonPrimitive && v !is JsonNull && v.isString

    private val ZERO = JsonPrimitive(0)
    private val EMPTY_ARR = JsonArray(emptyList())

    private val JS_WS: Set<Char> = setOf(
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', '　', '﻿',
    )

    private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)$")

    /** ECMA-262 StringToNumber. */
    fun stringToNumber(raw: String): Double {
        val s = raw.trim { it.isWhitespace() || it == '﻿' }
        if (s.isEmpty()) return 0.0
        when (s) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (s.length > 2 && s[0] == '0') {
            val radix = when (s[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> 0 }
            if (radix != 0) {
                val body = s.substring(2)
                return if (body.all { Character.digit(it, radix) >= 0 }) {
                    java.math.BigInteger(body, radix).toDouble()
                } else Double.NaN
            }
        }
        return if (DECIMAL.matches(s)) s.toDouble() else Double.NaN
    }
}
