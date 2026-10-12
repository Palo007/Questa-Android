package com.example.ui.analytics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.QuestaApplication
import com.example.data.RpgRepository
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.SerializationEngine
import com.example.ui.canonicalTagId
import com.example.ui.tagIdIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// =================================================================================================
// WP-8.20 (PX-110): Opti's analytics (app.js:3430-4485, 5069-5088 at 7d5d4f3), ported 1:1.
// Every function below names the Opti function it mirrors. Values are JS numbers (Double);
// day buckets are LOCAL midnights (Opti localDayKey / nextLocalDay), so a DST day stays one day.
// =================================================================================================

/** One Opti `anAllEvents()` entry (app.js:3520-3541). */
data class AnEvent(
    val taskId: String,
    val title: String,
    val type: String,
    val repsPerTap: Int,
    val date: Long,
    val value: Double?,
    val scoredUp: Int,
    val scoredDown: Int,
    val reps: Double,
    val repCounted: Boolean,
    val scored: Boolean,
    val completed: Boolean?,
)

data class AnMetricHabit(val id: String, val reps: Double?)

/** One `an.metrics[]` object. [raw] is the wire object, kept so edits keep every other key. */
data class AnMetric(
    val id: String,
    val name: String,
    val keyword: String,
    val exact: Boolean,
    val habits: List<AnMetricHabit>,
    val raw: JsonObject,
)

/** One PWA-shaped `an.views[]` object (a custom_views row with `source != null`). */
data class AnView(
    val id: String,
    val name: String,
    val source: String,
    val group: String,
    val chart: String,
    val tags: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val metricId: String? = null,
)

/**
 * The local (not synced) half of Opti's `S.prefs.an` (`fromOff`, `toOff`, `snap`,
 * `activeMetric`) plus the Kotlin-only view order and the "views were touched" flag
 * (see WP-8.20_NOTES.md, Guesses).
 */
data class AnPrefs(
    val fromOff: Int? = 90,
    val toOff: Int? = 0,
    val snap: String? = "90d",
    val activeMetric: String? = null,
    val viewOrder: List<String> = emptyList(),
    val viewsTouched: Boolean = false,
    /** Review 36: the ids of Opti's two default metrics, fixed once per install so a reference survives restarts. */
    val seedMetricIds: List<String> = emptyList(),
)

data class DayVal(val d: Long, val v: Double)
data class LabelVal(val label: String, val v: Double)

/** A list/bar row. [color] is the CSS color Opti writes (`#rrggbb` or `var(--muted)`), or null. */
data class AnRow(val label: String, val v: Double, val color: String? = null)

data class CumulativeReps(
    val total: Double,
    val taps: Int,
    val byTask: LinkedHashMap<String, Double>,
    val missingTapPts: Int,
    val activityDays: Int,
) {
    val matched: Int get() = byTask.size
}

data class Milestones(
    val total: Double,
    val taps: Int,
    val biggestDay: DayVal?,
    val biggestV: Double,
    val activityDays: Int,
    val repDays: Int,
    val firstDate: Long?,
    val matched: Int,
)

data class Adherence(val d: Long, val pct: Double, val done: Int, val tot: Int)

data class CompareSeries(val days: List<Long>, val metrics: List<AnMetric>, val per: Map<String, Map<Long, Double>>)

sealed class AnBucket {
    data class Day(val series: List<DayVal>, val dayMap: Map<Long, Double>) : AnBucket()
    data class Cat(val rows: List<AnRow>) : AnBucket()
}

/** One heatmap cell (Opti `anHeatmapHTML`, app.js:5069-5088). The list starts on a Monday. */
data class HeatCell(val dayMs: Long, val value: Double, val color: String, val tip: String)

data class LegendItem(val label: String, val color: String)

data class AnCardData(val k: String, val v: String, val sub: String, val kColor: String? = null)

data class SeriesChart(val series: List<DayVal>, val cumulative: Boolean, val color: String, val h: Int, val tips: List<String>?)

/** What Opti `renderView` (app.js:4290-4316) draws for one view. */
sealed class ViewBody {
    data class Cards(val cards: List<AnCardData>) : ViewBody()
    data class Note(val text: String) : ViewBody()
    data class TagSummary(val bars: List<LabelVal>, val rows: List<TagSummaryRow>) : ViewBody()
    data class ListRows(val rows: List<AnRow>) : ViewBody()
    data class Bars(val rows: List<AnRow>) : ViewBody()
    data class Line(val chart: SeriesChart) : ViewBody()
    data class Heatmap(val cells: List<HeatCell>) : ViewBody()
}

data class TagSummaryRow(val name: String, val color: String, val count: Int, val comp: Int, val created: Int)

/** Everything the analytics tab needs, from the local data. */
data class AnData(
    val tasks: List<TaskEntity> = emptyList(),
    val rewards: List<RewardEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val logs: List<EventLogEntity> = emptyList(),
    val views: List<AnView> = emptyList(),
    val metrics: List<AnMetric> = emptyList(),
    val events: List<AnEvent> = emptyList(),
)

/** Opti `anMatcher` result (app.js:3478-3519). */
class AnMatcher(val match: (AnEvent) -> Boolean, val reps: (AnEvent) -> Double, val keyword: String)

object AnalyticsCalc {
    const val DAY = 86_400_000L
    const val MAX_VIEWS = 20

    val METRIC_COLORS = listOf("#bda8ff", "#50b5e9", "#48b677", "#ffbe5c", "#f9a03f", "#f74e52", "#74b6e0")
    val HEAT_STOPS = listOf("#5b3a86", "#6f4ddb", "#8a5cff", "#a98bff", "#bda8ff")
    val HEAT_LEGEND = listOf("var(--panel2)", "#6f4ddb", "#8a5cff", "#a98bff", "#bda8ff")
    val V_SOURCES = listOf("Overview" to "overview", "Tag summary" to "tagsummary", "Completed" to "completed", "Created" to "created",
        "Updated" to "updated", "Incomplete" to "incomplete", "Reps" to "reps", "Reps metric" to "metric", "Streaks" to "streaks", "Tag count" to "tagcount")
    val V_SPECIAL = listOf("overview", "tagsummary")
    val V_GROUPS = listOf("Day" to "day", "Week" to "week", "Month" to "month", "Tag" to "tag", "Type" to "type")
    val V_CHARTS = listOf("List" to "list", "Bar" to "bar", "Line" to "line", "Heatmap" to "heatmap")
    val V_TYPES = listOf("Habits" to "habit", "Dailies" to "daily", "To-Dos" to "todo")
    val V_SNAPSHOT = listOf("incomplete", "streaks", "tagcount")
    val SNAP_CHIPS = listOf("7d" to "7d", "30d" to "30d", "90d" to "90d", "180d" to "180d", "1y" to "365d", "All" to "all")

