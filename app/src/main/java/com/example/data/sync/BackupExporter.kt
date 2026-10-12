package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Opti's `IO_SECTIONS` (app.js:7269-7350), in that order. [key] is the wire name. */
enum class Section(val key: String) {
    CHAR("char"),
    HABITS("habits"),
    DAILIES("dailies"),
    TODOS("todos"),
    REWARDS("rewards"),
    TAGS("tags"),
    PREFS("prefs"),
    HISTORY("history"),
    DEVICES("devices"),
    EVENTS("events");

    companion object {
        fun fromKey(key: String): Section? = entries.firstOrNull { it.key == key }
    }
}

/**
 * WP-8.3: the whole local `S` as a backup sees it. It is [wire] (everything syncSubset reads)
 * plus the parts that never go on the wire:
 *
 *  - [prefs]: `S.prefs` without `an` (the backup prefs overlay: UI prefs, `paused`, `pausedDays`,
 *    `pausedAt`, ...). `an` is taken from [LocalWireState.anViews] / [LocalWireState.anMetrics]
 *    and written at its position in [prefs] if it has one, else appended.
 *  - [version]: `S.version` (Opti freshState writes 1); null = absent.
 *  - [snapshotExtras]: the last imported original `S`, if any. Its key order is kept and keys
 *    Kotlin does not build (`__seq`, `__hlcLast`, unknown future keys) pass through unchanged.
 */
data class FullLocalState(
    val wire: LocalWireState,
    val prefs: JsonObject = JsonObject(emptyMap()),
    val version: Int? = 1,
    val snapshotExtras: JsonObject? = null
) {
    /** The `S` object, in Opti freshState order (app.js:110-122) unless [snapshotExtras] has one. */
    fun toSnapshot(): JsonObject {
        val an = buildJsonObject {
            put("views", wire.anViews)
            put("metrics", wire.anMetrics)
        }
        val prefsOut = SerializationEngine.overlayJson(prefs, buildJsonObject {
            put("an", SerializationEngine.overlayJson(prefs["an"] as? JsonObject, an))
            // app.js setPause (line 6696) / sync.js:808: S.prefs.{paused,pausedDays,pausedAt} are
            // the live pause row, not just whatever the last import happened to carry -- but only
            // when there IS a live pause row ([LocalWireState.pauseRowExists]); a caller with no
            // pause source (legacy exportBackup(), hand-built states) must not fabricate one.
            if (wire.pauseRowExists) {
                put("paused", wire.pause.getValue("paused"))
                put("pausedDays", wire.pause.getValue("pausedDays"))
                // Opti sets S.prefs.pausedAt only in setPause (app.js:6696) or when
                // subset.pause.at != 0 (sync.js:887). at == 0 (never paused / null pausedAt)
                // writes nothing, so an imported overlay pausedAt is kept, else the key is absent.
                val at = wire.pause.getValue("at")
                if ((at as? JsonPrimitive)?.longOrNull != 0L) put("pausedAt", at)
            }
        })
        val fresh = buildJsonObject {
            version?.let { put("version", JsonPrimitive(it)) }
            put("char", wire.char)
            put("tasks", wire.tasks)
            put("rewards", wire.rewards)
            put("tags", wire.tags)
            put("devices", wire.devices)
            put("lastCron", JsonPrimitive(wire.lastCron))
            put("history", wire.history)
            put("charHistory", wire.charHistory)
            put("monthlyBackups", wire.monthlyBackups)
            put("deletions", wire.deletions)
            put("prefs", prefsOut)
        }
        // A schema-1 file's root is S plus `events` / `_backup`; those are envelope keys, not S keys.
        val extras = snapshotExtras?.let { JsonObject(it.filterKeys { k -> k != "events" && k != "_backup" }) }
        return SerializationEngine.overlayJson(extras, fresh)
    }
}

/** Produces a backup envelope from the live local state (used by AppKeyMigration, 8.21, and 8.9). */
interface BackupExporter {
    suspend fun export(sections: Set<Section>? = null): JsonObject
}

/**
 * [BackupExporter] over injected loaders. The clock is injected too ([nowPhysical], a physical
 * clock) so this file adds no clock site (D18); DI (8.I) binds it.
 */
