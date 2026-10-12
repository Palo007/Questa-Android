package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "rewards")
data class RewardEntity(
    @PrimaryKey val id: String,
    val title: String,
    val cost: Double,
    val notes: String = "",
    val createdAt: Long, // WP-8.23: no clock default (clock baseline 0); the caller passes the stamp
    val updatedAt: Long,
    // Stage 3 / T3.1 (unknown-field passthrough): verbatim copy of the original PWA reward
    // object (compact JSON), null for rewards created fresh in Kotlin. See
    // SerializationEngine.buildRewardJson/parseRewardEntity.
    val extraJson: String? = null,
    // Stage 3 / T3.5 (D12, opus-review finding #3 on T3.1): persisted Room-only array-position
    // ordinal -- see TaskEntity.orderIndex's doc for the full rationale. Rewards differ from
    // tasks in PWA's own insertion mechanic: `addReward`/`saveReward` (app.js:4763) does
    // `S.rewards.push(REDIT)`, i.e. a brand NEW reward is appended, not prepended -- so
    // `RpgRepository.nextRewardOrderIndex()` assigns new rewards an ordinal LARGER than every
    // existing one (opposite direction from `nextTaskOrderIndex()`). `RewardDao` now
    // `ORDER BY orderIndex ASC` (see RewardDao.kt). Never emitted on the wire.
    val orderIndex: Long = 0L
)
