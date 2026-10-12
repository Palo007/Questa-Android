package com.example.data.sync.events

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WP-8.7 (PX-51) -- the boot self-heal of a future watermark (sync.js:3842-3930). */
class EvtWatermarkRepairTest {
    private val now = 1790251200000L

    @Test
    fun futureWatermark_resetsToZero_withDiagAndToast() = runBlocking {
        val w = FakeWatermark(now + 1)
        val store = FakeEventStore(listOf(EvtTestData.ev("a", now - 5), EvtTestData.ev("b", now + 99)))
        val diag = DiagRing({ now })
        val toast = EvtWatermarkRepair(w, store, { now }, diag).runIfFuture()
        assertEquals(0L, w.value)
        assertEquals("Sync repaired: upload watermark was in the future (2026-09-24T12:00:00.001Z). Reset to 1970-01-01T00:00:00.000Z. Events will upload on next sync.", toast)
        val d = diag.snapshot().single()
        assertEquals("evtWatermarkRepaired", (d["kind"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(now + 99), d["maxLocalTs"])
        assertEquals(JsonPrimitive(2), d["eventCount"])
        assertEquals(JsonPrimitive(0), d["resetTo"])
    }

    @Test
    fun watermarkAtOrBeforeNow_isLeftAlone() = runBlocking {
        val w = FakeWatermark(now)
        assertNull(EvtWatermarkRepair(w, FakeEventStore(), { now }, DiagRing({ now })).runIfFuture())
        assertEquals(now, w.value)
    }

    @Test
    fun readFailure_stillResets_noToast() = runBlocking {
        val w = FakeWatermark(now + 10)
        val store = FakeEventStore().apply { strictFails = true }
        val diag = DiagRing({ now })
        assertNull(EvtWatermarkRepair(w, store, { now }, diag).runIfFuture())
        assertEquals(0L, w.value)
        assertEquals(JsonPrimitive("read failed"), diag.snapshot().single()["error"])
    }

    @Test
    fun diagRing_keepsTheLast50() {
        val ring = DiagRing({ now })
        repeat(55) { ring.push("k", mapOf("i" to JsonPrimitive(it))) }
        val s = ring.snapshot()
        assertEquals(50, s.size)
        assertEquals(JsonPrimitive(5), s.first()["i"])
    }
}
