package com.example.data.sync

import com.example.data.clock.DayStamp
import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TombstoneEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * WP-8.3: everything Opti's `syncSubset()` (sync.js:806-824) reads from `S`, already in wire
 * JSON form. [SyncSubset.build] turns it into the state object of `/state.json`.
 *
 * Prefs stay off the wire except `an` ([anViews], [anMetrics]) and `pause` ([pause]). `pause` is
 * `{paused, pausedDays, at}` (see [pauseWire]) -- always fully populated (defaulted, never null),
 * since Opti's own `syncSubset()` (sync.js:808) always emits it unconditionally, defaults and all.
 *
 * [pauseRowExists] is separate: whether a genuine `SyncPrefsEntity` row backs [pause], vs. it being
 * pure fabricated defaults for a caller with no pause source at all (the legacy `exportBackup()`
 * path, and hand-built test/golden states). [FullLocalState.toSnapshot] uses this to decide whether
 * to fold [pause] into the backup's `prefs` (WP-8.3 review finding #1) -- a real device's pause
 * state must land in `S.prefs.paused/pausedDays/pausedAt`, but a caller with no pause source
 * shouldn't fabricate `paused:false` into a backup that never had that key (real Opti backups can
 * genuinely lack it, e.g. fixtures/real_backup.json).
 */
data class LocalWireState(
    val char: JsonObject,
    val tasks: JsonArray,
    val rewards: JsonArray,
    val tags: JsonArray,
    val devices: JsonArray,
    val lastCron: DayStamp,
    val history: JsonArray,
    val charHistory: JsonArray,
    val anViews: JsonArray,
    val anMetrics: JsonArray,
    val pause: JsonObject,
    val monthlyBackups: JsonArray,
    val deletions: JsonArray,
    val pauseRowExists: Boolean = true
) {
    companion object {

        /**
         * Room/DataStore rows -> wire JSON ("8.3 map", §4.3). Each record goes through its
         * SerializationEngine builder, so unknown fields ride along from `extraJson`.
         *
         *  - [character] null (no row yet) gives `{}`, like Opti's `S.char || {}`. The char never
         *    carries the Kotlin-only keys (PX-23, [SerializationEngine.buildCharWireJson]).
         *  - Only PWA-shaped views (`source != null`) are on the wire; native quick views are not.
         *  - [metrics] are the opaque `an.metrics` objects (MetricsExtrasEntity).
         *  - [deletions] become `{id, at}`.
         */
        fun fromEntities(
            character: CharacterEntity?,
            tasks: List<TaskEntity>,
            rewards: List<RewardEntity>,
            tags: List<TagEntity>,
            devices: List<DeviceEntity>,
            lastCron: DayStamp,
            history: JsonArray,
            charHistory: List<CharHistoryEntity>,
            views: List<CustomViewEntity>,
            metrics: JsonArray,
            syncPrefs: SyncPrefsEntity?,
            monthlyBackups: List<String>,
            deletions: List<TombstoneEntity>
        ): LocalWireState = LocalWireState(
            char = character?.let { SerializationEngine.buildCharWireJson(it) } ?: JsonObject(emptyMap()),
            tasks = JsonArray(tasks.map { SerializationEngine.buildTaskJson(it) }),
            rewards = JsonArray(rewards.map { SerializationEngine.buildRewardJson(it) }),
            tags = JsonArray(tags.map { SerializationEngine.buildTagJson(it) }),
            devices = JsonArray(devices.map { SerializationEngine.buildDeviceJson(it) }),
            lastCron = lastCron,
            history = history,
            charHistory = JsonArray(charHistory.map { SerializationEngine.buildCharHistoryJson(it) }),
            anViews = JsonArray(views.filter { it.source != null }.map { SerializationEngine.buildViewJson(it) }),
            anMetrics = metrics,
            pause = pauseWire(syncPrefs),
            monthlyBackups = JsonArray(monthlyBackups.map { JsonPrimitive(it) }),
            deletions = JsonArray(deletions.map { d ->
                buildJsonObject {
                    put("id", JsonPrimitive(d.id))
                    put("at", JsonPrimitive(d.at))
                }
            }),
            pauseRowExists = syncPrefs != null
        )

        /**
         * sync.js:808: `{ paused: !!prefs.paused, pausedDays: Array.isArray(prefs.pausedDays) ?
         * prefs.pausedDays : [], at: prefs.pausedAt || 0 }`.
         */
        fun pauseWire(prefs: SyncPrefsEntity?): JsonObject = buildJsonObject {
            put("paused", JsonPrimitive(prefs?.paused == true))
            put("pausedDays", JsonArray((prefs?.pausedDays ?: emptyList()).map { JsonPrimitive(it) }))
            put("at", JsonPrimitive(prefs?.pausedAt ?: 0L))
        }
    }
}
