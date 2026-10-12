package com.example.ui.analytics

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.sync.SyncDataStore
import com.example.ui.freshMainDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** WP-8.20 (PX-110): the tab renders Opti's labels, sections and empty states, and the builder opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnalyticsScreenTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private lateinit var db: AppDatabase
    private lateinit var vm: AnalyticsViewModel

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(same).setTransactionExecutor(same).build()
        val repo = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(), db.tagDao(), db.deviceDao(),
            db.charHistoryDao(), db.tombstoneDao(), db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            SyncDataStore(ctx), hlc = DefaultHlcClock(0L) { }
        )
        runBlocking {
            AnalyticsGoldenTest.tasks.forEach { repo.insertTask(it) }
            AnalyticsGoldenTest.tags.forEach { repo.insertTag(it) }
        }
        vm = AnalyticsViewModel(repo, InMemoryAnPrefsStore(), nowMs = { AnalyticsGoldenTest.now }, zone = { AnalyticsGoldenTest.zone })
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        AppBootQuiesce.quiesce()
    }

    @Test
    fun `header, default views, details and the view builder`() {
        rule.setContent { AnalyticsScreen(vm) }
        rule.waitForIdle()
        rule.onNodeWithText("📊 Analytics").assertExists()
        listOf("7d", "30d", "90d", "180d", "1y", "All").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithTag("an_range_lbl").assertExists()
        rule.onNodeWithText("📐 Custom views (6/20)").assertExists()
        rule.onNodeWithText("Overview").assertExists()
        rule.onAllNodesWithText("summary").assertCountEquals(2)
        rule.onAllNodesWithText("HABITS").assertCountEquals(1)
        rule.onNodeWithText("Completions / day").performScrollTo()
        rule.onNodeWithText("completed · by day · line").assertExists()
        rule.onNodeWithTag("an_add_view").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New view").assertExists()
        rule.onNodeWithText("Preview").assertExists()
        rule.onNodeWithText("Reps metric").assertExists()
    }

    @Test
    fun `full activity detail opens with Opti's sections`() {
        rule.setContent { AnalyticsScreen(vm) }
        rule.waitForIdle()
        rule.onNodeWithTag("an_list").performScrollToIndex(1)
        rule.onNodeWithTag("an_details").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Kliky — totals over time").assertExists()
        rule.onNodeWithText("+ add").assertExists()
        rule.onNodeWithText("Streak leaderboard").performScrollTo()
        rule.onNodeWithText("🔥 10").assertExists()
        rule.onNodeWithText("Recently created").performScrollTo().assertExists()
        check(rule.onAllNodesWithText("Activity heatmap").fetchSemanticsNodes().isNotEmpty())
        check(rule.onAllNodesWithTag("an_heat").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the feed is gone and the activity link passes the analytics window`() {
        var got: com.example.ui.activity.ActivityRange? = null
        rule.setContent { AnalyticsScreen(vm, onSeeActivity = { got = it }) }
        rule.waitForIdle()
        rule.onNodeWithTag("an_event_details").assertDoesNotExist()
        rule.onNodeWithTag("an_list").performScrollToIndex(2)
        rule.onNodeWithText("See activity for this period →").assertExists()
        rule.onNodeWithTag("an_see_activity").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(vm.window(noFloor = true).first, got?.fromMs)
        // the window ends now: the end stays open so later events show (review MAJOR-1)
        org.junit.Assert.assertEquals(null, got?.toMs)
    }

    @Test
    fun `the activity link on All opens the All range`() {
        var got: com.example.ui.activity.ActivityRange? = null
        vm.selectSnap("all")
        rule.setContent { AnalyticsScreen(vm, onSeeActivity = { got = it }) }
        rule.waitForIdle()
        rule.onNodeWithTag("an_list").performScrollToIndex(2)
        rule.onNodeWithTag("an_see_activity").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(com.example.ui.activity.ActivityRange(), got)
    }
}
