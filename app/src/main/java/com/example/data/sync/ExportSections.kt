package com.example.data.sync

import com.example.data.RpgRepository
import com.example.data.clock.PhysicalClock
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.UiPrefsOverlay
import com.example.data.sync.events.EvtDedup
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Opti import modes (`applyImportSections(data, keys, mode)`, app.js:7997). */
enum class ImportMode { REPLACE, MERGE }

data class ImportResult(
    /** The sections the user picked, in `IO_SECTIONS` order. */
    val sections: List<Section>,
    /** Every section picked (Opti `ioIsFullSelection`). */
    val full: Boolean,
    val mode: ImportMode,
    /** Room / wire state changed (localSavedAt was stamped). */
    val changed: Boolean,
    val eventsAdded: Int,
    val eventsSkipped: Int,
    /** Opti `eventImportSummary` of every embedded event (CR-KT-009); EMPTY when no events were imported. */
    val summary: ImportSummary = ImportSummary.EMPTY,
    /** New events that could not be written (Opti bulkAddEvents `failed`); > 0 = "Import incomplete". */
    val eventsFailed: Int = 0,
)

/**
 * WP-8.9 (PX-61) -- Opti's granular export/import registry (`IO_SECTIONS`, app.js:7198-7414),
 * applied to a state-shaped JSON object `S`. Pure functions: nothing here touches Room.
 * The guarantee of the import dialog: sections NOT picked are never read and never written.
 */
object ExportSections {

    private fun arr(o: Map<String, JsonElement>?, k: String): JsonArray? = o?.get(k) as? JsonArray

    private fun isObjectLike(e: JsonElement?): Boolean = e is JsonObject || e is JsonArray

    /** `sec.has(d)` (app.js:7269-7350). */
    fun has(sec: Section, d: JsonObject): Boolean = when (sec) {
        Section.CHAR -> isObjectLike(d["char"])
        Section.HABITS, Section.DAILIES, Section.TODOS -> d["tasks"] is JsonArray
        Section.REWARDS -> d["rewards"] is JsonArray
        Section.TAGS -> d["tags"] is JsonArray
        Section.PREFS -> isObjectLike(d["prefs"])
        Section.HISTORY -> d["history"] is JsonArray || d["charHistory"] is JsonArray || d["monthlyBackups"] is JsonArray
        Section.DEVICES -> d["devices"] is JsonArray || d["deletions"] is JsonArray
        Section.EVENTS -> d["events"] is JsonArray
    }

