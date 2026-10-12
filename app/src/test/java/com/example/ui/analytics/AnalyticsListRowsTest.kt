package com.example.ui.analytics

import com.example.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * KT-87: Opti `renderView` "list" chart on a day bucket shows rows newest first
 * (`series.slice().reverse()`). Plain JVM test (no Robolectric) over the public
 * [AnalyticsCalc.renderView].
 *
 * Before the fix the Kotlin code called the Java 21 `List` member; on a JDK 17 test JVM this
 * test throws NoSuchMethodError (on an API 24-34 phone the Analytics list view crashes). On
 * JDK 21+ it passes either way, so [com.example.toolchain.JdkMemberCallGuardTest] is the lasting guard.
 */
class AnalyticsListRowsTest {
    private val zone = ZoneId.of("Europe/Bratislava")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun task(id: String, created: Long) =
        TaskEntity(id = id, type = "todo", title = id, createdAt = created, updatedAt = created)

    private val data = AnData(
        tasks = listOf(
            task("a", ms(2026, 9, 3)),
            task("b", ms(2026, 9, 5)),
            task("c", ms(2026, 9, 5, 18)),
            task("d", ms(2026, 9, 10)),
        ),
    )
    private val from = ms(2026, 9, 1, 0)
    private val to = ms(2026, 9, 20, 23)
    private val now = to

    private fun view(chart: String) = AnView(id = "v1", name = "Created", source = "created", group = "day", chart = chart)

    @Test
    fun listChart_dayBucket_rowsNewestFirst() {
        val body = AnalyticsCalc.renderView(view("list"), data, AnPrefs(), from, to, now, zone, Locale.US)
        assertTrue("list chart on a day bucket is ListRows, got $body", body is ViewBody.ListRows)
        val rows = (body as ViewBody.ListRows).rows
        assertEquals(listOf("2026-09-10", "2026-09-05", "2026-09-03"), rows.map { it.label })
        assertEquals(listOf(1.0, 2.0, 1.0), rows.map { it.v })
    }

    @Test
    fun barChart_dayBucket_keepsOldestFirst_withEmptyDaysAsZero() {
        val body = AnalyticsCalc.renderView(view("bar"), data, AnPrefs(), from, to, now, zone, Locale.US)
        assertTrue("bar chart on a day bucket is Bars, got $body", body is ViewBody.Bars)
        val rows = (body as ViewBody.Bars).rows
        assertEquals((1..20).map { "2026-09-%02d".format(it) }, rows.map { it.label })
        assertEquals(listOf(1.0, 2.0, 1.0), rows.filter { it.v > 0 }.map { it.v })
        assertEquals(0.0, rows.first { it.label == "2026-09-04" }.v, 0.0)
    }

    @Test
    fun lineChart_dayBucket_fillsEveryDayOfWindow() {
        val body = AnalyticsCalc.renderView(view("line"), data, AnPrefs(), from, to, now, zone, Locale.US) as ViewBody.Line
        val s = body.chart.series
        assertEquals(20, s.size)
        assertEquals(20, body.chart.tips!!.size)
        assertEquals(4.0, s.sumOf { it.v }, 0.0)
    }

    @Test
    fun barChart_weekBucket_fillsEmptyWeeks() {
        val v = AnView(id = "v2", name = "Created", source = "created", group = "week", chart = "bar")
        val rows = (AnalyticsCalc.renderView(v, data, AnPrefs(), from, to, now, zone, Locale.US) as ViewBody.Bars).rows
        // Mondays covering 2026-09-01 .. 2026-09-20: Aug 31, Sep 7, Sep 14.
        assertEquals(listOf("2026-08-31", "2026-09-07", "2026-09-14"), rows.map { it.label })
        assertEquals(listOf(3.0, 1.0, 0.0), rows.map { it.v })
    }

    @Test
    fun fill_emptyInputStaysEmpty_soChartSaysNoData() {
        assertEquals(emptyList<DayVal>(), AnalyticsCalc.fillDays(emptyList(), from, to, zone))
        assertEquals(emptyList<LabelVal>(), AnalyticsCalc.fillPeriods(emptyList(), from, to, "month", zone))
    }

    @Test
    fun fillPeriods_monthKeysSpanWindow() {
        val r = AnalyticsCalc.fillPeriods(listOf(LabelVal("2026-09", 5.0)), ms(2026, 7, 15), ms(2026, 10, 2), "month", zone)
        assertEquals(listOf("2026-07", "2026-08", "2026-09", "2026-10"), r.map { it.label })
        assertEquals(listOf(0.0, 0.0, 5.0, 0.0), r.map { it.v })
    }

    @Test
    fun fillCompare_daysCoverWindow() {
        val c = CompareSeries(listOf(ms(2026, 9, 5, 0)), emptyList(), emptyMap())
        assertEquals(20, AnalyticsCalc.fillCompare(c, from, to, zone).days.size)
    }
}
