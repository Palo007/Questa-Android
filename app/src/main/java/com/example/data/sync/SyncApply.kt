package com.example.data.sync

import com.example.data.RpgRepository
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.merge.Js
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * WP-8.6 -- the local side of the round: Opti `syncSubset()` (sync.js:806-827) read from Room,
 * and Opti `syncApply(subset)` (sync.js:833-900) written back as a diff.
 *
 * Both run inside the round's ONE Room transaction (WP-8.5 note "reading local state inside the
 * merge write transaction"), so a user write either lands wholly before the snapshot (and is
 * merged) or wholly after the apply (and is kept).
 */
internal class LocalState(private val repo: RpgRepository, private val ds: SyncDataStore) {

    /**
     * Opti `syncSubset()`: the 12 keys in Opti order, via WP-8.3's [LocalWireState] + [SyncSubset].
     *
     * Room keeps no array position for devices, deletions, views and char history (tasks, rewards
     * and tags have `orderIndex`). Opti keeps its arrays in place, so after an apply its next
     * `syncSubset()` equals the merged state. To match that, those four arrays are put in the
     * order of [base] (the last state both sides agreed on); ids the base lacks keep Room's
     * order after them, and char history is sorted by date as merge() sorts it. Without this, a
     * Kotlin device and a PWA device would re-upload the same content in two orders forever.
     */
    suspend fun subset(base: JsonObject? = null): JsonObject {
        val character = repo.getCharacterSync()
        val wire = LocalWireState.fromEntities(
            character = character,
            tasks = repo.getAllTasksSync(),
            rewards = repo.getAllRewardsSync(),
            tags = repo.getAllTagsSync(),
            devices = repo.getAllDevicesSync(),
            // SY-17 (PX-37): the persisted cron day (Opti `S.lastCron || 0`), not the latest cron event.
            lastCron = character.lastCron.trim().toIntOrNull() ?: 0,
            // [DEFERRED_OPEN_QUESTION] top-level S.history is always [] in Opti (sync.js:800-805); not stored in Room.
            history = JsonArray(emptyList()),
            charHistory = repo.getAllCharHistorySync(),
            views = repo.getAllCustomViewsSync(),
            metrics = SerializationEngine.parseJsonArray(repo.getMetricsExtrasJson()),
            syncPrefs = repo.readSyncPrefs(),
            monthlyBackups = ds.monthlyBackups.first(),
            deletions = repo.getAllTombstonesSync().sortedBy { it.at },
        )
        val built = SyncSubset.build(wire)
        val out = LinkedHashMap<String, JsonElement>(built)
        out["devices"] = orderLike(built["devices"] as JsonArray, base?.get("devices"))
        out["deletions"] = orderLike(built["deletions"] as JsonArray, base?.get("deletions"))
        out["charHistory"] = JsonArray((built["charHistory"] as JsonArray).sortedBy { dateOf(it) })
        val an = built["an"] as JsonObject
        out["an"] = JsonObject(linkedMapOf(
            "views" to orderLike(an["views"] as JsonArray, (base?.get("an") as? JsonObject)?.get("views")),
            "metrics" to (an["metrics"] ?: JsonArray(emptyList())),
        ))
        return JsonObject(out)
    }

    private fun dateOf(e: JsonElement): Double =
        ((e as? JsonObject)?.get("date") as? JsonPrimitive)?.doubleOrNull ?: 0.0

