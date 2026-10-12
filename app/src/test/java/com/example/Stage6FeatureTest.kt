package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.CharacterEntity
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SerializationEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
class Stage6FeatureTest {

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
    fun testChecklistSubtaskReordering() {
        // Arrange: Build a checklist with 3 items
        val item1 = ChecklistItem(id = "1", text = "Subtask 1", done = false)
        val item2 = ChecklistItem(id = "2", text = "Subtask 2", done = false)
        val item3 = ChecklistItem(id = "3", text = "Subtask 3", done = false)
        
        val initialList = listOf(item1, item2, item3)
        
        // Act: Simulate reordering item 2 with item 1 (Move Up)
        val reorderedUpList = initialList.toMutableList().apply {
            val temp = this[1]
            this[1] = this[0]
            this[0] = temp
        }
        
        // Assert: Confirm the order is now 2, 1, 3
        assertEquals("2", reorderedUpList[0].id)
        assertEquals("1", reorderedUpList[1].id)
        assertEquals("3", reorderedUpList[2].id)
        
        // Act: Simulate reordering item 2 with item 3 (Move Down) in the new list
        val reorderedDownList = reorderedUpList.toMutableList().apply {
            val temp = this[0]
            this[0] = this[1]
            this[1] = temp
        }
        
        // Assert: Confirm the order has swapped back to 1, 2, 3
        assertEquals("1", reorderedDownList[0].id)
        assertEquals("2", reorderedDownList[1].id)
        assertEquals("3", reorderedDownList[2].id)
    }

    @Test
    fun testTagCombiningAndRegexLogic() {
        // Arrange: Create a task with formal tags and regex notes
        val task = TaskEntity(
            id = "task-uuid",
            type = "habit",
            title = "Practice Coding",
            notes = "Solving #kotlin puzzles on #leetcode",
            tags = listOf("work", "personal"), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()
        )
        
        // Act: Run parsing logic
        val notesTags = Regex("#\\w+").findAll(task.notes)
            .map { it.value.removePrefix("#") }
            .toList()
        
        val combinedTags = (notesTags + task.tags).distinct().filter { it.isNotEmpty() }.sorted()
        
        // Assert: Verify notes regex tags and formal tags are correctly extracted and combined
        assertEquals(4, combinedTags.size)
        assertTrue(combinedTags.contains("kotlin"))
        assertTrue(combinedTags.contains("leetcode"))
        assertTrue(combinedTags.contains("work"))
        assertTrue(combinedTags.contains("personal"))
        assertEquals(listOf("kotlin", "leetcode", "personal", "work"), combinedTags)
    }

    @Test
    fun testCustomViewDatabaseAndJsonParity() = runBlocking {
        val customViewDao = db.customViewDao()
        
        // Arrange: Insert two custom views
        val view1 = CustomViewEntity(
            id = "view-1",
            title = "Kliky 30D",
            chartType = "klik|30", createdAt = com.example.data.clock.PhysicalClock.nowMs()
        )
        val view2 = CustomViewEntity(
            id = "view-2",
            title = "Zdvihy 90D",
            chartType = "zdvih|90", createdAt = com.example.data.clock.PhysicalClock.nowMs()
        )
        
        customViewDao.insertCustomView(view1)
        customViewDao.insertCustomView(view2)
        
        // Act: Query custom views
        val allViews = customViewDao.getAllCustomViewsSync()
        
        // Assert: Confirm they are saved and retrieved correctly
        assertEquals(2, allViews.size)
        assertEquals("Kliky 30D", allViews[0].title)
        assertEquals("klik|30", allViews[0].chartType)
        
        // Act: Delete view 1
        customViewDao.deleteCustomViewById("view-1")
        val remainingViews = customViewDao.getAllCustomViewsSync()
        
        // Assert: Confirm view 1 is deleted
        assertEquals(1, remainingViews.size)
        assertEquals("view-2", remainingViews[0].id)
        
        // Act: Test composite parsing logic (chartType to metric and days)
        val parts = remainingViews[0].chartType.split("|")
        val metricKeyword = parts.getOrNull(0) ?: ""
        val timeWindowDays = parts.getOrNull(1)?.toIntOrNull() ?: 90
        
        // Assert: Verify metric and range parsing
        assertEquals("zdvih", metricKeyword)
        assertEquals(90, timeWindowDays)
    }

    @Test
    fun testSyncDataStoreHapticsToggle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val syncDataStore = SyncDataStore(context)
        
        // Default should be true
        assertTrue(syncDataStore.hapticsEnabled.first())
        
        // Set to false
        syncDataStore.setHapticsEnabled(false)
        assertFalse(syncDataStore.hapticsEnabled.first())
        
        // Set back to true
        syncDataStore.setHapticsEnabled(true)
        assertTrue(syncDataStore.hapticsEnabled.first())
    }

    @Test
    fun testExportBackupHapticsEmission() {
        val char = CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        
        // Test with haptics true
        val exportTrue = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = char,
            tasks = emptyList(),
            rewards = emptyList(),
            events = emptyList(),
            deletions = emptyList(),
            lastCron = null,
            hapticsEnabled = true
        )
        val jsonTrue = Json.parseToJsonElement(exportTrue).jsonObject
        val fmTrue = jsonTrue["FM"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val rmapTrue = fmTrue.entries.associate { it.value to it.key }
        val snapTrue = jsonTrue["S"]!!.jsonObject
        
        // prefs is tokenized, so we need to find its code or detokenize
        val prefsCode = fmTrue["prefs"] ?: "prefs"
        val hapticsCode = fmTrue["haptics"] ?: "haptics"
        
        val prefsTrue = snapTrue[prefsCode]!!.jsonObject
        assertEquals(JsonPrimitive(true), prefsTrue[hapticsCode])

        // Test with haptics false
        val exportFalse = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = char,
            tasks = emptyList(),
            rewards = emptyList(),
            events = emptyList(),
            deletions = emptyList(),
            lastCron = null,
            hapticsEnabled = false
        )
        val jsonFalse = Json.parseToJsonElement(exportFalse).jsonObject
        val fmFalse = jsonFalse["FM"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val snapFalse = jsonFalse["S"]!!.jsonObject
        val prefsCodeFalse = fmFalse["prefs"] ?: "prefs"
        val hapticsCodeFalse = fmFalse["haptics"] ?: "haptics"
        
        val prefsFalse = snapFalse[prefsCodeFalse]!!.jsonObject
        assertEquals(JsonPrimitive(false), prefsFalse[hapticsCodeFalse])
    }
}
