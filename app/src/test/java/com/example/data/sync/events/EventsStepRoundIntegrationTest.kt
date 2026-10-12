package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.RoundContext
import com.example.data.sync.RoundDevice
import com.example.data.sync.RoundTestBase
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncResult
import com.example.data.sync.SyncRoundStep
import com.example.data.sync.SyncTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.7 -- the EventsStep plugged into a real SyncManager round through `roundSteps` (the 8.I
 * wiring), against the in-memory FakeDropboxServer: Room events, the 8.6 watermark in the
 * DataStore, the round's own access token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventsStepRoundIntegrationTest : RoundTestBase() {
    private val devices = mutableListOf<RoundDevice>()
    private var now = System.currentTimeMillis()

    /** roundSteps is read when the round runs, so the step can be built after the device. */
    private class LateStep : SyncRoundStep {
        lateinit var inner: SyncRoundStep
        override val slot = StepSlot.EVENTS
        override val order = 0
        override suspend fun run(r: RoundContext): StepResult = inner.run(r)
    }

    @After fun closeDevices() = devices.forEach { it.close() }

    private fun eventsDevice(id: String): Pair<RoundDevice, EventsStep> {
        val late = LateStep()
        val d = RoundDevice(context, id, roundSteps = listOf(late)).also { devices += it }
        val step = EventsStep(
            w = DataStoreEventWatermark(d.syncDataStore),
            cfg = InMemoryEvtCfgStore(),
            store = RoomEventStore(d.db),
            deviceId = { d.syncDataStore.getOrCreateDeviceId() },
            clock = { now },
            diag = DiagRing({ now }),
            saveLastError = { d.syncDataStore.saveSyncStatus(it) },
        )
        late.inner = step
        return d to step
    }

    @Test
    fun ownEventIsPushed_foreignEventIsPulled() = runBlocking {
        val (d, _) = eventsDevice("dev-a")
        d.link()
        d.repository.insertLog(EventLogEntity(timestamp = now - 1000, kind = "habitTap", taskId = "t1", direction = 1))
        val foreignTs = now - 2000
        val month = com.example.data.sync.EventSyncEngine.evtMonthKey(foreignTs)
        fake.seedEventFile("dev-b-$month.json", """[{"ts":$foreignTs,"uid":"dev-b-x-1","dev":"dev-b","kind":"complete","taskId":"t2"}]""")

        assertEquals(SyncResult.Ok, d.manager.sync(SyncTrigger.MANUAL))

        val own = fake.eventFiles["dev-a-${com.example.data.sync.EventSyncEngine.evtMonthKey(now - 1000)}.json"]!!
        val recs = Json.parseToJsonElement(own.content).jsonArray
        assertEquals(1, recs.size)
        assertEquals("dev-a", recs[0].jsonObject["dev"]!!.jsonPrimitive.content)
        assertEquals(now - 1000, d.syncDataStore.evtLastUploadTs.first())
        val pulled = d.repository.allLogs.first().filter { it.dev == "dev-b" }
        assertEquals(listOf("dev-b-x-1"), pulled.map { it.uid })
        val bearer = fake.requests.filter { it.endpoint == "/2/files/list_folder" }.map { it.bearer }.toSet()
        assertEquals(setOf("access-dev-a"), bearer)
        assertTrue(d.syncDataStore.lastSyncError.first() == null)
    }
}
