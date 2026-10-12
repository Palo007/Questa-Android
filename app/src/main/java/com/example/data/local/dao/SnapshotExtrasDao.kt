package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.SnapshotExtrasEntity

@Dao
interface SnapshotExtrasDao {
    @Query("SELECT * FROM snapshot_extras WHERE id = 1 LIMIT 1")
    suspend fun getSync(): SnapshotExtrasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SnapshotExtrasEntity)
}
