package com.example.data.inbox

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.sync.DeleteResult
import com.example.data.sync.DropboxService
import com.example.data.sync.MoveResult
import com.example.data.sync.RawDownloadResult
import com.example.data.sync.SyncKeys
import com.example.data.sync.UploadOutcome
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Opti's inbox paths (sync.js:534-538). */
object InboxPaths {
    const val INBOX_DIR = "/inbox"
    const val INBOX_CLAIMED_ROOT = "/inbox-claimed"
    const val INBOX_REJECTED_DIR = "/inbox-rejected"
    const val INBOX_META_PATH = "/inbox-meta/habits.json"
    const val INBOX_REMINDERS_PATH = "/inbox-meta/reminders.json"
    /** sync.js:539: two list_folder calls at most once a minute. */
    const val INBOX_MIN_INTERVAL_MS = 60_000L

    fun claimedDir(deviceId: String) = "$INBOX_CLAIMED_ROOT/$deviceId"
}

/** A failed Dropbox call (Opti's `dbx*` helpers throw an HttpError). */
class InboxIoException(msg: String) : IOException(msg)

/**
 * The five Dropbox calls the inbox uses, with Opti's return conventions. Every failure other
 * than the documented "absent" cases throws [InboxIoException].
 */
interface InboxDropbox {
    /** `dbxListFolder`: file names of [path], all pages; `[]` when the folder is absent. */
    suspend fun listFileNames(path: String): List<String>
    /** `dbxMove`: true = this device moved it; false = `from_lookup/not_found` (another device won). */
    suspend fun move(from: String, to: String, autorename: Boolean): Boolean
    /** `dbxDownloadRaw`: the text, or null when the file is absent. */
    suspend fun downloadText(path: String): String?
    /** `dbxDelete`: an already-missing path is success. */
    suspend fun delete(path: String)
    /** `dbxUploadText`: mode overwrite. */
    suspend fun uploadText(path: String, text: String)
}

/** Production [InboxDropbox]: every call goes through [DropboxService], so the D7 interlock always runs first. */
class DropboxServiceInbox(private val accessToken: String) : InboxDropbox {
    override suspend fun listFileNames(path: String): List<String> {
        val out = ArrayList<String>()
        var page = DropboxService.listFolder(accessToken, path) ?: throw InboxIoException("list failed: $path")
        while (true) {
            page.entries.forEach { out.add(it.name) }
            if (!page.hasMore) return out
            val cursor = page.cursor ?: throw InboxIoException("list failed: no cursor")
            page = DropboxService.listFolderContinue(accessToken, cursor) ?: throw InboxIoException("list failed: $path")
        }
    }

    override suspend fun move(from: String, to: String, autorename: Boolean): Boolean =
        when (val r = DropboxService.move(accessToken, from, to, autorename)) {
            MoveResult.Ok -> true
            MoveResult.NotFound -> false
            MoveResult.Conflict -> throw InboxIoException("move failed: 409 conflict")
            is MoveResult.Http -> throw InboxIoException("move failed: ${r.status} ${r.summary ?: ""}".trim())
        }

    override suspend fun downloadText(path: String): String? =
        when (val r = DropboxService.downloadFile(accessToken, path)) {
            is RawDownloadResult.Ok -> r.body
            RawDownloadResult.NotFound -> null
            is RawDownloadResult.Http -> throw InboxIoException("download failed: ${r.status} ${r.summary ?: ""}".trim())
        }

    override suspend fun delete(path: String) {
        when (val r = DropboxService.delete(accessToken, path)) {
            DeleteResult.Ok -> Unit
            is DeleteResult.Http -> throw InboxIoException("delete failed: ${r.status} ${r.summary ?: ""}".trim())
        }
    }

    override suspend fun uploadText(path: String, text: String) {
        when (val r = DropboxService.uploadOverwrite(accessToken, path, text)) {
            is UploadOutcome.Ok -> Unit
            UploadOutcome.Conflict -> throw InboxIoException("upload failed: conflict")
            is UploadOutcome.Http -> throw InboxIoException("upload failed: ${r.status} ${r.summary ?: ""}".trim())
        }
    }
}

/** The inbox's persisted values (\u00a74.3: INBOX_HABITS_HASH, INBOX_REMINDERS_HASH, INBOX_LAST_RUN_AT). */
interface InboxPrefs {
    suspend fun lastRunAt(): Long
    suspend fun setLastRunAt(ms: Long)
    suspend fun habitsHash(): String?
    suspend fun setHabitsHash(hash: String)
    suspend fun remindersHash(): String?
    suspend fun setRemindersHash(hash: String)
}

/** [InboxPrefs] on the sync DataStore (production: `context.syncDataStore`). */
class DataStoreInboxPrefs(private val store: DataStore<Preferences>) : InboxPrefs {
    override suspend fun lastRunAt(): Long = store.data.first()[SyncKeys.INBOX_LAST_RUN_AT] ?: 0L
    override suspend fun setLastRunAt(ms: Long) { store.edit { it[SyncKeys.INBOX_LAST_RUN_AT] = ms } }
    override suspend fun habitsHash(): String? = store.data.first()[SyncKeys.INBOX_HABITS_HASH]
    override suspend fun setHabitsHash(hash: String) { store.edit { it[SyncKeys.INBOX_HABITS_HASH] = hash } }
    override suspend fun remindersHash(): String? = store.data.first()[SyncKeys.INBOX_REMINDERS_HASH]
    override suspend fun setRemindersHash(hash: String) { store.edit { it[SyncKeys.INBOX_REMINDERS_HASH] = hash } }
}

/** Opti `_inboxDiag` (sync.js:543): a diagnostic push, never an event-log row. */
fun interface InboxDiag {
    fun push(kind: String, data: Map<String, Any?>)

    companion object {
        val NONE = InboxDiag { _, _ -> }
    }
}
