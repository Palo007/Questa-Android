package com.example.data.sync.events

import com.example.data.sync.DropboxService.FileEntry
import com.example.data.sync.RoundContext
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.merge.MergeContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.7 -- the EVENTS round step: the lastError mapping of Opti `syncEventsSync`,
 * push-before-pull, and the named card acceptance lines (see also PendingPurgeConsumedTest).
 */
class EventsStepTest {
    private val now = 1790251200000L
    private val myDev = "dev-me"

    private fun round(token: String = "tok") = RoundContext(
        remote = null, local = JsonObject(emptyMap()), merged = null,
        ctx = MergeContext.forRound(localDev = myDev, remoteDev = null, nowPhysical = now, localSavedAt = null),
    ).also { it.accessToken = token }

    internal class Env(val now: Long) {
        val w = FakeWatermark()
        val cfg = InMemoryEvtCfgStore()
        val store = FakeEventStore()
        val remote = FakeEventsRemote()
        val errors = mutableListOf<String>()
        val toasts = mutableListOf<String>()
        var tokenSeen: String? = null
        val step = EventsStep(w, cfg, store, { "dev-me" }, { t -> tokenSeen = t; remote }, { now }, DiagRing({ now }),
            saveLastError = { errors.add(it) }, toast = { toasts.add(it) })
    }

    @Test
    fun slotIsEvents_andTheRoundTokenIsUsed() = runBlocking {
        val e = Env(now)
        assertEquals(StepSlot.EVENTS, e.step.slot)
        e.step.run(round("round-token"))
        assertEquals("round-token", e.tokenSeen)
    }

    @Test
    fun uploadFailure_isLastError_andSkipsThePull() = runBlocking {
        val e = Env(now)
        e.store.rows.add(EvtTestData.ev("dev-me-a", now - 1000))
        e.remote.uploadFails = true
        val r = e.step.run(round())
        assertEquals(StepResult.Failed("event sync failed: upload failed: 500"), r)
        assertEquals(listOf("event sync failed: upload failed: 500"), e.errors)
        assertEquals("no list_folder: the pull never ran", 0, e.remote.listCalls)
        assertEquals(0L, e.w.value)
    }

    /** WP-8.12-T1 review F3: the pusher's watermark reads the app (offset) clock, not the transport clock. */
    @Test
    fun pusherUsesThePushClock_forTheWatermark() = runBlocking {
        val fakeNow = now + 86_400_000L
        val w = FakeWatermark(); val store = FakeEventStore(); val remote = FakeEventsRemote()
        val step = EventsStep(w, InMemoryEvtCfgStore(), store, { "dev-me" }, { remote }, { now }, DiagRing({ now }), pushClock = { fakeNow })
        store.rows.add(EvtTestData.ev("dev-me-a", fakeNow - 1000))
        step.run(round())
        assertEquals("watermark = the event ts, not clamped to the real now", fakeNow - 1000, w.value)
    }

    @Test
    fun pushThenPull() = runBlocking {
        val e = Env(now)
        e.store.rows.add(EvtTestData.ev("dev-me-a", now - 1000))
        e.step.run(round())
        assertEquals(listOf("upload /events/dev-me-202609.json", "list"), e.remote.order)
        assertTrue(e.errors.isEmpty())
    }

    @Test
    fun blockedPush_toastsOptiText() = runBlocking {
        val e = Env(now)
        e.store.rows.add(EvtTestData.ev("s-1", now - 100))
        e.cfg.cfg = EvtCfg(fileCounts = mapOf("dev-me-202609.json" to kotlinx.serialization.json.buildJsonObject {
            put("count", kotlinx.serialization.json.JsonPrimitive(3))
            put("uids", kotlinx.serialization.json.JsonArray(listOf("s-1", "s-2").map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }))
        e.step.run(round())
        assertEquals(listOf("Sync blocked: 1 month file(s) would shrink. Use \"Force Push Events\" in Settings to override."), e.toasts)
        assertEquals(0, e.remote.uploadCalls)
    }

    @Test
    fun eventAtPlusOneDay_leavesWatermarkAtMostNow() = runBlocking {
        val e = Env(now)
        e.store.rows.add(EvtTestData.ev("f-1", now + 86_400_000L))
        e.step.run(round())
        assertEquals(1, e.remote.uploadCalls)
        assertTrue("watermark ${e.w.value} <= now", e.w.value <= now)
        assertEquals(now, e.w.value)
    }

    @Test
    fun badRev_retriesAfterExactly15Minutes() = runBlocking {
        val name = "dev-other-202609.json"
        for ((ageMs, expectDownload) in listOf(899_999L to 0, 900_000L to 1)) {
            val e = Env(now)
            e.remote.entries = listOf(FileEntry(name, "r1"))
            e.remote.downloads[name] = RawEventFile("[]", "r1")
            e.cfg.cfg = EvtCfg(badRevs = mapOf(name to EvtPullHealer.badRev("r1", now - ageMs)))
            e.step.run(round())
            assertEquals("age $ageMs", expectDownload, e.remote.downloadCalls)
        }
    }
}
