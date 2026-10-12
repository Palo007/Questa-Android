package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val createdAt: Long, // WP-8.23: no clock default (clock baseline 0); the caller passes the stamp
    val updatedAt: Long,
    // Stage 3 / T3.1 (unknown-field passthrough): verbatim copy of the original PWA tag object
    // (compact JSON), null for tags created fresh in Kotlin. See
    // SerializationEngine.buildTagJson/parseTagEntity.
    val extraJson: String? = null,
    // Stage 3 / T3.5 (D12, opus-review finding #3 on T3.1): persisted Room-only array-position
    // ordinal -- see TaskEntity.orderIndex's doc for the full rationale. PWA's `addTag`
    // (app.js:1912) does `S.tags.push(tg)` -- append, like rewards, not unshift like tasks -- so
    // `RpgRepository.nextTagOrderIndex()` assigns new tags an ordinal LARGER than every existing
    // one. `TagDao` now `ORDER BY orderIndex ASC` (see TagDao.kt). Never emitted on the wire.
    val orderIndex: Long = 0L
)
