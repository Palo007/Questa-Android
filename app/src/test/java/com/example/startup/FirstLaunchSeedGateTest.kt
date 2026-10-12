package com.example.startup

import androidx.datastore.preferences.core.edit
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncKeys
import com.example.seedFirstLaunch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R-4 (8.12 s10): the starter tasks / rewards are seeded ONLY on a true fresh install, once.
 * Never on an upgrade (LEGACY), never on a restored copy, never after the user emptied a list.
 */
class FirstLaunchSeedGateTest : StartupTestBase() {

    private fun gate() = FirstLaunchSeedGate(h.stamp, File(h.noBackupDir, FirstLaunchSeedGate.FILE_NAME))

    private suspend fun seed() = seedFirstLaunch(h.repo, gate(), awaitBarrier = {})

    private suspend fun rewards() = h.repo.allRewards.first().size

    @Test fun freshInstall_seeds() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        seed()
        assertEquals(4, taskCount())
        assertEquals(2, rewards())
        assertFalse("seeded once: the gate is closed now", gate().shouldSeed())
    }

    @Test fun upgrade_withZeroRewards_staysZero() = runBlocking {
        // The live s10 shape: an imported legacy DB with 2 tasks, 0 rewards, a character.
        h.repo.insertTask(TaskEntity(id = "t1", type = "habit", title = "A", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        h.repo.insertTask(TaskEntity(id = "t2", type = "todo", title = "B", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(InstallStamp.LEGACY, h.stamp.read())
        seed()
        assertEquals(0, rewards())
        assertEquals(2, taskCount())
    }

    @Test fun upgrade_withEmptyDb_doesNotSeed() = runBlocking {
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        seed()
        assertEquals(0, taskCount())
        assertEquals(0, rewards())
    }

    @Test fun userDeletesAllRewards_relaunch_staysZero() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        seed()
        h.repo.allRewards.first().forEach { h.repo.deleteReward(it) }
        assertEquals(0, rewards())
        // relaunch (same stamp, same no-backup dir)
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        seed()
        assertEquals(0, rewards())
        assertEquals(4, taskCount())
    }

    @Test fun userDeletesAllTasksAndRewards_relaunch_staysEmpty() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        seed()
        h.repo.allTasks.first().forEach { h.repo.deleteTask(it) }
        h.repo.allRewards.first().forEach { h.repo.deleteReward(it) }
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        seed()
        assertEquals(0, taskCount())
        assertEquals(0, rewards())
    }

    @Test fun restoredCopy_doesNotSeed() = runBlocking {
        // A copy restored from an Android backup: the marker came along, the no-backup stamp did not.
        h.prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = com.example.data.sync.auth.DropboxConfigRule.TEST_APP_KEY }
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(InstallStamp.RESTORED, h.stamp.read())
        seed()
        assertEquals(0, taskCount())
        assertEquals(0, rewards())
    }

    @Test fun freshStamp_withContentButNoSeedMarker_doesNotReseed() = runBlocking {
        // A pre-R-4 dev install: stamp FRESH, seeded long ago (no marker file yet), rewards emptied.
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        h.repo.insertTask(TaskEntity(id = "t1", type = "habit", title = "Mine", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        seed()
        assertEquals(1, taskCount())
        assertEquals(0, rewards())
        assertFalse(gate().shouldSeed())
    }

    @Test fun failedMigrations_noStamp_doesNotSeed() = runBlocking {
        // No stamp at all (step 0b never ran): not a proven fresh install.
        seed()
        assertEquals(0, taskCount())
        assertEquals(0, rewards())
    }

    @Test fun freshInstall_rewardOnlyContent_isNotTopUp() = runBlocking {
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        h.repo.insertReward(RewardEntity(id = "r1", title = "Mine", cost = 1.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        seed()
        assertEquals("no partial seed of tasks", 0, taskCount())
        assertEquals(1, rewards())
    }
}
