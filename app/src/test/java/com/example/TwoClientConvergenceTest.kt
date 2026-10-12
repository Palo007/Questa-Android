package com.example

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-5.5 / Part B — Scripted two-client convergence test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TwoClientConvergenceTest {

    private lateinit var fakeServer: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient

    @Before
    fun setUp() {
        fakeServer = FakeDropboxServer()
        com.example.data.clock.StartupBarrier.markDone() // WP-8.6: rounds await the barrier (SyncLock)
        originalClient = DropboxService.client
        DropboxService.client = fakeServer.asClient()
    }

    @After
    fun tearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
    }

    private class DeviceStack(context: Context, val deviceId: String) {
        val tempFile = File.createTempFile("datastore_${deviceId}", ".preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        val syncDataStore: SyncDataStore = SyncDataStore(context, dataStore)
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository: RpgRepository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore,
            // Per-device sync base and HLC. Without `database` the round falls back to the
            // process-wide AppDatabase.getDatabase(context) base (SyncManager `bases`), and without
            // `hlc` to the process-wide ProcessHlc -- so both "devices" shared ONE sync base: B's
            // merge read A's pushed char as its base (and vice versa), each side then looked
            // unchanged-vs-base on the other's edit, and char never converged. Opti keeps the
            // base per browser (Opti 7d5d4f3 sync.js:903 syncBaseGet / :927 syncBasePut), so each device needs its own here.
            database = db,
            hlc = com.example.data.clock.DefaultHlcClock(seed = 0L, persist = {}),
        )
        val tokens = com.example.data.sync.auth.InMemorySecureTokenStore()
        val syncManager: SyncManager = SyncManager(context, repository, syncDataStore, null, tokens)
        // WP-8.9 (PX-111): a device with no base facing an existing remote gets the first-connect
        // choice, not a merge. "Pull from Dropbox" = this force pull (QuestaApplication wiring).
        val forceOps = ForceOpsEngine(syncManager, syncDataStore, { syncManager.buildLocalSnapshot() }, { true },
            ratchet = { repository.hlcClock.ratchet(it) })

        suspend fun setup() {
            FakeDropboxServer.seedScratchTokens(syncDataStore, tokens, "access-$deviceId", "refresh-$deviceId")
            syncDataStore.saveLocalDeviceId(deviceId)
            FakeDropboxServer.linkScratchAccount(syncDataStore) // WP-8.1 D7
        }

        suspend fun getChar() = repository.getCharacterSync()
        suspend fun updateChar(char: CharacterEntity) = repository.insertCharacter(char)
        
        suspend fun getTask(id: String) = repository.getAllTasksSync().find { it.id == id }
        suspend fun updateTask(task: TaskEntity) = repository.updateTask(task)
        
        suspend fun getReward(id: String) = repository.getAllRewardsSync().find { it.id == id }
        suspend fun updateReward(reward: RewardEntity) = repository.insertReward(reward)
        suspend fun deleteReward(reward: RewardEntity) = repository.deleteReward(reward)

        fun close() {
            db.close()
            tempFile.delete()
        }
    }

    private fun locateFixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        var attempts = 0
        while (dir != null && attempts < 8) {
            val candidate = File(dir, "fixtures/$name")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
            attempts++
        }
        throw IllegalStateException("Could not locate fixtures/$name")
    }

    @Test
    fun testTwoClientConvergence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureText = locateFixture("golden_backup.json").readText()

        val deviceA = DeviceStack(context, "device-A")
        val deviceB = DeviceStack(context, "device-B")
        deviceA.setup()
        deviceB.setup()

        try {
            deviceA.syncManager.importSnapshotFromFile(fixtureText).getOrThrow()
            deviceB.syncManager.importSnapshotFromFile(fixtureText).getOrThrow()

            // res1: Establish remote state (A pushes fixture)
            assertEquals(SyncManager.SyncResult.Success, deviceA.syncManager.syncNow())
            // B has no base yet and a remote exists: first-connect gate (SyncManager step 2,
            // PX-111). B picks "Pull from Dropbox", which writes its own base.
            assertEquals(SyncResult.Ok, deviceB.forceOps.forcePull())

            // Important: Advance time so edits look fresh
            delay(100)
            val editTs = System.currentTimeMillis()
            val dayToday = com.example.data.sync.merge.OptiSnapshotMerger.dayStampOf(editTs, java.time.ZoneId.systemDefault())

            // --- Character conflict: B wins (newer) ---
            deviceA.updateChar(deviceA.getChar().copy(gold = 100.0, updatedAt = editTs + 10000))
            deviceB.updateChar(deviceB.getChar().copy(gold = 200.0, updatedAt = editTs + 20000))

            // --- Task conflict: A wins (completion beats miss) ---
            deviceA.updateTask(deviceA.getTask("lqxh2e5f6g7h")!!.copy(
                done = true, doneAt = editTs + 30000, updatedAt = editTs + 30000
            ))
            deviceB.updateTask(deviceB.getTask("lqxh2e5f6g7h")!!.copy(
                done = false, doneAt = null, missedOn = dayToday, updatedAt = editTs + 40000
            ))

            // --- Reward conflict: B wins (resurrection) ---
            deviceA.deleteReward(deviceA.getReward("lqxh4m1n2o3p")!!)
            
            // Ensure B's edit is strictly newer than A's deletion (which happened at System.currentTimeMillis())
            deviceB.updateReward(deviceB.getReward("lqxh4m1n2o3p")!!.copy(
                title = "Edited Movie Night", updatedAt = System.currentTimeMillis() + 50000
            ))

            // Sync Sequence: A -> B -> A
            assertEquals(SyncManager.SyncResult.Success, deviceA.syncManager.syncNow()) // resA1: pushes A's edits
            assertEquals(SyncManager.SyncResult.Success, deviceB.syncManager.syncNow()) // resB1: pulls A's, merges, pushes B's

            // Check B's Room state before resA2
            assertTrue("B should have merged A's completion", deviceB.getTask("lqxh2e5f6g7h")!!.done)
            // Plain LWW, no K3 accumulation: the fixture char (lvl 5, xp 230) is non-canonical,
            // since xp >= _xpNeed(5) = 196, so _charAccumulate returns null and the newer side wins
            // (Opti 7d5d4f3 sync.js:1667-1669 _xpNeed, :1692-1695 _charCanonical, :1790 precondition 2).
            assertEquals("B should have kept its own gold", 200.0, deviceB.getChar().gold, 0.0)
            assertEquals("B should have resurrected the reward", "Edited Movie Night", deviceB.getReward("lqxh4m1n2o3p")?.title)

            deviceA.syncManager.syncNow() // resA2: pulls B's, merges (B wins all), applies to Room

            // Verify Convergence
            val finalA = deviceA.syncManager.buildLocalSnapshot()
            val finalB = deviceB.syncManager.buildLocalSnapshot()
            
            fun strip(snap: JsonObject): JsonObject {
                val m = snap.jsonObject.toMutableMap()
                m.remove("cz") // __savedAt
                m.remove("cy") // __hlcLast
                m.remove("dq") // devices
                m.remove("bp") // legacy devices array
                return JsonObject(m)
            }
            
            assertEquals("Snapshots must converge", strip(finalA), strip(finalB))

            // Verify Specific Winners
            assertEquals("Device B should win character conflict (newer)", 200.0, deviceA.getChar().gold, 0.0)
            assertTrue("Completion should beat miss", deviceA.getTask("lqxh2e5f6g7h")!!.done)
            assertEquals("Edited Movie Night", deviceA.getReward("lqxh4m1n2o3p")?.title)

            // Verify Conflict Audit Events (WP-5.3)
            val logsB = deviceB.repository.getLogsInRangeSync(0, Long.MAX_VALUE)
            assertTrue("B should have conflictResolved for char", logsB.any { it.kind == "conflictResolved" && it.taskType == "char" })
            assertTrue("B should have missReverted for task", logsB.any { it.kind == "missReverted" })
            // PWA does not log conflictResolved for tombstone vs edit (resurrection)
            // so we don't assert it here.

        } finally {
            deviceA.close()
            deviceB.close()
        }
    }
}
