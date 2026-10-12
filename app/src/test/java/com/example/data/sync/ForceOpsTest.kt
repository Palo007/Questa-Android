package com.example.data.sync

import com.example.data.local.entities.TaskEntity
import com.example.data.sync.auth.InterlockPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun remoteState(fake: FakeDropboxServer): JsonObject =
    (Json.parseToJsonElement(fake.stateFile!!.content) as JsonObject)["state"] as JsonObject

private fun taskIds(state: JsonObject): List<String> =
    state["tasks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

/** Opti `_syncForcePushAttempt` vectors (sync.js:2587-2616). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForcePushTest : Wp89TestBase() {

    @Test fun overwritesTheRemoteWithLocal_noMerge_andWritesTheBase() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state(task("remote-only", "Remote", 50)))
        d.repository.insertTask(TaskEntity(id = "local-1", type = "habit", title = "Local", updatedAt = 10L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(SyncResult.Ok, d.force.forcePush())
        val st = remoteState(fake)
        assertEquals("remote content replaced, not merged", listOf("local-1"), taskIds(st))
        assertEquals("update mode against the current rev", 0, fake.stateUploadsWithNullRev())
        val base = d.base()
        assertNotNull(base)
        assertEquals(fake.stateFile!!.rev, base!!.rev)
        assertEquals(StableJson.stableStringify(st), StableJson.stableStringify(Json.parseToJsonElement(base.json)))
        assertNull(d.syncDataStore.lastSyncError.first())
        assertTrue(d.syncDataStore.lastSyncAt.first() > 0L)
        assertEquals("Room untouched by a push", listOf("local-1"), d.repository.getAllTasksSync().map { it.id })
    }

    @Test fun noRemoteYet_addMode() = runBlocking {
        val d = device89()
        d.link()
        assertEquals(SyncResult.Ok, d.force.forcePush())
        assertEquals(1, fake.stateUploadsWithNullRev())
    }

    @Test fun aConflict_retriesWithAFreshRev() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state())
        fake.forceStateConflicts = 2
        assertEquals(SyncResult.Ok, d.force.forcePush())
        assertEquals(3, fake.stateUploadAttempts)
    }

    @Test fun conflictsPastTheLimit_failWithForcePushFailed() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state())
        fake.forceStateConflicts = 10
        val r = d.force.forcePush()
        assertTrue(r is SyncResult.Error)
        assertEquals(1 + ForceOpsEngine.CONFLICT_RETRY_LIMIT, fake.stateUploadAttempts)
        assertTrue(d.syncDataStore.lastSyncError.first()!!.startsWith("force push failed"))
    }

    @Test fun anUploadError_isRecordedAsForcePushFailed() = runBlocking {
        val d = device89()
        d.link()
        fake.scriptNext("/2/files/upload", 500, "{}")
        val r = d.force.forcePush()
        assertTrue(r is SyncResult.Error)
        assertTrue(d.syncDataStore.lastSyncError.first()!!.startsWith("force push failed:"))
        assertNull(d.base())
    }

    @Test fun notConfigured_sendsNothing() = runBlocking {
        val d = device89()
        val r = d.force.forcePush()
        assertEquals(ErrorKind.NOT_LINKED, (r as SyncResult.Error).kind)
        assertEquals(0, fake.requests.size)
    }

    @Test fun interlockRefusal_zeroRequests() = runBlocking {
        val d = device89()
        d.link()
        InterlockPolicy.allowlist = emptySet()
        assertEquals(SyncResult.InterlockRefused, d.force.forcePush())
        assertEquals(0, fake.requests.size)
        assertEquals(SyncManager.INTERLOCK_REFUSED_MESSAGE, d.syncDataStore.lastSyncError.first())
    }
}

/** Opti `_syncForcePullAttempt` vectors (sync.js:2648-2686) and forcepull-events.test.js. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForcePullTest : Wp89TestBase() {

    @Test fun remoteReplacesLocal_andIsTheBase() = runBlocking {
        val d = device89()
        d.link()
        d.repository.insertTask(TaskEntity(id = "local-only", type = "habit", title = "Local", updatedAt = 999L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val rev = seedRemote(state(task("remote-task", "Remote", 5)))
        assertEquals(SyncResult.Ok, d.force.forcePull())
        assertEquals("unconditional: local-only is gone", listOf("remote-task"), d.repository.getAllTasksSync().map { it.id })
        assertEquals(rev, d.base()!!.rev)
        assertEquals(0, fake.stateUploadAttempts)
        assertNull(d.syncDataStore.lastSyncError.first())
    }

    @Test fun ratchetsTheHlcBeforeApplying() = runBlocking {
        val d = device89()
        d.link()
        val ahead = d.hlc.now() + 60_000L
        seedRemote(state(task("r", "R", ahead)))
        d.force.forcePull()
        assertTrue("the next stamp is above the imported one", d.hlc.now() > ahead)
    }

    @Test fun nothingInDropbox() = runBlocking {
        val d = device89()
        d.link()
        val r = d.force.forcePull()
        assertEquals(ForceOpsEngine.NOTHING_TO_PULL, (r as SyncResult.Error).msg)
        assertEquals(ForceOpsEngine.NOTHING_TO_PULL, d.syncDataStore.lastSyncError.first())
    }

    @Test fun aRemoteWithoutATasksArray_isCorrupt_andLocalIsUntouched() = runBlocking {
        val d = device89()
        d.link()
        d.repository.insertTask(TaskEntity(id = "keep", type = "habit", title = "Keep", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        seedRemote(JsonObject(mapOf("rewards" to JsonArray(emptyList()))))
        val r = d.force.forcePull()
        assertEquals(ForceOpsEngine.PULL_CORRUPT, (r as SyncResult.Error).msg)
        assertEquals(listOf("keep"), d.repository.getAllTasksSync().map { it.id })
        assertNull(d.base())
    }

    @Test fun pullsEventsWithForce_andAnEventFailureDoesNotFailThePull() = runBlocking {
        var calls = 0
        val d = device89(eventsForcePull = { calls++; throw IllegalStateException("simulated Dropbox list failure") })
        d.link()
        val rev = seedRemote(state(task("remote-task", "Remote", 5)))
        assertEquals(SyncResult.Ok, d.force.forcePull())
        assertEquals(1, calls)
        assertEquals(listOf("remote-task"), d.repository.getAllTasksSync().map { it.id })
        assertNull(d.syncDataStore.lastSyncError.first())
        assertEquals(rev, d.base()!!.rev)
    }

    @Test fun noEventsBinding_stillPulls() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state(task("remote-task", "Remote", 5)))
        assertEquals(SyncResult.Ok, d.force.forcePull())
        assertEquals(0, fake.listFolderCallCount)
    }
}

/** round3-force-push-lock.test.js: every force op holds the ONE sync lock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForceOpsLockTest : Wp89TestBase() {

    @Test fun forcePushEvents_runsTheFullForcedPushUnderTheLock() = runBlocking {
        var heldDuringPush = false
        var pushes = 0
        val d = device89(eventsForcePush = { pushes++; heldDuringPush = SyncLock.isHeld })
        d.link()
        assertEquals(SyncResult.Ok, d.force.forcePushEvents())
        assertEquals(1, pushes)
        assertTrue(heldDuringPush)
        assertFalse(SyncLock.isHeld)
    }

    @Test fun forcePushEvents_failureIsRecorded_notThrown() = runBlocking {
        val d = device89(eventsForcePush = { throw IllegalStateException("dropbox exploded") })
        d.link()
        val r = d.force.forcePushEvents()
        assertTrue(r is SyncResult.Error)
        val err = d.syncDataStore.lastSyncError.first()!!
        assertTrue(err.startsWith("event force push failed"))
        assertTrue(err.contains("dropbox exploded"))
    }

    @Test fun forcePushAndPull_waitForARunningRound() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state())
        var sawHeld = false
        // A spy internals: the download of a force op runs while the lock is held.
        val spy = object : SyncEngineInternals by d.manager {
            override suspend fun download(): DownloadResult { sawHeld = SyncLock.isHeld; return d.manager.download() }
        }
        val eng = ForceOpsEngine(spy, d.syncDataStore, { d.manager.buildLocalSnapshot() }, { true })
        eng.forcePush()
        assertTrue(sawHeld)
        sawHeld = false
        eng.forcePull()
        assertTrue(sawHeld)
    }
}

/** force-push-on-connect.test.js T3, T6, T7. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectForForcePushTest : Wp89TestBase() {

    @Test fun withTheIntent_forcePushInsteadOfTheNormalRound_andTheIntentIsConsumed() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state(task("remote-only", "Remote", 50)))
        d.repository.insertTask(TaskEntity(id = "mine", type = "habit", title = "Mine", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.force.markConnectForForcePush()
        assertEquals(SyncResult.Ok, d.force.connectForForcePush())
        assertEquals(0, d.normalSyncCalls)
        assertEquals(listOf("mine"), taskIds(remoteState(fake)))
        assertFalse(d.backupPrefs.pendingForcePush())
    }

    @Test fun withoutTheIntent_theNormalRoundRuns() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state(task("remote-only", "Remote", 50)))
        assertEquals(SyncResult.NeedsFirstConnectChoice, d.force.connectForForcePush())
        assertEquals(1, d.normalSyncCalls)
        assertEquals(0, fake.stateUploadAttempts)
    }

    @Test fun aPlainConnectOrAFailedRedirect_dropsTheIntent() = runBlocking {
        val d = device89()
        d.force.markConnectForForcePush()
        assertTrue(d.backupPrefs.pendingForcePush())
        d.force.clearPendingForcePush()
        assertFalse(d.backupPrefs.pendingForcePush())
    }
}

/** Dropbox calls of the force ops carry the interlock (§5 WP-8.9 invariant). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForceOpsInterlockTest : Wp89TestBase() {
    @Test fun everyForceOp_isRefusedWithZeroRequests() = runBlocking {
        val d = device89(eventsForcePush = { DropboxService.listFolder("t", "/events") })
        d.link()
        seedRemote(state())
        fake.requests.clear()
        InterlockPolicy.allowlist = emptySet()
        assertEquals(SyncResult.InterlockRefused, d.force.forcePush())
        assertEquals(SyncResult.InterlockRefused, d.force.forcePull())
        assertEquals(SyncResult.InterlockRefused, d.force.forcePushEvents())
        assertEquals(SyncResult.InterlockRefused, d.firstConnect.resolveFirstConnect(FirstConnectChoice.PullFromDropbox))
        assertEquals(SyncResult.InterlockRefused, d.firstConnect.resolveFirstConnect(FirstConnectChoice.PushThisDevice))
        assertEquals(0, fake.requests.size)
    }
}
