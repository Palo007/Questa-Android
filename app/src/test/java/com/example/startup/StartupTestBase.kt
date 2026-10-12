package com.example.startup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.InterlockPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class StartupTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var h: StartupHarness

    @get:org.junit.Rule val config = com.example.data.sync.auth.DropboxConfigRule()

    @Before fun setUpHarness() = runBlocking {
        // 8.I: the app's own StartupMigrations.run shares these files; let it settle first.
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        h = StartupHarness(context)
        h.reset()
    }

    @After fun tearDownHarness() {
        InterlockPolicy.resetToBuildConfig()
        h.close()
    }

    /**
     * A pre-Stage-8 install that was linked: plaintext h2bco tokens, base, EVT cursors, 3 tasks.
     * P5 (DEC-13): since G-W2-1 a stamp-less DataStore with a DEVICE_ID is a restore, so this
     * in-place upgrade is given its LEGACY class (as step 0b of an earlier launch would leave it).
     * These tests pin the legacy migration steps; the stamp-less class is in RestoreDetectionTest.
     */
    protected suspend fun seedLinkedLegacy() {
        stampLegacy()
        h.ds.saveLocalDeviceId("dev-legacy1")
        h.ds.saveTokens("legacy-at", "legacy-rt", 3600L)
        h.ds.saveSyncMetadata("rev-legacy", """{"tasks":[]}""")
        h.ds.saveEvtFileRevs(mapOf("events-dev-legacy1-2026-08.json" to "r1"))
        h.ds.saveEvtLastUploadTs(111L)
        h.ds.saveEvtLastPullAt(222L)
        h.ds.saveMonthlyBackups(listOf("backup-2026-08.json"))
        h.prefs.edit {
            it[SyncKeys.EVT_FILE_COUNTS] = """{"2026-08":{"n":3}}"""
            it[SyncKeys.EVT_BAD_REVS] = """{"x":1}"""
        }
        insertTasks(3)
    }

    /** P5 (DEC-13): the install stamp an upgraded legacy install carries (see seedLinkedLegacy). */
    protected fun stampLegacy() = h.stamp.write(InstallStamp.LEGACY)

    protected suspend fun insertTasks(n: Int) {
        repeat(n) { h.repo.insertTask(TaskEntity(id = "t$it", type = "habit", title = "T$it", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())) }
    }

    protected suspend fun taskCount(): Int = h.repo.allTasks.first().size

    protected suspend fun marker(): String? = h.p()[SyncKeys.APP_KEY_OF_DATA]

    protected suspend fun deviceId(): String? = h.p()[SyncDataStore.DEVICE_ID]
}
