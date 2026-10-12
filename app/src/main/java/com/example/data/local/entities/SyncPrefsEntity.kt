package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.clock.DayStamp
import kotlinx.serialization.Serializable

/**
 * WP-8.2 (Wave 1, schema v7): the Room home for Opti's `S.pause` object (PARITY_FIX_PLAN.html
 * §4.3 "Room sync_prefs (v7): pause", wire? yes via `pause`). Single-row table (`id = 0`), same
 * pattern as [SnapshotExtrasEntity]. WP-8.10 owns the actual pause read/write behavior and
 * [com.example.data.sync.PauseMerge] wiring (WP-8.4/8.5); WP-8.2 only creates the table and its
 * typed columns so the v6->v7 migration has somewhere to land.
 *
 * `pausedDays`/`pausedAt` null = "no pause fields known" (fresh row / never paused), matching the
 * TaskEntity "(+) null = absent" convention this same WP uses for its own new columns.
 */
@Serializable
@Entity(tableName = "sync_prefs")
data class SyncPrefsEntity(
    @PrimaryKey val id: Int = 0,
    val paused: Boolean?,
    val pausedDays: List<DayStamp>?,
    val pausedAt: Long?,
    val extraJson: String?
)
