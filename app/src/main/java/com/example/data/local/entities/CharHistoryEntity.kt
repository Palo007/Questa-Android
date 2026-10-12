package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "char_history")
data class CharHistoryEntity(
    @PrimaryKey val date: Long,
    val hp: Double,
    val maxHp: Double,
    val xp: Int,
    val mp: Int,
    val gold: Double,
    val lvl: Int,
    // Stage 3 / T3.1 opus-review FIX 3 (unknown-field passthrough gap #3 from the T3.1 review):
    // compact-JSON verbatim copy of the ORIGINAL `charHistory[]` entry as parsed from a PWA
    // import/merge, so any field this entity doesn't model survives export the same way
    // task/reward/tag/device/character extras already do. Null for an entry recorded fresh in the
    // Kotlin app. Requires MIGRATION_4_5's `ALTER TABLE char_history ADD COLUMN extraJson TEXT`
    // (see AppDatabase.kt). See SerializationEngine.buildCharHistoryJson / parseCharHistoryEntity.
    val extraJson: String? = null
)
