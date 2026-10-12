package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Stage 4 / T4.2-T4.3 — D6 event-log Dropbox sync, pure logic layer.
 *
 * Direct Kotlin port of legacy_pwa/sync.js's BEGIN_EVTSYNC_HELPERS ...
 * END_EVTSYNC_HELPERS block (lines 1402-1460) plus the syncEventsPush/syncEventsPull
 * filter/dedup logic (lines 1420-1455), extracted per plans/sync_format_spec.md section 3 addendum
 * (T4.1, 2026-07-18; rule numbers referenced below are that table's # column). This object
 * holds only pure functions (no Dropbox I/O, no Room I/O) so it mirrors the existing
 * SerializationEngine/CanonicalJson split: I/O orchestration lives in SyncManager, wire
 * shape + filter rules live here.
 *
 * All month-boundary math uses Calendar in the UTC time zone rather than java.time — this
 * project's minSdk is 24 and does not enable core-library desugaring (see app/build.gradle.kts),
 * so java.time is not safely available; Calendar/TimeZone is the pattern already used
 * elsewhere in this codebase (e.g. SerializationEngine's SimpleDateFormat + UTC TimeZone).
 */
object EventSyncEngine {

    /** Rule 14: `EVT_PULL_MIN_INTERVAL_MS` (sync.js:1400) — pull throttle. */
    const val EVT_PULL_MIN_INTERVAL_MS: Long = 60_000L

    /** Rule 24: `EVENT_AGE_LIMIT_MS` default (sync.js:1563) — `18 * 30 * 86400000` ≈ 548 days. */
    const val EVENT_AGE_LIMIT_MS: Long = 18L * 30L * 86_400_000L

    /**
     * Rule 3: UTC `"YYYYMM"` month key for an event timestamp (sync.js:1404-1407,
     * `evtMonthKey`): `String(d.getUTCFullYear()).padStart(4,"0") + String(d.getUTCMonth()+1
     * ).padStart(2,"0")`.
     */
    fun evtMonthKey(ts: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d%02d", year, month)
    }

    /**
     * `"YYYYMM"` -> `[from, to]` ms, both inclusive (sync.js:1409-1412, `evtMonthRange`):
     * `{from: Date.UTC(y, m-1, 1), to: Date.UTC(y, m, 1) - 1}`.
     */
    fun evtMonthRange(monthKey: String): Pair<Long, Long> {
        val year = monthKey.substring(0, 4).toInt()
        val month = monthKey.substring(4, 6).toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, 1, 0, 0, 0)
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val to = cal.timeInMillis - 1
        return from to to
    }

    /** Rule 12: is this whole month older than the prune window, compared against month END? */
    fun evtMonthOlderThan(monthKey: String, nowMs: Long, ageLimitMs: Long): Boolean {
        val (_, to) = evtMonthRange(monthKey)
        return (nowMs - to) > ageLimitMs
    }

    /**
     * Rule 9: filename parse regex `^(.+)-(\d{6})\.json$` (sync.js:1417, `evtParseFileName`) —
     * greedy `.+` guarantees the month is the rightmost 6-digit group even if a deviceId itself
     * contains digits or hyphens. Returns `(dev, month)` or `null`.
     */
    private val FILE_NAME_REGEX = Regex("^(.+)-(\\d{6})\\.json$")

    fun evtParseFileName(name: String): Pair<String, String>? {
        val m = FILE_NAME_REGEX.find(name) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /**
     * Rule 1: `evtUploadable` (sync.js:1420-1429) — own device's not-yet-uploaded, real (not
     * synthetic, not `"lifecycle"`), stamped events newer than the push watermark:
     * `e.uid && e.dev === myDev && !e.synthetic && e.kind !== "lifecycle" && typeof e.ts ===
     * "number" && e.ts > sinceTs`, plus WP-8.7's `(!e.imported || e.republish)` gate (PX-52,
     * sync.js:3062-3070; see [com.example.data.sync.events.EvtUploadPolicy]). (`e.ts` is always numeric here — `EventLogEntity.timestamp`
     * is a non-null `Long` — so no separate type check is needed.)
     */
    fun evtUploadable(events: List<EventLogEntity>, myDev: String, sinceTs: Long): List<EventLogEntity> =
        events.filter { e -> com.example.data.sync.events.EvtUploadPolicy.uploadable(e, myDev, sinceTs) }

    /**
     * Rule 21: `evtOwnMonthRecords` (sync.js:1430-1436) — full-month rebuild set for upload: same
     * ownership rule as [evtUploadable], but with NO watermark filter (every own-device eligible
     * event in the month is re-included every push, so the resulting file is a full rebuild, not
     * a diff/append).
     */
    fun evtOwnMonthRecords(events: List<EventLogEntity>, myDev: String): List<EventLogEntity> =
        events.filter { e -> com.example.data.sync.events.EvtUploadPolicy.ownMonthRecord(e, myDev) }

    /**
     * Rule 2/27: builds the bare-array-element JSON shape for one event, for the events/
     * <deviceId>-<YYYYMM>.json push payload — spec §1.2's "Event object" base key order (`ts ->
     * uid -> dev -> kind -> [kind-specific properties]`), local Room `id` never emitted (rule 2:
     * "strips local IDB `id` field"). Field names are the RAW/untokenized PWA event property
     * names (this file is never passed through `EXPORT_FIELD_MAP`/`FM` tokenization — that only
     * applies to the backup/export envelope, §2 of the spec) — same raw names
     * `SerializationEngine`'s backup-path `eventsJson` builder already uses for `dmg`/`dir`
     * (PWA's actual in-memory field names for damage/direction), so this is wire-compatible with
     * both a real PWA events/ folder file and a PWA reader of a Kotlin-produced one.
     *
     * Deliberately NOT reusing `SerializationEngine`'s backup-path event builder: that builder's
     * output feeds `CanonicalJson.buildHashPreimage` (spec §1.1), so changing its field set (to
     * add `uid`/`dev`) would change the backup hash pre-image bytes — out of scope for T4.2/T4.3
     * and forbidden by this task's "never modify wire/file formats beyond the spec" constraint.
     */
    fun buildEventPushJson(e: EventLogEntity): JsonObject = buildJsonObject {
        put("ts", JsonPrimitive(e.timestamp))
        e.uid?.let { put("uid", JsonPrimitive(it)) }
        e.dev?.let { put("dev", JsonPrimitive(it)) }
        put("kind", JsonPrimitive(e.kind))
        e.taskType?.let { put("taskType", JsonPrimitive(it)) }
        e.taskId?.let { put("taskId", JsonPrimitive(it)) }
        e.taskTitle?.let { put("taskTitle", JsonPrimitive(it)) }
        e.streak?.let { put("streak", JsonPrimitive(it)) }
        e.damage?.let { put("dmg", CanonicalJson.jsonNumber(it)) }
        e.direction?.let { put("dir", JsonPrimitive(it)) }
        e.reps?.let { put("reps", JsonPrimitive(it)) }
        e.value?.let { put("value", CanonicalJson.jsonNumber(it)) }
        e.cost?.let { put("cost", CanonicalJson.jsonNumber(it)) }
        // WP-8.7 (PX-52): Opti's record carries its `imported` / `republish` flags as it is stored.
        e.imported?.let { put("imported", JsonPrimitive(it)) }
        e.republish?.let { put("republish", JsonPrimitive(it)) }
        e.payloadJson?.let { payload ->
            try {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload) as? JsonObject
                parsed?.forEach { (key, v) -> put(key, v) }
            } catch (_: Exception) {
                // opaque extras blob was malformed; drop silently, same as SerializationEngine's
                // equivalent backup-path spread (best-effort, never fails the whole push).
            }
        }
    }

    /**
     * Rule 25: ingest filter (sync.js:1440-1455, `evtIncomingFilter`) — reject: non-object
     * (already guaranteed `JsonObject` by the caller's parse step), no uid, non-numeric `ts`,
     * `synthetic`, `kind === "lifecycle"`, own device, older than the age limit, or duplicate uid
     * (rule 18: two-layer dedup — `existingUidSet` passed in by the caller, plus an in-batch
     * `seen` set so two records in the SAME downloaded file with the same uid don't both survive).
     * Rule 27: strips `id` from surviving records (defensive; a real cross-device file never
     * carries one, since [buildEventPushJson] never emits it either).
     */
    fun evtIncomingFilter(
        records: List<JsonObject>,
        existingUidSet: Set<String>,
        myDev: String,
        nowMs: Long,
        ageLimitMs: Long
    ): List<JsonObject> =
        // WP-8.7: Opti's legacy Set form of evtIncomingFilter (sync.js:3103-3148), ported in EvtDedup.
        com.example.data.sync.events.EvtDedup.incomingFilter(records, existingUidSet, null, myDev, nowMs, ageLimitMs)

    /**
     * Rule 27/28 continuation: parses one surviving incoming record (already filtered by
     * [evtIncomingFilter]) into a Room-insertable [EventLogEntity]. Mirrors
     * `SerializationEngine`'s backup-import event parser's "known keys vs. opaque extras ->
     * payloadJson" shape, extended with the two new `uid`/`dev` typed columns (not applicable to
     * that other parser — see its own scope note) and `synthetic = false` (already guaranteed by
     * the ingest filter above, so no incoming record reaching here is ever synthetic).
     */
    fun parseIncomingEventJson(obj: JsonObject): EventLogEntity {
        // WP-8.7: no clock fallback (the ingest filter guarantees a numeric ts). A fractional JS ts
        // is truncated to Room's Long column [DEFERRED_OPEN_QUESTION].
        val ts = obj["ts"]?.jsonPrimitive?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } ?: 0L
        val uid = obj["uid"]?.jsonPrimitive?.contentOrNull
        val dev = obj["dev"]?.jsonPrimitive?.contentOrNull
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "info"
        val taskType = obj["taskType"]?.jsonPrimitive?.contentOrNull
        val taskId = obj["taskId"]?.jsonPrimitive?.contentOrNull
        val taskTitle = obj["taskTitle"]?.jsonPrimitive?.contentOrNull
        val streak = obj["streak"]?.jsonPrimitive?.intOrNull
        val damage = obj["dmg"]?.jsonPrimitive?.doubleOrNull
        val direction = obj["dir"]?.jsonPrimitive?.intOrNull
        val reps = obj["reps"]?.jsonPrimitive?.intOrNull
        val value = obj["value"]?.jsonPrimitive?.doubleOrNull
        val cost = obj["cost"]?.jsonPrimitive?.doubleOrNull

        val imported = (obj["imported"] as? JsonPrimitive)?.booleanOrNull
        val republish = (obj["republish"] as? JsonPrimitive)?.booleanOrNull
        val knownKeys = mutableSetOf(
            "ts", "uid", "dev", "kind", "taskType", "taskId", "taskTitle",
            "streak", "dmg", "dir", "reps", "value", "cost"
        ).apply {
            // A non-boolean flag stays an opaque extra, so it is re-emitted unchanged.
            if (imported != null) add("imported")
            if (republish != null) add("republish")
        }
        val extraFields = obj.filterKeys { it !in knownKeys }
        val payloadJson = if (extraFields.isNotEmpty()) {
            CanonicalJson.canonicalJsonString(JsonObject(extraFields))
        } else {
            null
        }

        return EventLogEntity(
            timestamp = ts,
            uid = uid,
            dev = dev,
            kind = kind,
            taskType = taskType,
            taskId = taskId,
            taskTitle = taskTitle,
            streak = streak,
            damage = damage,
            direction = direction,
            reps = reps,
            value = value,
            cost = cost,
            payloadJson = payloadJson,
            imported = imported,
            republish = republish,
            synthetic = false
        )
    }

    /**
     * PWA's `uid()` helper is `Date.now().toString(36) + rand36(5)` (spec §6); the event-specific
     * variant is `<deviceId>-<ts36>-<rand36>` (spec §6, "event uid"). Neither existed anywhere in
     * this Kotlin codebase prior to Stage 4 (every existing id is `UUID.randomUUID()`-based) — this
     * is a from-scratch port of that format, used by `RpgRepository.insertLog`'s stamp-if-missing
     * logic (the Kotlin equivalent of PWA's `logEvent()`/`syncEventUid()`, see its KDoc).
     */
    fun generateEventUid(deviceId: String, ts: Long): String {
        val ts36 = ts.toString(36)
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        // WP-8.7 (PX-55): Opti syncEventUid is `Math.random().toString(36).slice(2, 8)`, 6 chars.
        val rand36 = (1..6).map { alphabet.random() }.joinToString("")
        return "$deviceId-$ts36-$rand36"
    }
}