    /** [arr] with the ids of [baseArr] first, in its order; the rest keep their order. */
    private fun orderLike(arr: JsonArray, baseArr: JsonElement?): JsonArray {
        val b = baseArr as? JsonArray ?: return arr
        val rank = HashMap<String, Int>()
        b.forEachIndexed { i, e -> idOf(e)?.let { rank.putIfAbsent(it, i) } }
        if (rank.isEmpty()) return arr
        val indexed = arr.withIndex().toList()
        return JsonArray(indexed.sortedWith(compareBy({ rank[idOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value })
    }

    /**
     * Opti `syncApply(merged)`: writes only what differs from [local] (the snapshot the merge
     * read), inside [RpgRepository.syncApplying] so nothing re-schedules a sync (SY-04). Returns
     * true when anything changed (Opti `_changed`); the caller then stamps localSavedAt once.
     */
    suspend fun applyDiff(local: JsonObject, merged: JsonObject): Boolean {
        if (StableJson.deepEqual(local, merged)) return false
        var changed = false
        repo.syncApplying {
            changed = applyChar(local, merged) or changed
            changed = applyList(local, merged, "tasks",
                upsert = { i, o -> repo.insertTask(SerializationEngine.parseTaskEntity(o).copy(orderIndex = i.toLong())) },
                remove = { repo.deleteTaskRowOnly(it) }) or changed
            changed = applyList(local, merged, "rewards",
                upsert = { i, o -> repo.insertReward(SerializationEngine.parseRewardEntity(o).copy(orderIndex = i.toLong())) },
                remove = { repo.deleteRewardRowOnly(it) }) or changed
            changed = applyList(local, merged, "tags",
                upsert = { i, o -> repo.insertTag(SerializationEngine.parseTagEntity(o).copy(orderIndex = i.toLong())) },
                remove = { repo.deleteTagRowOnly(it) }) or changed
            // Opti `S.devices = cleanDevices(subset.devices)`: whole-array replace, so a device the
            // merge dropped is removed too (review 26 C1).
            changed = applyList(local, merged, "devices",
                upsert = { _, o -> repo.insertDevice(SerializationEngine.parseDeviceEntity(o)) },
                remove = { repo.deleteDeviceRowOnly(it) }) or changed
            // Opti `S.charHistory = subset.charHistory`. Rows carry no `id`: the key is `date`
            // (the Room PK), and a same-day loser of mergeDayArray is removed (review 26 C1).
            changed = applyList(local, merged, "charHistory", keyOf = ::charHistoryKey,
                upsert = { _, o -> repo.insertCharHistory(SerializationEngine.parseCharHistoryEntity(o)) },
                remove = { repo.deleteCharHistoryRowOnly(it.toLong()) }) or changed
            changed = applyDeletions(local, merged) or changed
            changed = applyAn(local, merged) or changed
            changed = applyPause(local, merged) or changed
            val mb = merged["monthlyBackups"]
            if (mb is JsonArray && !StableJson.deepEqual(mb, local["monthlyBackups"])) {
                ds.saveMonthlyBackups(mb.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                changed = true
            }
        }
        return changed
    }

    /** `S.char = subset.char || S.char`; `if(subset.lastCron) S.lastCron = subset.lastCron`. Local-only char columns are kept. */
    private suspend fun applyChar(local: JsonObject, merged: JsonObject): Boolean {
        val mc = merged["char"] as? JsonObject
        val lastCron = merged["lastCron"]
        val charChanged = mc != null && !StableJson.deepEqual(mc, local["char"])
        val cronTruthy = lastCron != null && SyncSubset.jsTruthy(lastCron)
        val cronChanged = cronTruthy && !StableJson.deepEqual(lastCron, local["lastCron"])
        if (!charChanged && !cronChanged) return false
        val cur: CharacterEntity = repo.getCharacterSync()
        var next = if (charChanged) {
            SerializationEngine.parseCharacterEntity(mc!!).copy(
                id = cur.id,
                needsDeathAcknowledge = cur.needsDeathAcknowledge,
                dayStart = cur.dayStart,
                lastCron = cur.lastCron,
            )
        } else cur
        if (cronChanged) next = next.copy(lastCron = SerializationEngine.lastCronFromJson(lastCron))
        repo.insertCharacter(next)
        return true
    }

    private fun idOf(e: JsonElement?): String? = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

    /** charHistory key: the `date` as Room stores it ([SerializationEngine.parseCharHistoryEntity]). */
    private fun charHistoryKey(e: JsonElement?): String? =
        (e as? JsonObject)?.let { SerializationEngine.parseCharHistoryEntity(it).date.toString() }

    /**
     * Replace semantics by key ([keyOf], `id` by default); a record is written when its JSON or
     * its position changed, and a local key absent from [merged] is removed (Opti replaces the
     * whole array). Rows without a key are skipped, as Room cannot hold them.
     */
    private suspend fun applyList(
        local: JsonObject,
        merged: JsonObject,
        key: String,
        keyOf: (JsonElement?) -> String? = ::idOf,
        upsert: suspend (Int, JsonObject) -> Unit,
        remove: suspend (String) -> Unit,
    ): Boolean {
        val m = merged[key] as? JsonArray ?: return false // an absent key keeps local (sync.js:855-862)
        val l = local[key] as? JsonArray ?: JsonArray(emptyList())
        if (StableJson.deepEqual(m, l)) return false
        val localAt = HashMap<String, Pair<Int, JsonElement>>()
        l.forEachIndexed { i, e -> keyOf(e)?.let { localAt[it] = i to e } }
        var changed = false
        val keep = HashSet<String>()
        m.forEachIndexed { i, e ->
            val o = e as? JsonObject ?: return@forEachIndexed
            val id = keyOf(o) ?: return@forEachIndexed
            keep += id
            val prev = localAt[id]
            if (prev == null || prev.first != i || !StableJson.deepEqual(prev.second, o)) {
                upsert(i, o)
                changed = true
            }
        }
        for (id in localAt.keys) if (id !in keep) { remove(id); changed = true }
        return changed
    }

    /** `S.deletions = subset.deletions` (tombstone `at` never invented on parse, PX-20). */
    private suspend fun applyDeletions(local: JsonObject, merged: JsonObject): Boolean {
        val m = merged["deletions"] as? JsonArray ?: return false
        if (StableJson.deepEqual(m, local["deletions"])) return false
        val want = LinkedHashMap<String, Long>()
        for (e in m) {
            val o = e as? JsonObject ?: continue
            val id = idOf(o) ?: continue
            val at = (o["at"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
            want[id] = at
        }
        val have = repo.getAllTombstonesSync().associate { it.id to it.at }
        var changed = false
        for ((id, _) in have) if (id !in want) { repo.deleteTombstoneRowOnly(id); changed = true }
        for ((id, at) in want) if (have[id] != at) { repo.insertTombstone(TombstoneEntity(id = id, at = at)); changed = true }
        return changed
    }

    /** `S.prefs.an.views/metrics` when `subset.an` is present. Native (source == null) views are never touched. */
    private suspend fun applyAn(local: JsonObject, merged: JsonObject): Boolean {
        val an = merged["an"] as? JsonObject ?: return false
        val la = local["an"] as? JsonObject
        var changed = false
        val views = an["views"] as? JsonArray
        if (views != null && !StableJson.deepEqual(views, la?.get("views"))) {
            val parsed = views.mapNotNull { (it as? JsonObject)?.let { o -> SerializationEngine.parseViewEntity(o) } }
            val ids = parsed.map { it.id }.toSet()
            repo.getAllCustomViewsSync().forEach { cv -> if (cv.source != null && cv.id !in ids) repo.deleteCustomViewById(cv.id) }
            parsed.forEach { repo.insertCustomView(it) }
            changed = true
        }
        val metrics = an["metrics"] as? JsonArray
        if (metrics != null && !StableJson.deepEqual(metrics, la?.get("metrics"))) {
            repo.saveMetricsExtrasJson(SerializationEngine.encodeJsonArray(metrics))
            changed = true
        }
        return changed
    }

    /**
     * Opti (sync.js:878-889): `paused = !!p.paused`; `pausedDays` only when an array;
     * `pausedAt` only when `p.at` is truthy. Writes the v7 `sync_prefs` row.
     */
    private suspend fun applyPause(local: JsonObject, merged: JsonObject): Boolean {
        val p = merged["pause"] as? JsonObject ?: return false
        if (StableJson.deepEqual(p, local["pause"])) return false
        val cur = repo.readSyncPrefs()
        val paused = SyncSubset.jsTruthy(p["paused"])
        val days = (p["pausedDays"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let { x -> x.longOrNull?.toInt() ?: x.doubleOrNull?.toInt() } }
        val at = p["at"]?.takeIf { SyncSubset.jsTruthy(it) }?.let { (it as? JsonPrimitive)?.let { x -> x.longOrNull ?: x.doubleOrNull?.toLong() } }
        val next = SyncPrefsEntity(
            id = 0,
            paused = paused,
            pausedDays = days ?: cur?.pausedDays ?: emptyList(),
            pausedAt = at ?: cur?.pausedAt,
            extraJson = cur?.extraJson,
        )
        if (next == cur) return false
        repo.writeSyncPrefs(next)
        return true
    }
}

/** WP-8.6 -- round arithmetic ported from sync.js. */
internal object RoundMath {
    /** sync.js `Number(x)||0` (P3' / KT-51: the shared JS port, so "0x10" -> 16, "5f" -> 0, [5] -> 5). */
    private fun num(e: JsonElement?): Double = Js.num(e)

    private fun arr(o: JsonObject?, k: String): List<JsonObject> =
        ((o?.get(k)) as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /**
     * `_maxOrderingTs` (sync.js:2405-2426), including the top-level `an.views/metrics`
     * (2026-09-18 fix, SY-24).
     */
    fun maxOrderingTs(st: JsonObject?): Long {
        if (st == null) return 0L
        var mx = 0.0
        fun see(v: Double) { if (v > mx) mx = v }
        arr(st, "tasks").forEach { t ->
            see(num(t["updatedAt"]))
            arr(t, "checklist").forEach { c -> see(num(c["touchedAt"])) }
        }
        see(num((st["char"] as? JsonObject)?.get("updatedAt")))
        arr(st, "devices").forEach { see(num(it["updatedAt"])) }
        arr(st, "deletions").forEach { see(num(it["at"])) }
        arr(st, "tags").forEach { see(num(it["updatedAt"])) }
        arr(st, "rewards").forEach { see(num(it["updatedAt"])) }
        val pv = (st["an"] as? JsonObject) ?: ((st["prefs"] as? JsonObject)?.get("an") as? JsonObject)
        arr(pv, "views").forEach { see(num(it["updatedAt"])) }
        arr(pv, "metrics").forEach { see(num(it["updatedAt"])) }
        // [DEFERRED_OPEN_QUESTION] Opti returns the raw Number `mx` (fractional or Infinity);
        // the Long HLC truncates / clamps it here (KT-51, Momus 13).
        return if (mx >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else mx.toLong()
    }
}
