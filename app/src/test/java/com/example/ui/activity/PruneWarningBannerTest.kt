package com.example.ui.activity

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.FakePruneWarnStore
import com.example.data.local.AgeWarning
import com.example.data.local.PendingBanner
import com.example.data.local.PruneWarnPolicy
import com.example.data.local.PruneWarnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CR-KT-014: the grace starts when the banner is seen; after the start date it says the deletion is under way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PruneWarningBannerTest {

    @get:Rule val rule = createComposeRule()

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    @Test fun warnedAtSavedOnlyWhenBannerIsShown() {
        val store = FakePruneWarnStore()
        runBlocking { store.write(PruneWarnState(pending = PendingBanner(age = true, cap = false, maxId = null))) }
        assertNull(store.state.value.age)
        rule.setContent { PruneWarningBannerHost(onBackup = {}, store = store, now = { t0 }) }
        rule.waitForIdle()
        assertEquals(AgeWarning(t0, PruneWarnPolicy.boundaryFor(t0)), store.state.value.age)
        rule.onNodeWithTag("prune_warning_banner").assertExists()
        rule.onNodeWithText("Back up now").assertExists()
    }

    @Test fun afterTheStartDate_saysBeingDeleted() {
        val store = FakePruneWarnStore()
        runBlocking {
            store.write(PruneWarnState(pending = PendingBanner(age = true, cap = false, maxId = null)))
            store.markBannerShown(t0)
        }
        rule.setContent { PruneWarningBannerHost(onBackup = {}, store = store, now = { t0 + 15L * day }) }
        rule.onNodeWithText("is being deleted.", substring = true).assertExists()
        assertEquals("a later show does not move the grace", t0, store.state.value.age!!.at)
    }

    @Test fun dismiss_hidesBanner_keepsWarning() {
        val store = FakePruneWarnStore()
        runBlocking { store.write(PruneWarnState(pending = PendingBanner(age = true, cap = false, maxId = null))) }
        rule.setContent { PruneWarningBannerHost(onBackup = {}, store = store, now = { t0 }) }
        rule.onNodeWithTag("prune_warning_dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("prune_warning_banner").assertDoesNotExist()
        assertEquals(t0, store.state.value.age!!.at)
    }

    // Hardening 1: a banner under a modal (e.g. Settings opened straight away) is not "shown".
    @Test fun coveredByModal_notMarkedShown_untilUncovered() {
        val store = FakePruneWarnStore()
        runBlocking { store.write(PruneWarnState(pending = PendingBanner(age = true, cap = false, maxId = null))) }
        var covered by mutableStateOf(true)
        rule.setContent { PruneWarningBannerHost(onBackup = {}, store = store, now = { t0 }, covered = covered) }
        rule.waitForIdle()
        assertNull("covered: grace not started", store.state.value.age)
        covered = false
        rule.waitForIdle()
        assertEquals(t0, store.state.value.age!!.at)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun notResumed_notMarkedShown_untilResumed() {
        val store = FakePruneWarnStore()
        runBlocking { store.write(PruneWarnState(pending = PendingBanner(age = true, cap = false, maxId = null))) }
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PruneWarningBannerHost(onBackup = {}, store = store, now = { t0 })
            }
        }
        rule.waitForIdle()
        assertNull("not resumed: grace not started", store.state.value.age)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(t0, store.state.value.age!!.at)
    }
}
