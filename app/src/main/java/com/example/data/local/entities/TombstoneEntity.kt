package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val id: String,
    val at: Long // WP-8.23: no clock default; a local delete passes the HLC stamp (Opti delMark now())
)
