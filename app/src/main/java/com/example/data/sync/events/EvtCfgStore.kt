package com.example.data.sync.events

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * WP-8.7 (§4.3) -- the event-engine part of Opti's `syncCfg()`: `evtFileCounts`, `evtPushBlocked`,
 * `evtFullPushAt`, `evtFileRevs`, `evtBadRevs`, `evtLastPullAt`, `evtFullScanAt`, plus the
 * `questa.events.pendingPurgeDev` localStorage key. `evtLastUploadTs` is NOT here: it lives behind
 * [com.example.data.sync.EventWatermark] (8.6).
 *
 * Maps are kept as raw JSON values in insertion order, so an entry is read back exactly as it
 * was written (Opti persists them through JSON.stringify).
 */
data class EvtCfg(
    val fullPushAt: Long = 0L,
    val fileCounts: Map<String, JsonElement> = emptyMap(),
    val pushBlocked: Map<String, JsonElement> = emptyMap(),
    val fileRevs: Map<String, JsonElement> = emptyMap(),
    val badRevs: Map<String, JsonElement> = emptyMap(),
    val lastPullAt: Long = 0L,
    val fullScanAt: Long = 0L,
)

interface EvtCfgStore {
    suspend fun read(): EvtCfg

    /** The push's final `syncCfgSave` (sync.js:3445-3456), without the watermark. One edit. */
    suspend fun savePush(fullPushAt: Long, fileCounts: Map<String, JsonElement>, pushBlocked: Map<String, JsonElement>)

    /** The empty-push bootstrap `syncCfgSave({evtFullPushAt})` (sync.js:3322). */
    suspend fun saveFullPushAt(ts: Long)

    /** The pull's final `syncCfgSave` (sync.js:3607-3612). One edit. */
    suspend fun savePull(fileRevs: Map<String, JsonElement>, badRevs: Map<String, JsonElement>, lastPullAt: Long, fullScanAt: Long)

    /** Reads and removes PENDING_PURGE_DEV in one edit (Opti: getItem, then removeItem). */
    suspend fun takePendingPurgeDev(): String?

    /** WP-8.9's reset-while-disconnected hand-off (app.js:6648); null removes it. */
    suspend fun setPendingPurgeDev(deviceId: String?)
}

/**
 * The DataStore home. `EVT_PUSH_BLOCKED` is a boolean key (SyncKeys, WP-8.2) that the 8.21 restore
 * reset clears; Opti's value is a map `{fname: {at, local, known}}`. So the map is stored under
 * [EVT_PUSH_BLOCKED_FILES] and the boolean is its "any blocked" flag: the map is read only while
 * the flag is true, so every writer that clears the flag (restore reset) also clears the map.
 */
class DataStoreEvtCfgStore(private val prefs: DataStore<Preferences>) : EvtCfgStore {

    companion object {
        /** New key (8.7): the per-file detail of [SyncKeys.EVT_PUSH_BLOCKED]. */
        val EVT_PUSH_BLOCKED_FILES = stringPreferencesKey("evt_push_blocked_files")

        private fun parseMap(raw: String?): Map<String, JsonElement> = try {
            (raw?.let { Json.parseToJsonElement(it) } as? JsonObject)?.let { LinkedHashMap<String, JsonElement>(it) } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        private fun write(m: Map<String, JsonElement>): String = JsonObject(m).toString()
    }

    override suspend fun read(): EvtCfg {
        val p = prefs.data.first()
        return EvtCfg(
            fullPushAt = p[SyncKeys.EVT_FULL_PUSH_AT] ?: 0L,
            fileCounts = parseMap(p[SyncKeys.EVT_FILE_COUNTS]),
            pushBlocked = if (p[SyncKeys.EVT_PUSH_BLOCKED] == true) parseMap(p[EVT_PUSH_BLOCKED_FILES]) else emptyMap(),
            fileRevs = parseMap(p[SyncDataStore.EVT_FILE_REVS]),
            badRevs = parseMap(p[SyncKeys.EVT_BAD_REVS]),
            lastPullAt = p[SyncDataStore.EVT_LAST_PULL_AT] ?: 0L,
            fullScanAt = p[SyncKeys.EVT_FULL_SCAN_AT] ?: 0L,
        )
    }

    override suspend fun savePush(fullPushAt: Long, fileCounts: Map<String, JsonElement>, pushBlocked: Map<String, JsonElement>) {
        prefs.edit { p ->
            p[SyncKeys.EVT_FULL_PUSH_AT] = fullPushAt
            p[SyncKeys.EVT_FILE_COUNTS] = write(fileCounts)
            p[EVT_PUSH_BLOCKED_FILES] = write(pushBlocked)
            p[SyncKeys.EVT_PUSH_BLOCKED] = pushBlocked.isNotEmpty()
        }
    }

    override suspend fun saveFullPushAt(ts: Long) {
        prefs.edit { it[SyncKeys.EVT_FULL_PUSH_AT] = ts }
    }

    override suspend fun savePull(fileRevs: Map<String, JsonElement>, badRevs: Map<String, JsonElement>, lastPullAt: Long, fullScanAt: Long) {
        prefs.edit { p ->
            p[SyncDataStore.EVT_FILE_REVS] = write(fileRevs)
            p[SyncKeys.EVT_BAD_REVS] = write(badRevs)
            p[SyncDataStore.EVT_LAST_PULL_AT] = lastPullAt
            p[SyncKeys.EVT_FULL_SCAN_AT] = fullScanAt
        }
    }

    override suspend fun takePendingPurgeDev(): String? {
        var taken: String? = null
        prefs.edit { p ->
            taken = p[SyncKeys.PENDING_PURGE_DEV]
            p.remove(SyncKeys.PENDING_PURGE_DEV)
        }
        return taken
    }

    override suspend fun setPendingPurgeDev(deviceId: String?) {
        prefs.edit { p -> if (deviceId == null) p.remove(SyncKeys.PENDING_PURGE_DEV) else p[SyncKeys.PENDING_PURGE_DEV] = deviceId }
    }
}

/** Test and preview home. */
class InMemoryEvtCfgStore(var cfg: EvtCfg = EvtCfg(), var pendingPurgeDev: String? = null) : EvtCfgStore {
    override suspend fun read(): EvtCfg = cfg
    override suspend fun savePush(fullPushAt: Long, fileCounts: Map<String, JsonElement>, pushBlocked: Map<String, JsonElement>) {
        cfg = cfg.copy(fullPushAt = fullPushAt, fileCounts = LinkedHashMap(fileCounts), pushBlocked = LinkedHashMap(pushBlocked))
    }
    override suspend fun saveFullPushAt(ts: Long) { cfg = cfg.copy(fullPushAt = ts) }
    override suspend fun savePull(fileRevs: Map<String, JsonElement>, badRevs: Map<String, JsonElement>, lastPullAt: Long, fullScanAt: Long) {
        cfg = cfg.copy(fileRevs = LinkedHashMap(fileRevs), badRevs = LinkedHashMap(badRevs), lastPullAt = lastPullAt, fullScanAt = fullScanAt)
    }
    override suspend fun takePendingPurgeDev(): String? = pendingPurgeDev.also { pendingPurgeDev = null }
    override suspend fun setPendingPurgeDev(deviceId: String?) { pendingPurgeDev = deviceId }
}
