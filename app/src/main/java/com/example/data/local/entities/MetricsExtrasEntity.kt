package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape", §7 D3) -- opaque passthrough storage
 * for the PWA's `S.prefs.an.metrics` collection (backup shape) / top-level `an.metrics` (Dropbox
 * `syncSubset()` shape).
 *
 * There is no first-class Kotlin feature that creates or edits metrics (no UI, no per-field
 * entity) -- unlike `CustomViewEntity`/`an.views`, which maps an existing Kotlin feature onto the
 * PWA shape, metrics have NO Kotlin storage at all today. Per the task's own explicit fallback
 * ("store them as an opaque snapshot-extras-style passthrough... rather than inventing an
 * entity"), this single-row table holds the last-known `an.metrics` JSON array VERBATIM
 * (compact-JSON, via `SerializationEngine.encodeJsonArray`/`parseJsonArray`) rather than modeling
 * `{id, name, keyword, exact, habits, createdAt, updatedAt}` as Kotlin fields. This is enough for
 * lossless round-trip (import -> merge -> export) because `SerializationEngine.mergeCollection`
 * already operates generically on `JsonObject` arrays keyed by `id`/`updatedAt` -- no per-field
 * Kotlin modeling is needed to merge or re-emit a metric object, only a place to persist the
 * result between calls (a single import/export call chain does not keep `BackupData` around).
 *
 * Unlike `SnapshotExtrasEntity` (deliberately scoped to the backup-file path ONLY), this table is
 * read/written by BOTH the backup-file path (`SerializationEngine.importBackup`/`exportBackup`)
 * and the Dropbox `state.json` sync path (`SyncManager.buildLocalSnapshot`/
 * `applyMergedSnapshotToDatabase`), because `an.metrics` is part of PWA's `syncSubset()` (spec
 * §3) on both wires -- first-class per-field metric modeling (a real `MetricEntity`, editor UI,
 * etc.) is left to a later feature task, per the T3.2 task text.
 */
@Serializable
@Entity(tableName = "metrics_extras")
data class MetricsExtrasEntity(
    @PrimaryKey val id: Int = 1,
    val json: String? = null
)
