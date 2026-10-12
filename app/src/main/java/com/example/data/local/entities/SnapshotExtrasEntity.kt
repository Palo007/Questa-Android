package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Stage 3 / T3.1 (unknown-field passthrough, spec §3 / §7 D3) -- snapshot-level extras blob.
 *
 * Single-row table (`id` is always 1) holding a verbatim compact-JSON copy of the last-seen
 * ORIGINAL top-level backup `S` object (spec §1.2 "Top-level `S` object"), captured at import
 * time (`SerializationEngine.importBackup` -> `BackupData.rawSnapshot`) and re-applied on export
 * (`SerializationEngine.exportBackup`'s `snapshotExtras` parameter) via the same
 * store-whole-object-then-overlay-fresh-values-in-place mechanism used per-record (see
 * `SerializationEngine.overlayJson`).
 *
 * This exists specifically to carry forward top-level fields Kotlin does not model at all --
 * `prefs` (only `prefs.haptics` is synthesized by Kotlin today; every other PWA pref, e.g.
 * `prefs.width`, `prefs.an.*`, `prefs.exportIntervalDays`, would otherwise be silently replaced
 * by the hardcoded `{"haptics": true}` object on every export), and genuinely-unknown top-level
 * keys such as the spec's example decoy `__futureField`, or the full-`S`-only bookkeeping keys
 * `__seq`/`__hlcLast`/`__savedAt` that appear in a backup file's `S` (but are correctly excluded
 * from the narrower Dropbox `state.json` `syncSubset()`, spec §3 -- that exclusion is unrelated
 * to this table and out of scope for T3.1/D12).
 *
 * Deliberately scoped to the BACKUP FILE round-trip (`exportSnapshotToFile`/
 * `importSnapshotFromFile`) only, not the Dropbox `state.json` wire (`buildLocalSnapshot`/
 * `mergeSnapshots`/`applyMergedSnapshotToDatabase`): `state.json`'s `state` body is deliberately a
 * narrower field set per spec §3's `syncSubset()` table (Stage 1 T1.2, aligned in Stage 3 T3.5 /
 * D12), so injecting arbitrary top-level extras onto that wire would fight T3.5's field-list
 * alignment rather than complement it. Per-RECORD extras (task/reward/tag/device/char
 * `extraJson` columns) are NOT scoped this way -- those travel through both the backup-file path
 * and the Dropbox sync/merge path, since a task's `reminders[]` must survive a Dropbox round-trip
 * just as much as a manual export/import.
 */
@Serializable
@Entity(tableName = "snapshot_extras")
data class SnapshotExtrasEntity(
    @PrimaryKey val id: Int = 1,
    val extraJson: String? = null
)
