package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.DropboxService
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * Stage 4 / T4.5 -- Robolectric, real-Room-backed integration tests for the event-log Dropbox
 * push/pull pipeline (T4.2/T4.3: `SyncManager.syncEventsPush`/`syncEventsPull`). Both methods are
 * `private`, so per this task's constraint they are exercised ONLY through the public
 * `syncManager.syncNow()` entry point -- `syncNow()` calls both, unconditionally, on every
 * successful state-sync round (see `pushStateWithConflictRetry`'s doc), so a plain `syncNow()`
 * call against a freshly-seeded fake is sufficient to drive them.
 *
 * Dropbox itself is faked via [FakeDropboxServer] (an in-memory OkHttp `Interceptor` installed
 * onto [DropboxService.client] -- the one production seam this task's diff added; see that
 * field's doc). No `/state.json` conflicts are ever forced here (see [SyncConflictRetryTest] for
 * that); `/state.json` starts absent (`fakeServer.stateFile == null`) so the very first
 * `uploadState` call always succeeds via Dropbox `mode=add`, letting every test focus on
 * the events pipeline undisturbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventSyncPushPullTest {

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
            syncDataStore, db // WP-8.6: the round's transaction and base use this database
        )
        syncManager = SyncManager(context, repository, syncDataStore, null, tokens)

        fakeServer = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fakeServer.asClient()

        // Defensive reset: this Robolectric Context's DataStore file is backed by a real on-disk
        // file keyed by a fixed name (`questa_sync_preferences`, see SyncDataStore.kt) -- other
        // tests in this codebase (see CharacterUpdatedAtSyncTest's `localSavedAt_isStamped...`
        // comment) explicitly flag that this file's state is NOT guaranteed to reset between test
        // METHODS. clearDropbox() wipes exactly the fields a stale prior run could have left
        // behind that would change this test's sync behavior (rev/base snapshot); the evt* keys
        // have no bulk-clear helper, so they're reset individually.
        syncDataStore.clearDropbox()
        syncDataStore.saveEvtLastPullAt(0L)
        syncDataStore.saveEvtLastUploadTs(0L)
        syncDataStore.saveEvtFileRevs(emptyMap())

        // Bypass the real OAuth/token-refresh flow entirely -- getActiveAccessToken() only needs
        // a non-expired access token + isDropboxEnabled=true, so neither
        // no token refresh or exchange is ever invoked by these tests.
        FakeDropboxServer.seedScratchTokens(syncDataStore, tokens)
        FakeDropboxServer.linkScratchAccount(syncDataStore) // WP-8.1 D7
    }

    @After
    fun tearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        db.close()
    }

    private fun foreignEventFileJson(dev: String, uid: String, ts: Long, kind: String = "complete", title: String = "Foreign event"): String =
        JsonArray(listOf(
            buildJsonObject {
                put("ts", JsonPrimitive(ts))
                put("uid", JsonPrimitive(uid))
                put("dev", JsonPrimitive(dev))
                put("kind", JsonPrimitive(kind))
                put("taskTitle", JsonPrimitive(title))
            }
        )).toString()

    // -----------------------------------------------------------------------------------------
    // Scenario 1: two devices' month files coexist -- push from A does not clobber B's file; pull
    // ingests B's events.
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_pushesOwnMonthFile_withoutClobberingForeignFile_andIngestsForeignEvents() = runBlocking {
        val now = System.currentTimeMillis()
        val monthKey = EventSyncEngine.evtMonthKey(now)
        val myDev = syncDataStore.getOrCreateDeviceId()

        val foreignDev = "dev-b-fixed"
        val foreignFileName = "$foreignDev-$monthKey.json"
        val foreignUid = "$foreignDev-u1"
        val originalForeignContent = foreignEventFileJson(foreignDev, foreignUid, now - 4000L)
        val originalForeignRev = fakeServer.seedEventFile(foreignFileName, originalForeignContent)

        // A's own uploadable event -- insertLog auto-stamps uid/dev to THIS device (see
        // RpgRepository.insertLog's doc) since neither is supplied here.
        repository.insertLog(EventLogEntity(timestamp = now - 5000L, kind = "complete", taskTitle = "A did X"))

        val result = syncManager.syncNow()
        assertTrue("syncNow must succeed against a fresh (no state.json yet) fake Dropbox", result is SyncManager.SyncResult.Success)

        // B's file must be BIT-FOR-BIT untouched by A's push.
        val foreignAfter = fakeServer.eventFiles[foreignFileName]
        assertNotNull("device B's month file must still exist", foreignAfter)
        assertEquals("device A's push must never overwrite device B's own-named file", originalForeignContent, foreignAfter!!.content)
        assertEquals(originalForeignRev, foreignAfter.rev)

        // A's own month file must exist, contain ONLY A's own events (not B's).
        val ownFileName = "$myDev-$monthKey.json"
        val ownFile = fakeServer.eventFiles[ownFileName]
        assertNotNull("device A must have pushed its own month file", ownFile)
        val ownRecords = Json.parseToJsonElement(ownFile!!.content).jsonArray
        assertTrue(ownRecords.isNotEmpty())
        assertTrue(ownRecords.all { (it as JsonObject)["dev"]?.jsonPrimitive?.content == myDev })

        // B's event must have been pulled into local Room.
        val logs = repository.allLogs.first()
        val ingested = logs.firstOrNull { it.uid == foreignUid }
        assertNotNull("device B's event must be ingested into local Room", ingested)
        assertEquals(foreignDev, ingested!!.dev)
    }

    // -----------------------------------------------------------------------------------------
    // Scenario 2: unchanged rev skipped -- second pull with the same rev does not re-download.
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_secondPull_sameRev_doesNotReDownloadOrReIngest() = runBlocking {
        val now = System.currentTimeMillis()
        val monthKey = EventSyncEngine.evtMonthKey(now)
        val foreignDev = "dev-b-fixed"
        val foreignFileName = "$foreignDev-$monthKey.json"
        val foreignUid = "$foreignDev-u1"
        fakeServer.seedEventFile(foreignFileName, foreignEventFileJson(foreignDev, foreignUid, now - 4000L))

        val path = "/events/$foreignFileName"

        val firstResult = syncManager.syncNow()
        assertTrue(firstResult is SyncManager.SyncResult.Success)
        assertEquals(1, fakeServer.downloadFileCallCounts[path])
        assertEquals(1, repository.allLogs.first().count { it.uid == foreignUid })

        // Bypass the 60s pull throttle (EVT_PULL_MIN_INTERVAL_MS) without needing to sleep --
        // rewind the DataStore watermark directly, same as production code would see after 60s.
        syncDataStore.saveEvtLastPullAt(0L)

        val secondResult = syncManager.syncNow()
        assertTrue(secondResult is SyncManager.SyncResult.Success)

        assertEquals(
            "an unchanged rev must skip the re-download entirely (rule 13)",
            1, fakeServer.downloadFileCallCounts[path]
        )
        assertEquals(
            "no re-ingest must occur since the file was never re-downloaded",
            1, repository.allLogs.first().count { it.uid == foreignUid }
        )
    }

    // -----------------------------------------------------------------------------------------
    // Scenario 3: foreign event dedup -- same uid pulled twice (across two separate pull cycles,
    // rev DOES change) still yields a single row in Room. (In-batch, same-download duplicate
    // dedup is covered at the pure-logic level in EventSyncEngineTest.)
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_sameUidPulledAcrossTwoRevChangedCycles_yieldsSingleDbRow() = runBlocking {
        val now = System.currentTimeMillis()
        val monthKey = EventSyncEngine.evtMonthKey(now)
        val foreignDev = "dev-b-fixed"
        val foreignFileName = "$foreignDev-$monthKey.json"
        val foreignUid = "$foreignDev-u1"
        fakeServer.seedEventFile(foreignFileName, foreignEventFileJson(foreignDev, foreignUid, now - 4000L))

        val firstResult = syncManager.syncNow()
        assertTrue(firstResult is SyncManager.SyncResult.Success)
        assertEquals(1, repository.allLogs.first().count { it.uid == foreignUid })

        syncDataStore.saveEvtLastPullAt(0L)

        // Device B does a full-month-rebuild re-push (rule 21): same uid, brand-new rev -- this is
        // exactly what a real second device's next push cycle looks like for an unchanged event.
        fakeServer.seedEventFile(foreignFileName, foreignEventFileJson(foreignDev, foreignUid, now - 4000L))

        val secondResult = syncManager.syncNow()
        assertTrue(secondResult is SyncManager.SyncResult.Success)

        assertEquals(
            "the existing-uid dedup layer (rule 16-18) must prevent a second insert of the same uid",
            1, repository.allLogs.first().count { it.uid == foreignUid }
        )
    }

    // -----------------------------------------------------------------------------------------
    // Additional coverage: corrupt-JSON pull still records rev (rule 6 rev-clobber).
    // -----------------------------------------------------------------------------------------

    @Test
    fun syncNow_corruptEventFile_stillRecordsRev_andIngestsNothing() = runBlocking {
        val now = System.currentTimeMillis()
        val monthKey = EventSyncEngine.evtMonthKey(now)
        val corruptDev = "dev-c-corrupt"
        val corruptFileName = "$corruptDev-$monthKey.json"
        val corruptRev = fakeServer.seedEventFile(corruptFileName, "{ this is not a valid JSON array")

        val result = syncManager.syncNow()
        assertTrue("a corrupt foreign event file must never fail the whole sync", result is SyncManager.SyncResult.Success)

        val revs = syncDataStore.evtFileRevs.first()
        assertEquals(
            "rule 6: the rev must be recorded even though the content failed to parse, so a " +
                "permanently-corrupt file isn't re-downloaded every pull cycle",
            corruptRev, revs[corruptFileName]
        )

        val logs = repository.allLogs.first()
        assertTrue("nothing must be ingested from unparseable content", logs.none { it.dev == corruptDev })
    }
}
