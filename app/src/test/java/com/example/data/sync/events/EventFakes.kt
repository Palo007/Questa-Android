package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.DropboxService
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.EventWatermark
import com.example.data.sync.merge.Js
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** WP-8.7 test fakes: no Room, no network. */

class FakeWatermark(var value: Long = 0L) : EventWatermark {
    override suspend fun get(): Long = value
    override suspend fun advanceTo(ts: Long) { if (ts > value) value = ts }
    override suspend fun lowerTo(ts: Long) { if (ts < value) value = ts }
}

class FakeEventStore(rows: List<EventLogEntity> = emptyList()) : LocalEventStore {
    val rows = rows.toMutableList()
    val inserted = mutableListOf<EventLogEntity>()
    var strictFails = false
    var insertFails = false

    private fun sorted() = rows.sortedBy { it.timestamp }
    override suspend fun since(since: Long) = sorted().filter { it.timestamp > since }
    override suspend fun inRange(from: Long, to: Long) = sorted().filter { it.timestamp in from..to }
    override suspend fun inRangeStrict(from: Long, to: Long) = if (strictFails) null else inRange(from, to)
    override suspend fun insertAll(records: List<EventLogEntity>): Boolean {
        if (insertFails) return false
        inserted.addAll(records)
        rows.addAll(records)
        return true
    }
}

class FakeEventsRemote(
    var entries: List<DropboxService.FileEntry> = emptyList(),
    val downloads: MutableMap<String, RawEventFile> = mutableMapOf(),
) : EventsRemote {
    val uploads = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()
    val deleteFails = mutableSetOf<String>()
    var listFails = false
    var uploadFails = false
    var listCalls = 0
    var downloadCalls = 0
    var uploadCalls = 0
    val order = mutableListOf<String>()

    override suspend fun listEvents(): List<DropboxService.FileEntry> {
        listCalls++
        order.add("list")
        if (listFails) throw EventsHttpException("list failed: 500", 500)
        return entries
    }

    override suspend fun downloadRaw(path: String): RawEventFile? {
        downloadCalls++
        order.add("download $path")
        return downloads[path.removePrefix("/events/")]
    }

    override suspend fun uploadText(path: String, text: String) {
        uploadCalls++
        order.add("upload $path")
        if (uploadFails) throw EventsHttpException("upload failed: 500", 500)
        uploads.add(path to text)
    }

    override suspend fun delete(path: String) {
        order.add("delete $path")
        deletes.add(path)
        if (path in deleteFails) throw EventsHttpException("delete failed", 500)
    }
}

object EvtTestData {
    /** An Opti event object (as a scenario or an Opti file holds it) -> the Room row Kotlin stores. */
    fun entityOf(o: JsonObject): EventLogEntity {
        val synthetic = Js.truthy(o["synthetic"])
        val clean = JsonObject(o.filterKeys { it != "synthetic" })
        return EventSyncEngine.parseIncomingEventJson(clean).copy(synthetic = synthetic)
    }

    /** A Room row -> the Opti object it stands for (the upload / insert shape). */
    fun jsonOf(e: EventLogEntity): JsonObject = EventSyncEngine.buildEventPushJson(e)

    fun ev(uid: String?, ts: Long, dev: String = "dev-me", kind: String = "habitTap", imported: Boolean? = null,
           republish: Boolean? = null, synthetic: Boolean = false): EventLogEntity =
        EventLogEntity(timestamp = ts, kind = kind, uid = uid, dev = dev, imported = imported, republish = republish, synthetic = synthetic)

    fun map(o: JsonElement?): Map<String, JsonElement> = (o as? JsonObject)?.let { LinkedHashMap(it) } ?: emptyMap()
}
