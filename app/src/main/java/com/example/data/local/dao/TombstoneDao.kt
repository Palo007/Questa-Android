package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.TombstoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM tombstones ORDER BY at DESC")
    fun getAllTombstones(): Flow<List<TombstoneEntity>>

    @Query("SELECT * FROM tombstones ORDER BY at DESC")
    suspend fun getAllTombstonesSync(): List<TombstoneEntity>

    // WP-8.2: used by BaseStoreMigration's keep-max-`at` union of LOCAL_DELETIONS into this table.
    @Query("SELECT * FROM tombstones WHERE id = :id LIMIT 1")
    suspend fun getTombstoneById(id: String): TombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: TombstoneEntity)

    @Query("DELETE FROM tombstones WHERE id = :id")
    suspend fun deleteTombstoneById(id: String)

    @Query("DELETE FROM tombstones WHERE at <= :timestamp")
    suspend fun deleteTombstonesOlderThan(timestamp: Long)
}
