package com.example.data.clock

import androidx.test.core.app.ApplicationProvider
import com.example.data.boot.BootGate
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.events.EventLogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.23 (clock baseline 0): the entities and the 3 DAOs have no clock default any more. The
 * repository stamps event rows from the physical clock seam ([BootGate.clock], which is
 * `PhysicalClock.nowMs()` in the app, so the WP-8.12-T1 debug offset still applies), and
 * [EventLogWriter] defaults to the same seam. Tombstone `at` is the shared HLC (review 49,
 * PX-20: Opti `delMark` stamps `now()`, app.js:198), so a delete always outranks the last edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntityStampSeamTest {

    private val fixed = 1_700_000_123_456L
    private val saved = BootGate.clock

    @Before fun setUp() { BootGate.clock = { fixed } }
    @After fun tearDown() { BootGate.clock = saved }

    /** An HLC running [aheadMs] ahead of physical time (a ratchet from a remote in the future). */
    private fun hlcAhead(aheadMs: Long = 60_000L): HlcClock =
        DefaultHlcClock(PhysicalClock.nowMs() + aheadMs) { }

    @Test
    fun `task, reward and tag deletes stamp the tombstone from the shared HLC (Opti delMark now())`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hlc = hlcAhead()
        val (db, repo) = AuthTestSupport.newRepository(context, SyncDataStore(context), hlc)
        try {
            repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "T", createdAt = 1L, updatedAt = 1L))
            repo.insertReward(RewardEntity(id = "r1", title = "R", cost = 1.0, createdAt = 1L, updatedAt = 1L))
            repo.insertTag(TagEntity(id = "g1", name = "G", color = "#fff", createdAt = 1L, updatedAt = 1L))
            val before = hlc.lastIssued.value
            repo.deleteTaskById("t1")
            repo.deleteRewardById("r1")
            repo.deleteTagById("g1")
            val ats = db.tombstoneDao().getAllTombstonesSync().associate { it.id to it.at }
            assertEquals(setOf("t1", "r1", "g1"), ats.keys)
            // Each delete issues its own HLC stamp (strictly increasing, above the ahead-running HLC
            // seed), never the physical clock (neither BootGate's fixed value nor real time).
            val seq = listOf(ats.getValue("t1"), ats.getValue("r1"), ats.getValue("g1"))
            assertTrue("expected increasing HLC stamps above $before, got $seq", seq[0] > before && seq[1] > seq[0] && seq[2] > seq[1])
            assertTrue("expected every stamp ahead of physical time, got $seq", seq.all { it > PhysicalClock.nowMs() + 30_000L })
            assertTrue(ats.values.none { it == fixed })
        } finally { db.close() }
    }

    @Test
    fun `PX-20 - with the HLC ahead, a delete after an edit stays deleted through the merge`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hlc = hlcAhead()
        val (db, repo) = AuthTestSupport.newRepository(context, SyncDataStore(context), hlc)
        try {
            repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "T", createdAt = 1L, updatedAt = 1L))
            // The edit (MainViewModel.saveEditedTask) stamps updatedAt from the shared HLC.
            val edited = repo.getTaskByIdSync("t1")!!.copy(title = "T2", updatedAt = hlc.now())
            repo.updateTask(edited)
            repo.deleteTaskById("t1")
            val at = db.tombstoneDao().getAllTombstonesSync().single { it.id == "t1" }.at
            assertTrue("tombstone at $at must be >= the edit's updatedAt ${edited.updatedAt}", at >= edited.updatedAt)

            // The remote (and base) still hold the edited task; local has deleted it.
            val remoteTask = """{"id":"t1","type":"todo","title":"T2","createdAt":1,"updatedAt":${edited.updatedAt}}"""
            val remote = Json.parseToJsonElement("""{"tasks":[$remoteTask],"deletions":[]}""").jsonObject
            val local = Json.parseToJsonElement("""{"tasks":[],"deletions":[{"id":"t1","at":$at}]}""").jsonObject
            val now = PhysicalClock.nowMs()
            val ctx = MergeContext(
                localDev = "devA", remoteDev = "devB", futureCeil = now + 120_000L, nowPhysical = now,
                localToday = 20260925, zone = java.time.ZoneId.of("UTC"),
            )
            val merged = OptiSnapshotMerger().merge(remote, local, remote, ctx) { }
            val ids = (merged["tasks"] as? JsonArray).orEmpty().map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertFalse("the deleted task came back: $ids", "t1" in ids)
        } finally { db.close() }
    }

    @Test
    fun `repository event rows stamp from the physical clock seam`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val (db, repo) = AuthTestSupport.newRepository(context, SyncDataStore(context))
        try {
            repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "T", createdAt = 1L, updatedAt = 1L))
            repo.completeTask("t1")
            val log = db.eventLogDao().getAllLogs().first().single { it.kind == "complete" }
            assertEquals(fixed, log.timestamp)
        } finally { db.close() }
    }

    @Test
    fun `EventLogWriter without a clock stamps from the physical clock seam`() = runBlocking {
        val rows = mutableListOf<com.example.data.local.entities.EventLogEntity>()
        val w = EventLogWriter({ rows.add(it) }, { "dev-me" }, CoroutineScope(Dispatchers.Unconfined))
        val e = w.logEventNow("lifecycle", mapOf("detail" to "x"))
        assertEquals(fixed, e.timestamp)
        assertEquals(fixed, rows.single().timestamp)
    }
}
