package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.AttachmentUploadEntity

/** CR-KT-015: the persisted pending-upload set for attachment blobs. */
@Dao
interface AttachmentUploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entity: AttachmentUploadEntity)

    @Query("SELECT * FROM attachment_uploads ORDER BY queuedAt ASC, sha ASC")
    suspend fun pending(): List<AttachmentUploadEntity>

    @Query("DELETE FROM attachment_uploads WHERE sha = :sha")
    suspend fun done(sha: String)
}
