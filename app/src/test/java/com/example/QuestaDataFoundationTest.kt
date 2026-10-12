package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.local.entities.CharHistoryEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.SerializationEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestaDataFoundationTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testAtomicTaskTombstone() = runBlocking {
        val taskDao = db.taskDao()
        val tombstoneDao = db.tombstoneDao()

        val taskId = "task-uuid-123"
        val task = TaskEntity(
            id = taskId,
            type = "todo",
            title = "Atomic test task",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        taskDao.insertTask(task)

        // Confirm task is present
        assertNotNull(taskDao.getTaskByIdSync(taskId))

        // Confirm no tombstones exist yet
        assertTrue(tombstoneDao.getAllTombstonesSync().isEmpty())

        // Delete with tombstone (atomic `@Transaction` method!)
        taskDao.deleteWithTombstone(taskId, System.currentTimeMillis())

        // Confirm task is deleted
        assertNull(taskDao.getTaskByIdSync(taskId))

        // Confirm tombstone is created
        val tombstones = tombstoneDao.getAllTombstonesSync()
        assertEquals(1, tombstones.size)
        assertEquals(taskId, tombstones[0].id)
    }

    @Test
    fun testAtomicRewardTombstone() = runBlocking {
        val rewardDao = db.rewardDao()
        val tombstoneDao = db.tombstoneDao()

        val rewardId = "reward-uuid-456"
        val reward = RewardEntity(
            id = rewardId,
            title = "Atomic test reward",
            cost = 100.0,
            createdAt = System.currentTimeMillis(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()
        )

        rewardDao.insertReward(reward)

        // Confirm reward is present
        assertNotNull(rewardDao.getRewardByIdSync(rewardId))

        // Confirm no tombstones exist yet
        assertTrue(tombstoneDao.getAllTombstonesSync().isEmpty())

        // Delete with tombstone (atomic `@Transaction` method!)
        rewardDao.deleteWithTombstone(rewardId, System.currentTimeMillis())

        // Confirm reward is deleted
        assertNull(rewardDao.getRewardByIdSync(rewardId))

        // Confirm tombstone is created
        val tombstones = tombstoneDao.getAllTombstonesSync()
        assertEquals(1, tombstones.size)
        assertEquals(rewardId, tombstones[0].id)
    }

    @Test
    fun testSchema2ParitySerializationCycle() {
        // Build Schema-2 compliant data
        val character = CharacterEntity(
            id = 1,
            name = "Mage Warrior",
            face = "🧙",
            cls = "Sorcerer",
            lvl = 5,
            xp = 500,
            hp = 48.5,
            maxHp = 50.0,
            mp = 30,
            gold = 99.9,
            updatedAt = 999999L
        )

        val task = TaskEntity(
            id = "task-1",
            type = "daily",
            title = "Do dailies",
            notes = "Include tests",
            done = false,
            tags = listOf("work", "personal"),
            missedOn = 20260716,
            updatedAt = 888888L, createdAt = com.example.data.clock.PhysicalClock.nowMs()
        )

        val reward = RewardEntity(
            id = "reward-1",
            title = "A Great Reward",
            cost = 50.0,
            notes = "For being atomic",
            createdAt = 777777L,
            updatedAt = 666666L
        )

        val tags = listOf(
            TagEntity(id = "tag-1", name = "work", color = "blue", createdAt = 111L, updatedAt = 222L)
        )

        val devices = listOf(
            DeviceEntity(id = "device-1", name = "Android Phone", updatedAt = 333L)
        )

        val charHistory = listOf(
            CharHistoryEntity(date = 444L, hp = 45.0, maxHp = 50.0, xp = 100, mp = 10, gold = 20.0, lvl = 2)
        )

        // Export using SerializationEngine (tokenized Schema-2 representation)
        val exported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = character,
            tasks = listOf(task),
            rewards = listOf(reward),
            events = emptyList(),
            deletions = listOf("old-deleted" to 555L),
            lastCron = "20260716",
            tags = tags,
            devices = devices,
            charHistory = charHistory
        )

        assertNotNull(exported)
        assertTrue(exported.contains("_backup"))

        // Re-import and check parity
        val imported = SerializationEngine.importBackup(exported)

        // Character parity
        assertEquals("Mage Warrior", imported.character.name)
        assertEquals("Sorcerer", imported.character.cls)
        assertEquals(5, imported.character.lvl)
        assertEquals(999999L, imported.character.updatedAt)

        // Task parity (including TypeConverters lists/history/missedOn)
        assertEquals(1, imported.tasks.size)
        val importedTask = imported.tasks[0]
        assertEquals("Do dailies", importedTask.title)
        assertEquals(listOf("work", "personal"), importedTask.tags)
        assertEquals(20260716, importedTask.missedOn)
        assertEquals(888888L, importedTask.updatedAt)

        // Reward parity
        assertEquals(1, imported.rewards.size)
        val importedReward = imported.rewards[0]
        assertEquals("A Great Reward", importedReward.title)
        assertEquals(666666L, importedReward.updatedAt)

        // Extra Schema-2 collection parity (tags, devices, charHistory)
        assertEquals(1, imported.tags.size)
        assertEquals("tag-1", imported.tags[0].id)
        assertEquals("work", imported.tags[0].name)

        assertEquals(1, imported.devices.size)
        assertEquals("device-1", imported.devices[0].id)
        assertEquals("Android Phone", imported.devices[0].name)

        assertEquals(1, imported.charHistory.size)
        assertEquals(444L, imported.charHistory[0].date)
        assertEquals(45.0, imported.charHistory[0].hp, 0.01)
    }
}
