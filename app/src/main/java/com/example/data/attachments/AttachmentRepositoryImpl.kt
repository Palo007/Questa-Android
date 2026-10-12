package com.example.data.attachments

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.dao.AttachmentUploadDao
import com.example.data.local.entities.AttachmentUploadEntity
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * CR-KT-015: the data-layer [AttachmentRepository], and the round's [AttachmentBlobSync].
 *
 * Local files: `filesDir/attachments/<sha>.jpg` (full) and `<sha>_t.jpg` (thumb). Content is
 * addressed by the sha256 of the full JPEG, so a file never changes once written.
 * Task edits run in one Room transaction and bump `updatedAt` from the HLC, like other task edits
 * ([RpgRepository.bulkSetDifficulty]); the write also stamps localSavedAt and schedules a sync.
 *
 * [DEFERRED_OPEN_QUESTION] remove() and task delete keep the local and Dropbox files (orphan
 * sweep is CR-KT-015 item 9, not in this CR).
 */
class AttachmentRepositoryImpl internal constructor(
    private val root: File,
    private val repo: RpgRepository,
    private val uploads: AttachmentUploadDao,
    private val encoder: ImageEncoder,
    private val remote: BlobRemote,
    private val token: suspend () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
) : AttachmentRepository, AttachmentBlobSync {

    private val editLock = Mutex()
    /** Per local file name: the state every observer of that blob shares. */
    private val states = ConcurrentHashMap<String, MutableStateFlow<BlobState>>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal fun fullFile(sha: String) = File(root, "$sha.jpg")
    internal fun thumbFile(sha: String) = File(root, "${sha}_t.jpg")

    // ---- AttachmentRepository ----------------------------------------------------------------

    override suspend fun add(taskId: String, source: Uri): AddResult = withContext(io) {
        val before = repo.getTaskByIdSync(taskId) ?: return@withContext AddResult.Failed("task not found")
        if (before.attachments.size >= Attachment.MAX_PER_TASK) return@withContext AddResult.LimitReached
        val enc = try {
            encoder.encode(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null // a huge image must not crash the app from this scope
        } ?: return@withContext AddResult.Failed("could not read the image")
        val sha = sha256Hex(enc.full)
        try {
            writeAtomic(fullFile(sha), enc.full)
            writeAtomic(thumbFile(sha), enc.thumb)
        } catch (e: Exception) {
            return@withContext AddResult.Failed("could not store the image")
        }
        val now = repo.physicalNow()
        val att = Attachment(
            id = pwaUid(now, random), sha = sha, w = enc.w, h = enc.h,
            bytes = enc.full.size.toLong(), addedAt = now,
        )
        val result = editLock.withLock {
            repo.inTransaction {
                val cur = repo.getTaskByIdSync(taskId) ?: return@inTransaction AddResult.Failed("task not found")
                if (cur.attachments.size >= Attachment.MAX_PER_TASK) return@inTransaction AddResult.LimitReached
                // Queue the upload in the same transaction: a state push never names a sha that
                // has no queued upload.
                uploads.enqueue(AttachmentUploadEntity(sha, now))
                repo.updateTaskWithAttachments(cur.copy(attachments = cur.attachments + att, updatedAt = repo.hlcClock.now()))
                AddResult.Added(att)
            }
        }
        if (result is AddResult.Added) {
            states[fullFile(sha).name]?.value = BlobState.Ready(fullFile(sha))
            states[thumbFile(sha).name]?.value = BlobState.Ready(thumbFile(sha))
        }
        result
    }

    override suspend fun remove(taskId: String, attachmentId: String) {
        withContext(io) {
            editLock.withLock {
                repo.inTransaction {
                    val cur = repo.getTaskByIdSync(taskId) ?: return@inTransaction
                    if (cur.attachments.none { it.id == attachmentId }) return@inTransaction
                    repo.updateTaskWithAttachments(cur.copy(
                        attachments = cur.attachments.filter { it.id != attachmentId },
                        updatedAt = repo.hlcClock.now(),
                    ))
                }
            }
        }
    }

    override fun attachments(taskId: String): Flow<List<Attachment>> =
        repo.observeTask(taskId).map { it?.attachments ?: emptyList() }.distinctUntilChanged()

    // An invalid sha (AttachmentJson.isValidSha) is Missing: no file and no network access.
    override fun thumb(sha: String): Flow<BlobState> =
        if (!AttachmentJson.isValidSha(sha)) flowOf(BlobState.Missing)
        else blob(thumbFile(sha), Attachment.thumbPath(sha), sha = null)

    override fun full(sha: String): Flow<BlobState> =
        if (!AttachmentJson.isValidSha(sha)) flowOf(BlobState.Missing)
        else blob(fullFile(sha), Attachment.fullPath(sha), sha = sha)

    /** Thumbs are not sha-checked: an undecodable one is deleted and re-downloaded next round. */
    override suspend fun invalidateThumb(sha: String) {
        if (!AttachmentJson.isValidSha(sha)) return
        val file = thumbFile(sha)
        withContext(io) { file.delete() }
        stateOf(file).value = BlobState.Missing
    }

    internal fun stateOf(file: File): MutableStateFlow<BlobState> =
        states.computeIfAbsent(file.name) { MutableStateFlow(BlobState.Loading) }

    /** Ready when local; else Loading + a download; a Missing blob waits for the next round. */
    private fun blob(file: File, remotePath: String, sha: String?): Flow<BlobState> = flow {
        val st = stateOf(file)
        if (file.exists()) {
            st.value = BlobState.Ready(file)
        } else if (st.value !is BlobState.Missing) {
            startDownload(file, remotePath, sha, null)
        }
        emitAll(st)
    }.flowOn(io)

    /**
     * Starts one download per file. The state is published only AFTER the file leaves [inFlight],
     * so a round that sees Missing can always start the retry (it never sticks on Loading).
     */
    private fun startDownload(file: File, remotePath: String, sha: String?, roundToken: String?) {
        if (!inFlight.add(file.name)) return
        val st = stateOf(file)
        st.value = BlobState.Loading
        scope.launch {
            var result: BlobState = BlobState.Missing
            try {
                result = fetch(file, remotePath, sha, roundToken)
            } catch (e: CancellationException) {
                inFlight.remove(file.name)
                throw e
            } catch (e: Exception) {
                result = BlobState.Missing
            }
            inFlight.remove(file.name)
            st.value = result
        }
    }

    private suspend fun fetch(file: File, remotePath: String, sha: String?, roundToken: String?): BlobState {
        if (file.exists()) return BlobState.Ready(file)
        if (sha != null && !AttachmentJson.isValidSha(sha)) return BlobState.Missing
        val tok = roundToken ?: token() ?: return BlobState.Missing
        return when (val r = remote.download(tok, remotePath)) {
            is BlobFetch.Ok -> {
                // A full image must match its address; a bad copy is never stored.
                if (sha != null && sha256Hex(r.bytes) != sha) return BlobState.Missing
                writeAtomic(file, r.bytes)
                BlobState.Ready(file)
            }
            BlobFetch.NotFound, BlobFetch.Failed -> BlobState.Missing
        }
    }

    // ---- AttachmentBlobSync --------------------------------------------------------------------

    override suspend fun uploadPending(accessToken: String) = withContext(io) {
        for (p in uploads.pending()) {
            // Only a local add queues a row (its sha is computed here), so an invalid sha is a
            // corrupt row: it can never upload and names no file -> drop it.
            if (!AttachmentJson.isValidSha(p.sha)) { uploads.done(p.sha); continue }
            val full = fullFile(p.sha)
            val thumb = thumbFile(p.sha)
            // The full file is the only copy of a not-yet-uploaded image. If it is not there (now),
            // keep the row (never drop an upload silently) and go on with the next one.
            if (!full.exists()) { Log.w(TAG, "upload waits: no local file for ${p.sha}"); continue }
            if (!remote.upload(accessToken, Attachment.fullPath(p.sha), full.readBytes())) break
            if (thumb.exists() && !remote.upload(accessToken, Attachment.thumbPath(p.sha), thumb.readBytes())) break
            uploads.done(p.sha)
        }
    }

    override suspend fun onRoundFinished(accessToken: String) {
        for ((name, st) in states) {
            if (st.value !is BlobState.Missing) continue
            val file = File(root, name)
            val isThumb = name.endsWith("_t.jpg")
            val sha = name.removeSuffix(if (isThumb) "_t.jpg" else ".jpg")
            if (!AttachmentJson.isValidSha(sha)) continue
            startDownload(file, if (isThumb) Attachment.thumbPath(sha) else Attachment.fullPath(sha), if (isThumb) null else sha, accessToken)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Content-addressed write: a unique temp file per call, renamed into place. An existing
     * [target] is never replaced or deleted (same name = same content), so parallel writers of
     * one sha cannot remove each other's file.
     */
    internal fun writeAtomic(target: File, bytes: ByteArray) {
        if (target.exists()) return
        val dir = target.parentFile ?: throw java.io.IOException("no parent: ${target.name}")
        dir.mkdirs()
        val tmp = File.createTempFile(target.name + ".", ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target) && !target.exists()) throw java.io.IOException("rename failed: ${target.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val TAG = "Attachments"
        @Volatile private var instance: AttachmentRepositoryImpl? = null

        /** Set by QuestaApplication: the D7-gated access token for on-demand downloads. */
        @Volatile private var tokenProvider: (suspend () -> String?)? = null

        fun bindTokenProvider(provider: suspend () -> String?) { tokenProvider = provider }

        /** The process instance (the UI's [AttachmentRepository]). */
        fun get(context: Context): AttachmentRepository = impl(context)

        /** The same instance as the sync round's [AttachmentBlobSync]. */
        fun blobSync(context: Context): AttachmentBlobSync = impl(context)

        private fun impl(context: Context): AttachmentRepositoryImpl =
            instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

        private fun create(app: Context): AttachmentRepositoryImpl {
            val db = AppDatabase.getDatabase(app)
            return AttachmentRepositoryImpl(
                root = File(app.filesDir, "attachments"),
                repo = appRepository(app, db),
                uploads = db.attachmentUploadDao(),
                encoder = AndroidImageEncoder(app.contentResolver),
                remote = DropboxBlobRemote,
                token = { tokenProvider?.invoke() },
            )
        }

        /** The app's repository (its localWrites drive the sync debounce), else a new one on the same database. */
        private fun appRepository(app: Context, db: AppDatabase): RpgRepository {
            val fromApp = try {
                (app as? com.example.QuestaApplication)?.repository
            } catch (e: UninitializedPropertyAccessException) {
                null
            }
            return fromApp ?: RpgRepository(
                db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
                db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
                db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
                SyncDataStore(app), db,
            )
        }

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** PWA `uid()` (app.js:2084): `Date.now().toString(36)` + 5 random base-36 chars. */
        internal fun pwaUid(nowMs: Long, random: Random = Random.Default): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
            return nowMs.toString(36) + (1..5).map { alphabet[random.nextInt(36)] }.joinToString("")
        }
    }
}
