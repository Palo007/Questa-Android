package com.example.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.sync.SyncDataStore
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.ui.analytics.AnalyticsGoldenTest
import com.example.ui.analytics.AnalyticsScreen
import com.example.ui.analytics.AnalyticsViewModel
import com.example.ui.analytics.InMemoryAnPrefsStore
import com.example.ui.settings.SettingsActions
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsUiState
import com.example.ui.visuals.BellItem
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * P8 (KT-19, KT-70, KT-71, KT-73): TalkBack must hear a role, a state and a plain label on the
 * most-used custom controls. Semantics only: no size, padding or look is asserted or changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class A11ySemanticsTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private var db: AppDatabase? = null
    private var vm: AnalyticsViewModel? = null

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        db?.close()
        if (db != null) AppBootQuiesce.quiesce()
    }

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)
    private val hasSelected = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    private fun toggle(on: Boolean) =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off)

    private fun analyticsVm(): AnalyticsViewModel {
        AppBootQuiesce.quiesce()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        val d = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(same).setTransactionExecutor(same).build()
        db = d
        val repo = RpgRepository(
            d.characterDao(), d.taskDao(), d.rewardDao(), d.eventLogDao(), d.tagDao(), d.deviceDao(),
            d.charHistoryDao(), d.tombstoneDao(), d.customViewDao(), d.snapshotExtrasDao(), d.metricsExtrasDao(),
            SyncDataStore(ctx), hlc = DefaultHlcClock(0L) { }
        )
        runBlocking {
            AnalyticsGoldenTest.tasks.forEach { repo.insertTask(it) }
            AnalyticsGoldenTest.tags.forEach { repo.insertTag(it) }
        }
        return AnalyticsViewModel(repo, InMemoryAnPrefsStore(), nowMs = { AnalyticsGoldenTest.now }, zone = { AnalyticsGoldenTest.zone })
            .also { vm = it }
    }

    /** KT-70: the range chips (7d .. All) are tabs and say which one is selected. */
    @Test
    fun analyticsTabs_haveTabRoleAndSelected() {
        val v = analyticsVm()
        rule.setContent { AnalyticsScreen(v) }
        rule.waitForIdle()
        listOf("7d", "30d", "90d", "180d", "1y", "All").forEach {
            rule.onNodeWithText(it).assert(role(Role.Tab)).assert(hasSelected)
        }
    }

    /** KT-19 / KT-70: a custom clickable Box in Analytics is announced as a button. */
    @Test
    fun customClickables_haveButtonRole() {
        val v = analyticsVm()
        rule.setContent { AnalyticsScreen(v) }
        rule.waitForIdle()
        rule.onNodeWithTag("an_add_view").performScrollTo().assert(role(Role.Button))
    }

    /** KT-71: a Settings switch says its name and on/off; a tier row is a checkbox with a state. */
    @Test
    fun settingsToggleRows_haveSwitchRoleAndState() {
        rule.setContent {
            SettingsScreen(SettingsUiState(category = "activityFeed", paused = true), object : SettingsActions {})
        }
        rule.onNodeWithTag("toggle_pause")
            .assert(role(Role.Switch)).assert(toggle(true)).assert(hasContentDescription("Pause tracking"))
    }

    @Test
    fun settingsBackupTierRows_haveCheckboxRoleAndState() {
        rule.setContent {
            SettingsScreen(SettingsUiState(option = "autoBackup", autoBackup = AutoBackupEnabled(daily = true)), object : SettingsActions {})
        }
        rule.onNodeWithTag("tier_daily").assert(role(Role.Checkbox)).assert(toggle(true))
        rule.onNodeWithTag("tier_weekly").assert(role(Role.Checkbox)).assert(toggle(false))
    }

    /** KT-73: the bell rail item is read as words, not as the emoji name. */
    @Test
    fun railItems_haveActionContentDescription() {
        rule.setContent { BellItem() }
        rule.onNodeWithTag("rail_bell").assert(hasContentDescription("Reminder set"))
    }
}
