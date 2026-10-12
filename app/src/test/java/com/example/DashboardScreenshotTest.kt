package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.ui.DashboardScreen
import com.example.ui.MainViewModel
import com.example.data.RpgRepository
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncDataStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel8)
class DashboardScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun captureDashboardAndSheet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.getDatabase(context)
        val syncDataStore = SyncDataStore(context)
        val repository = RpgRepository(
            db.characterDao(),
            db.taskDao(),
            db.rewardDao(),
            db.eventLogDao(),
            db.tagDao(),
            db.deviceDao(),
            db.charHistoryDao(),
            db.tombstoneDao(),
            db.customViewDao(),
            db.snapshotExtrasDao(),
            db.metricsExtrasDao(),
            syncDataStore
        )
        val syncManager = SyncManager(context, repository, syncDataStore)
        
        runBlocking {
            db.characterDao().insertCharacter(CharacterEntity(name = "Hero", lvl = 5, hp = 50.0, maxHp = 50.0, xp = 100, updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
            db.taskDao().insertTask(TaskEntity(id = "1", title = "Drink Water", type = "habit", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
            db.taskDao().insertTask(TaskEntity(id = "2", title = "Read Book", type = "daily", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        }

        val viewModel = MainViewModel(repository, syncManager, syncDataStore)

        composeTestRule.setContent {
            DashboardScreen(viewModel = viewModel)
        }

        // Wait for UI to settle
        composeTestRule.waitForIdle()

        // Capture Dashboard
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/dashboard.png")

        // Click Add FAB to open sheet
        composeTestRule.onNodeWithTag("add_fab").performClick()
        composeTestRule.waitForIdle()

        // Capture Sheet
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/task_edit_sheet.png")
    }
}
