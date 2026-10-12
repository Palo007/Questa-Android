package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.CustomViewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomViewDao {
    @Query("SELECT * FROM custom_views ORDER BY createdAt ASC")
    fun getAllCustomViews(): Flow<List<CustomViewEntity>>

    @Query("SELECT * FROM custom_views ORDER BY createdAt ASC")
    suspend fun getAllCustomViewsSync(): List<CustomViewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomView(customView: CustomViewEntity)

    @Query("DELETE FROM custom_views WHERE id = :id")
    suspend fun deleteCustomViewById(id: String)
}
