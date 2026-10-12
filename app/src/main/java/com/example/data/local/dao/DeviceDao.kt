package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.DeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY updatedAt DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY updatedAt DESC")
    suspend fun getAllDevicesSync(): List<DeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    /** WP-8.6 (review 26 C1): a sync apply removes a device the merged array no longer holds. */
    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteDeviceById(id: String)
}