class DefaultBackupExporter(
    private val loadState: suspend () -> FullLocalState,
    private val loadEvents: suspend () -> List<EventLogEntity>,
    private val deviceId: suspend () -> String,
    private val nowPhysical: () -> Long,
    private val appVersion: String = DEFAULT_APP_VERSION
) : BackupExporter {
    override suspend fun export(sections: Set<Section>?): JsonObject =
        buildBackupFile(loadState(), loadEvents(), sections, partial = false, nowPhysical(), deviceId(), appVersion)
}

const val DEFAULT_APP_VERSION = "v1.0-android"
const val BACKUP_HASH_ALGO = "sha256"

/**
 * WP-8.3: port of Opti `buildBackupFile(eventsArr, sectionKeys)` (app.js:7416-7475). Returns the
 * schema-2 envelope `{_backup, K, SRC, TID, TT, FM, S, E}`; the file text is
 * `CanonicalJson.stringifyPretty(result)` (`JSON.stringify(env, null, 2)`).
 *
 *  - [sections] null or empty = every section. The file is full when every section is selected
 *    and [partial] is false; otherwise it is partial: `_backup.partial = true` and
 *    `_backup.sections` lists the keys in `IO_SECTIONS` order.
 *  - The pre-image is `CanonicalJson.stringify({...S-or-slice, events, _backup})` with
 *    `_backup.hashAlgo = "sha256"` already set and no `hash`; `hash` is then added last (PX-27).
 *  - `exportedAt` and the `lastActivityAt` fallback come from [nowPhysical] (no clock read here).
 *  - [deviceId] is not part of Opti's file format and is not written; it is accepted for the
 *    contract signature only.
 */
fun buildBackupFile(
    state: FullLocalState,
    events: List<EventLogEntity>,
    sections: Set<Section>?,
    partial: Boolean,
    nowPhysical: Long,
    deviceId: String,
    appVersion: String = DEFAULT_APP_VERSION
): JsonObject {
    val keys = if (sections.isNullOrEmpty()) Section.entries.toList() else Section.entries.filter { it in sections }
    val isFull = !partial && keys.size == Section.entries.size
    val evts: List<JsonObject> = if (Section.EVENTS in keys) events.map { stripBackupOnlyEventFields(SerializationEngine.buildEventJson(it)) } else emptyList()
    val s = state.toSnapshot()
    val src = if (isFull) s else sliceStateForExport(s, keys)

    val lastMs = maxOf(lastMs(src["tasks"]), lastMs(src["rewards"]))
    val views = ((src["prefs"] as? JsonObject)?.get("an") as? JsonObject)?.get("views") as? JsonArray
    val meta = LinkedHashMap<String, JsonElement>()
    meta["schema"] = JsonPrimitive(2)
    meta["exportedAt"] = JsonPrimitive(isoString(nowPhysical))
    meta["appVersion"] = JsonPrimitive(appVersion)
    meta["eventCount"] = JsonPrimitive(evts.size)
    meta["items"] = buildJsonObject {
        put("tasks", JsonPrimitive((src["tasks"] as? JsonArray)?.size ?: 0))
        put("rewards", JsonPrimitive((src["rewards"] as? JsonArray)?.size ?: 0))
        put("tags", JsonPrimitive((src["tags"] as? JsonArray)?.size ?: 0))
        put("views", JsonPrimitive(views?.size ?: 0))
        put("lastActivityAt", JsonPrimitive(isoString(if (lastMs > 0.0) lastMs.toLong() else nowPhysical)))
    }
    if (!isFull) {
        meta["partial"] = JsonPrimitive(true)
        meta["sections"] = JsonArray(keys.map { JsonPrimitive(it.key) })
    }
    meta["hashAlgo"] = JsonPrimitive(BACKUP_HASH_ALGO)
    val preimage = CanonicalJson.buildHashPreimage(src, evts, JsonObject(LinkedHashMap(meta)))
    meta["hash"] = JsonPrimitive(SerializationEngine.computeSha256(preimage))

    val tok = SerializationEngine.tokenizeEvents(evts)
    val snap = JsonObject(src.filterKeys { it != "events" })
    val fm = SerializationEngine.snapshotFieldMap(snap)
    return buildJsonObject {
        put("_backup", JsonObject(meta))
        put("K", JsonArray(tok.K.map { JsonPrimitive(it) }))
        put("SRC", JsonArray(tok.SRC.map { JsonPrimitive(it) }))
        put("TID", JsonArray(tok.TID.map { JsonPrimitive(it) }))
        put("TT", JsonArray(tok.TT.map { JsonPrimitive(it) }))
        put("FM", JsonObject(fm.mapValues { JsonPrimitive(it.value) }))
        put("S", SerializationEngine.tokenizeDeep(snap, fm))
        put("E", JsonArray(tok.E))
    }
}

