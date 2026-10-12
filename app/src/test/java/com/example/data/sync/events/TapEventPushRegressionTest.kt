package com.example.data.sync.events

import com.example.data.boot.restoreRealBootClock
import com.example.data.clock.PhysicalClock
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.RoundContext
import com.example.data.sync.RoundDevice
import com.example.data.sync.RoundTestBase
import com.example.data.sync.StepResult
import com.example.data.sync.StepSlot
import com.example.data.sync.SyncClocks
import com.example.data.sync.SyncRoundStep
import com.example.data.sync.SyncTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live scenario 12 regression probe: the repository tap path (LiveInteropTest "tap" =
 * RpgRepository.scoreHabitUp) followed by rounds wired as production (pushClock = mergeNow).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TapEventPushRegressionTest : RoundTestBase() {
    private val devices = mutableListOf<RoundDevice>()

    private class LateStep : SyncRoundStep {
        lateinit var inner: SyncRoundStep
        override val slot = StepSlot.EVENTS
        override val order = 0
        override suspend fun run(r: RoundContext): StepResult = inner.run(r)
    }

    /** KT-97: the tap is stamped by BootGate.clock; ownUids looks in the REAL month's file. */
    @Before fun realBootClock() = restoreRealBootClock()

    @After fun closeDevices() = devices.forEach { it.close() }

    private fun device(id: String): Pair<RoundDevice, EventsStep> {
        val late = LateStep()
        val d = RoundDevice(context, id, roundSteps = listOf(late)).also { devices += it }
        val step = EventsStep(
            w = DataStoreEventWatermark(d.syncDataStore),
            cfg = InMemoryEvtCfgStore(),
            store = RoomEventStore(d.db),
            deviceId = { d.syncDataStore.getOrCreateDeviceId() },
            clock = SyncClocks.transport,
            diag = DiagRing(SyncClocks.transport),
            saveLastError = { d.syncDataStore.saveSyncStatus(it) },
            pushClock = SyncClocks.mergeNow,
        )
        late.inner = step
        return d to step
    }

    private fun ownUids(d: RoundDevice, dev: String): Set<String> {
        val f = fake.eventFiles["$dev-${EventSyncEngine.evtMonthKey(PhysicalClock.nowMs())}.json"] ?: return emptySet()
        return Json.parseToJsonElement(f.content).jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content }.toSet()
    }

    private suspend fun habit(d: RoundDevice): String {
        val stamp = d.repository.hlcClock.now()
        d.repository.insertTask(TaskEntity(id = "h-s12", type = "habit", title = "LIVE-s12", createdAt = stamp, updatedAt = stamp, up = true, down = true))
        return "h-s12"
    }

    @Test
    fun repositoryTapReachesOwnEventFile() = runBlocking {
        val (d, _) = device("dev-a")
        d.link()
        val id = habit(d)
        d.manager.sync(SyncTrigger.MANUAL)
        d.repository.scoreHabitUp(id)
        val tap = d.repository.allLogs.first().single { it.kind == "habitTap" }
        d.manager.sync(SyncTrigger.MANUAL)
        val dev = d.syncDataStore.getOrCreateDeviceId()
        assertTrue("tap ${tap.uid} ts=${tap.timestamp} not in ${ownUids(d, dev)}; wm=${d.syncDataStore.evtLastUploadTs.first()} err=${d.syncDataStore.lastSyncError.first()}",
            tap.uid in ownUids(d, dev))
    }

    @Test
    fun repositoryTapReachesOwnEventFile_afterFakeDayFutureEvents() = runBlocking {
        val (d, _) = device("dev-a")
        d.link()
        val id = habit(d)
        // Fake-day leftovers: own events stamped 2.5 h ahead, pushed, watermark in the future.
        val future = PhysicalClock.nowMs() + 9_006_126L
        d.repository.insertLog(EventLogEntity(timestamp = future, kind = "complete", taskId = id))
        d.manager.sync(SyncTrigger.MANUAL)
        val w = DataStoreEventWatermark(d.syncDataStore)
        if (w.get() < future) w.advanceTo(future)
        EvtWatermarkRepair(w, RoomEventStore(d.db), SyncClocks.transport, DiagRing(SyncClocks.transport)).runIfFuture()
        d.manager.sync(SyncTrigger.MANUAL)
        d.repository.scoreHabitUp(id)
        val tap = d.repository.allLogs.first().single { it.kind == "habitTap" }
        d.manager.sync(SyncTrigger.MANUAL)
        val dev = d.syncDataStore.getOrCreateDeviceId()
        assertTrue("tap ${tap.uid} ts=${tap.timestamp} not in ${ownUids(d, dev)}; wm=${d.syncDataStore.evtLastUploadTs.first()} err=${d.syncDataStore.lastSyncError.first()}",
            tap.uid in ownUids(d, dev))
    }
}
