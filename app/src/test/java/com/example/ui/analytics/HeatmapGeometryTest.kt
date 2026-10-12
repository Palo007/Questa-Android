package com.example.ui.analytics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * WP-8.20 (PX-110): Opti `anHeatmapHTML` / `heatColor` / `.anHeatCell` geometry. Columns start on
 * the Monday on or before the window start (Mon on top, Sun at the bottom), one cell per LOCAL
 * day to the day of `to`, 11dp cells with 3dp gaps (index.html:660-665).
 */
class HeatmapGeometryTest {
    private val zone = ZoneId.of("Europe/Bratislava")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `cell and gap sizes are Opti's css`() {
        assertEquals(11.dp, HEAT_CELL)
        assertEquals(3.dp, HEAT_GAP)
    }

    @Test
    fun `columns start on Monday and end on the local day of to`() {
        val from = ms(2026, 9, 3)  // a Thursday
        val to = ms(2026, 9, 20)   // a Sunday
        val cells = AnalyticsCalc.heatmapCells(from, to, emptyMap(), 1.0, zone)
        val first = LocalDate.parse(cells.first().tip.lines()[0].removePrefix("📅 "))
        assertEquals(LocalDate.of(2026, 8, 31), first)
        assertEquals(DayOfWeek.MONDAY, first.dayOfWeek)
        assertEquals("📅 2026-09-20", cells.last().tip.lines()[0])
        val cols = heatColumns(cells)
        assertEquals(3, cols.size)
        assertTrue(cols.all { it.size == 7 })
        assertEquals("💤 no activity", cells[0].tip.lines()[1])
    }

    @Test
    fun `a DST change keeps one cell per local day`() {
        // 2026-03-29 is a 23-hour day in Bratislava; 2026-10-25 a 25-hour day.
        for ((a, b) in listOf(ms(2026, 3, 23) to ms(2026, 4, 5), ms(2026, 10, 19) to ms(2026, 11, 1))) {
            val cells = AnalyticsCalc.heatmapCells(a, b, emptyMap(), 1.0, zone)
            assertEquals(14, cells.size)
            val days = cells.map { it.tip.lines()[0] }
            assertEquals(days.distinct(), days)
            assertTrue(cells.all { AnalyticsCalc.localDayKey(it.dayMs, zone) == it.dayMs })
        }
    }

    @Test
    fun `a partial last week makes a short last column`() {
        val cells = AnalyticsCalc.heatmapCells(ms(2026, 9, 14), ms(2026, 9, 16), emptyMap(), 1.0, zone)
        assertEquals(listOf(3), heatColumns(cells).map { it.size })
    }

    @Test
    fun `heatColor uses the five stops, panel2 for zero and a net-negative day`() {
        assertEquals("var(--panel2)", AnalyticsCalc.heatColor(0.0, 9.0))
        assertEquals("#6f4ddb", AnalyticsCalc.heatColor(1.0, 9.0))
        assertEquals("#8a5cff", AnalyticsCalc.heatColor(3.0, 9.0))
        assertEquals("#a98bff", AnalyticsCalc.heatColor(5.0, 9.0))
        assertEquals("#bda8ff", AnalyticsCalc.heatColor(9.0, 9.0))
        assertEquals("#bda8ff", AnalyticsCalc.heatColor(50.0, 9.0))
        assertEquals("var(--panel2)", AnalyticsCalc.heatColor(-20.0, 9.0))
        assertEquals(listOf("var(--panel2)", "#6f4ddb", "#8a5cff", "#a98bff", "#bda8ff"), AnalyticsCalc.HEAT_LEGEND)
    }

    @Test
    fun `the tip names the activity count`() {
        val d = AnalyticsCalc.localDayKey(ms(2026, 9, 16), zone)
        val cells = AnalyticsCalc.heatmapCells(ms(2026, 9, 14), ms(2026, 9, 16), mapOf(d to 4.0), 4.0, zone)
        assertEquals("📅 2026-09-16\n🔥 4 activity", cells.last().tip)
        assertEquals("#bda8ff", cells.last().color)
    }
}
