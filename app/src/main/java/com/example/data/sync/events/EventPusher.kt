package com.example.data.sync.events

import com.example.data.sync.CanonicalJson
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.EventWatermark
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Opti `syncEventsPush(opts)` options: `force` = the shrink-guard override, `forceFullPush` = watermark 0. */
data class PushOpts(val force: Boolean = false, val forceFullPush: Boolean = false)

/** What one push did (for the caller's toast and tests). */
data class PushReport(val uploaded: List<String>, val blocked: List<String>, val toast: String?)

/**
 * WP-8.7 (PX-50, PX-51, PX-52) -- Opti `syncEventsPush` (sync.js:3296-3462), line for line.
 *
 * Own new events select the touched UTC months; each month file is rebuilt in full and
 * overwritten, unless the shrink guard blocks it. The watermark never lands past now and never
 * advances past a blocked month; the 24 h full re-push re-scans from 0. A Dropbox failure throws
 * out of the push before anything is saved, as Opti's `await dbxUploadText` does.
 */
class EventPusher(
    private val w: EventWatermark,
    private val cfg: EvtCfgStore,
    private val store: LocalEventStore,
    private val deviceId: suspend () -> String,
    private val clock: () -> Long,
    private val diag: DiagRing,
    private val ageLimitMs: Long = EvtUploadPolicy.EVENT_AGE_LIMIT_MS,
) {
    suspend fun push(remote: EventsRemote, opts: PushOpts = PushOpts()): PushReport {
        val myDev = deviceId()
        val c = cfg.read()
        val since = w.get()
        var effectiveSince = since
        if (opts.forceFullPush) {
            effectiveSince = 0L
        } else if (c.fullPushAt != 0L && (clock() - c.fullPushAt) > EvtUploadPolicy.FULL_PUSH_INTERVAL_MS) {
            effectiveSince = 0L
        }
        val fresh = store.since(effectiveSince).filter { EvtUploadPolicy.uploadable(it, myDev, effectiveSince) }
        if (fresh.isEmpty()) {
            if (c.fullPushAt == 0L) cfg.saveFullPushAt(clock())
            return PushReport(emptyList(), emptyList(), null)
        }
        val months = fresh.map { EventSyncEngine.evtMonthKey(it.timestamp) }.toSortedSet().toList()
        var maxTs = since
        var anyBlocked = false
        val fileCounts = LinkedHashMap(c.fileCounts)
        val pushBlocked = LinkedHashMap(c.pushBlocked)
        val uploaded = mutableListOf<String>()
        for (mk in months) {
            val (from, to) = EventSyncEngine.evtMonthRange(mk)
            val recs = store.inRange(from, to).filter { EvtUploadPolicy.ownMonthRecord(it, myDev) }
            if (recs.isEmpty()) continue
            val fname = "$myDev-$mk.json"
            when (val d = EvtPushGuard.decide(from, fileCounts[fname], recs, clock(), ageLimitMs, opts.force)) {
                is PushDecision.Blocked -> {
                    anyBlocked = true
                    diag.push("evtPushShrinkBlocked", mapOf(
                        "file" to JsonPrimitive(fname),
                        "local" to JsonPrimitive(d.local),
                        "known" to d.known,
                        "localHash" to JsonPrimitive(d.localHash),
                        "knownHash" to d.knownHash,
                    ))
                    pushBlocked[fname] = buildJsonObject {
                        put("at", JsonPrimitive(clock()))
                        put("local", JsonPrimitive(d.local))
                        d.known?.let { put("known", it) }
                    }
                    continue
                }
                PushDecision.Upload -> Unit
            }
            val body = CanonicalJson.canonicalJsonString(JsonArray(recs.map { EventSyncEngine.buildEventPushJson(it) }))
            remote.uploadText(EventsDir.file(fname), body)
            fileCounts[fname] = EvtPushGuard.fileCountEntry(recs)
            pushBlocked.remove(fname)
            uploaded.add(fname)
            recs.forEach { if (it.timestamp > maxTs) maxTs = it.timestamp }
        }
        val now = clock()
        val finalMaxTs = EvtUploadPolicy.watermark(maxTs, now)
        val bounded: Map<String, JsonElement> = EvtPushGuard.boundFileCounts(fileCounts, now, ageLimitMs)
        val newTs = if (anyBlocked) since else finalMaxTs
        // Opti writes the value outright; through the EventWatermark API that is a raise, or a lower
        // when a poisoned (future) watermark is clamped back to now.
        if (newTs > since) w.advanceTo(newTs) else if (newTs < since) w.lowerTo(newTs)
        val t = clock()
        val fullPushAt = if (opts.forceFullPush || c.fullPushAt == 0L || (t - c.fullPushAt) > EvtUploadPolicy.FULL_PUSH_INTERVAL_MS) t else c.fullPushAt
        cfg.savePush(fullPushAt, bounded, pushBlocked)
        val toast = if (anyBlocked) blockedToast(pushBlocked.size) else null
        return PushReport(uploaded, pushBlocked.keys.toList(), toast)
    }

    companion object {
        /** Opti's toast text (sync.js:3460). */
        fun blockedToast(files: Int): String =
            "Sync blocked: $files month file(s) would shrink. Use \"Force Push Events\" in Settings to override."
    }
}
