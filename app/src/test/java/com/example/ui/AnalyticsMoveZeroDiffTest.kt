package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-8.14 (PX-110) move guard, updated by WP-8.20. The analytics code now differs from the moved
 * copy on purpose (8.20 ports Opti's visuals), so the verbatim-signature check and the two
 * native quick-view behaviour tests (`save, export, import and delete behave as before the move`,
 * `createCustomView keeps the raw chartType`) are retired: the quick-view API (`"$metric|$range"`
 * chartType) has no Opti counterpart and is gone. What stays: one call site, and no analytics
 * state back in MainViewModel.
 */
class AnalyticsMoveZeroDiffTest {

    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/com/example/ui/$rel"), File("app/src/main/java/com/example/ui/$rel")).first { it.isFile }
        return f.readText()
    }

    @Test
    fun `DashboardScreen has one analytics call site and MainViewModel no analytics state`() {
        val dash = src("DashboardScreen.kt")
        assertFalse(dash.contains("fun AnalyticsTabContent("))
        assertEquals(1, Regex("""AnalyticsScreen\(analyticsViewModel\b""").findAll(dash).count())
        assertTrue(src("analytics/AnalyticsScreen.kt").contains("fun AnalyticsScreen(vm: AnalyticsViewModel, onModalChange"))
        val main = src("MainViewModel.kt")
        for (gone in listOf("fun createCustomView", "fun deleteCustomView", "fun exportCustomViewsToJson", "fun importCustomViewsFromJson", "val allCustomViews", "analyticsRange")) {
            assertFalse("MainViewModel still has $gone", main.contains(gone))
        }
    }
}