    /** `detectExportSections(data)` (app.js:7382-7392): the `_backup.sections` manifest wins. */
    fun detect(data: JsonObject?): List<Section> {
        if (data == null) return emptyList()
        val man = ((data["_backup"] as? JsonObject)?.get("sections") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return Section.entries.filter { sec -> if (man != null) sec.key in man else has(sec, data) }
    }

    /** `sec.count(data)` (app.js:7394-7400). */
    fun count(sec: Section, s: JsonObject): Int = when (sec) {
        Section.CHAR -> if (SyncSubset.jsTruthy(s["char"])) 1 else 0
        Section.HABITS -> tasksOfType(arr(s, "tasks"), "habit").size
        Section.DAILIES -> tasksOfType(arr(s, "tasks"), "daily").size
        Section.TODOS -> tasksOfType(arr(s, "tasks"), "todo").size
        Section.REWARDS -> arr(s, "rewards")?.size ?: 0
        Section.TAGS -> arr(s, "tags")?.size ?: 0
        Section.PREFS -> (s["prefs"] as? JsonObject)?.size ?: 0
        Section.HISTORY -> (arr(s, "history")?.size ?: 0) + (arr(s, "charHistory")?.size ?: 0) + (arr(s, "monthlyBackups")?.size ?: 0)
        Section.DEVICES -> (arr(s, "devices")?.size ?: 0) + (arr(s, "deletions")?.size ?: 0)
        Section.EVENTS -> arr(s, "events")?.size ?: 0
    }

    /** `ioIsFullSelection(keys)`. */
    fun isFull(keys: Collection<Section>): Boolean = Section.entries.all { it in keys }

    /** `_tasksOfType(list, ty)`: `t && t.type === ty`. */
    fun tasksOfType(list: JsonArray?, ty: String): List<JsonElement> = (list ?: JsonArray(emptyList())).filter {
        val t = (it as? JsonObject)?.get("type") as? JsonPrimitive
        t != null && t.isString && t.content == ty
    }

    /** `_ioRecTs(r)`: the larger numeric of updatedAt / createdAt; absent = 0. */
    fun recTs(r: JsonElement?): Double {
        val o = r as? JsonObject ?: return 0.0
        fun num(k: String): Double {
            val p = o[k] as? JsonPrimitive ?: return 0.0
            if (p is JsonNull || p.isString || p.booleanOrNull != null) return 0.0
            return p.doubleOrNull ?: 0.0
        }
        val u = num("updatedAt")
        val c = num("createdAt")
        return if (u > c) u else c
    }

    private fun idKey(r: JsonObject): String? {
        val id = r["id"] ?: return null
        if (id is JsonNull) return null
        return (id as? JsonPrimitive)?.content ?: id.toString()
    }

    /** `_ioMergeById(local, incoming)`: incoming replaces local only when strictly newer; never shrinks. */
    fun mergeById(local: JsonArray?, incoming: JsonArray?): JsonArray {
        val out = (local ?: JsonArray(emptyList())).toMutableList()
        val at = HashMap<String, Int>()
        out.forEachIndexed { i, r -> (r as? JsonObject)?.let { o -> idKey(o)?.let { at[it] = i } } }
        for (r in incoming ?: JsonArray(emptyList())) {
            if (!SyncSubset.jsTruthy(r)) continue
            val o = r as? JsonObject
            val k = o?.let { idKey(it) }
            if (k == null) { out.add(r); continue }
            val i = at[k]
            if (i == null) { at[k] = out.size; out.add(r); continue }
            if (recTs(r) > recTs(out[i])) out[i] = r
        }
        return JsonArray(out)
    }

    /** `_ioMergeObj(local, incoming)`: local wins field by field; incoming only fills missing keys. */
    fun mergeObj(local: JsonElement?, incoming: JsonElement?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        (incoming as? JsonObject)?.let { out.putAll(it) }
        (local as? JsonObject)?.forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    /** `_ioMergeLog(local, incoming)`: concat, de-duplicated by JSON identity. */
    fun mergeLog(local: JsonArray?, incoming: JsonArray?): JsonArray {
        val out = (local ?: JsonArray(emptyList())).toMutableList()
        val seen = HashSet<String>()
        out.forEach { seen += CanonicalJson.canonicalJsonString(it) }
        for (r in incoming ?: JsonArray(emptyList())) {
            val k = CanonicalJson.canonicalJsonString(r)
            if (k in seen) continue
            seen += k
            out.add(r)
        }
        return JsonArray(out)
    }

    /** `_ioApplyTaskType(tgt, data, ty, mode)`: the new block sits where that type's first task was. */
    private fun applyTaskType(tgt: LinkedHashMap<String, JsonElement>, data: JsonObject, ty: String, mode: ImportMode) {
        val inc = tasksOfType(arr(data, "tasks"), ty)
        val cur = tgt["tasks"] as? JsonArray ?: JsonArray(emptyList())
        val next = if (mode == ImportMode.MERGE) mergeById(JsonArray(tasksOfType(cur, ty)), JsonArray(inc)).toList() else inc
        val out = mutableListOf<JsonElement>()
        var placed = false
        for (t in cur) {
            val tt = (t as? JsonObject)?.get("type") as? JsonPrimitive
            if (tt != null && tt.isString && tt.content == ty) {
                if (!placed) { placed = true; out.addAll(next) }
                continue
            }
            out.add(t)
        }
        if (!placed) out.addAll(next)
        tgt["tasks"] = JsonArray(out)
    }

    /**
     * `applySectionsToState(tgt, data, keys, mode)` (app.js:7402-7411): returns a new `S` with the
     * picked sections of [data] applied; everything else of [tgt] is kept as is.
     */
    fun applySectionsToState(tgt: JsonObject, data: JsonObject, keys: Collection<Section>, mode: ImportMode): JsonObject {
        val s = LinkedHashMap<String, JsonElement>(tgt)
        val merge = mode == ImportMode.MERGE
        for (sec in Section.entries) {
            if (sec !in keys || !has(sec, data)) continue
            when (sec) {
                Section.CHAR -> s["char"] = if (merge) mergeObj(s["char"], data["char"]) else data.getValue("char")
                Section.HABITS -> applyTaskType(s, data, "habit", mode)
                Section.DAILIES -> applyTaskType(s, data, "daily", mode)
                Section.TODOS -> applyTaskType(s, data, "todo", mode)
                Section.REWARDS -> s["rewards"] = if (merge) mergeById(arr(s, "rewards"), arr(data, "rewards")) else (arr(data, "rewards") ?: JsonArray(emptyList()))
                Section.TAGS -> s["tags"] = if (merge) mergeById(arr(s, "tags"), arr(data, "tags")) else (arr(data, "tags") ?: JsonArray(emptyList()))
                Section.PREFS -> {
                    s["prefs"] = if (merge) mergeObj(s["prefs"], data["prefs"]) else ((data["prefs"] as? JsonObject) ?: JsonObject(emptyMap()))
                    val lc = data["lastCron"]
                    if (!merge && lc != null && lc !is JsonNull) s["lastCron"] = lc
                }
                Section.HISTORY -> for (k in listOf("history", "charHistory", "monthlyBackups")) {
                    val d = arr(data, k) ?: continue
                    s[k] = if (merge) mergeLog(arr(s, k), d) else d
                }
                Section.DEVICES -> {
                    arr(data, "devices")?.let { d -> s["devices"] = if (merge) mergeById(arr(s, "devices"), d) else d }
                    // Tombstones are never merged away: even Replace keeps the union of both sides.
                    arr(data, "deletions")?.let { d -> s["deletions"] = mergeLog(arr(s, "deletions"), d) }
                }
                Section.EVENTS -> Unit // written by the importer's union-add path, never here
            }
        }
        return JsonObject(s)
    }

    /** Opti `syncSubset()` (sync.js:806-824) of a state-shaped object `S`. */
    fun subsetOf(s: JsonObject): JsonObject {
        fun or(k: String, dflt: JsonElement): JsonElement = s[k]?.takeIf { SyncSubset.jsTruthy(it) } ?: dflt
        val empty = JsonArray(emptyList())
        val prefs = s["prefs"] as? JsonObject
        val anObj = prefs?.get("an")?.takeIf { SyncSubset.jsTruthy(it) } as? JsonObject
        val an = buildJsonObject {
            put("views", anObj?.get("views")?.takeIf { SyncSubset.jsTruthy(it) } ?: empty)
            put("metrics", anObj?.get("metrics")?.takeIf { SyncSubset.jsTruthy(it) } ?: empty)
        }
        val pause = buildJsonObject {
            put("paused", JsonPrimitive(SyncSubset.jsTruthy(prefs?.get("paused"))))
            put("pausedDays", prefs?.get("pausedDays") as? JsonArray ?: empty)
            put("at", prefs?.get("pausedAt")?.takeIf { SyncSubset.jsTruthy(it) } ?: JsonPrimitive(0))
        }
        val devices = or("devices", empty)
        return buildJsonObject {
            put("char", or("char", JsonObject(emptyMap())))
            put("tasks", or("tasks", empty))
            put("rewards", or("rewards", empty))
            put("tags", or("tags", empty))
            put("devices", if (devices is JsonArray) SyncSubset.cleanDevices(devices) else devices)
            put("lastCron", or("lastCron", JsonPrimitive(0)))
            put("history", or("history", empty))
            put("charHistory", or("charHistory", empty))
            put("an", an)
            put("pause", pause)
            put("monthlyBackups", or("monthlyBackups", empty))
            put("deletions", or("deletions", empty))
        }
    }
}

/**
 * WP-8.9 -- the live local state as a backup sees it, read from Room and DataStore. Unlike the
 * legacy `SerializationEngine.exportBackup` (pauseRowExists = false, review 22), the pause comes
 * from the live `sync_prefs` row, so a stale imported pause never reaches a real backup.
 * The prefs overlay adds `haptics`, `autoBackupEnabled` and `lastExportTs` (§4.3), and with
 * [uiPrefs] (WP-8.15) the local UI prefs of [UiPrefsOverlay.KEYS].
 */
class LiveBackupSource(
    private val repository: RpgRepository,
    private val syncDataStore: SyncDataStore,
    private val backupPrefs: BackupPrefs,
    private val uiPrefs: UiPrefsOverlay? = null,
) {
    suspend fun fullLocalState(): FullLocalState = repository.inTransaction {
        val character = repository.getCharacterSync()
        val extras = SerializationEngine.parseExtraJson(repository.getSnapshotExtrasJson())
        val wire = LocalWireState.fromEntities(
            character = character,
            tasks = repository.getAllTasksSync(),
            rewards = repository.getAllRewardsSync(),
            tags = repository.getAllTagsSync(),
            devices = repository.getAllDevicesSync(),
            lastCron = character.lastCron.trim().toIntOrNull() ?: 0,
            history = extras?.get("history") as? JsonArray ?: JsonArray(emptyList()),
            charHistory = repository.getAllCharHistorySync(),
            views = repository.getAllCustomViewsSync(),
            metrics = SerializationEngine.parseJsonArray(repository.getMetricsExtrasJson()),
            syncPrefs = repository.readSyncPrefs(),
            monthlyBackups = syncDataStore.monthlyBackups.first(),
            deletions = repository.getAllTombstonesSync().sortedBy { it.at },
        )
        val lastExport = syncDataStore.lastExportTs.first()
        val prefs = SerializationEngine.overlayJson(extras?.get("prefs") as? JsonObject, buildJsonObject {
            put("haptics", JsonPrimitive(syncDataStore.hapticsEnabled.first()))
            uiPrefs?.read()?.forEach { (k, v) -> put(k, v) }
            put("autoBackupEnabled", backupPrefs.autoBackupEnabled().toJson())
            if (lastExport > 0L) put("lastExportTs", JsonPrimitive(lastExport))
        })
        FullLocalState(wire = wire, prefs = prefs, version = 1, snapshotExtras = extras)
    }

    /** A [BackupExporter] over the live state and the whole event log. */
    fun exporter(
        nowPhysical: () -> Long = { PhysicalClock.nowMs() }, // clock-ok:backup-local-stamp
    ): BackupExporter = DefaultBackupExporter(
        loadState = { fullLocalState() },
        loadEvents = { repository.allLogs.first() },
        deviceId = { syncDataStore.getOrCreateDeviceId() },
        nowPhysical = nowPhysical,
    )

    /** The file text of a full backup (`JSON.stringify(env, null, 2)`). */
    suspend fun backupText(exporter: BackupExporter = exporter()): String = CanonicalJson.stringifyPretty(exporter.export(null))
}

/**
 * WP-8.9 (PX-61, PX-62) -- Opti `importData` + `applyImportSections` (app.js:7997-8090,
 * 8175-8225) onto Room. The file is decoded (unknown event codes refused, hash checked), the
 * picked [Section]s are applied to the live `S` in [ImportMode], and the result is written as a
 * diff ([LocalState.applyDiff]) in ONE Room transaction, so rows of sections that were not picked
 * are never written. Events are union-added only; neither mode removes one.
 *
 * Hand-offs: the caller runs `startDay()` afterwards (Opti does, 8.10) and schedules a sync.
 * [DEFERRED_OPEN_QUESTION] Opti re-parents imported events to this device
 * (`reparentEventsForImport`, `imported: true`); the Kotlin import keeps each event's own
 * uid/dev as the pre-Stage-8 import did (event-log parity is WP-8.7's).
 */
class SectionImporter(
    private val repository: RpgRepository,
    private val syncDataStore: SyncDataStore,
    private val backupPrefs: BackupPrefs,
    /** WP-8.15: restores the local UI prefs of an imported `S.prefs`. */
    private val uiPrefs: UiPrefsOverlay? = null,
    /** The single event write of the import (a test seam for a failed write). */
    private val insertEvent: suspend (EventLogEntity) -> Unit = { repository.insertImportedLog(it) },
) {
    private val localState = LocalState(repository, syncDataStore)

    /** Decode a backup file (schema 1 or 2) into Opti's `data` shape (`S` + `events` + `_backup`). */
    fun decode(file: JsonObject): JsonObject {
        val d = SerializationEngine.decodeBackup(file)
        val out = LinkedHashMap<String, JsonElement>(d.snapshot)
        if (d.isSchema2) {
            out["events"] = JsonArray(d.events)
            d.meta?.let { out["_backup"] = it }
        }
        return JsonObject(out)
    }

    /** The live `S` built from the same subset the diff is taken against (so untouched keys compare equal). */
    private suspend fun liveS(subset: JsonObject): JsonObject {
        val extras = SerializationEngine.parseExtraJson(repository.getSnapshotExtrasJson())
        val pause = subset["pause"] as? JsonObject ?: JsonObject(emptyMap())
        val prefs = LinkedHashMap<String, JsonElement>((extras?.get("prefs") as? JsonObject) ?: JsonObject(emptyMap()))
        prefs["an"] = subset["an"] ?: buildJsonObject { }
        prefs["paused"] = pause["paused"] ?: JsonPrimitive(false)
        prefs["pausedDays"] = pause["pausedDays"] ?: JsonArray(emptyList())
        val at = pause["at"]
        if (at != null && SyncSubset.jsTruthy(at)) prefs["pausedAt"] = at else prefs.remove("pausedAt")
        prefs["haptics"] = JsonPrimitive(syncDataStore.hapticsEnabled.first())
        uiPrefs?.read()?.forEach { (k, v) -> prefs[k] = v }
        prefs["autoBackupEnabled"] = backupPrefs.autoBackupEnabled().toJson()
        val lastExport = syncDataStore.lastExportTs.first()
        if (lastExport > 0L) prefs["lastExportTs"] = JsonPrimitive(lastExport) else prefs.remove("lastExportTs")
        val s = LinkedHashMap<String, JsonElement>()
        s["version"] = JsonPrimitive(1)
        for (k in listOf("char", "tasks", "rewards", "tags", "devices", "lastCron", "history", "charHistory", "monthlyBackups", "deletions")) {
            subset[k]?.let { s[k] = it }
        }
        s["prefs"] = JsonObject(prefs)
        return JsonObject(s)
    }

    suspend fun importBackup(file: JsonObject, sections: Set<Section>, mode: ImportMode): ImportResult {
        val data = decode(file)
        val detected = ExportSections.detect(data)
        if (detected.isEmpty()) throw IllegalArgumentException("That file does not look like a valid Questa backup.")
        // Opti's picker offers only the sections the FILE contains (showImportSectionPicker), so a
        // partial file can never be a "full selection" that replaces absent sections (PX-61).
        val keys = Section.entries.filter { it in sections && it in detected }
        val full = ExportSections.isFull(keys)
        val replacing = mode == ImportMode.REPLACE
        var eventsAdded = 0
        var eventsSkipped = 0
        var eventsFailed = 0

        val changed = repository.inTransaction {
            val local = localState.subset(null)
            val tgt: JsonObject = if (full && replacing) {
                // The historic whole-file import: `S = migrate(data)` (the clobber guard is moot here).
                JsonObject(data.filterKeys { it != "events" && it != "_backup" })
            } else {
                ExportSections.applySectionsToState(liveS(local), data, keys, mode)
            }
            val next = ExportSections.subsetOf(tgt)
            val applied = localState.applyDiff(local, next)
            val prefsTouched = full && replacing || (Section.PREFS in keys && ExportSections.has(Section.PREFS, data))
            if (prefsTouched) writePrefsOverlay(tgt, if (full && replacing) tgt else null)
            if (Section.EVENTS in keys && data["events"] is JsonArray) {
                val u = unionAddEvents((data["events"] as JsonArray).mapNotNull { it as? JsonObject })
                eventsAdded = u.added; eventsSkipped = u.skipped; eventsFailed = u.failed
            }
            if (applied || prefsTouched) repository.stampLocalSaved()
            applied || prefsTouched
        }
        val embedded = Section.EVENTS in keys && data["events"] is JsonArray
        // Opti counts ALL embedded events before dedup (app.js:8556); reparenting changes no kind/taskType.
        val summary = if (embedded) ImportSummary.of(data["events"] as JsonArray) else ImportSummary.EMPTY
        val note = if (embedded) {
            ImportSummary.importNote(eventsAdded, summary, eventsSkipped, full, keys.map { it.key }, replacing)
        } else if (full) "Imported from backup"
        else "Imported sections: " + keys.joinToString("+") { it.key } + " (" + (if (replacing) "replace" else "merge") + ")"
        repository.insertLog(EventLogEntity(
            timestamp = repository.physicalNow(),
            kind = "import",
            taskTitle = "Import Data",
            payloadJson = CanonicalJson.canonicalJsonString(buildJsonObject { put("notes", JsonPrimitive(note)) }),
        ))
        return ImportResult(keys, full, mode, changed, eventsAdded, eventsSkipped, summary, eventsFailed)
    }

    /**
     * The non-wire prefs of the new `S`: the snapshot-extras overlay (all prefs, and on a full
     * replace the whole file `S`), `autoBackupEnabled` (with the `migrate()` backfill from
     * `exportIntervalDays`) and `lastExportTs`. [wholeS] non-null = full replace.
     */
    private suspend fun writePrefsOverlay(tgt: JsonObject, wholeS: JsonObject?) {
        val prefs = tgt["prefs"] as? JsonObject ?: JsonObject(emptyMap())
        val extras: JsonObject = if (wholeS != null) wholeS else {
            val cur = SerializationEngine.parseExtraJson(repository.getSnapshotExtrasJson())
            val m = LinkedHashMap<String, JsonElement>(cur ?: JsonObject(emptyMap()))
            m["prefs"] = prefs
            tgt["lastCron"]?.let { if (cur?.containsKey("lastCron") == true) m["lastCron"] = it }
            JsonObject(m)
        }
        repository.saveSnapshotExtrasJson(SerializationEngine.encodeExtraJson(extras))
        uiPrefs?.write(prefs)
        val ab = prefs["autoBackupEnabled"]
        backupPrefs.setAutoBackupEnabled(
            if (ab is JsonObject && SyncSubset.jsTruthy(ab)) AutoBackupEnabled.fromJson(ab)
            else AutoBackupEnabled.fromExportIntervalDays(AutoBackupEnabled.parseIntJs(prefs["exportIntervalDays"]))
        )
        val ts = (prefs["lastExportTs"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
        syncDataStore.saveLastExportTs(ts)
    }

    /**
     * Union-add of the file's events (never removes), deduplicated by the Opti content signature,
     * then by uid, else by the legacy content key.
     */
    private class UnionAdd(val added: Int, val skipped: Int, val failed: Int)

    private class MonthIndex(val uids: Set<String>, val legacy: Set<String>, val sigs: Set<String>)

    private suspend fun unionAddEvents(events: List<JsonObject>): UnionAdd {
        val monthCache = HashMap<String, MonthIndex>()
        val seen = HashSet<String>()
        val seenSigs = HashSet<String>()
        var added = 0
        var skipped = 0
        var failed = 0
        for (obj in events) {
            try {
                val log = SerializationEngine.parseEventEntity(obj)
                val monthKey = EventSyncEngine.evtMonthKey(log.timestamp)
                val idx = monthCache.getOrPut(monthKey) {
                    val (from, to) = EventSyncEngine.evtMonthRange(monthKey)
                    val logs = repository.getLogsInRangeSync(from, to)
                    MonthIndex(
                        logs.mapNotNull { it.uid }.toSet(),
                        logs.filter { it.uid == null }.map { dedupKey(it) }.toSet(),
                        logs.map { EvtDedup.entitySig(it) }.toSet(),
                    )
                }
                // Opti eventMergeFilter (app.js:1406): the content signature goes first, so the same
                // event stored under another uid (a reparented 'rep-' copy) is a duplicate. The
                // signature holds ts, so the month index holds every stored match.
                // [DEFERRED_OPEN_QUESTION] A uid hit with a DIFFERENT signature (a collision) is still
                // skipped here; Opti keeps it under eventUidDisambiguate's re-minted uid.
                // A record without a whole-number ts parses to 0; Opti skips it, so no signature here
                // (else all such records of one kind would fold into one).
                val sig = if (log.timestamp > 0L && com.example.data.sync.merge.Js.isNumber(obj["ts"])) EvtDedup.entitySig(log) else null
                val key = log.uid ?: dedupKey(log)
                val dup = (sig != null && (sig in idx.sigs || sig in seenSigs)) ||
                    if (log.uid != null) log.uid in idx.uids || key in seen else key in idx.legacy || key in seen
                if (dup) { skipped++; continue }
                seen += key
                if (sig != null) seenSigs += sig
                // Opti reports what bulkAddEvents ACTUALLY wrote (app.js:8566-8570): a failed write is counted.
                // [DEFERRED_OPEN_QUESTION] A disk-full / I/O error can make SQLite roll back the whole Room
                // transaction; the import then ends as an error, not "Import incomplete" (review 2026-10-08).
                try {
                    insertEvent(log)
                    added++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort per record, as the pre-Stage-8 import
            }
        }
        return UnionAdd(added, skipped, failed)
    }

    private fun dedupKey(e: EventLogEntity): String =
        "${e.timestamp}|${e.kind}|${e.taskId}|${e.taskType}|${e.value}|${e.cost}|${e.streak}|${e.damage}|${e.direction}|${e.reps}|${e.taskTitle}"

    /** Convenience: decode the file text first (throws on non-JSON). */
    suspend fun importBackupText(text: String, sections: Set<Section>, mode: ImportMode): ImportResult =
        importBackup(Json.parseToJsonElement(text) as JsonObject, sections, mode)
}
