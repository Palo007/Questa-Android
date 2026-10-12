package com.example

import com.example.data.sync.SerializationEngine
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncConflictResolutionTest {

    private fun parseJsonObj(s: String) = Json.parseToJsonElement(s) as JsonObject

    // WP-8.5: the seven legacy 3-way-merge tests that lived here asserted the pre-parity
    // SerializationEngine.mergeSnapshots. The merge is now OptiSnapshotMerger, checked against
    // real Opti output by merge/OptiMergeVectorsTest (fixtures/opti/G2_G3_core).

    @Test
    fun `test export and import backup`() {
        val character = com.example.data.local.entities.CharacterEntity(
            id = 1,
            name = "Test Hero",
            face = "🧙",
            cls = "Mage",
            lvl = 3,
            xp = 120,
            hp = 45.0,
            maxHp = 50.0,
            mp = 20,
            gold = 15.5, updatedAt = com.example.data.clock.PhysicalClock.nowMs()
        )

        val tasks = listOf(
            com.example.data.local.entities.TaskEntity(
                id = "t1",
                type = "todo",
                title = "Test Task",
                notes = "Notes here",
                done = true,
                checklist = listOf(
                    com.example.data.local.entities.ChecklistItem("cl1", "Subtask A", true, 1000L)
                ), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()
            )
        )

        val rewards = listOf(
            com.example.data.local.entities.RewardEntity(
                id = "r1",
                title = "Test Reward",
                cost = 15.0,
                createdAt = 1000L, updatedAt = com.example.data.clock.PhysicalClock.nowMs()
            )
        )

        val events = listOf(
            com.example.data.local.entities.EventLogEntity(
                id = 1,
                timestamp = 1000L,
                kind = "cron",
                taskType = "daily",
                taskId = "d1",
                taskTitle = "Daily Task",
                streak = 3,
                damage = 2.5,
                direction = 1,
                reps = 1,
                value = 5.0,
                cost = 0.0,
                payloadJson = """{"yesterdayStamp":"20260716"}"""
            )
        )

        val deletions = listOf("deleted_item" to 1500L)

        val exported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = character,
            tasks = tasks,
            rewards = rewards,
            events = events,
            deletions = deletions,
            lastCron = "20260716"
        )

        assertNotNull(exported)
        assertTrue(exported.contains("_backup"))

        val imported = SerializationEngine.importBackup(exported)
        assertEquals("Test Hero", imported.character.name)
        assertEquals(3, imported.character.lvl)
        assertEquals(1, imported.tasks.size)
        assertEquals("Test Task", imported.tasks[0].title)
        assertEquals(1, imported.rewards.size)
        assertEquals("Test Reward", imported.rewards[0].title)
        assertEquals(1, imported.events.size)
        assertEquals("Daily Task", imported.events[0].taskTitle)
        assertEquals(1, imported.deletions.size)
        assertEquals("deleted_item", imported.deletions[0].first)
    }

    private fun jsonArrayOf() = JsonArray(emptyList())
}
