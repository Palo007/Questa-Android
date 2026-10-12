package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.merge.SnapshotMerger
import com.example.data.sync.merge.FakeStableMerger
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import com.example.data.sync.auth.DropboxConfigRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.6 acceptance (PX-42, SY-04): two idle devices that agree with the remote and their base
 * run 20 rounds, alternating -> 0 uploads and 0 conflict events. [merger] is the fake here and
 * OptiSnapshotMerger in [IdleConvergenceIntegrationTest] (run green by 8.I at the W3 close).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
open class IdleConvergenceTest {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var fake: FakeDropboxServer
    private lateinit var originalClient: OkHttpClient
    private val devices = mutableListOf<RoundDevice>()

    // Pins the runtime app key so the bases these devices seed are valid.
    @get:Rule val config = DropboxConfigRule()

    protected open fun merger(): SnapshotMerger = FakeStableMerger()

    @Before fun setUp() {
        fake = FakeDropboxServer()
        originalClient = DropboxService.client
        DropboxService.client = fake.asClient()
    }

    @After fun tearDown() {
        DropboxService.client = originalClient
        FakeDropboxServer.restoreInterlockPolicy()
        devices.forEach { it.close() }
    }

    protected fun device(id: String) = RoundDevice(context, id, merger()).also { devices += it }

    /** Seeds [a] with real rows, then makes [b], the remote and both bases equal to a's subset. */
    protected suspend fun agreeOn(a: RoundDevice, b: RoundDevice): JsonObject {
        a.link(); b.link()
        a.repository.insertCharacter(CharacterEntity(name = "Hero", lvl = 3, xp = 40, gold = 12.5, updatedAt = 1_700_000_000_000L))
        a.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 1_700_000_000_100L, createdAt = 1L))
        a.repository.insertTask(TaskEntity(id = "t2", type = "daily", title = "Read", updatedAt = 1_700_000_000_200L, createdAt = 2L))
        a.repository.insertReward(RewardEntity(id = "r1", title = "Tea", cost = 5.0, updatedAt = 1_700_000_000_300L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val snap = a.manager.buildLocalSnapshot()
        b.manager.applyRemoteAsLocal(snap)
        assertEquals("b must rebuild exactly a's subset", StableJson.stableStringify(snap), StableJson.stableStringify(b.manager.buildLocalSnapshot()))
        val body = CanonicalJson.canonicalJsonString(snap)
        val rev = fake.seedState(CanonicalJson.canonicalJsonString(wrap(snap, "dev-a", 1L)))
        a.seedBase(rev, body)
        b.seedBase(rev, body)
        return snap
    }

    @Test fun twoIdleDevices_twentyRounds_zeroUploads_zeroConflicts() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        agreeOn(a, b)
        repeat(20) { i ->
            val d = if (i % 2 == 0) a else b
            assertEquals("round $i", SyncResult.NothingToPush, d.manager.sync(SyncTrigger.MANUAL))
        }
        assertEquals("uploads", 0, fake.stateUploadAttempts)
        assertEquals("conflict events on a", 0, a.conflictEvents())
        assertEquals("conflict events on b", 0, b.conflictEvents())
    }
}

/** 8.I runs this green at the W3 close: the same idle rounds through the real merge core. */
class IdleConvergenceIntegrationTest : IdleConvergenceTest() {
    override fun merger(): SnapshotMerger = OptiSnapshotMerger()

    @Test fun realMerger_editOnA_reachesB() = runBlocking {
        val a = device("dev-a")
        val b = device("dev-b")
        agreeOn(a, b)
        val t = a.repository.getAllTasksSync().first { it.id == "t1" }
        a.repository.updateTask(t.copy(title = "Walk 2", updatedAt = 1_700_000_001_000L))
        assertEquals(SyncResult.Ok, a.manager.sync(SyncTrigger.MANUAL))
        b.manager.sync(SyncTrigger.MANUAL)
        assertEquals("Walk 2", b.repository.getAllTasksSync().first { it.id == "t1" }.title)
        val uploads = fake.stateUploadAttempts
        repeat(20) { i -> (if (i % 2 == 0) a else b).manager.sync(SyncTrigger.MANUAL) }
        assertEquals("idle after convergence", uploads, fake.stateUploadAttempts)
        assertEquals(0, a.conflictEvents())
        assertEquals(0, b.conflictEvents())
    }
}
