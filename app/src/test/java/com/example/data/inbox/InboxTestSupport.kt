package com.example.data.inbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.boot.BootGate
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.CanonicalJson
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.DownloadResult
import com.example.data.sync.DropboxService
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.RoundContext
import com.example.data.sync.RoundDevice
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncLock
import com.example.data.sync.SyncRoundStep
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.wrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import java.time.LocalDateTime
import java.time.ZoneId

internal val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
internal fun at(d: Int, h: Int, mi: Int = 0): Long =
    LocalDateTime.of(2026, 9, d, h, mi).atZone(ZONE).toInstant().toEpochMilli()

/** Bubblewrap `InboxRecord.toJson()` (Questa-Bubblewrap quicklog/InboxRecord.kt), byte for byte. */
internal fun bubblewrapJson(id: String, habitId: String, dir: Int, ts: Long, tzOffsetMin: Int = -120, src: String = "android-shortcut"): String =
    "{\"v\":1,\"id\":${bwQ(id)},\"kind\":\"habit\",\"habitId\":${bwQ(habitId)}," +
        "\"dir\":$dir,\"ts\":$ts,\"tzOffsetMin\":$tzOffsetMin,\"src\":${bwQ(src)}}"

/** Bubblewrap `InboxRecord.q` (the same escape table, including U+2028/U+2029). */
internal fun bwQ(s: String): String {
    val b = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
    }
    return b.append('"').toString()
}

class MemInboxPrefs : InboxPrefs {
    var last = 0L; var habits: String? = null; var reminders: String? = null
    override suspend fun lastRunAt() = last
    override suspend fun setLastRunAt(ms: Long) { last = ms }
    override suspend fun habitsHash() = habits
    override suspend fun setHabitsHash(hash: String) { habits = hash }
    override suspend fun remindersHash() = reminders
    override suspend fun setRemindersHash(hash: String) { reminders = hash }
}

/** A step resolved at run time (the steps need the device's repository, built after the step list). */
class LateStep(override val slot: StepSlot, override val order: Int, private val target: () -> SyncRoundStep) : SyncRoundStep {
    override suspend fun run(r: RoundContext): StepResult = target().run(r)
}

/**
 * One device ("dev-a") whose SyncManager runs the three WP-8.8 steps, on the shared
 * [FakeDropboxServer] (state) plus [InboxFakeDropbox] (inbox paths). No real network.
 */
abstract class InboxTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var fake: FakeDropboxServer
    protected lateinit var inbox: InboxFakeDropbox
    private lateinit var originalClient: OkHttpClient
    protected lateinit var dev: RoundDevice
    protected val prefs = MemInboxPrefs()
    protected val diags = mutableListOf<Pair<String, Map<String, Any?>>>()
    protected var tapNow = at(25, 12)
    protected var throttleNow = 10_000_000L
    protected var gateClosed = false
    protected lateinit var consumer: InboxConsumer
    protected lateinit var finisher: InboxFinisher
    protected lateinit var meta: InboxMetaWriter
    protected val claimed = InboxPaths.claimedDir("dev-a")

    protected val diag = InboxDiag { k, d -> diags.add(k to d) }
    protected fun diagKinds() = diags.map { it.first }

    @Before fun inboxSetUp() = runBlocking {
        fake = FakeDropboxServer()
        inbox = InboxFakeDropbox(fake)
        originalClient = DropboxService.client
        DropboxService.client = inbox.asClient()
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { tapNow }, ZONE)
        dev = RoundDevice(context, "dev-a", roundSteps = listOf(
            LateStep(StepSlot.AFTER_RATCHET, 0) { consumer },
            LateStep(StepSlot.POST_UPLOAD, 0) { finisher },
            LateStep(StepSlot.BACKUPS, 100) { meta },
        ))
        val wm = DataStoreEventWatermark(dev.syncDataStore)
        consumer = InboxConsumer(dev.repository, wm, prefs, gateBlocks = { gateClosed }, tapNow = { tapNow },
            throttleNow = { throttleNow }, zone = { ZONE }, diag = diag)
        finisher = InboxFinisher(consumer, diag = diag)
        meta = InboxMetaWriter(dev.repository, prefs, clock = { 1790000000000L }, diag = diag)
        dev.link()
        dev.repository.insertTask(TaskEntity(id = "h1", type = "habit", title = "Water", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
    }

    @After fun inboxTearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        SyncLock.resetForTest()
        dev.close()
        com.example.data.boot.restoreRealBootClock()
    }

    /** The next round is past the 60 s throttle. */
    protected fun nextMinute() { throttleNow += 61_000L }

    protected fun drop(id: String, habit: String = "h1", dir: Int = 1, ts: Long = at(25, 9), where: String = InboxPaths.INBOX_DIR) =
        inbox.put("$where/$id.json", bubblewrapJson(id, habit, dir, ts))

    protected suspend fun h1() = dev.repository.getTaskByIdSync("h1")!!

    protected fun remoteWith(vararg tasks: JsonObject): DownloadResult.Ok {
        val st = JsonObject(mapOf("tasks" to JsonArray(tasks.toList())))
        val body = CanonicalJson.canonicalJsonString(wrap(st, "other", 1L))
        return DownloadResult.Ok("rev-x", body, Json.parseToJsonElement(body) as JsonObject)
    }

    protected fun habitJson(id: String, type: String = "habit") = buildJsonObject {
        put("id", JsonPrimitive(id)); put("type", JsonPrimitive(type)); put("title", JsonPrimitive(id))
    }

    protected fun round(remote: DownloadResult.Ok? = null) = RoundContext(
        remote, JsonObject(emptyMap()), null, MergeContext.forRound("dev-a", null, 0L, null, ZONE),
    ).also { it.accessToken = "access-dev-a" }

    protected fun id(n: Int) = "aaaaaaaa-0000-4000-8000-%012d".format(n)
}
