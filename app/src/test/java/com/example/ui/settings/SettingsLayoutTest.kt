package com.example.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * WP-8.14-R1: the Settings sheet on a phone (Xiaomi 2511FPC34G ≈ 412 dp at 3.08x) at 360 / 392 /
 * 430 dp and font scale 1.0 / 1.3. Real text layout (NATIVE graphics): no label breaks inside a
 * word, the buttons of one row are equally tall, and the Sync block has Opti's structure.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w480dp-h2400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutTest(private val widthDp: Int, private val fontScale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp x{1}")
        fun params() = listOf(arrayOf<Any>(392, 1.0f), arrayOf<Any>(392, 1.3f), arrayOf<Any>(360, 1.3f), arrayOf<Any>(430, 1.0f), arrayOf<Any>(360, 1.0f))
    }

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val connected = SettingsUiState(appKey = "k", 
        connected = true, lastSyncAt = 1_000L, nowMs = 61_000L, deviceId = "dev-38ab12cd", deviceName = "",
        lastSyncError = "boom", appVersion = "v1.0",
    )

    private fun show(state: SettingsUiState) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                // SettingsHost: the sheet inside a 12 dp padded dialog at the screen width.
                Box(Modifier.width(widthDp.dp).padding(12.dp)) { SettingsScreen(state, object : SettingsActions {}) }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Every laid-out text: each line (but the last) ends at a space or a hyphen, never inside a word. */
    private fun assertNoMidWordBreaks() {
        val nodes = rule.onAllNodesWithTextLayout().fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        val bad = mutableListOf<String>()
        var checked = 0
        for (n in nodes) {
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            for (r in results) {
                val t = r.layoutInput.text.text
                checked++
                for (i in 0 until r.lineCount - 1) {
                    val e = r.getLineEnd(i)
                    if (e <= 0 || e >= t.length) continue
                    val ok = t[e - 1].isWhitespace() || t[e].isWhitespace() || t[e - 1] == '-'
                    if (!ok) bad += "'$t' broken at $e (line ${i + 1}/${r.lineCount})"
                }
            }
        }
        assertTrue("checked no text", checked > 0)
        assertEquals("mid-word breaks at ${widthDp}dp x$fontScale", emptyList<String>(), bad)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextLayout() =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)

    private fun assertSameHeight(vararg tags: String) {
        val hs = tags.map { bounds(it).height }
        assertTrue("heights of ${tags.toList()} differ: $hs", hs.all { abs(it - hs[0]) < 1f })
    }

    private fun assertSameTop(vararg tags: String) {
        val ts = tags.map { bounds(it).top }
        assertTrue("tops of ${tags.toList()} differ: $ts", ts.all { abs(it - ts[0]) < 1f })
    }

    @Test
    fun connected_noMidWordBreaks() {
        show(connected)
        assertNoMidWordBreaks()
    }

    @Test
    fun notConnected_noMidWordBreaks_andEqualConnectButtons() {
        show(SettingsUiState(appKey = "k", appVersion = "v1.0", reconnectRequired = true))
        assertNoMidWordBreaks()
        assertSameHeight("connect_dropbox", "connect_force_push")
        assertSameTop("connect_dropbox", "connect_force_push")
    }

    @Test
    fun syncRow_isOptiStructure_withEqualHeights() {
        show(connected)
        // Row: Sync now | device name field | Disconnect, one line, one height.
        assertSameTop("sync_now_button", "set_device_name", "disconnect_button")
        assertSameHeight("sync_now_button", "set_device_name", "disconnect_button")
        val sync = bounds("sync_now_button"); val field = bounds("set_device_name"); val disc = bounds("disconnect_button")
        assertTrue(sync.right <= field.left && field.right <= disc.left)
        // Header: the status and "Device name" above the row; "Device dev-38" centred over Disconnect.
        val status = bounds("last_sync_row"); val nameLbl = bounds("dev_name_label"); val devLbl = bounds("dev_short_label")
        assertTrue("status above the row", status.bottom <= sync.top)
        assertTrue("status on the left", status.left <= sync.left + 1f)
        assertTrue("'Device name' above the row", nameLbl.bottom <= field.top)
        val block = bounds("dev_name_block")
        assertTrue("'Device name' centred", abs(nameLbl.center.x - block.center.x) < 2f)
        assertTrue("'Device …' just above Disconnect", devLbl.bottom <= disc.top && disc.top - devLbl.bottom < 12f * rule.density.density)
        assertTrue("'Device …' centred over Disconnect", abs(devLbl.center.x - disc.center.x) < 2f)
        assertTrue("the header labels do not overlap", status.right <= nameLbl.left && nameLbl.right <= devLbl.left)
    }

    @Test
    fun forceRow_twoPerLine_thenEventsFullWidth() {
        show(connected)
        assertSameTop("force_push", "force_pull")
        assertSameHeight("force_push", "force_pull")
        val row = bounds("force_row"); val ev = bounds("force_push_events")
        assertTrue("Force Push Events wraps to its own line", ev.top > bounds("force_push").bottom)
        assertTrue("… full width", abs(ev.width - row.width) < 1f)
        assertSameHeight("export_backup_button", "import_backup_button")
        assertSameTop("export_backup_button", "import_backup_button")
    }

    @Test
    fun catButtons_wrapLikeOpti_andAvatarRowFits() {
        show(connected)
        val a = bounds("cat_appearance"); val i = bounds("cat_interaction"); val f = bounds("cat_activityFeed")
        // flex: 1 1 30%, min-width 140px: never three on a line at phone width.
        assertTrue(f.top >= a.bottom)
        if (abs(a.top - i.top) < 1f) assertSameHeight("cat_appearance", "cat_interaction")
        // Browse image keeps its label on one line, as tall as the emoji field (Opti: both 38px).
        assertSameHeight("set_face", "avatar_browse")
        assertSameTop("set_face", "avatar_browse", "set_name")
    }
}

/** The pure flex solver. */
class FlexWrapTest {
    @Test fun equalShares_whenAllFit() {
        val l = FlexWrap.lines(300, listOf(50, 50, 50), 0, 10, wrap = true)
        assertEquals(1, l.size)
        assertEquals(listOf(94, 93, 93), l[0].map { it.width })
    }

    @Test fun basisHalf_wrapsTheThirdItemToAFullLine() {
        val l = FlexWrap.lines(300, listOf(40, 40, 60), 146, 8, wrap = true)
        assertEquals(listOf(2, 1), l.map { it.size })
        assertEquals(300, l[1][0].width)
        assertEquals(300, l[0].sumOf { it.width } + 8)
    }

    @Test fun noWrap_shrinksAboveMinOnly() {
        // basis 100 each, W = 300, gap 8 -> 16 px too wide; the middle (min 20) gives it all.
        val l = FlexWrap.lines(300, listOf(100, 20, 100), 100, 8, wrap = false).single()
        assertEquals(listOf(100, 84, 100), l.map { it.width })
    }

    @Test fun anItemWiderThanItsBasisKeepsItsMin() {
        val l = FlexWrap.lines(300, listOf(120, 10, 10), 94, 8, wrap = false).single()
        assertTrue(l[0].width >= 120)
        assertEquals(300, l.sumOf { it.width } + 16)
    }
}