    // ---- dates -----------------------------------------------------------------------------------
    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun localDayKey(ms: Long, zone: ZoneId): Long = localDate(ms, zone).atStartOfDay(zone).toInstant().toEpochMilli()
    fun nextLocalDay(ms: Long, zone: ZoneId): Long = localDate(ms, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun fmtDate(ms: Long, zone: ZoneId): String = localDate(ms, zone).toString()
    private fun monthKey(ms: Long, zone: ZoneId): String = localDate(ms, zone).let { "%04d-%02d".format(it.year, it.monthValue) }
    private fun mondayKey(ms: Long, zone: ZoneId): String = localDate(ms, zone).let { it.minusDays((it.dayOfWeek.value - 1).toLong()).toString() }
    /** JS `Date.getDay()`: 0 = Sunday .. 6 = Saturday. */
    fun jsDay(ms: Long, zone: ZoneId): Int = localDate(ms, zone).dayOfWeek.let { if (it == DayOfWeek.SUNDAY) 0 else it.value }

    // ---- number formatting --------------------------------------------------------------------------
    /** JS `String(n)` for the values analytics prints raw. */
    fun jsNum(v: Double): String =
        if (v.isNaN()) "NaN" else if (v == floor(v) && abs(v) < 1e21) v.toLong().toString() else v.toString()

    /** JS `n.toLocaleString()` (grouping, up to 3 fraction digits). */
    fun loc(v: Double, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.maximumFractionDigits = 3
        return nf.format(if (v == 0.0) 0.0 else v)
    }

    /** JS `n.toFixed(1)`. */
    fun fixed1(v: Double): String = if (v.isNaN() || v.isInfinite()) jsNum(v) else BigDecimal(v).setScale(1, RoundingMode.HALF_UP).toPlainString().let { if (it == "-0.0") "0.0" else it }

    /** JS `Math.round`. */
    fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    // ---- items ------------------------------------------------------------------------------------
    /** Opti `repsPerTap(title)`: the first digit run anywhere in the title, else 1. */
    fun repsPerTap(title: String?): Int = Regex("\\d+").find(title ?: "")?.value?.toIntOrNull() ?: 1

    /** Opti `createdMs` (app.js:3323). */
    fun createdMs(t: TaskEntity): Long =
        t.createdAt.takeIf { it != 0L } ?: t.history.firstOrNull()?.date?.takeIf { it != 0L } ?: t.completedAt?.takeIf { it != 0L } ?: 0L

    /** Opti `updatedMs` (app.js:3324). */
    fun updatedMs(t: TaskEntity): Long = t.updatedAt.takeIf { it != 0L } ?: createdMs(t)

    fun createdMs(r: RewardEntity): Long = r.createdAt.takeIf { it != 0L } ?: 0L
    fun updatedMs(r: RewardEntity): Long = r.updatedAt.takeIf { it != 0L } ?: createdMs(r)

    /** Opti `isDailyDueOn(t, new Date().getDay())`. */
    fun isDailyDueToday(t: TaskEntity, now: Long, zone: ZoneId): Boolean = isDueOn(t, jsDay(now, zone))
    private fun isDueOn(t: TaskEntity, dow: Int): Boolean = t.repeat == null || (t.repeat.getOrNull(dow) == true)

    private fun extraNumber(extra: String?, key: String): Double? {
        if (extra == null || !extra.contains("\"$key\"")) return null
        val o = runCatching { Json.parseToJsonElement(extra) as? JsonObject }.getOrNull() ?: return null
        if (!o.containsKey(key)) return null
        return (o[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    }

    private fun extraHasKey(extra: String?, key: String): Boolean {
        if (extra == null || !extra.contains("\"$key\"")) return false
        val o = runCatching { Json.parseToJsonElement(extra) as? JsonObject }.getOrNull() ?: return false
        return o.containsKey(key)
    }

    /** Opti `anAllEvents()` (app.js:3520-3541). */
    fun allEvents(tasks: List<TaskEntity>): List<AnEvent> {
        val out = ArrayList<AnEvent>()
        for (t in tasks) {
            val rpt = t.repsPerTap?.takeIf { it != 0 } ?: repsPerTap(t.title)
            for (p in t.history) {
                if (p.date == 0L && p.extraJson != null && !extraHasKey(p.extraJson, "date")) continue
                val su = p.scoredUp ?: 0
                val reps = when {
                    p.reps != null -> p.reps.toDouble()
                    else -> extraNumber(p.extraJson, "reps") ?: (rpt * su).toDouble()
                }
                val repCounted = p.repCounted ?: if (extraHasKey(p.extraJson, "repCounted")) false else su > 0
                val scored = p.scored ?: (su > 0 || p.completed == true)
                out += AnEvent(t.id, t.title, t.type, rpt, p.date, p.value, su, p.scoredDown ?: 0, reps, repCounted, scored, p.completed)
            }
        }
        return out
    }

    // ---- metrics ------------------------------------------------------------------------------------
    fun parseMetrics(raw: String?): List<AnMetric> =
        SerializationEngine.parseJsonArray(raw).mapNotNull { (it as? JsonObject)?.let(::parseMetric) }

    fun parseMetric(o: JsonObject): AnMetric {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""
        val habits = (o["habits"] as? JsonArray)?.mapNotNull { h ->
            val ho = h as? JsonObject ?: return@mapNotNull null
            val id = (ho["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val rp = ho["reps"] as? JsonPrimitive
            val reps = when {
                rp == null || rp is JsonNull -> null
                rp.isString -> if (rp.content.isEmpty()) null else (rp.content.trim().toDoubleOrNull() ?: Double.NaN)
                else -> rp.doubleOrNull
            }
            AnMetricHabit(id, reps)
        } ?: emptyList()
        val exact = (o["exact"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.isNotEmpty() == true && it.content != "0") } ?: false
        return AnMetric(str("id"), str("name"), str("keyword"), exact, habits, o)
    }

    /** Opti `anMatcher` for a metric object (null = match nothing). */
    fun matcher(m: AnMetric?): AnMatcher {
        if (m == null) return AnMatcher({ false }, { 0.0 }, "")
        if (m.exact && m.habits.isNotEmpty()) {
            val map = LinkedHashMap<String, Double?>()
            m.habits.forEach { map[it.id] = it.reps }
            return AnMatcher(
                match = { e -> e.type == "habit" && map.containsKey(e.taskId) },
                reps = { e -> val ov = map[e.taskId]; if (ov == null || e.scoredUp == 0) e.reps else ov * e.scoredUp },
                keyword = m.keyword.lowercase(Locale.ROOT),
            )
        }
        val kw = m.keyword.trim().lowercase(Locale.ROOT)
        return AnMatcher({ e -> e.type == "habit" && e.title.lowercase(Locale.ROOT).contains(kw) }, { it.reps }, kw)
    }

    /** Opti `anActiveMetric()`. */
    fun activeMetric(metrics: List<AnMetric>, p: AnPrefs): AnMetric? =
        metrics.firstOrNull { it.id == p.activeMetric } ?: metrics.firstOrNull()

    // ---- window -------------------------------------------------------------------------------------
    /** Opti `anSpan()`. */
    fun span(events: List<AnEvent>, now: Long): Pair<Long, Long> {
        if (events.isEmpty()) return (now - 90 * DAY) to now
        var mn = Long.MAX_VALUE; var mx = Long.MIN_VALUE
        events.forEach { if (it.date < mn) mn = it.date; if (it.date > mx) mx = it.date }
        return mn to max(mx, now)
    }

    /** Opti `anWindow(noFloor)`. `fromOff` 0 reads as 90 there (`p.fromOff||90`); kept. */
    fun window(p: AnPrefs, mn: Long, now: Long, noFloor: Boolean = false): Pair<Long, Long> {
        var to = now - (p.toOff ?: 0) * DAY
        var from = now - (p.fromOff?.takeIf { it != 0 } ?: 90) * DAY
        if (p.snap == "all") {
            if (noFloor) { from = 0; to = now } else { from = mn; to = now }
        }
        if (!noFloor && from < mn) from = mn
        if (to > now) to = now
        if (from > to) from = to
        return from to to
    }

    // ---- the range slider (Opti initAnalytics, app.js:3848-3925) ---------------------------------------
    data class SliderGeom(val mn: Long, val mx: Long, val now: Long) {
        val totalDays: Int get() = max(1L, jsRound((mx - mn).toDouble() / DAY)).toInt()
        private val spanMs: Long get() = max(1L, mx - mn)
        fun offToFrac(off: Int): Double = ((now - off * DAY - mn).toDouble() / spanMs).coerceIn(0.0, 1.0)
        fun fracToOff(fr: Double): Int = jsRound((now - (mn + fr * spanMs)) / DAY).toInt()
    }

    /** Opti `normRange()`. */
    fun normRange(p: AnPrefs, g: SliderGeom): AnPrefs {
        val td = g.totalDays
        var f = (p.fromOff ?: td).coerceIn(0, td)
        var t = (p.toOff ?: 0).coerceIn(0, td)
        if (f < t) { val x = f; f = t; t = x }
        return p.copy(fromOff = f, toOff = t)
    }

    /** Opti `drag(handle, which)` onMove: the handles never cross; a manual drag clears the snap. */
    fun dragTo(p: AnPrefs, g: SliderGeom, which: Int, frac: Double): AnPrefs {
        val off = g.fracToOff(frac.coerceIn(0.0, 1.0))
        return if (which == 0) p.copy(fromOff = min(max(off, p.toOff ?: 0), g.totalDays), snap = null)
        else p.copy(toOff = max(min(off, p.fromOff ?: g.totalDays), 0), snap = null)
    }

    /** Opti snap-chip click. */
    fun snapTo(p: AnPrefs, g: SliderGeom, snap: String): AnPrefs =
        if (snap == "all") p.copy(snap = snap, fromOff = g.totalDays, toOff = 0)
        else p.copy(snap = snap, fromOff = snap.takeWhile { it.isDigit() }.toInt(), toOff = 0)

    // ---- reps -----------------------------------------------------------------------------------------
    /** Opti `anCumulativeReps`. */
    fun cumulativeReps(events: List<AnEvent>, m: AnMetric?, from: Long, to: Long): CumulativeReps {
        val mm = matcher(m)
        var total = 0.0; var taps = 0; var missing = 0; var act = 0
        val byTask = LinkedHashMap<String, Double>()
        events.filter { mm.match(it) && it.date >= from && it.date <= to }.forEach { e ->
            val r = mm.reps(e)
            total += r; taps += e.scoredUp
            if (e.scored) act++
            if (!e.repCounted && e.scored) missing++
            byTask[e.title] = (byTask[e.title] ?: 0.0) + r
        }
        return CumulativeReps(total, taps, byTask, missing, act)
    }

    /** Opti `anRepsSeries`. */
    fun repsSeries(events: List<AnEvent>, m: AnMetric?, from: Long, to: Long, zone: ZoneId): List<DayVal> {
        val mm = matcher(m)
        val b = HashMap<Long, Double>()
        events.forEach { e ->
            if (!mm.match(e) || e.date < from || e.date > to) return@forEach
            val d = localDayKey(e.date, zone)
            b[d] = (b[d] ?: 0.0) + mm.reps(e)
        }
        return b.keys.sorted().map { DayVal(it, b.getValue(it)) }
    }

    /** Opti `anActivitySeries`. */
    fun activitySeries(events: List<AnEvent>, m: AnMetric?, from: Long, to: Long, zone: ZoneId): List<Long> {
        val mm = matcher(m)
        return events.filter { mm.match(it) && it.date >= from && it.date <= to && it.scored }
            .map { localDayKey(it.date, zone) }.distinct().sorted()
    }

    /** Opti `anRollup(kw, mode)`: all-time per-year / per-month totals. */
    fun rollup(events: List<AnEvent>, m: AnMetric?, mode: String, now: Long, zone: ZoneId): List<LabelVal> {
        val mn = span(events, now).first
        val b = HashMap<String, Double>()
        repsSeries(events, m, mn, now, zone).forEach { s ->
            val k = if (mode == "year") localDate(s.d, zone).year.toString() else monthKey(s.d, zone)
            b[k] = (b[k] ?: 0.0) + s.v
        }
        return b.keys.sorted().map { LabelVal(it, b.getValue(it)) }
    }

    /** Opti `anAdherenceSeries`. */
    fun adherence(events: List<AnEvent>, from: Long, to: Long, zone: ZoneId): List<Adherence> {
        val day = HashMap<Long, IntArray>()
        events.forEach { e ->
            if (e.type != "daily" || e.completed == null) return@forEach
            if (e.date < from || e.date > to) return@forEach
            val a = day.getOrPut(localDayKey(e.date, zone)) { IntArray(2) }
            a[1]++; if (e.completed) a[0]++
        }
        return day.keys.sorted().map { d -> val a = day.getValue(d); Adherence(d, if (a[1] > 0) a[0].toDouble() / a[1] else 0.0, a[0], a[1]) }
    }

    /** Opti `anIntensity`. */
    fun intensity(events: List<AnEvent>, from: Long, to: Long, zone: ZoneId): Map<Long, Double> {
        val day = LinkedHashMap<Long, Double>()
        events.forEach { e ->
            if (e.date < from || e.date > to) return@forEach
            var w = e.scoredUp.toDouble(); if (e.completed == true) w += 1
            if (w > 0) { val d = localDayKey(e.date, zone); day[d] = (day[d] ?: 0.0) + w }
        }
        return day
    }

    /** Opti `anStreaks`. */
    fun streaks(tasks: List<TaskEntity>): List<Pair<String, Int>> =
        tasks.filter { it.type == "daily" }.map { it.title to (it.streak ?: 0) }.sortedByDescending { it.second }

    /** Opti `anMilestones` (all-time). */
    fun milestones(events: List<AnEvent>, m: AnMetric?, now: Long, zone: ZoneId): Milestones {
        val mn = span(events, now).first
        val r = cumulativeReps(events, m, mn, now)
        val series = repsSeries(events, m, mn, now, zone)
        var big: DayVal? = null; var bigV = 0.0
        series.forEach { if (it.v > bigV) { big = it; bigV = it.v } }
        val act = activitySeries(events, m, mn, now, zone)
        val repDays = series.count { it.v > 0 }
        val first = series.firstOrNull()?.d ?: act.firstOrNull()
        return Milestones(r.total, r.taps, big, bigV, act.size, repDays, first, r.matched)
    }

    /** Opti `anBreakdown` (week = Monday date, month = yyyy-MM). */
    fun breakdown(events: List<AnEvent>, m: AnMetric?, from: Long, to: Long, mode: String, zone: ZoneId): List<LabelVal> {
        val b = HashMap<String, Double>()
        repsSeries(events, m, from, to, zone).forEach { s ->
            val k = if (mode == "month") monthKey(s.d, zone) else mondayKey(s.d, zone)
            b[k] = (b[k] ?: 0.0) + s.v
        }
        return b.keys.sorted().map { LabelVal(it, b.getValue(it)) }
    }

    /** Opti `anCompareSeries`. */
    fun compareSeries(events: List<AnEvent>, metrics: List<AnMetric>, from: Long, to: Long, zone: ZoneId): CompareSeries {
        val days = sortedSetOf<Long>()
        val per = LinkedHashMap<String, Map<Long, Double>>()
        metrics.forEach { m ->
            val mp = HashMap<Long, Double>()
            repsSeries(events, m, from, to, zone).forEach { mp[it.d] = it.v; days += it.d }
            per[m.id] = mp
        }
        return CompareSeries(days.toList(), metrics, per)
    }

    // ---- gap fill (Questa, 2026-09-26): charts show empty days/weeks/months as 0, not skipped ----------
    // The calc functions above stay PWA-exact (golden tests); only the chart inputs are filled.
    // An empty input stays empty so the chart still says "No data". from <= 0 (unbounded) is not filled.

    /** Every local day key in [from, to]. */
    fun dayKeys(from: Long, to: Long, zone: ZoneId): List<Long> {
        val out = ArrayList<Long>()
        var x = localDayKey(from, zone); val end = localDayKey(to, zone)
        while (x <= end) { out += x; x = nextLocalDay(x, zone) }
        return out
    }

    /** Every week (Monday date) or month (yyyy-MM) label in [from, to], in the keys [breakdown] and [bucket] use. */
    fun periodKeys(from: Long, to: Long, mode: String, zone: ZoneId): List<String> {
        val out = ArrayList<String>()
        val end = localDate(to, zone)
        var d = localDate(from, zone).let { if (mode == "month") it.withDayOfMonth(1) else it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        while (!d.isAfter(end)) {
            out += if (mode == "month") "%04d-%02d".format(d.year, d.monthValue) else d.toString()
            d = if (mode == "month") d.plusMonths(1) else d.plusWeeks(1)
        }
        return out
    }

    fun fillDays(s: List<DayVal>, from: Long, to: Long, zone: ZoneId): List<DayVal> {
        if (s.isEmpty() || from <= 0) return s
        val m = s.associate { it.d to it.v }
        return dayKeys(from, to, zone).map { DayVal(it, m[it] ?: 0.0) }
    }

    fun fillPeriods(rows: List<LabelVal>, from: Long, to: Long, mode: String, zone: ZoneId): List<LabelVal> {
        if (rows.isEmpty() || from <= 0) return rows
        val m = rows.associate { it.label to it.v }
        return periodKeys(from, to, mode, zone).map { LabelVal(it, m[it] ?: 0.0) }
    }

    fun fillRows(rows: List<AnRow>, from: Long, to: Long, mode: String, zone: ZoneId): List<AnRow> {
        if (rows.isEmpty() || from <= 0) return rows
        val m = rows.associate { it.label to it.v }
        return periodKeys(from, to, mode, zone).map { AnRow(it, m[it] ?: 0.0) }
    }

    fun fillCompare(c: CompareSeries, from: Long, to: Long, zone: ZoneId): CompareSeries =
        if (c.days.isEmpty() || from <= 0) c else c.copy(days = dayKeys(from, to, zone))

    // ---- views ----------------------------------------------------------------------------------------
    /** Opti `anViewItems`. */
    fun viewItems(v: AnView, tasks: List<TaskEntity>, tags: List<TagEntity>): List<TaskEntity> {
        var items = tasks
        if (v.types.isNotEmpty()) items = items.filter { it.type in v.types }
        if (v.tags.isNotEmpty()) {
            // task.tags may carry names (Kotlin editor) or ids (PWA rows); v.tags and tagRows speak ids
            val idByName = tagIdIndex(tags)
            items = items.filter { t ->
                val taskIds = t.tags.map { canonicalTagId(it, idByName) }
                v.tags.any { sel -> canonicalTagId(sel, idByName) in taskIds }
            }
        }
        return items
    }

    data class SrcEvent(val ts: Long, val v: Double, val item: TaskEntity?, val title: String?)

    /** Opti `anViewMetric`. */
    fun viewMetric(v: AnView?, metrics: List<AnMetric>, p: AnPrefs): AnMetric? =
        metrics.firstOrNull { it.id == v?.metricId } ?: metrics.firstOrNull { it.id == p.activeMetric } ?: metrics.firstOrNull()

    /** Opti `anSourceEvents` (signed reps kept, 2026-09-18 round 2). */
    fun sourceEvents(v: AnView, d: AnData, p: AnPrefs): List<SrcEvent> {
        val items = viewItems(v, d.tasks, d.tags)
        val out = ArrayList<SrcEvent>()
        when (v.source) {
            "created" -> items.forEach { t -> val c = createdMs(t); if (c != 0L) out += SrcEvent(c, 1.0, t, null) }
            "updated" -> items.forEach { t -> val u = updatedMs(t); val c = createdMs(t); if (u != 0L && u != c) out += SrcEvent(u, 1.0, t, null) }
            "completed" -> items.forEach { t ->
                var any = false
                t.history.forEach { h -> if (h.completed == true) { out += SrcEvent(h.date, 1.0, t, null); any = true } }
                if (!any && t.type == "todo" && (t.completedAt ?: 0L) != 0L) out += SrcEvent(t.completedAt!!, 1.0, t, null)
            }
            "reps" -> {
                val ids = items.associateBy { it.id }
                d.events.forEach { e ->
                    val it0 = ids[e.taskId] ?: return@forEach
                    val r = e.reps
                    val value = if (r != 0.0 && !r.isNaN()) r else e.scoredUp.toDouble()
                    if (value != 0.0) out += SrcEvent(e.date, value, it0, e.title)
                }
            }
            "metric" -> {
                val m = viewMetric(v, d.metrics, p)
                if (m != null) {
                    val mm = matcher(m)
                    d.events.forEach { e ->
                        if (!mm.match(e)) return@forEach
                        val r = mm.reps(e)
                        val value = if (r != 0.0 && !r.isNaN()) r else e.scoredUp.toDouble()
                        if (value != 0.0) out += SrcEvent(e.date, value, null, e.title)
                    }
                }
            }
        }
        return out
    }

    /** Opti `anTagRows`. */
    fun tagRows(b: LinkedHashMap<String, Double>, tags: List<TagEntity>): List<AnRow> =
        b.entries.map { (id, v) ->
            val g = if (id == "__none") null else tags.firstOrNull { it.id == id }
            AnRow(g?.name ?: "(untagged)", v, g?.color ?: "var(--muted)")
        }.sortedByDescending { it.v }

    /** Opti `anBucket`. */
    fun bucket(events: List<SrcEvent>, group: String, from: Long, to: Long, tags: List<TagEntity>, zone: ZoneId): AnBucket {
        val ev = events.filter { it.ts >= from && it.ts <= to }
        when (group) {
            "day" -> {
                val day = LinkedHashMap<Long, Double>()
                ev.forEach { val k = localDayKey(it.ts, zone); day[k] = (day[k] ?: 0.0) + it.v }
                return AnBucket.Day(day.keys.sorted().map { DayVal(it, day.getValue(it)) }, day)
            }
            "week", "month" -> {
                val b = HashMap<String, Double>()
                ev.forEach { val k = if (group == "month") monthKey(it.ts, zone) else mondayKey(it.ts, zone); b[k] = (b[k] ?: 0.0) + it.v }
                return AnBucket.Cat(b.keys.sorted().map { AnRow(it, b.getValue(it)) })
            }
            "tag" -> {
                val b = LinkedHashMap<String, Double>()
                val idByName = tagIdIndex(tags)
                ev.forEach { e ->
                    val ids = e.item?.tags ?: emptyList()
                    if (ids.isEmpty()) b["__none"] = (b["__none"] ?: 0.0) + e.v
                    // distinct(): a task storing both spellings of one tag still counts once
                    else ids.map { canonicalTagId(it, idByName) }.distinct().forEach { b[it] = (b[it] ?: 0.0) + e.v }
                }
                return AnBucket.Cat(tagRows(b, tags))
            }
        }
        val b = LinkedHashMap<String, Double>()
        ev.forEach { e -> val ty = e.item?.type ?: (if (!e.title.isNullOrEmpty()) "habit" else "?"); b[ty] = (b[ty] ?: 0.0) + e.v }
        return AnBucket.Cat(b.entries.map { AnRow(it.key, it.value) }.sortedByDescending { it.v })
    }

    /** Opti `anSnapshotRows` (current state). */
    fun snapshotRows(v: AnView, d: AnData, now: Long, zone: ZoneId): List<AnRow> {
        val items = viewItems(v, d.tasks, d.tags)
        val vals: List<Pair<TaskEntity, Double>> = when (v.source) {
            "incomplete" -> items.filter { t -> when (t.type) { "todo" -> !t.done; "daily" -> isDailyDueToday(t, now, zone) && !t.done; else -> false } }.map { it to 1.0 }
            "streaks" -> items.filter { it.type == "daily" }.map { it to (it.streak ?: 0).toDouble() }
            "tagcount" -> items.map { it to 1.0 }
            else -> emptyList()
        }
        if (v.group == "type") {
            val b = LinkedHashMap<String, Double>()
            vals.forEach { (t, w) -> b[t.type] = (b[t.type] ?: 0.0) + w }
            return b.entries.map { AnRow(it.key, it.value) }.sortedByDescending { it.v }
        }
        val b = LinkedHashMap<String, Double>()
        val idByName = tagIdIndex(d.tags)
        vals.forEach { (t, w) ->
            if (t.tags.isEmpty()) b["__none"] = (b["__none"] ?: 0.0) + w
            else t.tags.map { canonicalTagId(it, idByName) }.distinct().forEach { b[it] = (b[it] ?: 0.0) + w }
        }
        return tagRows(b, d.tags)
    }

    /** Opti `anSnapshotHistoryBucket` (app.js:4200-4275). */
    fun snapshotHistoryBucket(v: AnView, d: AnData, from: Long, to: Long, now: Long, zone: ZoneId): AnBucket {
        if (v.group == "tag" || v.group == "type") return AnBucket.Cat(snapshotRows(v, d, now, zone))
        val items = viewItems(v, d.tasks, d.tags)
        val mFrom = localDayKey(from, zone)
        val mTo = localDayKey(to, zone)
        val dayMap = LinkedHashMap<Long, Double>()
        run { var x = mFrom; while (x <= mTo) { dayMap[x] = 0.0; x = nextLocalDay(x, zone) } }
        items.forEach { t ->
            if (v.source == "streaks" && t.type != "daily") return@forEach
            val cMs = createdMs(t)
            if (cMs > to) return@forEach
            val comp: Set<Long>? = if (t.type == "daily") t.history.filter { it.completed == true }.map { localDayKey(it.date, zone) }.toSet() else null
            var cur = 0
            val doneKey = t.completedAt?.takeIf { it != 0L }?.let { localDayKey(it, zone) }
            // The walk before the window only feeds the running streak; skip it for other sources.
            var x = if (v.source == "streaks" || cMs >= mFrom) localDayKey(cMs, zone) else mFrom
            while (x <= mTo) {
                val dueToday = t.type == "daily" && (t.repeat == null || t.repeat.getOrNull(jsDay(x, zone)) == true)
                if (dueToday) { if (comp!!.contains(x)) cur++ else cur = 0 }
                if (x >= mFrom) {
                    when (v.source) {
                        "streaks" -> dayMap[x] = dayMap.getValue(x) + cur
                        "incomplete" -> if (t.type == "todo") { if (doneKey == null || doneKey > x) dayMap[x] = dayMap.getValue(x) + 1 }
                            else if (t.type == "daily") { if (dueToday && !comp!!.contains(x)) dayMap[x] = dayMap.getValue(x) + 1 }
                        "tagcount" -> dayMap[x] = dayMap.getValue(x) + 1
                    }
                }
                x = nextLocalDay(x, zone)
            }
        }
        val series = dayMap.entries.map { DayVal(it.key, it.value) }
        if (v.group == "day") return AnBucket.Day(series, dayMap)
        val b = LinkedHashMap<String, Double>(); val counts = HashMap<String, Int>()
        series.forEach { pt ->
            val k = if (v.group == "month") monthKey(pt.d, zone) else mondayKey(pt.d, zone)
            b[k] = (b[k] ?: 0.0) + pt.v; counts[k] = (counts[k] ?: 0) + 1
        }
        return AnBucket.Cat(b.keys.sorted().map { AnRow(it, jsRound(b.getValue(it) / counts.getValue(it)).toDouble()) })
    }

    /** Opti `renderView`. */
    fun renderView(v: AnView, d: AnData, p: AnPrefs, from: Long, to: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): ViewBody {
        if (v.source == "overview") return overview(d, from, to, now, zone)
        if (v.source == "tagsummary") return tagSummary(d, from, to)
        var chart = v.chart
        val bucket: AnBucket
        if (v.source in V_SNAPSHOT) {
            bucket = snapshotHistoryBucket(v, d, from, to, now, zone)
            if (chart == "list" && v.source == "streaks" && bucket is AnBucket.Cat) {
                return ViewBody.ListRows(viewItems(v, d.tasks, d.tags).filter { it.type == "daily" }
                    .map { AnRow(it.title, (it.streak ?: 0).toDouble()) }.sortedByDescending { it.v })
            }
        } else {
            bucket = bucket(sourceEvents(v, d, p), v.group, from, to, d.tags, zone)
        }
        if (bucket is AnBucket.Cat) {
            if (chart == "line" || chart == "heatmap") chart = "bar"
            if (chart == "list") return ViewBody.ListRows(bucket.rows)
            return ViewBody.Bars(if (v.group == "week" || v.group == "month") fillRows(bucket.rows, from, to, v.group, zone) else bucket.rows)
        }
        val b = bucket as AnBucket.Day
        val filled = fillDays(b.series, from, to, zone)
        return when (chart) {
            "heatmap" -> ViewBody.Heatmap(heatmapCells(from, to, b.dayMap, max(1.0, b.dayMap.values.maxOrNull() ?: 0.0), zone))
            "list" -> ViewBody.ListRows(b.series.map { AnRow(fmtDate(it.d, zone), it.v) }.asReversed())
            "bar" -> ViewBody.Bars(filled.map { AnRow(fmtDate(it.d, zone), it.v) })
            else -> ViewBody.Line(SeriesChart(filled, false, "var(--accent)", 80, filled.map { "📅 " + fmtDate(it.d, zone) + "\n" + loc(it.v, locale) }))
        }
    }

    /** Opti `anOverviewBody`. */
    fun overview(d: AnData, from: Long, to: Long, now: Long, zone: ZoneId): ViewBody.Cards {
        fun inWin(ms: Long) = ms != 0L && ms >= from && ms <= to
        val tasks = d.tasks
        val created = tasks.count { inWin(createdMs(it)) }
        val updated = tasks.count { val u = updatedMs(it); u != createdMs(it) && inWin(u) }
        var completed = 0
        tasks.forEach { t -> t.history.forEach { h -> if (h.completed == true && inWin(h.date)) completed++ } }
        val openTodos = tasks.count { it.type == "todo" && !it.done }
        val dueDailies = tasks.count { it.type == "daily" && isDailyDueToday(it, now, zone) && !it.done }
        return ViewBody.Cards(listOf(
            AnCardData("Habits", tasks.count { it.type == "habit" }.toString(), "total"),
            AnCardData("Dailies", tasks.count { it.type == "daily" }.toString(), "$dueDailies due now"),
            AnCardData("To-Dos", tasks.count { it.type == "todo" }.toString(), "$openTodos open"),
            AnCardData("Created", created.toString(), "in window"),
            AnCardData("Updated", updated.toString(), "in window"),
            AnCardData("Completed", completed.toString(), "in window"),
        ))
    }

    /** Opti `anTagSummaryBody`. */
    fun tagSummary(d: AnData, from: Long, to: Long): ViewBody {
        if (d.tags.isEmpty()) return ViewBody.Note("No tags yet. Add tags to tasks (in the task editor) to see per-tag analytics.")
        fun inWin(ms: Long) = ms != 0L && ms >= from && ms <= to
        val idByName = tagIdIndex(d.tags)
        val rows = d.tags.map { g ->
            val items = d.tasks.filter { g.id in it.tags.map { token -> canonicalTagId(token, idByName) } }
            var comp = 0
            items.forEach { t -> t.history.forEach { h -> if (h.completed == true && inWin(h.date)) comp++ } }
            TagSummaryRow(g.name, g.color, items.size, comp, items.count { inWin(createdMs(it)) })
        }.sortedByDescending { it.count }
        return ViewBody.TagSummary(rows.map { LabelVal(it.name, it.count.toDouble()) }, rows)
    }

    /** Opti `viewMetaLabel`. */
    fun viewMetaLabel(v: AnView, metrics: List<AnMetric>, p: AnPrefs): String {
        if (v.source in V_SPECIAL) return "summary"
        if (v.source == "metric") return "metric: " + (viewMetric(v, metrics, p)?.name ?: "—") + " · by " + v.group + " · " + v.chart
        return v.source + " · by " + v.group + " · " + v.chart
    }

    /** Opti `anRowLegend`: the rows that carry a color. */
    fun rowLegend(rows: List<AnRow>): List<LegendItem> = rows.filter { it.color != null }.map { LegendItem(it.label, it.color!!) }

    // ---- heatmap --------------------------------------------------------------------------------------
    /** Opti `heatColor`. */
    fun heatColor(v: Double, mx: Double): String {
        if (v == 0.0 || v.isNaN()) return "var(--panel2)"
        val t = min(1.0, v / max(1.0, mx))
        val idx = min(HEAT_STOPS.size - 1, floor(t * (HEAT_STOPS.size - 1)).toInt() + 1)
        // A net-negative day (a reps removal) gives a negative index: Opti writes
        // `background:undefined`, which the CSS drops, so the cell keeps .anHeatCell's panel2.
        return HEAT_STOPS.getOrNull(idx) ?: "var(--panel2)"
    }

    /** Opti `anHeatmapHTML` cells: from the Monday on/before `from` to the local day of `to`. */
    fun heatmapCells(from: Long, to: Long, inten: Map<Long, Double>, maxI: Double, zone: ZoneId): List<HeatCell> {
        val s = localDate(localDayKey(from, zone), zone)
        val start = s.minusDays((s.dayOfWeek.value - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = localDayKey(to, zone)
        val out = ArrayList<HeatCell>()
        var t = start
        while (t <= end) {
            val v = inten[localDayKey(t, zone)] ?: 0.0
            out += HeatCell(t, v, heatColor(v, maxI), "📅 " + fmtDate(t, zone) + "\n" + (if (v != 0.0) "🔥 " + jsNum(v) + " activity" else "💤 no activity"))
            t = nextLocalDay(t, zone)
        }
        return out
    }

    // ---- chart geometry (Opti svgBars / svgSpark / svgCompare, viewBox units) ----------------------------
    data class BarGeom(val x: Double, val y: Double, val bw: Double, val r: Double, val base: Double, val slotX: Double, val slotW: Double) {
        fun d(): String = "M" + fixed1(x) + " " + fixed1(base) + " V" + fixed1(y + r) + " Q" + fixed1(x) + " " + fixed1(y) + " " + fixed1(x + r) + " " + fixed1(y) +
            " H" + fixed1(x + bw - r) + " Q" + fixed1(x + bw) + " " + fixed1(y) + " " + fixed1(x + bw) + " " + fixed1(y + r) + " V" + fixed1(base) + " Z"
    }

    const val BARS_W = 300.0
    const val BARS_H = 90.0

    /** Opti `svgBars` geometry (w 300, h 90, baseline at 89). */
    fun barsGeom(values: List<Double>): List<BarGeom> {
        if (values.isEmpty()) return emptyList()
        val w = BARS_W; val h = BARS_H
        val mx = max(1.0, values.maxOrNull() ?: 0.0)
        val n = values.size; val slot = w / n; val bw = max(2.0, slot * 0.66); val gap = slot - bw
        val base = h - 1; val top = 4.0; val plot = base - top; val rad = min(2.0, bw / 2)
        return values.mapIndexed { i, v ->
            var bh = (v / mx) * plot; if (v > 0) bh = max(2.0, bh)
            val x = i * slot + gap / 2; val y = base - bh
            BarGeom(x, y, bw, min(rad, bh), base, i * slot, slot)
        }
    }

    fun barTip(label: String, v: Double, locale: Locale = Locale.getDefault()) = "📊 $label\n🔁 ${loc(v, locale)} reps"

    data class SparkGeom(val w: Double, val h: Double, val pts: List<Pair<Double, Double>>) {
        fun lineD(): String = pts.mapIndexed { i, (x, y) -> (if (i > 0) "L" else "M") + fixed1(x) + " " + fixed1(y) + " " }.joinToString("")
        fun areaD(): String = "M0 " + jsNum(h) + " " + pts.joinToString("") { (x, y) -> "L" + fixed1(x) + " " + fixed1(y) + " " } + "L" + jsNum(w) + " " + jsNum(h) + " Z"
    }

    /** Opti `svgSpark` geometry (w 300; null when there is no data). */
    fun sparkGeom(values: List<Double>, cumulative: Boolean, h: Int): SparkGeom? {
        if (values.isEmpty()) return null
        val w = 300.0
        var run = 0.0
        val pts = if (cumulative) values.map { run += it; run } else values
        val mx = max(1.0, pts.maxOrNull() ?: 0.0)
        val n = pts.size
        return SparkGeom(w, h.toDouble(), pts.mapIndexed { i, v ->
            val x = if (n == 1) w / 2 else (i.toDouble() / (n - 1)) * w
            x to (h - 4 - (v / mx) * (h - 10))
        })
    }

    /** Opti `svgCompare` geometry (w 300, h 110): one cumulative line per metric; null when no days. */
    fun compareGeom(c: CompareSeries): List<Pair<String, List<Pair<Double, Double>>>>? {
        val w = 300.0; val h = 110.0
        val all = ArrayList<Double>()
        c.metrics.forEach { m -> var run = 0.0; c.days.forEach { d -> run += c.per[m.id]?.get(d) ?: 0.0; all += run } }
        val mx = max(1.0, all.maxOrNull() ?: 0.0)
        if (c.days.isEmpty()) return null
        val n = c.days.size
        return c.metrics.mapIndexed { mi, m ->
            var run = 0.0
            METRIC_COLORS[mi % METRIC_COLORS.size] to c.days.mapIndexed { i, d ->
                run += c.per[m.id]?.get(d) ?: 0.0
                (if (n == 1) w / 2 else (i.toDouble() / (n - 1)) * w) to (h - 4 - (run / mx) * (h - 12))
            }
        }
    }

    fun compareTips(c: CompareSeries, zone: ZoneId, locale: Locale = Locale.getDefault()): List<List<String>> =
        c.metrics.map { m -> var run = 0.0; c.days.map { d -> run += c.per[m.id]?.get(d) ?: 0.0; "📈 " + m.name + "\n📅 " + fmtDate(d, zone) + "\n∑ " + loc(run, locale) + " total" } }

    // ---- views / metrics wire objects (Opti key order) ----------------------------------------------------
    /** An Opti view object as `saveView` / `cloneView` / `importViews` leave it. */
    fun viewJson(v: AnView, base: JsonObject?, createdAt: Long?, updatedAt: Long, metricKey: Boolean = false): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        base?.let { m.putAll(it) }
        m["id"] = JsonPrimitive(v.id)
        m["name"] = JsonPrimitive(v.name)
        m["source"] = JsonPrimitive(v.source)
        m["group"] = JsonPrimitive(v.group)
        m["chart"] = JsonPrimitive(v.chart)
        m["tags"] = JsonArray(v.tags.map { JsonPrimitive(it) })
        m["types"] = JsonArray(v.types.map { JsonPrimitive(it) })
        if (v.metricId != null || metricKey || m.containsKey("metricId")) m["metricId"] = v.metricId?.let { JsonPrimitive(it) } ?: JsonNull
        m["updatedAt"] = JsonPrimitive(updatedAt)
        if (createdAt != null) m["createdAt"] = JsonPrimitive(createdAt)
        return JsonObject(m)
    }

    fun jsNumPrimitive(v: Double): JsonPrimitive = if (v == floor(v) && abs(v) < 1e15) JsonPrimitive(v.roundToLong()) else JsonPrimitive(v)

    /** An Opti metric object as `saveMetricEditor` / `bSaveMetric` leave it (edit keeps the key order). */
    fun metricJson(base: JsonObject?, id: String, name: String, keyword: String, exact: Boolean, habits: List<AnMetricHabit>, createdAt: Long?, updatedAt: Long): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (base != null) m.putAll(base) else m["id"] = JsonPrimitive(id)
        m["name"] = JsonPrimitive(name)
        m["keyword"] = JsonPrimitive(keyword)
        m["exact"] = JsonPrimitive(exact)
        m["habits"] = JsonArray(habits.map { h -> JsonObject(linkedMapOf("id" to JsonPrimitive(h.id), "reps" to (h.reps?.let { jsNumPrimitive(it) } ?: JsonNull))) })
        if (base == null && createdAt != null) m["createdAt"] = JsonPrimitive(createdAt)
        m["updatedAt"] = JsonPrimitive(updatedAt)
        return JsonObject(m)
    }

    /** Opti `exportViews` payload (`JSON.stringify(payload, null, 2)`). */
    fun exportPayload(views: List<JsonObject>, appVersion: String, nowMs: Long): String {
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowMs))
        val payload = JsonObject(linkedMapOf("_questaViews" to JsonPrimitive(1), "appVersion" to JsonPrimitive(appVersion),
            "exportedAt" to JsonPrimitive(iso), "views" to JsonArray(views)))
        return PRETTY.encodeToString(JsonElement.serializer(), payload)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Opti `importViews` parse: the view objects a file carries, or null when it is not a views file. */
    fun importArray(text: String): List<JsonElement>? {
        val d = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val arr = when (d) {
            is JsonArray -> d
            is JsonObject -> d["views"]?.let { it as? JsonArray ?: return null } ?: JsonArray(emptyList())
            else -> return null
        }
        return if (arr.isEmpty()) null else arr
    }

    // ---- event feed (Opti renderEventDetail helpers, app.js:4618-4640) ------------------------------------
    val DIAGNOSTIC_KINDS = com.example.data.sync.EventKinds.DIAGNOSTIC_KINDS

    fun eventCategory(e: EventLogEntity): String = com.example.data.sync.EventKinds.category(e.kind, e.taskType)
}

/** Where Opti keeps the non-synced `S.prefs.an` keys: local-only storage. */
interface AnPrefsStore {
    fun load(): AnPrefs
    fun save(p: AnPrefs)
}

class InMemoryAnPrefsStore(private var p: AnPrefs = AnPrefs()) : AnPrefsStore {
    override fun load() = p
    override fun save(p: AnPrefs) { this.p = p }
}

class SharedPrefsAnPrefsStore(context: Context) : AnPrefsStore {
    private val sp = context.getSharedPreferences("questa_analytics", Context.MODE_PRIVATE)
    override fun load(): AnPrefs = AnPrefs(
        fromOff = sp.getInt("fromOff", 90),
        toOff = sp.getInt("toOff", 0),
        snap = if (sp.contains("snap")) sp.getString("snap", null) else "90d",
        activeMetric = sp.getString("activeMetric", null),
        viewOrder = sp.getString("viewOrder", "")!!.split(',').filter { it.isNotEmpty() },
        viewsTouched = sp.getBoolean("viewsTouched", false),
        seedMetricIds = sp.getString("seedMetricIds", "")!!.split(',').filter { it.isNotEmpty() },
    )
    override fun save(p: AnPrefs) {
        sp.edit().apply {
            putInt("fromOff", p.fromOff ?: 90)
            putInt("toOff", p.toOff ?: 0)
            putString("snap", p.snap)
            putString("activeMetric", p.activeMetric)
            putString("viewOrder", p.viewOrder.joinToString(","))
            putBoolean("viewsTouched", p.viewsTouched)
            putString("seedMetricIds", p.seedMetricIds.joinToString(","))
        }.apply()
    }
}

/** A view being edited in the builder sheet (Opti VDRAFT). [id] null = new view. */
data class ViewDraft(
    val id: String? = null,
    val name: String = "",
    val source: String = "completed",
    val group: String = "day",
    val chart: String = "line",
    val tags: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val metricId: String? = null,
    /** Opti `vSet('source','metric')` adds the `metricId` key even when it stays null. */
    val metricKey: Boolean = false,
) {
    fun asView() = AnView(id ?: "", name, source, group, chart, tags, types, metricId)
}

/** A metric being edited (Opti MEDIT). [mid] null = new metric. Reps are the raw input text. */
data class MetricDraft(
    val mid: String? = null,
    val name: String = "",
    val keyword: String = "",
    val exact: Boolean = false,
    val habits: List<Pair<String, String>> = emptyList(),
)

/**
 * WP-8.20 (PX-110): the Analytics tab state. `an.views` rows live in custom_views (the
 * `source != null` rows), `an.metrics` in metrics_extras; every write stamps `updatedAt` from the
 * HLC (Opti `now()`), and a delete writes a tombstone (Opti `delMark`).
 */
class AnalyticsViewModel(
    private val repository: RpgRepository,
    private val prefsStore: AnPrefsStore = InMemoryAnPrefsStore(),
    val nowMs: () -> Long = { com.example.data.boot.BootGate.clock() },
    val zone: () -> ZoneId = { com.example.data.boot.BootGate.zoneProvider() },
    private val appVersion: String = "",
) : ViewModel() {

    /** Writes run one at a time, in call order (a materialize must not race a second edit). */
    private val writeLock = Mutex()
    @Volatile internal var lastWrite: Job? = null

    private val _prefs = MutableStateFlow(prefsStore.load())
    val prefs: StateFlow<AnPrefs> = _prefs.asStateFlow()

    private val metricsJson = MutableStateFlow<String?>(null)
    private val metricsLoaded = MutableStateFlow(false)

    /** Default views / metrics Opti's `anPrefs()` seeds, shown until the user writes (see notes). */
    private val seedViews: List<AnView> = listOf(
        AnView(UUID.randomUUID().toString(), "Overview", "overview", "day", "list"),
        AnView(UUID.randomUUID().toString(), "By tag", "tagsummary", "tag", "bar"),
        AnView(UUID.randomUUID().toString(), "Completions / day", "completed", "day", "line"),
        AnView(UUID.randomUUID().toString(), "Created by tag", "created", "tag", "bar"),
        AnView(UUID.randomUUID().toString(), "Open items by type", "incomplete", "type", "bar"),
        AnView(UUID.randomUUID().toString(), "Activity heatmap", "reps", "day", "heatmap"),
    )
    private val seedMetricIds: List<String> = _prefs.value.seedMetricIds.takeIf { it.size == 2 }
        ?: listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString()).also { ids -> updatePrefs { it.copy(seedMetricIds = ids) } }
    private val seedMetrics: List<AnMetric> = listOf(
        AnMetric(seedMetricIds[0], "Kliky", "klik", false, emptyList(), JsonObject(emptyMap())),
        AnMetric(seedMetricIds[1], "Zdvihy", "zdvih", false, emptyList(), JsonObject(emptyMap())),
    ).map { it.copy(raw = JsonObject(linkedMapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name), "keyword" to JsonPrimitive(it.keyword)))) }

    private val baseInputs = combine(repository.allTasks, repository.allRewards, repository.allTags, repository.allLogs) { t, r, g, l -> Inputs(t, r, g, l) }

    private data class Inputs(val tasks: List<TaskEntity>, val rewards: List<RewardEntity>, val tags: List<TagEntity>, val logs: List<EventLogEntity>)

    /** Stored PWA views in display order (the Kotlin order list first, then createdAt). */
    private val storedViews: StateFlow<List<CustomViewEntity>> = combine(repository.allCustomViews, _prefs) { rows, p ->
        val pwa = rows.filter { it.source != null }
        val idx = p.viewOrder.withIndex().associate { it.value to it.index }
        pwa.sortedWith(compareBy<CustomViewEntity> { idx[it.id] ?: Int.MAX_VALUE }.thenBy { it.createdAt })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val data: StateFlow<AnData> = combine(baseInputs, storedViews, metricsJson, metricsLoaded, _prefs) { inp, rows, mj, loaded, p ->
        val views = if (rows.isEmpty() && !p.viewsTouched) seedViews else rows.map(::toView)
        val metrics = if (mj == null && loaded) seedMetrics else AnalyticsCalc.parseMetrics(mj)
        AnData(inp.tasks, inp.rewards, inp.tags, inp.logs, views, metrics, AnalyticsCalc.allEvents(inp.tasks))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AnData())

    init {
        // Metrics have no Flow; reload whenever synced data may have changed.
        combine(repository.allCustomViews, repository.allTombstones, repository.allTasks) { _, _, _ -> }
            .onEach { reloadMetrics() }.launchIn(viewModelScope)
        repository.allCustomViews.onEach { rows ->
            if (rows.any { it.source != null } && !_prefs.value.viewsTouched) updatePrefs { it.copy(viewsTouched = true) }
        }.launchIn(viewModelScope)
    }

    fun reloadMetrics() {
        write { metricsJson.value = repository.getMetricsExtrasJson(); metricsLoaded.value = true }
    }

    private fun toView(r: CustomViewEntity) = AnView(r.id, r.name ?: r.title, r.source ?: "", r.group ?: "", r.chart ?: "", r.tags, r.types, r.metricId)

    /** Atomic (CAS) so a background flag update never overwrites a concurrent order change. */
    private fun updatePrefs(f: (AnPrefs) -> AnPrefs) { prefsStore.save(_prefs.updateAndGet(f)) }

    private fun write(block: suspend () -> Unit): Job =
        viewModelScope.launch { writeLock.withLock { block() } }.also { lastWrite = it }

    /** Test hook: waits for the queued writes. */
    internal suspend fun awaitWrites() { lastWrite?.join() }

    // ---- window + slider ------------------------------------------------------------------------------
    fun sliderGeom(d: AnData = data.value): AnalyticsCalc.SliderGeom {
        val now = nowMs(); val (mn, mx) = AnalyticsCalc.span(d.events, now)
        return AnalyticsCalc.SliderGeom(mn, mx, now)
    }

    fun window(p: AnPrefs = prefs.value, d: AnData = data.value, noFloor: Boolean = false): Pair<Long, Long> {
        val now = nowMs()
        return AnalyticsCalc.window(p, AnalyticsCalc.span(d.events, now).first, now, noFloor)
    }

    /** Opti `layout()`'s `normRange()` + `save()`, run when the tab is laid out. */
    fun normalize() { val g = sliderGeom(); updatePrefs { AnalyticsCalc.normRange(it, g) } }

    fun selectSnap(snap: String) { val g = sliderGeom(); updatePrefs { AnalyticsCalc.snapTo(it, g, snap) } }

    /** The slider commit on release (Opti `up()` -> save + refreshAnalytics). */
    fun commitRange(p: AnPrefs) { val g = sliderGeom(); updatePrefs { AnalyticsCalc.normRange(it.copy(fromOff = p.fromOff, toOff = p.toOff, snap = p.snap), g) } }

    fun selectMetric(id: String) {
        updatePrefs { it.copy(activeMetric = id) }
        write { persistSeedMetricsIfReferenced(id) }
    }

    /**
     * Review 36: Opti persists its default metrics on first open (`initAnalytics` -> `save()`,
     * app.js:3912). Kotlin keeps them virtual (G-1) until something refers to one — a saved view's
     * `metricId` or the active metric — and then writes them, so no view points at a metric that
     * is not in the synced `an.metrics`.
     */
    private suspend fun persistSeedMetricsIfReferenced(id: String?) {
        if (id == null || id !in seedMetricIds) return
        if (repository.getMetricsExtrasJson() != null) return
        val enc = SerializationEngine.encodeJsonArray(JsonArray(seedMetrics.map { it.raw }))
        repository.saveMetricsExtrasJson(enc)
        metricsJson.value = enc; metricsLoaded.value = true
    }

    // ---- views ----------------------------------------------------------------------------------------
    private suspend fun materializeViews(): List<CustomViewEntity> {
        val rows = repository.getAllCustomViewsSync().filter { it.source != null }
        if (rows.isNotEmpty() || prefs.value.viewsTouched) return rows
        val now = nowMs()
        seedViews.forEachIndexed { i, v ->
            val obj = AnalyticsCalc.viewJson(v, null, null, repository.hlcClock.now())
            repository.insertCustomView(entityOf(v, obj, now + i))
        }
        updatePrefs { it.copy(viewsTouched = true, viewOrder = seedViews.map { it.id }) }
        return repository.getAllCustomViewsSync().filter { it.source != null }
    }

    private fun entityOf(v: AnView, obj: JsonObject, createdAt: Long): CustomViewEntity {
        val base = SerializationEngine.parseViewEntity(obj)
        return base.copy(createdAt = (obj["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: createdAt)
    }

    private fun currentOrder(rows: List<CustomViewEntity>): List<String> {
        val idx = prefs.value.viewOrder.withIndex().associate { it.value to it.index }
        return rows.sortedWith(compareBy<CustomViewEntity> { idx[it.id] ?: Int.MAX_VALUE }.thenBy { it.createdAt }).map { it.id }
    }

    private fun toast(msg: String) { _messages.value = msg }
    private val _messages = MutableStateFlow<String?>(null)
    /** One-shot toast / alert texts (Opti `toast(...)`). */
    val messages: StateFlow<String?> = _messages.asStateFlow()
    fun consumeMessage() { _messages.value = null }

    fun newViewDraft(): ViewDraft? {
        if (data.value.views.size >= AnalyticsCalc.MAX_VIEWS) { toast("Max 20 view sections"); return null }
        return ViewDraft()
    }

    fun editDraft(id: String): ViewDraft? = data.value.views.firstOrNull { it.id == id }?.let { ViewDraft(it.id, it.name, it.source, it.group, it.chart, it.tags, it.types, it.metricId) }

    /** Opti `vSet('source','metric')` picks the first metric when the draft has none. */
    fun draftSet(d: ViewDraft, field: String, value: String): ViewDraft = when (field) {
        "source" -> if (value == "metric" && d.metricId == null) d.copy(source = value, metricId = data.value.metrics.firstOrNull()?.id, metricKey = true)
            else d.copy(source = value)
        "group" -> d.copy(group = value)
        "chart" -> d.copy(chart = value)
        "metricId" -> d.copy(metricId = value)
        else -> d
    }

    /** Opti `saveView()`. Returns false (with a toast) when it did not save. */
    fun saveView(draft: ViewDraft, onDone: () -> Unit = {}): Boolean {
        val name = draft.name.trim()
        if (name.isEmpty()) { toast("Name required"); return false }
        val cur = data.value.views
        if (draft.id == null && cur.size >= AnalyticsCalc.MAX_VIEWS) { toast("Max 20 view sections"); return false }
        write {
            val rows = materializeViews()
            persistSeedMetricsIfReferenced(draft.metricId)
            val stamp = repository.hlcClock.now()
            val existing = draft.id?.let { id -> rows.firstOrNull { it.id == id } }
            if (existing != null) {
                val base = SerializationEngine.buildViewJson(existing)
                val v = draft.copy(name = name).asView()
                val obj = AnalyticsCalc.viewJson(v, base, null, stamp, draft.metricKey)
                repository.insertCustomView(entityOf(v, obj, existing.createdAt).copy(createdAt = existing.createdAt))
            } else {
                val id = draft.id ?: UUID.randomUUID().toString()
                val v = draft.copy(id = id, name = name).asView()
                val created = nowMs()
                val obj = AnalyticsCalc.viewJson(v, null, created, stamp, draft.metricKey)
                repository.insertCustomView(entityOf(v, obj, created))
                updatePrefs { it.copy(viewOrder = currentOrder(rows) + id, viewsTouched = true) }
            }
            onDone()
        }
        return true
    }

    /** Opti `delView()`: delMark + remove. */
    fun deleteView(id: String) {
        write {
            materializeViews()
            repository.insertTombstone(TombstoneEntity(id, repository.hlcClock.now()))
            repository.deleteCustomViewById(id)
            updatePrefs { it.copy(viewOrder = it.viewOrder - id, viewsTouched = true) }
        }
    }

    /** Opti `cloneView()`: a copy named "clone - <name>", inserted just below the original. */
    fun cloneView(draft: ViewDraft) {
        if (data.value.views.size >= AnalyticsCalc.MAX_VIEWS) { toast("Max 20 view sections"); return }
        write {
            val rows = materializeViews()
            persistSeedMetricsIfReferenced(draft.metricId)
            val src = draft.id?.let { id -> rows.firstOrNull { it.id == id } }
            val id = UUID.randomUUID().toString()
            val v = draft.copy(id = id, name = "clone - " + draft.name.ifEmpty { "view" }).asView()
            val created = nowMs()
            val base = src?.let { SerializationEngine.buildViewJson(it) }
            val obj = AnalyticsCalc.viewJson(v, base, created, repository.hlcClock.now(), draft.metricKey)
            repository.insertCustomView(entityOf(v, obj, created))
            val order = currentOrder(rows).toMutableList()
            val at = draft.id?.let { order.indexOf(it) } ?: -1
            if (at >= 0) order.add(at + 1, id) else order.add(id)
            updatePrefs { it.copy(viewOrder = order, viewsTouched = true) }
            toast("Cloned view")
        }
    }

    /** Opti `moveView(id, dir)`. The order is local (see notes). */
    fun moveView(id: String, dir: Int) {
        write {
            val rows = materializeViews()
            val order = currentOrder(rows).toMutableList()
            val i = order.indexOf(id); val j = i + dir
            if (i < 0 || j < 0 || j >= order.size) return@write
            val t = order[i]; order[i] = order[j]; order[j] = t
            updatePrefs { it.copy(viewOrder = order) }
        }
    }

    /** Opti `exportViews()` file text. */
    fun exportViewsJson(): String = AnalyticsCalc.exportPayload(
        storedViews.value.map { SerializationEngine.buildViewJson(it) }.ifEmpty {
            if (prefs.value.viewsTouched) emptyList() else seedViews.map { AnalyticsCalc.viewJson(it, null, null, 0).let { o -> JsonObject(o - "updatedAt") } }
        },
        appVersion, nowMs()
    )

    fun viewsCount(): Int = data.value.views.size

    /** Opti `importViews()`. Returns (imported, capped), or null when the file is not a views file. */
    fun importViewsJson(text: String, onDone: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int>? {
        val arr = AnalyticsCalc.importArray(text) ?: return null
        var n = 0; var capped = 0
        var count = data.value.views.size
        val toAdd = ArrayList<AnView>()
        arr.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val src = (o["source"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            if (src.isNullOrEmpty()) return@forEach
            if (count >= AnalyticsCalc.MAX_VIEWS) { capped++; return@forEach }
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
            fun l(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            toAdd += AnView(UUID.randomUUID().toString(), s("name") ?: "Imported view", src, s("group") ?: "day", s("chart") ?: "line", l("tags"), l("types"), s("metricId"))
            count++; n++
        }
        write {
            val rows = materializeViews()
            toAdd.forEach { persistSeedMetricsIfReferenced(it.metricId) }
            val created = nowMs()
            toAdd.forEach { v ->
                val obj = JsonObject(linkedMapOf("id" to JsonPrimitive(v.id), "name" to JsonPrimitive(v.name), "source" to JsonPrimitive(v.source),
                    "group" to JsonPrimitive(v.group), "chart" to JsonPrimitive(v.chart), "tags" to JsonArray(v.tags.map { JsonPrimitive(it) }),
                    "types" to JsonArray(v.types.map { JsonPrimitive(it) }), "metricId" to (v.metricId?.let { JsonPrimitive(it) } ?: JsonNull),
                    "createdAt" to JsonPrimitive(created), "updatedAt" to JsonPrimitive(repository.hlcClock.now())))
                repository.insertCustomView(entityOf(v, obj, created))
            }
            updatePrefs { it.copy(viewOrder = currentOrder(rows) + toAdd.map { it.id }, viewsTouched = true) }
            onDone(n, capped)
        }
        return n to capped
    }

    // ---- metrics --------------------------------------------------------------------------------------
    fun metricDraft(mid: String?): MetricDraft {
        val m = mid?.let { id -> data.value.metrics.firstOrNull { it.id == id } } ?: return MetricDraft()
        return MetricDraft(m.id, m.name, m.keyword, m.exact, m.habits.map { it.id to (it.reps?.let(AnalyticsCalc::jsNum) ?: "") })
    }

    private suspend fun materializeMetrics(): MutableList<JsonObject> {
        val raw = repository.getMetricsExtrasJson()
        if (raw == null) return seedMetrics.map { it.raw }.toMutableList()
        return SerializationEngine.parseJsonArray(raw).mapNotNull { it as? JsonObject }.toMutableList()
    }

    /**
     * Opti `saveMetricEditor()` / `bSaveMetric()`. Returns the saved metric id through [onSaved],
     * or false (with a toast) when the draft is not valid.
     */
    fun saveMetric(d: MetricDraft, makeActive: Boolean = true, onSaved: (String) -> Unit = {}): Boolean {
        val name = d.name.trim()
        if (name.isEmpty()) { toast("Name required"); return false }
        val keyword = if (d.exact) d.keyword else d.keyword.trim()
        if (d.exact) { if (d.habits.isEmpty()) { toast("Pick at least one habit"); return false } }
        else if (keyword.isEmpty()) { toast("Keyword required"); return false }
        val habits = d.habits.map { (id, r) -> AnMetricHabit(id, if (r.isEmpty()) null else (r.trim().toDoubleOrNull() ?: Double.NaN)) }
        write {
            val list = materializeMetrics()
            val stamp = repository.hlcClock.now()
            val id: String
            val i = d.mid?.let { mid -> list.indexOfFirst { (it["id"] as? JsonPrimitive)?.contentOrNull == mid } } ?: -1
            if (i >= 0) {
                id = d.mid!!
                list[i] = AnalyticsCalc.metricJson(list[i], id, name, keyword, d.exact, habits, null, stamp)
            } else {
                id = UUID.randomUUID().toString()
                list += AnalyticsCalc.metricJson(null, id, name, keyword, d.exact, habits, nowMs(), stamp)
            }
            val enc = SerializationEngine.encodeJsonArray(JsonArray(list))
            repository.saveMetricsExtrasJson(enc)
            metricsJson.value = enc; metricsLoaded.value = true
            if (makeActive && i < 0) updatePrefs { it.copy(activeMetric = id) }
            onSaved(id)
        }
        return true
    }

    /** Opti metric Delete: delMark + remove; the active metric falls back to the first. */
    fun deleteMetric(mid: String, onDone: (String?) -> Unit = {}) {
        write {
            val list = materializeMetrics().filter { (it["id"] as? JsonPrimitive)?.contentOrNull != mid }
            repository.insertTombstone(TombstoneEntity(mid, repository.hlcClock.now()))
            val enc = SerializationEngine.encodeJsonArray(JsonArray(list))
            repository.saveMetricsExtrasJson(enc)
            metricsJson.value = enc; metricsLoaded.value = true
            val first = (list.firstOrNull()?.get("id") as? JsonPrimitive)?.contentOrNull
            if (prefs.value.activeMetric == mid || prefs.value.activeMetric == null) updatePrefs { it.copy(activeMetric = first) }
            onDone(first)
        }
    }

    companion object {
        /** Wiring patch (m10): used at the DashboardScreen call site as `viewModel(factory = Factory)`. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as QuestaApplication
                AnalyticsViewModel(app.repository, SharedPrefsAnPrefsStore(app), appVersion = "v" + com.example.BuildConfig.VERSION_NAME)
            }
        }
    }
}
