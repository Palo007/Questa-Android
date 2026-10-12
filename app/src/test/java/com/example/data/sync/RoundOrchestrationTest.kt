package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.clock.HlcClock
import com.example.data.clock.PhysicalClock
import com.example.data.local.entities.SyncBaseEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.merge.ConflictSink
import com.example.data.sync.merge.FakeStableMerger
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.merge.OptiSnapshotMerger
import com.example.data.sync.merge.SnapshotMerger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.DropboxConfigRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Shared set-up for the WP-8.6 round tests: one fake server, no real network. */
abstract class RoundTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var fake: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient
    private val devices = mutableListOf<RoundDevice>()

    // Pins the runtime app key so every round test sees the same, known key.
    @get:Rule val config = DropboxConfigRule()

    @Before fun baseSetUp() {
        fake = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fake.asClient()
    }

    @After fun baseTearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        SyncLock.resetForTest()
        devices.forEach { it.close() }
    }

    protected fun device(
        id: String = "dev-a",
        merger: SnapshotMerger = FakeStableMerger(),
        sleeper: suspend (Long) -> Unit = {},
        baseStore: BaseStore? = null,
    ) = RoundDevice(context, id, merger, sleeper = sleeper, baseStoreOverride = baseStore).also { devices += it }

    protected fun state(vararg tasks: JsonObject, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) = JsonObject(
        linkedMapOf<String, kotlinx.serialization.json.JsonElement>("tasks" to JsonArray(tasks.toList())) + extra
    )

    protected fun task(id: String, title: String, ua: Long) = buildJsonObject {
        put("id", JsonPrimitive(id)); put("type", JsonPrimitive("habit")); put("title", JsonPrimitive(title)); put("updatedAt", JsonPrimitive(ua))
    }

    protected fun seedRemote(st: JsonObject, dev: String = "other"): String =
        fake.seedState(CanonicalJson.canonicalJsonString(wrap(st, dev, 1L)))
}

/** A merger that counts its calls (for "0 merges"). */
class CountingMerger(private val inner: SnapshotMerger = FakeStableMerger()) : SnapshotMerger {
    val calls = AtomicInteger()
    override fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, ctx: MergeContext, sink: ConflictSink): JsonObject {
        calls.incrementAndGet()
        return inner.merge(base, local, remote, ctx, sink)
    }
}

