package com.example.ui.visuals

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.CharacterEntity
import com.example.ui.freshMainDispatcher
import com.example.ui.settings.SyncBadgeMode
import com.example.ui.settings.SyncBadgeState
import com.example.ui.settings.SyncBadgeTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CR-KT-018: the header sync badge (Opti #syncBadge, index.html:70-76, 1103; app.js:7473-7495).
 * Dot modes show only the 9 dp dot, STALE only the label, HIDDEN nothing; a tap calls back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncBadgeHeaderTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val hero = CharacterEntity(name = "Pali", face = "🧙", lvl = 4, cls = "Mage", hp = 40.0, maxHp = 50.0, xp = 30, gold = 41.9, updatedAt = 1L)

    private val unsynced = SyncBadgeState(SyncBadgeMode.UNSYNCED, 1L)
    private val error = SyncBadgeState(SyncBadgeMode.ERROR, 1L)
    private val stale = SyncBadgeState(SyncBadgeMode.STALE, 1L, "Last synced 2 hours ago")

    private fun header(state: SyncBadgeState, onClick: () -> Unit = {}) = rule.setContent {
        Box(Modifier.width(412.dp)) {
            OptiHeader(hero, onGear = {}, avatar = null, animate = false, syncBadge = state, onSyncBadgeClick = onClick)
        }
    }

    @Test fun hidden_rendersNothing() {
        header(SyncBadgeState.HIDDEN)
        rule.onAllNodesWithTag("sync_badge", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("sync_badge_dot", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("sync_badge_label", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun defaultParam_isHidden() {
        rule.setContent { OptiHeader(hero, onGear = {}, avatar = null, animate = false) }
        rule.onAllNodesWithTag("sync_badge", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun unsynced_dotOnly_noVisibleLabel_orangeTooltip() {
        header(unsynced)
        rule.onNodeWithTag("sync_badge").assert(hasContentDescription(SyncBadgeTexts.UNSYNCED))
        rule.onNodeWithTag("sync_badge_dot", useUnmergedTree = true).assertWidthIsEqualTo(9.dp).assertHeightIsEqualTo(9.dp)
        rule.onAllNodesWithTag("sync_badge_label", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun error_dotOnly_noVisibleLabel_redTooltip() {
        header(error)
        rule.onNodeWithTag("sync_badge").assert(hasContentDescription(SyncBadgeTexts.ERROR))
        rule.onNodeWithTag("sync_badge_dot", useUnmergedTree = true).assertWidthIsEqualTo(9.dp)
        rule.onAllNodesWithTag("sync_badge_label", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun stale_labelOnly_noDot() {
        header(stale)
        rule.onNodeWithTag("sync_badge").assert(hasContentDescription("Last synced 2 hours ago"))
        rule.onNodeWithTag("sync_badge_label", useUnmergedTree = true).assertTextEquals("Last synced 2 hours ago")
        rule.onAllNodesWithTag("sync_badge_dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun dotColors_areThePwaHex() {
        assertEquals(0xFFF7A24E.toInt(), SyncDotUnsynced.toArgb())
        assertEquals(0xFFF74E52.toInt(), SyncDotError.toArgb())
    }

    @Test fun badge_sitsBeforeRefresh_T10a() {
        header(unsynced)
        val b = rule.onNodeWithTag("sync_badge").getUnclippedBoundsInRoot()
        val r = rule.onNodeWithTag("refresh_button").getUnclippedBoundsInRoot()
        assertTrue(b.right <= r.left)
    }

    @Test fun tap_callsOnSyncBadgeClick() {
        var taps = 0
        header(stale) { taps++ }
        rule.onNodeWithTag("sync_badge").performClick()
        assertEquals(1, taps)
    }
}
