package com.example.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject

/**
 * `JSON.stringify` for a kotlinx [JsonElement] tree, byte for byte.
 *
 * Stage 2 / T2.2 introduced this writer because `Json.encodeToString` re-renders numbers through
 * the JVM's `Double.toString()`. WP-8.3 (PX-22, SY-40) makes it a full port of the ECMA-262
 * `SerializeJSONObject` / `QuoteJSONString` rules that decide the bytes:
 *
 *  1. Key order is the JS own-property order: array-index keys ("0", "1", ... up to 2^32-2, in
 *     canonical form) first, ascending by number, then every other key in insertion order.
 *     A kotlinx [JsonObject] only keeps insertion order, so the index keys are moved here.
 *  2. A lone UTF-16 surrogate is written as a lowercase `\udxxx` escape (ES2019 well-formed
 *     `JSON.stringify`). A valid surrogate pair is written raw.
 *  3. Every non-string primitive that is a number is re-rendered with [JsNumber.format], exactly as
 *     JS would after `JSON.parse`: `1.0` -> `1`, `1E21` -> `1e+21`, `9007199254740993` ->
 *     `9007199254740992`, `-0` -> `0`. NaN and Infinity are written as `null`.
 *
 * Serializer mapping (WP-8.3 card): the upload body, the stored base and the backup hash
 * pre-image use [stringify]; the exported backup file uses [stringifyPretty]
 * (`JSON.stringify(x, null, 2)`); equality uses [StableJson].
 */
object CanonicalJson {

    // ---------------------------------------------------------------------
    // Numbers
    // ---------------------------------------------------------------------

    /** Kept for existing callers; see [JsNumber.format]. */
    fun jsNumberToString(d: Double): String = JsNumber.format(d)

    /**
     * A number primitive whose content is already the JS text. Built as a [JsonUnquotedLiteral] so
     * it stays unquoted. NaN/Infinity become [JsonNull], as `JSON.stringify` does.
     */
    fun jsonNumber(d: Double): JsonPrimitive =
        if (d.isNaN() || d.isInfinite()) JsonNull else JsonUnquotedLiteral(JsNumber.format(d))

    /** Renders a non-string primitive's content as JS would (see class doc, rule 3). */
    internal fun literalText(content: String): String {
        if (content == "true" || content == "false" || content == "null") return content
        if (isPlainSafeInteger(content)) return if (content == "-0") "0" else content
        val d = content.toDoubleOrNull() ?: return content
        if (d.isNaN() || d.isInfinite()) return "null"
        return JsNumber.format(d)
    }

    /** `-?(0|[1-9][0-9]{0,14})`: exactly representable, and already in JS form. */
    private fun isPlainSafeInteger(s: String): Boolean {
        val start = if (s.startsWith("-")) 1 else 0
        val len = s.length - start
        if (len < 1 || len > 15) return false
        if (s[start] == '0' && len > 1) return false
        for (i in start until s.length) if (s[i] !in '0'..'9') return false
        return true
    }

    // ---------------------------------------------------------------------
    // Key order
    // ---------------------------------------------------------------------

