package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    // Stage 3 / T3.5 (D12, opus-review finding #3): ordered by the persisted `orderIndex` ordinal
    // (see TagEntity.orderIndex's doc). Real behavior fix, not just a rename: PWA's `addTag`
    // (app.js:1912) appends via `S.tags.push(tg)`, so a new tag belongs at the END of the array
    // (oldest-first) -- the previous `createdAt DESC` clause was backwards relative to PWA.
    @Query("SELECT * FROM tags ORDER BY orderIndex ASC, createdAt ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY orderIndex ASC, createdAt ASC")
    suspend fun getAllTagsSync(): List<TagEntity>

    // Stage 3 / T3.5: used by RpgRepository.nextTagOrderIndex() to mirror PWA's `S.tags.push(tg)`
    // -- a brand new tag must sort AFTER every existing one.
    @Query("SELECT MAX(orderIndex) FROM tags")
    suspend fun getMaxOrderIndex(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity)

    @Query("INSERT OR REPLACE INTO tombstones (id, at) VALUES (:id, :at)")
    suspend fun insertTombstone(id: String, at: Long)

    @Delete
    suspend fun deleteTagInternal(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagByIdInternal(id: String)

    // WP-8.2 / WP-8.23: `at` has no default; the caller passes it (see TaskDao.deleteWithTombstone).
    @Transaction
    suspend fun deleteWithTombstone(id: String, at: Long) {
        deleteTagByIdInternal(id)
        insertTombstone(id, at)
    }
}
