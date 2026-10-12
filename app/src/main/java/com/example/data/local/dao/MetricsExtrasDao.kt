package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.MetricsExtrasEntity

@Dao
interface MetricsExtrasDao {
    @Query("SELECT * FROM metrics_extras WHERE id = 1 LIMIT 1")
    suspend fun getSync(): MetricsExtrasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetricsExtrasEntity)
}