/**
 * WP-8.9-R1 (REWORK R-1, Decision Pali 2026-09-25: B): event keys a BACKUP file never carries.
 * Opti 7d5d4f3 `importData` refuses any backup whose events hold `src` (its detokenizer rejects
 * an unknown short key, app.js:7105-7110; `src` is not in `_EXPORT_FIELD_MAP`), so a phone tap
 * would make the whole file unusable to the PWA. No Opti or Kotlin reader uses `src`.
 *
 * Applied in [buildBackupFile] BEFORE the hash pre-image, so the hash covers the stripped events.
 * Backup files only: sync `/events` (EventSyncEngine.buildEventPushJson), inbox handling and Room
 * keep `src`, and import still accepts it (G-9). Event dedup does not read `src`
 * (EvtDedup.incomingSig), so a restored stripped event and the same uid pulled with `src` from
 * `/events` stay one row.
 */
val BACKUP_STRIPPED_EVENT_FIELDS: Set<String> = setOf("src")

internal fun stripBackupOnlyEventFields(e: JsonObject): JsonObject =
    if (BACKUP_STRIPPED_EVENT_FIELDS.none { it in e }) e else JsonObject(e.filterKeys { it !in BACKUP_STRIPPED_EVENT_FIELDS })

/** The exact hash pre-image [buildBackupFile] hashes, for verification by tests and importers. */
fun backupHashPreimage(detokenizedSnapshot: JsonObject, events: List<JsonObject>, backupMeta: JsonObject): String =
    CanonicalJson.buildHashPreimage(detokenizedSnapshot, events, JsonObject(backupMeta.filterKeys { it != "hash" }))

/** `sliceStateForExport(s, keys)` (app.js:7363-7380): arrays from several sections concatenate. */
fun sliceStateForExport(s: JsonObject, keys: List<Section>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    s["version"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { out["version"] = it }
    fun tasksOfType(ty: String): JsonArray = JsonArray(((s["tasks"] as? JsonArray) ?: JsonArray(emptyList())).filter {
        val t = (it as? JsonObject)?.get("type") as? JsonPrimitive
        t != null && t.isString && t.content == ty // `t && t.type === ty`
    })
    fun arrOrEmpty(k: String): JsonArray = s[k] as? JsonArray ?: JsonArray(emptyList())
    for (sec in Section.entries) {
        if (sec !in keys) continue
        val part = LinkedHashMap<String, JsonElement>()
        when (sec) {
            Section.CHAR -> (s["char"] as? JsonObject)?.let { part["char"] = it }
            Section.HABITS -> part["tasks"] = tasksOfType("habit")
            Section.DAILIES -> part["tasks"] = tasksOfType("daily")
            Section.TODOS -> part["tasks"] = tasksOfType("todo")
            Section.REWARDS -> part["rewards"] = arrOrEmpty("rewards")
            Section.TAGS -> part["tags"] = arrOrEmpty("tags")
            Section.PREFS -> {
                s["prefs"]?.let { part["prefs"] = it }
                s["lastCron"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { part["lastCron"] = it }
            }
            Section.HISTORY -> for (k in listOf("history", "charHistory", "monthlyBackups")) (s[k] as? JsonArray)?.let { part[k] = it }
            Section.DEVICES -> for (k in listOf("devices", "deletions")) (s[k] as? JsonArray)?.let { part[k] = it }
            Section.EVENTS -> {}
        }
        for ((k, v) in part) {
            val prev = out[k]
            out[k] = if (v is JsonArray && prev is JsonArray) JsonArray(prev + v) else v
        }
    }
    return JsonObject(out)
}

/** `_lastMs(arr)`: max of `createdAt || 0` and `updatedAt || 0` over the records. */
private fun lastMs(arr: JsonElement?): Double {
    var mx = 0.0
    (arr as? JsonArray)?.forEach { x ->
        val o = x as? JsonObject ?: return@forEach
        for (k in listOf("createdAt", "updatedAt")) {
            val p = o[k] as? JsonPrimitive ?: continue
            if (p.isString) continue
            val v = p.doubleOrNull ?: continue
            if (v > mx) mx = v
        }
    }
    return mx
}

/** `new Date(ms).toISOString()` for years 0000-9999. */
private fun isoString(ms: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms))
}
