package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.CharHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharHistoryDao {
    @Query("SELECT * FROM char_history ORDER BY date DESC")
    fun getAllCharHistory(): Flow<List<CharHistoryEntity>>

    @Query("SELECT * FROM char_history ORDER BY date DESC")
    suspend fun getAllCharHistorySync(): List<CharHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharHistory(entry: CharHistoryEntity)

    /** WP-8.6 (review 26 C1): a sync apply removes a same-day loser of mergeDayArray. */
    @Query("DELETE FROM char_history WHERE date = :date")
    suspend fun deleteCharHistoryByDate(date: Long)
}
