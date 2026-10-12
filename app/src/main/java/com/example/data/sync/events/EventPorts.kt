package com.example.data.sync.events

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.DeleteResult
import com.example.data.sync.DropboxService
import com.example.data.sync.RawDownloadResult
import com.example.data.sync.UploadOutcome
import kotlinx.coroutines.CancellationException

/**
 * WP-8.7 -- the two I/O ports of the event engine, so push, pull and purge run against fakes in
 * tests and against Room / [DropboxService] in production.
 */

/** Opti `getEvents({from, to, includeDiag: true[, strict: true]})` and `evtInsertNew`. */
interface LocalEventStore {
    /** Rows with `ts > since`, ascending (Opti `getEvents({from: since + 1})`). */
    suspend fun since(since: Long): List<EventLogEntity>

    /** Rows with `from <= ts <= to`, ascending. */
    suspend fun inRange(from: Long, to: Long): List<EventLogEntity>

    /** The strict read: null when the read failed (Opti `strict: true`, sync.js:3566). */
    suspend fun inRangeStrict(from: Long, to: Long): List<EventLogEntity>?

    /** One transaction; false when it did not commit (Opti `{ok:false}`, sync.js:3279-3291). */
    suspend fun insertAll(records: List<EventLogEntity>): Boolean
}

class RoomEventStore(private val db: AppDatabase) : LocalEventStore {
    private val dao get() = db.eventLogDao()
    override suspend fun since(since: Long): List<EventLogEntity> = dao.getLogsSinceSync(since)
    override suspend fun inRange(from: Long, to: Long): List<EventLogEntity> = dao.getLogsInRangeSync(from, to)
    override suspend fun inRangeStrict(from: Long, to: Long): List<EventLogEntity>? = try {
        dao.getLogsInRangeSync(from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    override suspend fun insertAll(records: List<EventLogEntity>): Boolean {
        if (records.isEmpty()) return true
        return try {
            db.withTransaction { records.forEach { dao.insertLog(it) } }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/** A Dropbox failure; Opti's `HttpError` (its message is what lands in lastError). */
class EventsHttpException(message: String, val status: Int) : Exception(message)

data class RawEventFile(val text: String, val rev: String?)

/** Opti `dbxListFolder(EVENTS_DIR)`, `dbxDownloadRaw`, `dbxUploadText`, `dbxDelete` (paths are full). */
interface EventsRemote {
    /** Every file entry, all pages. A missing folder is an empty list. Throws on failure. */
    suspend fun listEvents(): List<DropboxService.FileEntry>

    /** null = not found (409 not_found); throws on any other failure. */
    suspend fun downloadRaw(path: String): RawEventFile?

    /** Overwrite upload; throws on failure. */
    suspend fun uploadText(path: String, text: String)

    /** Throws on failure (an already-missing path is success). */
    suspend fun delete(path: String)
}

/** Production [EventsRemote] over the D7-gated [DropboxService] with the round's token. */
class DropboxEventsRemote(private val accessToken: String) : EventsRemote {
    override suspend fun listEvents(): List<DropboxService.FileEntry> {
        val out = mutableListOf<DropboxService.FileEntry>()
        var page = DropboxService.listFolder(accessToken, EventsDir.PATH) ?: throw EventsHttpException("list failed", 0)
        out.addAll(page.entries)
        while (page.hasMore) {
            val cursor = page.cursor ?: throw EventsHttpException("list failed: no cursor", 0)
            page = DropboxService.listFolderContinue(accessToken, cursor) ?: throw EventsHttpException("list failed", 0)
            out.addAll(page.entries)
        }
        return out
    }

    override suspend fun downloadRaw(path: String): RawEventFile? = when (val r = DropboxService.downloadFile(accessToken, path)) {
        is RawDownloadResult.Ok -> RawEventFile(r.body, r.rev.ifEmpty { null })
        RawDownloadResult.NotFound -> null
        is RawDownloadResult.Http -> throw EventsHttpException("download failed: " + r.status + (r.summary?.let { " $it" } ?: ""), r.status)
    }

    override suspend fun uploadText(path: String, text: String) {
        when (val r = DropboxService.uploadOverwrite(accessToken, path, text)) {
            is UploadOutcome.Ok -> Unit
            UploadOutcome.Conflict -> throw EventsHttpException("upload failed: 409", 409)
            is UploadOutcome.Http -> throw EventsHttpException("upload failed: " + r.status + (r.summary?.let { " $it" } ?: ""), r.status)
        }
    }

    override suspend fun delete(path: String) {
        when (val r = DropboxService.delete(accessToken, path)) {
            DeleteResult.Ok -> Unit
            is DeleteResult.Http -> throw EventsHttpException("delete failed: " + r.status + (r.summary?.let { " $it" } ?: ""), r.status)
        }
    }
}

object EventsDir {
    /** Opti `EVENTS_DIR`. */
    const val PATH = "/events"
    fun file(name: String) = "$PATH/$name"
}
