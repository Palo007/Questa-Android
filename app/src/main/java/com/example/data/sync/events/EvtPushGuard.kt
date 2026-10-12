package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.merge.Js
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/** One month's shrink-guard verdict. */
sealed interface PushDecision {
    object Upload : PushDecision { override fun toString() = "Upload" }

    /**
     * The push of this month is refused. [known] is the stored `count` as Opti holds it (any JSON
     * value, null = absent); [knownHash] the stored `hash` (null = absent).
     */
    data class Blocked(val local: Int, val known: JsonElement?, val localHash: String, val knownHash: JsonElement?) : PushDecision
}

/**
 * WP-8.7 (PX-50, SY-32) -- the event push shrink guard, sync.js:3153-3212 and :3338-3383, plus
 * the `evtFileCounts` bounds at :3401-3442.
 *
 * A stored count is `{count, hash, uids}` (uids a plain ARRAY, F8), kept as raw JSON so an entry
 * written by an older build (no `uids`, or a non-number count) is compared exactly as JS would.
 */
object EvtPushGuard {
    const val EVT_FILE_COUNTS_MAX = 240

    /**
     * `uidHash`: SHA-256 of the sorted, truthy uids joined by ",", first 16 hex chars. The JVM
     * always has SHA-256, so Opti's 32-bit `fallback-` branch is never produced here (it is still
     * refused on the known side, see [uidsAreSuperset]).
     */
    fun uidHash(uids: List<String?>): String {
        val sorted = uids.filter { !it.isNullOrEmpty() }.map { it!! }.sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(sorted.joinToString(",").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * `uidsAreSuperset(localRecs, knownHash, knownUids, localHash)`: the known uid ARRAY, when
     * non-empty, decides alone; otherwise both hashes must be strings, neither a `fallback-` hash,
     * and equal.
     */
    fun uidsAreSuperset(localUids: List<String?>, knownHash: JsonElement?, knownUids: JsonElement?, localHash: String?): Boolean {
        val local = localUids.filter { !it.isNullOrEmpty() }.toSet()
        if (knownUids is JsonArray && knownUids.isNotEmpty()) {
            for (u in knownUids) {
                val s = if (u is JsonPrimitive && u !is JsonNull && u.isString) u.content else null
                if (s == null || !local.contains(s)) return false
            }
            return true
        }
        val kh = (knownHash as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: return false
        if (localHash == null) return false
        if (localHash.take(9) == "fallback-" || kh.take(9) == "fallback-") return false
        return localHash == kh
    }

    /**
     * The per-month verdict (sync.js:3338-3383). [known] is `fileCounts[fname]` (JS `!= null`,
     * so JSON null counts as absent); [force] is the Force Push Events override.
     */
    fun decide(
        monthFrom: Long,
        known: JsonElement?,
        recs: List<EventLogEntity>,
        nowMs: Long,
        ageLimitMs: Long,
        force: Boolean,
    ): PushDecision {
        if (known == null || known is JsonNull || force) return PushDecision.Upload
        val localCount = recs.size
        val knownCount: JsonElement? = Js.get(known, "count")
        val knownCountNum = Js.number(knownCount) // undefined -> NaN: every comparison is false, as in JS
        val uids = recs.map { it.uid }
        val localHash = uidHash(uids)
        val isSuperset = uidsAreSuperset(uids, Js.get(known, "hash"), Js.get(known, "uids"), localHash)
        val shrinks = localCount < knownCountNum
        var legitimate = false
        if (shrinks) {
            val cutoff = nowMs - ageLimitMs
            val localMinTs = recs.minOf { it.timestamp }
            if (localMinTs >= cutoff && monthFrom < cutoff) legitimate = true
        }
        if (shrinks && !isSuperset && !legitimate) {
            return PushDecision.Blocked(localCount, knownCount, localHash, Js.get(known, "hash"))
        }
        return PushDecision.Upload
    }

    /** The stored entry after a successful month upload: `{count, hash, uids}`. */
    fun fileCountEntry(recs: List<EventLogEntity>): JsonObject {
        val uids = recs.map { it.uid }
        return buildJsonObject {
            put("count", JsonPrimitive(recs.size))
            put("hash", JsonPrimitive(uidHash(uids)))
            put("uids", JsonArray(uids.filter { !it.isNullOrEmpty() }.map { JsonPrimitive(it) }))
        }
    }

    /**
     * Rules 1a (aged out), 1b (not started beyond the 120 s skew) and 2 (hard cap, oldest month
     * first; a stable sort, as V8's) from sync.js:3426-3442. Returns a new map.
     */
    fun boundFileCounts(fileCounts: Map<String, JsonElement>, nowMs: Long, ageLimitMs: Long): LinkedHashMap<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val cutoff = nowMs - ageLimitMs
        val futureLimit = nowMs + EvtUploadPolicy.MAX_FUTURE_SKEW_MS
        for ((fn, v) in fileCounts) {
            val parsed = EventSyncEngine.evtParseFileName(fn) ?: continue
            val (from, to) = EventSyncEngine.evtMonthRange(parsed.second)
            if (to < cutoff) continue
            if (from > futureLimit) continue
            out[fn] = v
        }
        if (out.size > EVT_FILE_COUNTS_MAX) {
            val sorted = out.keys.sortedBy { EventSyncEngine.evtParseFileName(it)?.second ?: "" }
            val excess = sorted.size - EVT_FILE_COUNTS_MAX
            for (i in 0 until excess) out.remove(sorted[i])
        }
        return out
    }
}
