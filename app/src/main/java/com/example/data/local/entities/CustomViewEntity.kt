package com.example.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape", §7 D3) -- this table pre-dates the
 * PWA-parity effort and originally modeled ONLY a Kotlin-native "quick view" feature
 * (`title` + composite `chartType` = "<metricKeyword>|<days>", see `Stage6FeatureTest`,
 * `MainViewModel.createCustomView`, `DashboardScreen`'s chartType-parsing code) that has no
 * PWA counterpart. `name`/`source`/`group`/`chart`/`tags`/`types`/`metricId`/`updatedAt`/
 * `extraJson` below are ADDITIVE fields mapping this same table to the PWA's real
 * `S.prefs.an.views` shape (spec §3), so the two features now share storage:
 * - Native quick-views (created via `createCustomView(title, chartType)`) leave all the new
 *   fields at their defaults (`source == null`).
 * - PWA-synced views (round-tripped via `SerializationEngine.parseViewEntity`) always have
 *   `source` set (a required PWA field, one of V_SOURCES) -- this is the marker
 *   `buildLocalSnapshot`/`exportBackup`/`applyMergedSnapshotToDatabase` use to select which rows
 *   participate in the synced `an.views` collection, so native quick-view rows are never leaked
 *   onto the wire and PWA-synced rows are never silently deleted by native-only code paths.
 * `extraJson` follows the same overlay pattern as every other per-record entity (T3.1): verbatim
 * copy of the last-seen original view object, so a future/legacy PWA view key this table doesn't
 * model still round-trips losslessly.
 *
 * Known scope gap (not fixed here): `DashboardScreen`'s existing "quick view" UI reads `title`/
 * `chartType` off of every row in this table with no `source`-based filter, so a PWA-synced view
 * will also appear in that native list (with `chartType` = the PWA `source` enum string, e.g.
 * "reps", rather than the "<keyword>|<days>" composite that UI expects). Fixing that display is a
 * UI task (Stage 6/7), out of scope for this data/sync-correctness task.
 */
@Serializable
@Entity(tableName = "custom_views")
data class CustomViewEntity(
    @PrimaryKey val id: String,
    val title: String,
    val chartType: String, // "tag_summary", "completions_line", "heatmap", "item_breakdown"
    val createdAt: Long, // WP-8.23: no clock default (clock baseline 0); the caller passes the stamp
    // --- PWA `an.views` shape (spec §3 T3.2a) ---
    val name: String? = null,
    val source: String? = null,
    val group: String? = null,
    val chart: String? = null,
    // `@ColumnInfo(defaultValue = ...)` is REQUIRED here, not cosmetic: SQLite's `ALTER TABLE ...
    // ADD COLUMN` (used by MIGRATION_4_5 below) rejects a NOT NULL column with no DEFAULT on a
    // non-empty table, so the migration's raw SQL must specify `DEFAULT '[]'` -- and Room's
    // migration validator separately compares the ACTUAL post-migration schema against the
    // EXPECTED schema it derives from these entity annotations, so that expected schema must
    // declare the identical default or validation fails at runtime even though the ALTER TABLE
    // itself succeeded. Both sides must agree; this annotation is what makes them agree.
    @ColumnInfo(defaultValue = "'[]'") val tags: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val types: List<String> = emptyList(),
    val metricId: String? = null,
    val updatedAt: Long? = null,
    val extraJson: String? = null
)
