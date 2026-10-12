package com.example.data.attachments

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * CR-KT-015: one image on a task. Wire key `attachments` on the task JSON.
 * Items never change after they are added; a user can only add or remove one.
 * Golden fixture: shared/fixtures/CR-KT-015/task_attachments.json.
 */
@kotlinx.serialization.Serializable // TaskEntity is @Serializable and holds a List<Attachment>.
data class Attachment(
    val id: String,
    /** sha256 hex (lower case) of the FULL jpeg bytes. */
    val sha: String,
    val mime: String = MIME_JPEG,
    val w: Int,
    val h: Int,
    val bytes: Long,
    val addedAt: Long,
) {
    companion object {
        const val MIME_JPEG = "image/jpeg"
        const val MAX_PER_TASK = 5
        const val FULL_MAX_EDGE = 1600
        const val FULL_QUALITY = 80
        const val THUMB_MAX_EDGE = 256
        const val THUMB_QUALITY = 70
        const val DROPBOX_DIR = "/attachments"
        fun fullPath(sha: String) = "$DROPBOX_DIR/$sha.jpg"
        fun thumbPath(sha: String) = "$DROPBOX_DIR/${sha}_t.jpg"
    }
}

/** Where a blob is for the UI. */
sealed interface BlobState {
    data class Ready(val file: File) : BlobState
    /** Not on this device yet; a download is running or queued. */
    data object Loading : BlobState
    /** Not on this device and not in Dropbox (yet). Retried on the next sync. */
    data object Missing : BlobState
}

sealed interface AddResult {
    data class Added(val attachment: Attachment) : AddResult
    data object LimitReached : AddResult
    data class Failed(val reason: String) : AddResult
}

/** The only API the UI uses. Implemented in the data layer. */
interface AttachmentRepository {
    /** Decode, fix EXIF rotation, resize, re-encode (strips EXIF/GPS), store local files, add to the task, queue upload. */
    suspend fun add(taskId: String, source: Uri): AddResult
    suspend fun remove(taskId: String, attachmentId: String)
    fun attachments(taskId: String): Flow<List<Attachment>>
    /** Thumbnail file; starts a download when not local. */
    fun thumb(sha: String): Flow<BlobState>
    /** Full image file; starts a download when not local. */
    fun full(sha: String): Flow<BlobState>
    /**
     * The local thumbnail of [sha] cannot be decoded: delete it and mark it Missing, so the next
     * sync round downloads it again (thumbs are not sha-checked).
     */
    suspend fun invalidateThumb(sha: String)
}
