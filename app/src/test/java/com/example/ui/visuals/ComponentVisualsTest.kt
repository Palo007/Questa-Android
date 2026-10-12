package com.example.ui.visuals

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.CharacterEntity
import com.example.ui.RyaItem
import com.example.ui.RyaSheet
import com.example.ui.RyaSheetState
import com.example.ui.RyaText
import com.example.ui.freshMainDispatcher
import com.example.ui.settings.AvatarImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WP-8.18: header / bars (PX-102), rep sheet, death overlay (PX-107) and RYA (PX-108). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentVisualsTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    @get:Rule
    val tmp = TemporaryFolder()

    private val hero = CharacterEntity(name = "Pali", face = "🧙", lvl = 4, cls = "Mage", hp = 12.2, maxHp = 50.0, xp = 30, gold = 41.9, updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    @Test fun `header shows renderStats values and 14 dp bars`() {
        var gear = 0
        rule.setContent { Box(Modifier.width(412.dp)) { OptiHeader(hero, onGear = { gear++ }, avatar = null, animate = false) } }
        rule.onNodeWithTag("hp_bar").assertHeightIsEqualTo(14.dp)
        rule.onNodeWithTag("xp_bar").assertHeightIsEqualTo(14.dp)
        rule.onNodeWithTag("avatar").assertWidthIsEqualTo(46.dp).assertHeightIsEqualTo(46.dp)
        rule.onNodeWithTag("avatar_face").assertTextEquals("🧙")
        rule.onNodeWithTag("stat_gold").assertTextEquals("41")
        rule.onNodeWithTag("char_level").assertTextEquals("Level 4 · Mage")
        rule.onAllNodesWithTag("bar_label")[0].assertTextEquals("13 / 50 HP")
        rule.onNodeWithTag("edit_character_button").performClick()
        assertEquals(1, gear)
    }

    @Config(qualifiers = "w915dp-h600dp")
    @Test fun `app width caps and centres the header and list at a wide viewport`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                AppWidthColumn(480.dp, Modifier.testTag("app_col")) { Box(Modifier.fillMaxWidth().height(10.dp).testTag("inner")) }
            }
        }
        rule.onNodeWithTag("app_col").assertWidthIsEqualTo(480.dp)
        rule.onNodeWithTag("inner").assertWidthIsEqualTo(480.dp).assertLeftPositionInRootIsEqualTo(217.5.dp)
    }

    @Config(qualifiers = "w915dp-h600dp")
    @Test fun `nav bar caps at the same width`() {
        rule.setContent { OptiNavBar(listOf("Habits", "Dailies"), 0, 480.dp) {} }
        rule.onNodeWithTag("nav_Habits").assertWidthIsEqualTo(240.dp).assertLeftPositionInRootIsEqualTo(217.5.dp)
    }

    @Test fun `header has Opti's refresh before the gear, and it calls onRefresh`() {
        var refresh = 0
        rule.setContent { Box(Modifier.width(300.dp)) { OptiHeader(hero, onGear = {}, onRefresh = { refresh++ }, avatar = null, animate = false) } }
        val r = rule.onNodeWithTag("refresh_button").assertTextEquals("↻").getUnclippedBoundsInRoot()
        val g = rule.onNodeWithTag("edit_character_button").getUnclippedBoundsInRoot()
        assertTrue(r.right <= g.left)
        rule.onNodeWithTag("refresh_button").performClick()
        assertEquals(1, refresh)
    }

    @Test fun `paused shows Zzz on the avatar and the Paused badge`() {
        rule.setContent { OptiHeader(hero, onGear = {}, paused = true, avatar = null, animate = false) }
        rule.onNodeWithTag("avatar_zzz").assertTextEquals(" Zzz")
        rule.onNodeWithTag("pause_badge").assertTextEquals("⏸️ Paused")
    }

    @Test fun `not paused shows no pause marks`() {
        rule.setContent { OptiHeader(hero, onGear = {}, paused = false, avatar = null, animate = false) }
        rule.onAllNodesWithTag("avatar_zzz").assertCountEquals(0)
        rule.onAllNodesWithTag("pause_badge").assertCountEquals(0)
    }

    @Test fun `gear reports the stale state`() {
        rule.setContent { OptiHeader(hero, onGear = {}, gearStale = true, avatar = null, animate = false) }
        rule.onNodeWithTag("edit_character_button")
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, GEAR_STALE_STATE))
    }

    @Test fun `hp fill width is the hp fraction`() {
        rule.setContent { Box(Modifier.width(300.dp)) { OptiHeader(hero.copy(hp = 25.0), onGear = {}, avatar = null, animate = false) } }
        // The fill is inside the bar's 1 dp border box: half of the bar's width.
        rule.onAllNodesWithTag("bar_fill")[0].assertWidthIsEqualTo(138.dp)
    }

    @Test fun `avatar image replaces the face`() {
        val f = tmp.newFile("face_img")
        f.outputStream().use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.setContent { OptiHeader(hero, onGear = {}, avatar = AvatarImage(f, 1L), animate = false) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("avatar_image").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("avatar_face").assertCountEquals(0)
    }

    @Test fun `rep sheet - confirm disabled at 0, quick and adjust, signed commit`() {
        var committed: Int? = null
        rule.setContent { RepSheet("Push-ups", positive = true, onClose = {}, onCommit = { committed = it }) }
        rule.onNodeWithTag("rep_title").assertTextEquals("Push-ups")
        rule.onNodeWithTag("confirm_bulk_reps_button").performClick()
        assertEquals(null, committed)
        rule.onNodeWithTag("rep_quick_+5").performClick()
        rule.onNodeWithTag("rep_side_+").performClick()
        rule.onNodeWithTag("bulk_reps_input").assertTextEquals("6")
        rule.onNodeWithTag("confirm_bulk_reps_button").performClick()
        assertEquals(6, committed)
    }

    @Test fun `minus rep sheet commits a negative count from typed input`() {
        var committed: Int? = null
        rule.setContent { RepSheet("Snacks", positive = false, onClose = {}, onCommit = { committed = it }) }
        rule.onNodeWithText("−10").assertIsDisplayed()
        rule.onNodeWithTag("bulk_reps_input").performTextReplacement("-4")
        rule.onNodeWithTag("confirm_bulk_reps_button").performClick()
        assertEquals(-4, committed)
    }

    @Test fun `death overlay uses Opti's copy and Continue`() {
        var cont = 0
        rule.setContent { DeathOverlay(onContinue = { cont++ }) }
        rule.onNodeWithTag("death_title").assertTextEquals(DeathText.TITLE)
        rule.onNodeWithText(DeathText.BODY).assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, cont)
    }

    @Test fun `rya sheet rows toggle their note`() {
        val toggled = mutableListOf<String>()
        val s = RyaSheetState(items = listOf(RyaItem("d1", "Read")), ticked = setOf("d1"), armDay = 0)
        rule.setContent { RyaSheet(s, onToggle = { toggled += it }, onStartDay = {}) }
        rule.onNodeWithText(RyaText.HEAD).assertIsDisplayed()
        rule.onNodeWithText(RyaText.note(true)).assertIsDisplayed()
        rule.onNodeWithTag("rya_item_d1").performClick()
        assertEquals(listOf("d1"), toggled)
    }
}
