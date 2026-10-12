package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long = 0L,
    // Stage 3 / T3.1 (unknown-field passthrough): verbatim copy of the original PWA device
    // object (compact JSON), null for devices created fresh in Kotlin. See
    // SerializationEngine.buildDeviceJson/parseDeviceEntity.
    val extraJson: String? = null
)