    /** ECMA-262 array index: canonical numeric string of an integer in 0 .. 2^32-2. */
    fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10) return false
        if (key[0] == '0') return key.length == 1
        for (c in key) if (c !in '0'..'9') return false
        return key.toLong() <= 4294967294L
    }

    /** The order `Object.keys` / `for..in` / `JSON.stringify` visit [keys] in (rule 1). */
    fun jsKeyOrder(keys: Collection<String>): List<String> {
        var anyIndex = false
        for (k in keys) if (isArrayIndex(k)) { anyIndex = true; break }
        if (!anyIndex) return keys.toList()
        val index = keys.filter { isArrayIndex(it) }.sortedBy { it.toLong() }
        return index + keys.filter { !isArrayIndex(it) }
    }

    /** Key order after `stableClone` (sorted by UTF-16 code units) then `JSON.stringify`. */
    internal fun stableKeyOrder(keys: Collection<String>): List<String> = jsKeyOrder(keys.sorted())

    // ---------------------------------------------------------------------
    // Writer
    // ---------------------------------------------------------------------

    private val ESCAPE_STRINGS: Array<String?> = arrayOfNulls<String>(93).also { arr ->
        val hex = "0123456789abcdef"
        for (c in 0..0x1f) {
            arr[c] = "\\u" +
                hex[(c shr 12) and 0xf] + hex[(c shr 8) and 0xf] + hex[(c shr 4) and 0xf] + hex[c and 0xf]
        }
        arr['"'.code] = "\\\""
        arr['\\'.code] = "\\\\"
        arr['\t'.code] = "\\t"
        arr['\b'.code] = "\\b"
        arr['\n'.code] = "\\n"
        arr['\r'.code] = "\\r"
        arr[0x0c] = "\\f"
    }

    private fun appendUnicodeEscape(sb: StringBuilder, c: Char) {
        val hex = "0123456789abcdef"
        val v = c.code
        sb.append("\\u").append(hex[(v shr 12) and 0xf]).append(hex[(v shr 8) and 0xf])
            .append(hex[(v shr 4) and 0xf]).append(hex[v and 0xf])
    }

    internal fun writeQuotedString(s: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            val c = ch.code
            when {
                c < ESCAPE_STRINGS.size && ESCAPE_STRINGS[c] != null -> sb.append(ESCAPE_STRINGS[c])
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(ch).append(s[i + 1])
                        i++
                    } else {
                        appendUnicodeEscape(sb, ch)
                    }
                }
                Character.isLowSurrogate(ch) -> appendUnicodeEscape(sb, ch)
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    private class Writer(val pretty: Boolean, val stable: Boolean) {
        val sb = StringBuilder()

        fun indent(depth: Int) { repeat(depth) { sb.append("  ") } }

        fun element(e: JsonElement, depth: Int) {
            when (e) {
                is JsonObject -> obj(e, depth)
                is JsonArray -> arr(e, depth)
                is JsonPrimitive -> if (e.isString) writeQuotedString(e.content, sb) else sb.append(literalText(e.content))
            }
        }

        fun obj(o: JsonObject, depth: Int) {
            if (o.isEmpty()) { sb.append("{}"); return }
            val keys = if (stable) stableKeyOrder(o.keys) else jsKeyOrder(o.keys)
            sb.append('{')
            if (pretty) sb.append('\n')
            var first = true
            for (k in keys) {
                if (!first) { sb.append(','); if (pretty) sb.append('\n') }
                first = false
                if (pretty) indent(depth + 1)
                writeQuotedString(k, sb)
                sb.append(':')
                if (pretty) sb.append(' ')
                element(o.getValue(k), depth + 1)
            }
            if (pretty) { sb.append('\n'); indent(depth) }
            sb.append('}')
        }

        fun arr(a: JsonArray, depth: Int) {
            if (a.isEmpty()) { sb.append("[]"); return }
            sb.append('[')
            if (pretty) sb.append('\n')
            var first = true
            for (v in a) {
                if (!first) { sb.append(','); if (pretty) sb.append('\n') }
                first = false
                if (pretty) indent(depth + 1)
                element(v, depth + 1)
            }
            if (pretty) { sb.append('\n'); indent(depth) }
            sb.append(']')
        }
    }

    /** `JSON.stringify(e)`. */
    fun stringify(e: JsonElement): String = Writer(pretty = false, stable = false).also { it.element(e, 0) }.sb.toString()

    /** `JSON.stringify(e, null, 2)`. */
    fun stringifyPretty(e: JsonElement): String = Writer(pretty = true, stable = false).also { it.element(e, 0) }.sb.toString()

    /** `JSON.stringify(stableClone(e))`; see [StableJson.stableStringify]. */
    internal fun stringifyStable(e: JsonElement): String = Writer(pretty = false, stable = true).also { it.element(e, 0) }.sb.toString()

    /** Legacy name, kept for existing callers: [stringify], or [stringifyPretty] when [prettyPrint]. */
    fun canonicalJsonString(element: JsonElement, prettyPrint: Boolean = false): String =
        if (prettyPrint) stringifyPretty(element) else stringify(element)

    /**
     * The backup hash pre-image as Opti's `buildBackupFile` builds it (app.js:7416-7449):
     * `JSON.stringify(Object.assign({}, S, {events}))` with `_backup` (hashAlgo included, hash
     * not) appended last.
     */
    fun buildHashPreimage(
        snapshotWithoutEvents: JsonObject,
        events: List<JsonObject>,
        hashlessBackupMeta: JsonObject
    ): String {
        val reconstituted = buildJsonObject {
            // put() on an existing key keeps its position, like Object.assign / `b._backup =`.
            for ((k, v) in snapshotWithoutEvents) put(k, v)
            put("events", JsonArray(events))
            put("_backup", hashlessBackupMeta)
        }
        return stringify(reconstituted)
    }
}
