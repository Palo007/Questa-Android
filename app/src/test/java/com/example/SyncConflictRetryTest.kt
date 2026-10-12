package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.DropboxService
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 4 / T4.4/T4.5 -- Robolectric tests for [SyncManager.pushStateWithConflictRetry]'s
 * `/state.json` conflict-retry loop, exercised ONLY through the public `syncManager.syncNow()`
 * entry point (the retry loop itself is `private`; per this task's constraint, no visibility was
 * widened for it). Dropbox conflicts are forced deterministically via
 * [FakeDropboxServer.forceStateConflicts], which makes the NEXT N `/state.json` upload attempts
 * return a genuine 409 `update/conflict` response regardless of the supplied rev -- this exercises
 * exactly the same [com.example.data.sync.DropboxService.uploadState] 409-body-parsing path
 * a real concurrent writer would trigger, while keeping the test deterministic (no real second
 * writer/timing needs to be simulated).
 *
 * See `EventSyncPushPullTest` for this file's sibling (events/ files push/pull, no `/state.json`
 * conflicts) and [FakeDropboxServer]'s doc for why `DropboxService.client` needed a
 * production-code seam to make any of this reachable from a plain JVM test at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncConflictRetryTest {
    @get:org.junit.Rule val config = com.example.data.sync.auth.DropboxConfigRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncDataStore: SyncDataStore
    private lateinit var syncManager: SyncManager
    private val tokens = com.example.data.sync.auth.InMemorySecureTokenStore()
    private lateinit var fakeServer: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.data.clock.StartupBarrier.markDone() // WP-8.6: rounds await the barrier (SyncLock)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore, db
        )
        syncManager = SyncManager(context, repository, syncDataStore, null, tokens)

        fakeServer = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fakeServer.asClient()

        // Defensive reset -- see EventSyncPushPullTest's setUp for why this Robolectric DataStore
        // file's state cannot be assumed to reset between test methods on its own.
        syncDataStore.clearDropbox()
        syncDataStore.saveEvtLastPullAt(0L)
        syncDataStore.saveEvtLastUploadTs(0L)
        syncDataStore.saveEvtFileRevs(emptyMap())

        FakeDropboxServer.seedScratchTokens(syncDataStore, tokens)
        FakeDropboxServer.linkScratchAccount(syncDataStore) // WP-8.1 D7
    }

    @After
    fun tearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        db.close()
    }

    private val remoteOnlyTaskId = "remote-task-1"
    private val localOnlyTaskId = "local-task-1"

    private fun seedRemoteState(): String {
        val body = """
            {
              "schema": 1,
              "savedAt": 1000,
              "deviceId": "dev-remote-seed",
              "state": {
                "char": { "name": "Hero", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
                "tasks": [
                  { "id": "$remoteOnlyTaskId", "type": "todo", "title": "Remote Task", "done": false, "updatedAt": 1000, "createdAt": 500 }
                ],
                "rewards": [], "tags": [], "devices": [],
                "lastCron": "20260716",
                "history": [], "charHistory": [], "monthlyBackups": [], "deletions": []
              }
            }
        """.trimIndent()
        val rev = fakeServer.seedState(body)
        // WP-8.6: a round with a remote needs a base valid for (APP_KEY, account) (first-connect
        // gate). An empty base with an older rev keeps the old "no common history" merge.
        kotlinx.coroutines.runBlocking {
            com.example.data.sync.auth.AuthTestSupport.seedValidBase(db, "rev-base-seed", "{}")
        }
        return rev
    }

    private suspend fun seedLocalOnlyTask() {
        repository.insertTask(TaskEntity(id = localOnlyTaskId, type = "todo", title = "Local Task", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
    }

    private fun mergedTaskIds(content: String): Set<String> {
        val wrapper = Json.parseToJsonElement(content).jsonObject
        val tasks = wrapper["state"]!!.jsonObject["tasks"]!!.jsonArray
        return tasks.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
    }

    // -----------------------------------------------------------------------------------------
    // Scenario 4: a single 409 conflict -> re-download, re-merge, retry succeeds. The final
    // uploaded content must be the genuinely re-merged result (both local's and remote's tasks),
    // not a stale copy of the pre-conflict merge.
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_singleConflict_reDownloadsReMergesAndSucceedsOnRetry() = runBlocking {
        seedRemoteState()
        seedLocalOnlyTask()
        fakeServer.forceStateConflicts = 1

        val result = syncManager.syncNow()

        if (result is SyncManager.SyncResult.Error) {
            fail("a single conflict must be absorbed by the retry loop, but failed with: ${result.message}")
        }
        assertTrue("result should be Success", result is SyncManager.SyncResult.Success)
        assertEquals("initial attempt (conflicted) + one retry (succeeded)", 2, fakeServer.stateUploadAttempts)

        val finalContent = fakeServer.stateFile?.content
        assertNotNull("a successful retry must actually persist new content to /state.json", finalContent)
        val finalIds = mergedTaskIds(finalContent!!)
        assertTrue("the retry's re-merge must include remote's task", finalIds.contains(remoteOnlyTaskId))
        assertTrue("the retry's re-merge must include local's task", finalIds.contains(localOnlyTaskId))
    }

    // -----------------------------------------------------------------------------------------
    // Scenario 5: every attempt conflicts (initial + all 3 retries = 4 total) -> retries exhausted
    // -> SyncResult.Error, and /state.json is never actually overwritten.
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_fourthConsecutiveConflict_exhaustsRetries_surfacesAsError() = runBlocking {
        val originalRev = seedRemoteState()
        val originalContent = fakeServer.stateFile!!.content
        seedLocalOnlyTask()
        // SYNC_CONFLICT_RETRY_LIMIT = 3 -> 1 initial + 3 retries = 4 total attempts if every one
        // of them conflicts. Force comfortably more than that so exhaustion is unambiguous even if
        // an off-by-one crept in on either side.
        fakeServer.forceStateConflicts = 10

        val result = syncManager.syncNow()

        assertTrue(
            "exhausting every retry attempt must surface as SyncResult.Error (this port's " +
                "deliberate PWA divergence -- see pushStateWithConflictRetry's doc)",
            result is SyncManager.SyncResult.Error
        )
        val message = (result as SyncManager.SyncResult.Error).message
        assertTrue("error message should mention retry exhaustion: $message", message == SyncManager.CONFLICT_MESSAGE) // WP-8.6: Opti's text (sync.js:2544)

        assertEquals("initial attempt + exactly 3 retries = 4 total upload tries", 4, fakeServer.stateUploadAttempts)

        // /state.json must be left exactly as it was -- no partial/half-merged write ever lands.
        assertEquals(originalRev, fakeServer.stateFile!!.rev)
        assertEquals(originalContent, fakeServer.stateFile!!.content)
    }

    // -----------------------------------------------------------------------------------------
    // WP-5.1 (spec §7 D10) -- retries-exhausted no longer only returns a silent SyncResult.Error:
    // pushStateWithConflictRetry's exhaustion branch now also invokes the `onConflictExhausted`
    // constructor callback BEFORE returning, so a caller with a scheduler (SyncScheduler) can react
    // by arming its own conflict-backoff retry instead of treating the Error as a dead end. This
    // constructs a SEPARATE SyncManager instance (sharing the same repository/syncDataStore/fake
    // server as `syncManager` above) wired with a callback that just flips a flag, since asserting
    // the callback fires is independent of asserting the return value itself (already covered by
    // `syncNow_fourthConsecutiveConflict_exhaustsRetries_surfacesAsError` above).
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_retriesExhausted_alsoInvokesOnConflictExhaustedCallback() = runBlocking {
        seedRemoteState()
        seedLocalOnlyTask()
        fakeServer.forceStateConflicts = 10

        var callbackInvoked = false
        val callbackWiredManager = SyncManager(
            context = ApplicationProvider.getApplicationContext(),
            repository = repository,
            syncDataStore = syncDataStore,
            onConflictExhausted = { callbackInvoked = true },
            tokenStore = tokens
        )

        val result = callbackWiredManager.syncNow()

        assertTrue(
            "exhaustion must still return SyncResult.Error (onConflictExhausted must not change syncNow()'s return value)",
            result is SyncManager.SyncResult.Error
        )
        assertTrue("onConflictExhausted callback must be invoked on retry exhaustion", callbackInvoked)
    }

    // -----------------------------------------------------------------------------------------
    // WP-8.5 follow-ups: the round's physical now comes from the injected physicalClock (not
    // System.currentTimeMillis()), and each conflict retry re-reads it, as Opti re-reads
    // Date.now() on every merge() call.
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_mergeNowPhysical_comesFromInjectedClock_andIsReReadOnConflictRetry() = runBlocking {
        seedRemoteState()
        seedLocalOnlyTask()
        fakeServer.forceStateConflicts = 1

        val clockReads = mutableListOf<Long>()
        var next = System.currentTimeMillis() + 7_777L // clock-ok:test-clock-seed
        val fakeClock: () -> Long = { next += 60_000L; clockReads.add(next); next }
        val seenNowPhysical = mutableListOf<Long>()
        val real = com.example.data.sync.merge.OptiSnapshotMerger()
        val recordingMerger = com.example.data.sync.merge.SnapshotMerger { base, local, remote, ctx, sink ->
            seenNowPhysical.add(ctx.nowPhysical)
            real.merge(base, local, remote, ctx, sink)
        }
        val manager = SyncManager(
            context = ApplicationProvider.getApplicationContext(),
            repository = repository,
            syncDataStore = syncDataStore,
            tokenStore = tokens,
            merger = recordingMerger,
            mergeClock = fakeClock, // WP-8.6: merge()'s physical now has its own seam
        )

        val result = manager.syncNow()

        assertTrue("single conflict must succeed: $result", result is SyncManager.SyncResult.Success)
        assertEquals("initial merge + one retry merge", 2, seenNowPhysical.size)
        assertTrue(
            "initial merge nowPhysical must be a physicalClock read: $seenNowPhysical vs $clockReads",
            seenNowPhysical[0] in clockReads
        )
        assertTrue(
            "retry merge nowPhysical must be a fresh physicalClock read: $seenNowPhysical vs $clockReads",
            seenNowPhysical[1] in clockReads && seenNowPhysical[1] > seenNowPhysical[0]
        )
    }
}
