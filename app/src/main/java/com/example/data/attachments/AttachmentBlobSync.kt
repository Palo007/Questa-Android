package com.example.data.attachments

import com.example.data.sync.BlobDownloadResult
import com.example.data.sync.DropboxService
import com.example.data.sync.UploadOutcome

/**
 * CR-KT-015: the sync round's hook for attachment blobs (SyncManager `blobSync`).
 * Both calls are best effort: SyncManager catches anything they throw, so a blob failure never
 * blocks the `/state.json` push (readers tolerate a missing blob).
 */
interface AttachmentBlobSync {
    /** Uploads every queued full + thumb with mode `add`. Runs BEFORE the state.json push. */
    suspend fun uploadPending(accessToken: String)

    /** After a settled round: retries the blobs that were [BlobState.Missing]. */
    suspend fun onRoundFinished(accessToken: String)
}

/** Result of one blob fetch. */
sealed interface BlobFetch {
    class Ok(val bytes: ByteArray) : BlobFetch
    object NotFound : BlobFetch
    object Failed : BlobFetch
}

/** The remote blob store (Dropbox in production). */
interface BlobRemote {
    /** True when the file is stored, or was already there (a 409 path conflict). */
    suspend fun upload(accessToken: String, path: String, bytes: ByteArray): Boolean
    suspend fun download(accessToken: String, path: String): BlobFetch
}

/** [BlobRemote] over [DropboxService] (D7 interlock and 401 refresh included). */
object DropboxBlobRemote : BlobRemote {
    override suspend fun upload(accessToken: String, path: String, bytes: ByteArray): Boolean =
        when (DropboxService.uploadBlob(accessToken, path, bytes)) {
            is UploadOutcome.Ok, UploadOutcome.Conflict -> true
            is UploadOutcome.Http -> false
        }

    override suspend fun download(accessToken: String, path: String): BlobFetch =
        when (val r = DropboxService.downloadBlob(accessToken, path)) {
            is BlobDownloadResult.Ok -> BlobFetch.Ok(r.bytes)
            BlobDownloadResult.NotFound -> BlobFetch.NotFound
            is BlobDownloadResult.Http -> BlobFetch.Failed
        }
}
