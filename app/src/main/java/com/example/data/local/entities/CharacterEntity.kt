package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "character")
data class CharacterEntity(
    @PrimaryKey val id: Int = 1,
    val name: String = "Adventurer",
    val face: String = "🧙",
    val cls: String = "Warrior",
    val lvl: Int = 1,
    val xp: Int = 0,
    val hp: Double = 50.0,
    val maxHp: Double = 50.0,
    val mp: Int = 0,
    val gold: Double = 0.0,
    val needsDeathAcknowledge: Boolean = false,
    val lastCron: String = "",
    // Stage 6 / T6.3: kept for schema stability and wire round-trip only. No UI sets this; every
    // production caller passes/reads 0. Do NOT build a settings UI for this without a
    // corresponding PWA-side port first (see plans/stages/stage_6_behavior_parity.md T6.3,
    // locked decision Pali 2026-07-19).
    val dayStart: Int = 0,
    val updatedAt: Long, // WP-8.23: no clock default; the caller passes the HLC stamp
    // Stage 3 / T3.1 (unknown-field passthrough): verbatim copy of the original PWA `char`
    // object (compact JSON), null if there is no known PWA original (fresh character). See
    // SerializationEngine.buildCharJson/parseCharacterEntity.
    val extraJson: String? = null,
    // WP-8.2 (schema v7, D9, review r1 blocker 3) -- new typed column backfilled from `extraJson`
    // by MIGRATION_6_7. `abs[deviceId] = {ua, xp, gold, mp}` (sync.js:1699-1712, `_absEntry`) is
    // the conflict-retry absorption watermark for character totals -- see CharAbsEntry's KDoc.
    // null = absent (no conflict retry has ever produced this field for this character).
    val abs: Map<String, CharAbsEntry>? = null
)