/** A BaseStore wrapper that counts reads and can fail the next write (a "kill"). */
class SpyBaseStore(private val inner: BaseStore) : BaseStore {
    val reads = AtomicInteger()
    var failNextWrite = false
    override suspend fun read(): SyncBaseEntity? { reads.incrementAndGet(); return inner.read() }
    override suspend fun validFor(appKey: String, accountId: String?): SyncBaseEntity? { reads.incrementAndGet(); return inner.validFor(appKey, accountId) }
    override suspend fun writeAtomic(appKey: String, accountId: String?, rev: String, frozenJson: String) {
        if (failNextWrite) { failNextWrite = false; throw IllegalStateException("killed before the base write") }
        inner.writeAtomic(appKey, accountId, rev, frozenJson)
    }
    override suspend fun wipe() = inner.wipe()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstConnectGateTest : RoundTestBase() {
    @Test fun remotePresent_noBase_needsChoice_withZeroMerges() = runBlocking {
        val merger = CountingMerger()
        val d = device(merger = merger)
        d.link()
        seedRemote(state(task("x", "Remote", 5)))
        assertEquals(SyncResult.NeedsFirstConnectChoice, d.manager.sync())
        assertEquals("0 merges", 0, merger.calls.get())
        assertEquals("0 uploads", 0, fake.stateUploadAttempts)
        assertTrue("Room untouched", d.repository.getAllTasksSync().isEmpty())
        assertNull(d.base())
    }

    @Test fun baseOfAnotherAccount_isNotValid() = runBlocking {
        val d = device()
        d.link()
        val rev = seedRemote(state())
        d.db.syncBaseDao().writeAtomic(com.example.data.sync.auth.DropboxConfigRule.TEST_APP_KEY, "dbid:someone-else", rev, "{}")
        assertEquals(SyncResult.NeedsFirstConnectChoice, d.manager.sync())
    }

    @Test fun baseOfTheLegacyAppKey_isNotValid() = runBlocking {
        val d = device()
        d.link()
        val rev = seedRemote(state())
        d.db.syncBaseDao().writeAtomic(com.example.data.sync.auth.DropboxConfig.LEGACY_SENTINEL, FakeDropboxServer.SCRATCH_ACCOUNT_ID, rev, "{}")
        assertEquals(SyncResult.NeedsFirstConnectChoice, d.manager.sync())
    }

    @Test fun successfulRound_writesItsBaseUnderTheCurrentAppKey() = runBlocking {
        DropboxConfig.setCacheForTests("k2")
        val d = device()
        d.link()
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertNotNull("base written under k2", RoomBaseStore(d.db.syncBaseDao()).validFor("k2", d.syncDataStore.getAccountId()))
        assertEquals("k2", d.base()!!.appKey)
    }

    @Test fun remoteAbsent_addUpload_andTheBaseIsWritten() = runBlocking {
        val d = device()
        d.link()
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertEquals(1, fake.stateUploadsWithNullRev())
        val base = d.base()
        assertNotNull(base)
        assertEquals(fake.stateFile!!.rev, base!!.rev)
        assertEquals(FakeDropboxServer.SCRATCH_ACCOUNT_ID, base.accountId)
        // the base is the frozen state string that was uploaded
        val uploaded = kotlinx.serialization.json.Json.parseToJsonElement(fake.stateFile!!.content) as JsonObject
        assertEquals(StableJson.stableStringify(uploaded["state"]!!), StableJson.stableStringify(kotlinx.serialization.json.Json.parseToJsonElement(base.json)))
    }

    @Test fun legacySyncNow_mapsTheChoiceToAnError() = runBlocking {
        val d = device()
        d.link()
        seedRemote(state())
        assertEquals(SyncManager.SyncResult.Error(SyncManager.FIRST_CONNECT_MESSAGE), d.manager.syncNow())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptRemoteTest : RoundTestBase() {
    private fun assertCorrupt(body: String) = runBlocking {
        val d = device()
        d.link()
        fake.scriptNext("/2/files/download", 200, body)
        val r = d.manager.sync()
        assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.CORRUPT_REMOTE)
        assertEquals("0 uploads", 0, fake.stateUploadAttempts)
        assertEquals("lastError persisted", (r as SyncResult.Error).msg, d.syncDataStore.lastSyncError.first())
    }

    @Test fun stateNull_isCorrupt() = assertCorrupt("""{"schema":1,"savedAt":1,"state":null}""")
    @Test fun schemaOnly_isCorrupt() = assertCorrupt("""{"schema":1}""")
    @Test fun notJson_isCorrupt() = assertCorrupt("<html>nope</html>")
    @Test fun stateArray_isCorrupt() = assertCorrupt("""{"schema":1,"state":[]}""")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HlcRatchetTest : RoundTestBase() {
    @Test fun remoteSixtySecondsAhead_nextStampIsAboveIt() = runBlocking {
        val d = device()
        d.link()
        val ahead = PhysicalClock.nowMs() + 60_000L
        val st = state(task("t", "x", ahead))
        val rev = seedRemote(st)
        d.seedBase(rev, CanonicalJson.canonicalJsonString(st))
        d.manager.sync()
        assertTrue("HLC ratcheted", d.hlc.now() > ahead)
    }

    @Test fun remoteOneHourAhead_isRefused_notRatcheted() = runBlocking {
        // The old Kotlin ratchet accepted up to 1 h; Opti's tolerance is 120 s (app.js:131).
        val d = device()
        d.link()
        val ahead = PhysicalClock.nowMs() + 3_600_000L - 1_000L
        val st = state(task("t", "x", ahead))
        val rev = seedRemote(st)
        d.seedBase(rev, CanonicalJson.canonicalJsonString(st))
        d.manager.sync()
        assertTrue("refused beyond 120 s", d.hlc.now() < ahead)
    }

    @Test fun anViewsCountForTheRatchet() {
        val ahead = 5_000_000L
        val st = buildJsonObject {
            put("tasks", JsonArray(emptyList()))
            put("an", buildJsonObject { put("views", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("v")); put("updatedAt", JsonPrimitive(ahead)) }))) })
        }
        assertEquals(ahead, RoundMath.maxOrderingTs(st))
    }

    @Test fun conflictRetry_ratchetsOnTheFreshRemote() = runBlocking {
        val d = device()
        d.link()
        val st = state(task("t", "x", 1L))
        val rev = seedRemote(st)
        d.seedBase(rev, CanonicalJson.canonicalJsonString(st))
        d.repository.insertTask(TaskEntity(id = "mine", type = "habit", title = "local", updatedAt = 2L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        // The first upload conflicts; the retry downloads a remote 60 s ahead.
        val ahead = PhysicalClock.nowMs() + 60_000L
        fake.forceStateConflicts = 1
        val fresh = state(task("t", "x", ahead))
        fake.scriptNext("/2/files/download", 200, CanonicalJson.canonicalJsonString(wrap(st, "other", 1L)), mapOf("dropbox-api-result" to """{"rev":"$rev"}"""))
        fake.scriptNext("/2/files/download", 200, CanonicalJson.canonicalJsonString(wrap(fresh, "other", 1L)), mapOf("dropbox-api-result" to """{"rev":"$rev"}"""))
        d.manager.sync()
        assertTrue("ratcheted on the retry path", d.hlc.now() > ahead)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseKillProcessTest : RoundTestBase() {
    @Test fun killBetweenUploadAndBaseWrite_isDetectedAndHealedNextRound() = runBlocking {
        val first = device("dev-a")
        first.link()
        val spy = SpyBaseStore(RoomBaseStore(first.db.syncBaseDao()))
        run {
            // the same device (its Room + DataStore) with a base store that can "die" mid-write
            val manager = SyncManager(context, first.repository, first.syncDataStore, null, first.tokens,
                merger = FakeStableMerger(), baseStore = spy, hlcClock = first.hlc, roundSteps = emptyList(), sleeper = {})
            first.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
            spy.failNextWrite = true
            val r = manager.sync()
            assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.BASE_WRITE)
            assertEquals(1, fake.stateUploadAttempts)
            assertNull("no base after the kill", first.base())
            assertEquals(SyncManager.BASE_WRITE_FAILED_MESSAGE, first.syncDataStore.lastSyncError.first())

            // Next round: remote present, no base -> the gate stops it (0 merges, 0 uploads).
            assertEquals(SyncResult.NeedsFirstConnectChoice, manager.sync())

            // With the pre-kill base still present instead, the stale rev is re-uploaded once, then idle.
            val remote = kotlinx.serialization.json.Json.parseToJsonElement(fake.stateFile!!.content) as JsonObject
            first.seedBase("rev-before-kill", CanonicalJson.canonicalJsonString(state()))
            assertEquals(SyncResult.Ok, manager.sync())
            assertEquals(2, fake.stateUploadAttempts)
            assertEquals(fake.stateFile!!.rev, first.base()!!.rev)
            assertEquals(SyncResult.NothingToPush, manager.sync())
            assertEquals(2, fake.stateUploadAttempts)
            assertNotNull(remote)
        }
    }

    @Test fun selfCheck_sameRevDifferentContent_dropsTheBase() = runBlocking {
        val merger = object : SnapshotMerger {
            var sawBase: JsonObject? = JsonObject(emptyMap())
            override fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, ctx: MergeContext, sink: ConflictSink): JsonObject {
                sawBase = base; return local
            }
        }
        val d = device(merger = merger)
        d.link()
        val rev = seedRemote(state(task("t", "remote", 1L)))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(state(task("t", "poisoned", 1L))))
        d.manager.sync()
        assertNull("poisoned base discarded (2-way merge)", merger.sawBase)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SingleFlightTest : RoundTestBase() {
    @Test fun workerPlusScheduler_oneUpload() = runBlocking {
        val d = device()
        d.link()
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val scheduler = SyncScheduler(scope = this, localWrites = kotlinx.coroutines.flow.emptyFlow(), syncManager = d.manager)
        val worker = async { d.manager.sync(SyncTrigger.WORKER) }
        scheduler.requestSync(SyncTrigger.FOREGROUND)
        worker.await()
        withTimeout(10_000) { while (SyncLock.isHeld) delay(10) }
        delay(200)
        withTimeout(10_000) { while (SyncLock.isHeld) delay(10) }
        assertEquals("exactly one upload", 1, fake.stateUploadAttempts)
    }

    @Test fun workerSkipsWhileARoundHoldsTheLock() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val holder = async { SyncLock.singleFlight(SyncTrigger.MANUAL) { entered.complete(Unit); release.await(); SyncResult.Ok } }
        entered.await()
        assertNull("lock-skip", SyncLock.singleFlight(SyncTrigger.WORKER) { SyncResult.Ok })
        release.complete(Unit)
        assertEquals(SyncResult.Ok, holder.await())
    }

    @Test fun manualWaitsForTheLock_neverOverlaps() = runBlocking {
        val inside = AtomicInteger()
        var maxInside = 0
        val jobs = (1..5).map {
            async {
                SyncLock.singleFlight(SyncTrigger.MANUAL) {
                    val n = inside.incrementAndGet(); if (n > maxInside) maxInside = n
                    delay(20); inside.decrementAndGet(); SyncResult.Ok
                }
            }
        }
        assertTrue(jobs.awaitAll().all { it == SyncResult.Ok })
        assertEquals(1, maxInside)
    }

    @Test fun settleHookGetsEveryResult_includingSkipAndException() = runBlocking {
        val seen = mutableListOf<SyncResult?>()
        SyncLock.onSettled = { seen += it }
        SyncLock.singleFlight(SyncTrigger.MANUAL) { SyncResult.NothingToPush }
        try { SyncLock.singleFlight<SyncResult>(SyncTrigger.MANUAL) { throw IllegalStateException("boom") } } catch (e: IllegalStateException) { }
        assertEquals(listOf(SyncResult.NothingToPush, null), seen)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupBarrierGateTest : RoundTestBase() {
    @Test fun roundBeforeMarkDone_suspends_andNeverReadsTheBase() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        SyncLock.awaitBarrier = { gate.await() }
        val probe = device()
        probe.link()
        val spy = SpyBaseStore(RoomBaseStore(probe.db.syncBaseDao()))
        val manager = SyncManager(context, probe.repository, probe.syncDataStore, null, probe.tokens,
            merger = FakeStableMerger(), baseStore = spy, hlcClock = probe.hlc, roundSteps = emptyList(), sleeper = {})
        val round = async { manager.sync(SyncTrigger.FOREGROUND) }
        delay(300)
        assertFalse("suspended before markDone", round.isCompleted)
        assertEquals("no base read", 0, spy.reads.get())
        assertEquals("no request", 0, fake.requests.size)
        gate.complete(Unit)
        assertEquals(SyncResult.Ok, round.await())
        assertTrue(spy.reads.get() > 0)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoundRetryTest : RoundTestBase() {
    @Test fun transient429_thenOk_retriesOnTheLadder() = runBlocking {
        val waits = mutableListOf<Long>()
        val d = device(sleeper = { waits += it })
        d.link()
        fake.scriptNext("/2/files/download", 429, """{"error_summary":"too_many_requests/"}""", mapOf("Retry-After" to "3"))
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertEquals(listOf(3_000L), waits) // max(1000 ladder, 3000 asked)
    }

    @Test fun retryAfterAboveSixtySeconds_abortsWithOptisMessage() = runBlocking {
        val d = device()
        d.link()
        fake.scriptNext("/2/files/download", 503, "{}", mapOf("Retry-After" to "120"))
        val r = d.manager.sync()
        assertEquals(SyncResult.Error(ErrorKind.RATE_LIMITED, "Dropbox is rate limiting this app — try again in 120s"), r)
    }

    @Test fun fourServerErrors_giveUpAfterThreeRetries() = runBlocking {
        val waits = mutableListOf<Long>()
        val d = device(sleeper = { waits += it })
        d.link()
        repeat(4) { fake.scriptNext("/2/files/download", 500, "{}") }
        val r = d.manager.sync()
        assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.HTTP)
        assertEquals(RetryPolicy.LADDER_MS, waits)
    }

    @Test fun notTransient_noRetry() = runBlocking {
        val waits = mutableListOf<Long>()
        val d = device(sleeper = { waits += it })
        d.link()
        fake.scriptNext("/2/files/download", 400, "{}")
        assertTrue(d.manager.sync() is SyncResult.Error)
        assertTrue(waits.isEmpty())
    }

    @Test fun conflict_reMergedAndRetried_thenOk() = runBlocking {
        val d = device()
        d.link()
        val rev = seedRemote(state())
        d.seedBase(rev, CanonicalJson.canonicalJsonString(state()))
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        fake.forceStateConflicts = 1
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertEquals(2, fake.stateUploadAttempts)
    }

    @Test fun conflictExhausted_isTheConflictError_afterFourTries() = runBlocking {
        var exhausted = false
        val d = device()
        d.link()
        val manager = SyncManager(context, d.repository, d.syncDataStore, { exhausted = true }, d.tokens,
            merger = FakeStableMerger(), hlcClock = d.hlc, roundSteps = emptyList(), sleeper = {})
        val rev = seedRemote(state())
        d.seedBase(rev, CanonicalJson.canonicalJsonString(state()))
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        fake.forceStateConflicts = 10
        assertEquals(SyncResult.Error(ErrorKind.CONFLICT, SyncManager.CONFLICT_MESSAGE), manager.sync())
        assertEquals(4, fake.stateUploadAttempts)
        assertTrue(exhausted)
        assertEquals(SyncManager.CONFLICT_MESSAGE, d.syncDataStore.lastSyncError.first())
    }

    @Test fun ok_persistsLastSyncAt_andClearsLastError() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.saveSyncStatus("old error")
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertNull(d.syncDataStore.lastSyncError.first())
        assertTrue(d.syncDataStore.lastSyncAt.first() > 0L)
    }

    @Test fun notLinked_isNotLinked_andTouchesNoStatus() = runBlocking {
        val d = device()
        com.example.data.clock.StartupBarrier.markDone()
        FakeDropboxServer.linkScratchAccount(d.syncDataStore)
        val r = d.manager.sync()
        assertTrue("got $r", r is SyncResult.Error && r.kind == ErrorKind.NOT_LINKED)
        assertNull(d.syncDataStore.lastSyncError.first())
    }

    @Test fun interlockRefused_isItsOwnResult() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.saveAccountId("dbid:someone-else")
        assertEquals(SyncResult.InterlockRefused, d.manager.sync())
        assertEquals(0, fake.requests.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeApplyTest : RoundTestBase() {
    @Test fun mergedPause_isWrittenToRoom() = runBlocking {
        val d = device(merger = OptiSnapshotMerger())
        d.link()
        val local = d.manager.buildLocalSnapshot()
        val rev = seedRemote(JsonObject(local + ("pause" to buildJsonObject {
            put("paused", JsonPrimitive(true))
            put("pausedDays", JsonArray(listOf(JsonPrimitive(20260920), JsonPrimitive(20260921))))
            put("at", JsonPrimitive(1_758_000_000_000L))
        })))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(local))
        d.manager.sync()
        val p = d.repository.readSyncPrefs()
        assertNotNull(p)
        assertEquals(true, p!!.paused)
        assertEquals(listOf(20260920, 20260921), p.pausedDays)
        assertEquals(1_758_000_000_000L, p.pausedAt)
        // and it is read back into the next local subset
        assertEquals(JsonPrimitive(true), (d.manager.buildLocalSnapshot()["pause"] as JsonObject)["paused"])
    }

    @Test fun applyingAMerge_doesNotScheduleASync() = runBlocking {
        val d = device(merger = OptiSnapshotMerger())
        d.link()
        val local = d.manager.buildLocalSnapshot()
        val rev = seedRemote(JsonObject(local + ("tasks" to JsonArray(listOf(task("r1", "Remote", 5L))))))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(local))
        val emissions = AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val collector = scope.launch { d.repository.localWrites.collect { emissions.incrementAndGet() } }
        delay(50)
        d.manager.sync()
        delay(50)
        collector.cancel()
        assertEquals("the remote task was applied", listOf("r1"), d.repository.getAllTasksSync().map { it.id })
        assertEquals("no localWrites from the apply", 0, emissions.get())
        assertTrue("an apply that changed rows stamps localSavedAt", d.syncDataStore.localSavedAt.first() > 0L)
    }

    @Test fun removedRemoteRow_isDeletedWithoutANewTombstone() = runBlocking {
        val d = device(merger = FakeStableMerger())
        d.link()
        d.repository.insertTask(TaskEntity(id = "gone", type = "habit", title = "x", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val local = d.manager.buildLocalSnapshot()
        val without = JsonObject(local + ("tasks" to JsonArray(emptyList())))
        d.manager.applyRemoteAsLocal(without)
        assertTrue(d.repository.getAllTasksSync().isEmpty())
        assertTrue("no invented tombstone", d.repository.getAllTombstonesSync().isEmpty())
    }

    @Test fun lastCron_comesFromThePersistedCronDay() = runBlocking {
        val d = device()
        d.link()
        d.repository.insertCharacter(d.repository.getCharacterSync().copy(lastCron = "20260924"))
        assertEquals(JsonPrimitive(20260924), d.manager.buildLocalSnapshot()["lastCron"])
    }

    @Test fun localSubset_hasOptisTwelveKeys() = runBlocking {
        val d = device()
        d.link()
        assertEquals(SyncSubset.KEY_ORDER, d.manager.buildLocalSnapshot().keys.toList())
    }

    @Test fun deviceName_registersOwnDevice_once() = runBlocking {
        val d = device("dev-self")
        d.link()
        assertFalse("clearing a name that never existed creates nothing", d.repository.setDeviceName("  "))
        assertTrue(d.repository.setDeviceName("Pixel", hlcNow = 777L))
        assertFalse("same name again is a no-op", d.repository.setDeviceName("Pixel "))
        val dev = d.repository.getAllDevicesSync().single()
        assertEquals("dev-self", dev.id)
        assertEquals("Pixel", dev.name)
        assertEquals(777L, dev.updatedAt)
    }
}

