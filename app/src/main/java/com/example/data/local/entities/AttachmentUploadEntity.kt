package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * CR-KT-015 (schema v8): one image whose full + thumb blobs still have to be uploaded to
 * Dropbox `/attachments/`. Local only, never on the wire. A row is removed once both uploads
 * succeed (a 409 path conflict counts as success: the content-addressed file is already there).
 */
@Entity(tableName = "attachment_uploads")
data class AttachmentUploadEntity(
    @PrimaryKey val sha: String,
    val queuedAt: Long,
)
