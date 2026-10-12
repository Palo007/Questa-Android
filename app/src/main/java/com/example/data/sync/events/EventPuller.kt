package com.example.data.sync.events

import com.example.data.sync.EventSyncEngine
import com.example.data.sync.merge.Js
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * WP-8.7 (PX-53, SY-35) -- the self-healing knobs of the pull (sync.js:3010-3016).
 */
object EvtPullHealer {
    /** Opti `EVT_PULL_MIN_INTERVAL_MS`: list_folder at most once a minute. */
    const val EVT_PULL_MIN_INTERVAL_MS = 60_000L
    /** Opti `EVT_FULL_SCAN_INTERVAL_MS`: the daily rev-cache-ignoring re-scan. */
    const val EVT_FULL_SCAN_INTERVAL_MS = 24L * 3_600_000L
    /** Opti `EVT_BAD_REV_RETRY_MS`: a corrupt / failed file is retried after 15 min. */
    const val BAD_REV_RETRY_MS = 900_000L
    /** Opti `EVT_BAD_REVS_MAX`. */
    const val BAD_REVS_MAX = 200

    /** `!ignoreRevCache && bad && (now - (bad.at || 0)) < EVT_BAD_REV_RETRY_MS` (sync.js:3530). */
    fun inBackoff(bad: JsonElement?, now: Long): Boolean {
        if (!Js.truthy(bad)) return false
        val at = Js.num(Js.get(bad, "at"))
        return (now - at) < BAD_REV_RETRY_MS
    }

    /** Keep the [BAD_REVS_MAX] most recently touched entries (sync.js:3602-3606; stable sort by `at || 0`). */
    fun boundBadRevs(badRevs: LinkedHashMap<String, JsonElement>) {
        if (badRevs.size <= BAD_REVS_MAX) return
        val sorted = badRevs.keys.sortedBy { Js.num(Js.get(badRevs[it], "at")) }
        val excess = sorted.size - BAD_REVS_MAX
        for (i in 0 until excess) badRevs.remove(sorted[i])
    }

    fun badRev(rev: String?, at: Long): JsonElement = buildJsonObject {
        put("rev", if (rev == null) JsonNull else JsonPrimitive(rev))
        put("at", JsonPrimitive(at))
    }
}

/**
 * WP-8.7 (PX-53, PX-54) -- Opti `syncEventsPull(opts)` (sync.js:3470-3613), line for line: the
 * 60 s throttle, the daily full scan, the 15 min bad-rev backoff (capped at 200), the strict
 * prior-rows read, the signature dedup, and a rev cached only after a committed insert.
 * A list failure or a non-404 download failure throws before anything is saved, as in Opti.
 */
class EventPuller(
    private val cfg: EvtCfgStore,
    private val store: LocalEventStore,
    private val deviceId: suspend () -> String,
    private val clock: () -> Long,
    private val diag: DiagRing,
    private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
) {
    /** Opti's module-scoped `_evtPullLastThrottleDiag`. */
    private var lastThrottleDiag = 0L

    private fun skip(name: String?, reason: String) =
        diag.push("evtPullSkip", mapOf("name" to DiagRing.str(name), "reason" to JsonPrimitive(reason)))

    suspend fun pull(remote: EventsRemote, force: Boolean = false) {
        val now = clock()
        val c = cfg.read()
        if (!force && now - c.lastPullAt < EvtPullHealer.EVT_PULL_MIN_INTERVAL_MS) {
            if (now - lastThrottleDiag >= EvtPullHealer.EVT_PULL_MIN_INTERVAL_MS) {
                lastThrottleDiag = now
                skip(null, "throttled")
            }
            return
        }
        val myDev = deviceId()
        val lastFullScanAt = c.fullScanAt
        val isFullScan = lastFullScanAt > 0 && (now - lastFullScanAt) > EvtPullHealer.EVT_FULL_SCAN_INTERVAL_MS
        val ignoreRevCache = force || isFullScan
        val entries = remote.listEvents()
        if (isFullScan) diag.push("evtFullScan", mapOf("entries" to JsonPrimitive(entries.size)))
        val revs = LinkedHashMap(c.fileRevs)
        val badRevs = LinkedHashMap(c.badRevs)
        for (ent in entries) {
            val parsed = EventSyncEngine.evtParseFileName(ent.name)
            if (parsed == null) { skip(ent.name, "unparsedName"); continue }
            val (dev, month) = parsed
            if (dev == myDev) { skip(ent.name, "ownDevice"); continue }
            if (EventSyncEngine.evtMonthOlderThan(month, now, ageLimitMs)) {
                revs.remove(ent.name)
                badRevs.remove(ent.name)
                skip(ent.name, "tooOld")
                continue
            }
            val entRev = ent.rev?.takeIf { it.isNotEmpty() }
            if (!ignoreRevCache && EvtPullHealer.inBackoff(badRevs[ent.name], now)) { skip(ent.name, "badRevBackoff"); continue }
            if (!ignoreRevCache && entRev != null && (revs[ent.name] as? JsonPrimitive)?.takeIf { it.isString }?.content == entRev) {
                skip(ent.name, "revUnchanged")
                continue
            }
            val dl = remote.downloadRaw(EventsDir.file(ent.name))
            if (dl == null) {
                skip(ent.name, "downloadFailed")
                badRevs[ent.name] = EvtPullHealer.badRev(entRev, now)
                continue
            }
            val dlRev = dl.rev?.takeIf { it.isNotEmpty() } ?: entRev
            var parseFailed = false
            val records: JsonElement? = try { Json.parseToJsonElement(dl.text) } catch (e: Exception) { parseFailed = true; null }
            if (records is JsonArray) {
                val (from, to) = EventSyncEngine.evtMonthRange(month)
                val priorRows = store.inRangeStrict(from, to)
                if (priorRows == null) {
                    diag.push("evtPullGuardReadFailed", mapOf("name" to JsonPrimitive(ent.name)))
                    badRevs[ent.name] = EvtPullHealer.badRev(dlRev, now)
                    continue
                }
                val existing = LinkedHashMap<String, String>()
                priorRows.forEach { e -> if (!e.uid.isNullOrEmpty()) existing[e.uid] = EvtDedup.entitySig(e) }
                val toInsert = EvtDedup.incomingFilter(records, existing.keys, existing, myDev, now, ageLimitMs)
                val ok = store.insertAll(toInsert.map { EventSyncEngine.parseIncomingEventJson(it) })
                if (ok) {
                    diag.push("evtPullOk", mapOf("name" to JsonPrimitive(ent.name), "inserted" to JsonPrimitive(toInsert.size)))
                    revs[ent.name] = if (dlRev == null) JsonNull else JsonPrimitive(dlRev)
                    badRevs.remove(ent.name)
                } else {
                    diag.push("evtPullInsertFailed", mapOf("name" to JsonPrimitive(ent.name)))
                    badRevs[ent.name] = EvtPullHealer.badRev(dlRev, now)
                }
            } else {
                skip(ent.name, if (parseFailed) "parseError" else "notArray")
                badRevs[ent.name] = EvtPullHealer.badRev(dlRev, now)
            }
        }
        EvtPullHealer.boundBadRevs(badRevs)
        cfg.savePull(revs, badRevs, now, if (isFullScan) now else (if (lastFullScanAt != 0L) lastFullScanAt else now))
    }
}
