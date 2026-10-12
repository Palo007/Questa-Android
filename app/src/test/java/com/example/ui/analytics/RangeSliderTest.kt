package com.example.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WP-8.20 (PX-110): the window slider and snap chips (Opti initAnalytics, app.js:3848-3925, and
 * anWindow). Offsets are whole days back from today; handles never cross; a drag clears the snap.
 */
class RangeSliderTest {
    private val day = AnalyticsCalc.DAY
    private val now = 1_790_000_000_000L
    private val g = AnalyticsCalc.SliderGeom(mn = now - 100 * day, mx = now, now = now)

    @Test
    fun `snap chips are Opti's 7d 30d 90d 180d 1y All`() {
        assertEquals(listOf("7d" to "7d", "30d" to "30d", "90d" to "90d", "180d" to "180d", "1y" to "365d", "All" to "all"), AnalyticsCalc.SNAP_CHIPS)
        assertEquals(AnPrefs(fromOff = 7, toOff = 0, snap = "7d"), AnalyticsCalc.snapTo(AnPrefs(fromOff = 50, toOff = 9, snap = null), g, "7d"))
        assertEquals(AnPrefs(fromOff = 365, toOff = 0, snap = "365d"), AnalyticsCalc.snapTo(AnPrefs(), g, "365d"))
        assertEquals(AnPrefs(fromOff = 100, toOff = 0, snap = "all"), AnalyticsCalc.snapTo(AnPrefs(), g, "all"))
    }

    @Test
    fun `offToFrac and fracToOff are inverse on whole days`() {
        assertEquals(100, g.totalDays)
        assertEquals(1.0, g.offToFrac(0), 1e-12)
        assertEquals(0.0, g.offToFrac(100), 1e-12)
        assertEquals(0.0, g.offToFrac(500), 1e-12) // clamped
        for (off in 0..100) assertEquals(off, g.fracToOff(g.offToFrac(off)))
    }

    @Test
    fun `normRange clamps to the span and swaps a reversed pair`() {
        assertEquals(AnPrefs(fromOff = 100, toOff = 0), AnalyticsCalc.normRange(AnPrefs(fromOff = 365, toOff = 0), g))
        assertEquals(AnPrefs(fromOff = 40, toOff = 10), AnalyticsCalc.normRange(AnPrefs(fromOff = 10, toOff = 40), g))
        assertEquals(AnPrefs(fromOff = 100, toOff = 0), AnalyticsCalc.normRange(AnPrefs(fromOff = null, toOff = null), g))
    }

    @Test
    fun `dragging the left handle stops at the right one and clears the snap`() {
        val p = AnPrefs(fromOff = 60, toOff = 20, snap = "90d")
        val moved = AnalyticsCalc.dragTo(p, g, 0, g.offToFrac(80))
        assertEquals(80, moved.fromOff); assertEquals(20, moved.toOff); assertNull(moved.snap)
        assertEquals(20, AnalyticsCalc.dragTo(p, g, 0, g.offToFrac(5)).fromOff)   // cannot pass the right handle
        assertEquals(100, AnalyticsCalc.dragTo(p, g, 0, -3.0).fromOff)            // clamped at the span start
    }

    @Test
    fun `dragging the right handle stops at the left one - a fromOff of 0 is not read as full history`() {
        val p = AnPrefs(fromOff = 60, toOff = 20)
        assertEquals(60, AnalyticsCalc.dragTo(p, g, 1, g.offToFrac(70)).toOff)
        assertEquals(0, AnalyticsCalc.dragTo(p, g, 1, 2.0).toOff)
        // Opti round 2 (2026-09-18): null-aware, so fromOff 0 keeps the right handle at today.
        assertEquals(0, AnalyticsCalc.dragTo(AnPrefs(fromOff = 0, toOff = 0), g, 1, g.offToFrac(30)).toOff)
    }

    @Test
    fun `anWindow floors at the first event, clamps to today, and reads fromOff 0 as 90 days`() {
        val mn = now - 300 * day
        assertEquals((now - 30 * day) to (now - 2 * day), AnalyticsCalc.window(AnPrefs(fromOff = 30, toOff = 2, snap = null), mn, now))
        assertEquals(mn to now, AnalyticsCalc.window(AnPrefs(fromOff = 900, toOff = 0, snap = null), mn, now))
        assertEquals((now - 900 * day) to now, AnalyticsCalc.window(AnPrefs(fromOff = 900, toOff = 0, snap = null), mn, now, noFloor = true))
        assertEquals(mn to now, AnalyticsCalc.window(AnPrefs(snap = "all"), mn, now))
        assertEquals(0L to now, AnalyticsCalc.window(AnPrefs(snap = "all"), mn, now, noFloor = true))
        // [DEFERRED_OPEN_QUESTION] Opti `p.fromOff||90`: a window starting today reads as 90 days.
        assertEquals((now - 90 * day) to now, AnalyticsCalc.window(AnPrefs(fromOff = 0, toOff = 0, snap = null), mn, now))
        // to before from collapses to one instant
        assertEquals((now - 10 * day) to (now - 10 * day), AnalyticsCalc.window(AnPrefs(fromOff = 5, toOff = 10, snap = null), mn, now))
    }

    @Test
    fun `the range label is yyyy-MM-dd arrow yyyy-MM-dd`() {
        val z = java.time.ZoneId.of("Europe/Bratislava")
        val from = java.time.LocalDate.of(2026, 6, 22).atStartOfDay(z).toInstant().toEpochMilli() + 5
        assertEquals("2026-06-22", AnalyticsCalc.fmtDate(from, z))
    }
}
