package com.example.data.sync.events

import com.example.data.clock.DefaultHlcClock
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.EventSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.7 -- the event-log writer bound to the HLC sink and the BootGate lifecycle sink, and the
 * PX-55 id formats.
 */
class EventLogWriterTest {
    private val now = 1790251200000L

    @Test
    fun lifecycleSink_writesALocalOnlyLifecycleEvent() {
        val rows = mutableListOf<EventLogEntity>()
        val scope = TestScope(StandardTestDispatcher())
        val w = EventLogWriter({ rows.add(it) }, { "dev-me" }, scope, clock = { now })
        w.lifecycleSink("lifecycle", "reload:newDay")
        scope.advanceUntilIdle()
        val e = rows.single()
        assertEquals("lifecycle", e.kind)
        assertEquals(now, e.timestamp)
        assertEquals("dev-me", e.dev)
        assertEquals("""{"detail":"reload:newDay"}""", e.payloadJson)
        assertFalse(e.synthetic)
        assertFalse("lifecycle never uploads", EvtUploadPolicy.uploadable(e, "dev-me", 0))
    }

    @Test
    fun hlcSink_deliversBufferedBootEvents_asUploadableEvents() {
        val rows = mutableListOf<EventLogEntity>()
        val scope = TestScope(StandardTestDispatcher())
        val w = EventLogWriter({ rows.add(it) }, { "dev-me" }, scope, clock = { now })
        // A clock seeded 10 min in the future heals at boot and emits hlcReset BEFORE any sink is bound.
        val clock = DefaultHlcClock(System.currentTimeMillis() + 600_000L) { }
        clock.heal()
        clock.bindEventSink(w.hlcSink)
        scope.advanceUntilIdle()
        val e = rows.single()
        assertEquals("hlcReset", e.kind)
        val p = Json.parseToJsonElement(e.payloadJson!!).jsonObject
        assertEquals(setOf("was", "to", "driftMs"), p.keys)
        assertTrue("Opti uploads diagnostics (includeDiag: true)", EvtUploadPolicy.uploadable(e, "dev-me", 0))
        val pushed = EventSyncEngine.buildEventPushJson(e)
        assertEquals(listOf("ts", "uid", "dev", "kind", "was", "to", "driftMs"), pushed.keys.toList())
    }

    @Test
    fun writeFailure_neverReachesTheCaller() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val w = EventLogWriter({ throw IllegalStateException("db closed") }, { "dev-me" }, scope, clock = { now })
        w.logEvent("clockSkew", mapOf("remoteTs" to now + 1, "localTs" to now))
    }

    @Test
    fun eventUid_isOptiShape_devTs36Rand6() = runBlocking {
        val rows = mutableListOf<EventLogEntity>()
        val w = EventLogWriter({ rows.add(it) }, { "dev-1a2b3c4d" }, CoroutineScope(Dispatchers.Unconfined), clock = { now })
        val e = w.logEventNow("clockSkew", mapOf("remoteTs" to now + 1, "localTs" to now, "undefinedKey" to null))
        assertTrue(e.uid!!, Regex("^dev-1a2b3c4d-" + now.toString(36) + "-[0-9a-z]{6}$").matches(e.uid!!))
        assertEquals("""{"remoteTs":${now + 1},"localTs":$now}""", e.payloadJson)
    }

    @Test
    fun deviceAndUidFormats_areAcceptedByTheFileNameParserAndTheUploadGate() {
        // PX-55: Kotlin's deviceId stays `dev-` + 8 hex (SyncDataStore, not an 8.7 file); Opti's is
        // base36 ts + 5 random. Both parse back out of `<dev>-<YYYYMM>.json` (greedy regex).
        for (dev in listOf("dev-1a2b3c4d", "mfz3k2a1b9xyz", "dev-2026-09")) {
            assertEquals(dev to "202609", EventSyncEngine.evtParseFileName("$dev-202609.json"))
            val uid = EventSyncEngine.generateEventUid(dev, now)
            assertTrue(EvtUploadPolicy.uploadable(EventLogEntity(timestamp = now, kind = "habitTap", uid = uid, dev = dev), dev, 0))
        }
    }
}
