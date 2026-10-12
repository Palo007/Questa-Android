package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.RewardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RewardDao {
    // Stage 3 / T3.5 (D12, opus-review finding #3): ordered by the persisted `orderIndex` ordinal
    // (see RewardEntity.orderIndex's doc). This is also a real behavior FIX, not just a rename:
    // PWA's `addReward` (app.js:4763) appends via `S.rewards.push(...)`, so a new reward belongs
    // at the END of the array (oldest-first), whereas the previous `createdAt DESC` clause put
    // newest-first -- backwards relative to PWA. `createdAt ASC` kept as a tie-break only.
    @Query("SELECT * FROM rewards ORDER BY orderIndex ASC, createdAt ASC")
    fun getAllRewards(): Flow<List<RewardEntity>>

    @Query("SELECT * FROM rewards ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getAllRewardsSync(): List<RewardEntity>

    // Stage 3 / T3.5: used by RpgRepository.nextRewardOrderIndex() to mirror PWA's
    // `S.rewards.push(REDIT)` -- a brand new reward must sort AFTER every existing one, so its
    // ordinal is one more than the current maximum (0 if the table is empty).
    @Query("SELECT MAX(orderIndex) FROM rewards")
    suspend fun getMaxOrderIndex(): Long?

    @Query("SELECT * FROM rewards WHERE id = :id LIMIT 1")
    suspend fun getRewardByIdSync(id: String): RewardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReward(reward: RewardEntity)

    @Query("INSERT OR REPLACE INTO tombstones (id, at) VALUES (:id, :at)")
    suspend fun insertTombstone(id: String, at: Long)

    @Delete
    suspend fun deleteRewardInternal(reward: RewardEntity)

    @Query("DELETE FROM rewards WHERE id = :id")
    suspend fun deleteRewardByIdInternal(id: String)

    // WP-8.2 / WP-8.23: `at` has no default; the caller passes it (see TaskDao.deleteWithTombstone).
    @Transaction
    suspend fun deleteWithTombstone(id: String, at: Long) {
        deleteRewardByIdInternal(id)
        insertTombstone(id, at)
    }
}
