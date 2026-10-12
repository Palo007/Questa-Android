package com.example.ui.analytics

import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SerializationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * WP-8.20 (PX-110): every analytics formula against golden values computed by Opti's REAL code
 * (app.js at 7d5d4f3, sliced and run under node by `analytics/gen_goldens.js`). The fixture is
 * Opti-shaped and goes through SerializationEngine, as synced data does.
 */
class AnalyticsGoldenTest {

    companion object {
        private fun res(name: String): String {
            val f = listOf(File("src/test/resources/analytics/$name"), File("app/src/test/resources/analytics/$name")).first { it.isFile }
            return f.readText()
        }
        val fixture: JsonObject by lazy { Json.parseToJsonElement(res("fixture.json")).jsonObject }
        val golden: JsonObject by lazy { Json.parseToJsonElement(res("goldens.json")).jsonObject }
        val zone: ZoneId = ZoneId.of("Europe/Bratislava")
        val now: Long get() = fixture.getValue("now").jsonPrimitive.long

        val tasks: List<TaskEntity> by lazy { fixture.getValue("tasks").jsonArray.map { SerializationEngine.parseTaskEntity(it.jsonObject) } }
        val tags: List<TagEntity> by lazy { fixture.getValue("tags").jsonArray.map { SerializationEngine.parseTagEntity(it.jsonObject) } }
        val rewards: List<RewardEntity> by lazy { fixture.getValue("rewards").jsonArray.map { SerializationEngine.parseRewardEntity(it.jsonObject) } }
        val metrics: List<AnMetric> by lazy { fixture.getValue("metrics").jsonArray.map { AnalyticsCalc.parseMetric(it.jsonObject) } }
        val views: List<AnView> by lazy {
            fixture.getValue("views").jsonArray.map { e ->
                val o = e.jsonObject
                AnView(o.s("id")!!, o.s("name")!!, o.s("source")!!, o.s("group")!!, o.s("chart")!!,
                    o.getValue("tags").jsonArray.map { it.jsonPrimitive.content }, o.getValue("types").jsonArray.map { it.jsonPrimitive.content }, o.s("metricId"))
            }
        }
        val data: AnData by lazy { AnData(tasks, rewards, tags, emptyList(), views, metrics, AnalyticsCalc.allEvents(tasks)) }

        fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        fun JsonElement.d(): Double = jsonPrimitive.double
        fun prefsOf(pc: JsonObject) = AnPrefs(
            fromOff = pc.getValue("fromOff").jsonPrimitive.int, toOff = pc.getValue("toOff").jsonPrimitive.int,
            snap = pc.s("snap"), activeMetric = "m1")
        fun cases(): List<Pair<String, JsonObject>> = fixture.getValue("prefCases").jsonArray.map { it.jsonObject }.map { it.s("name")!! to it }
    }

    private val eps = 1e-9

    private fun assertDayVals(msg: String, exp: JsonArray, act: List<DayVal>) {
        assertEquals("$msg size", exp.size, act.size)
        exp.forEachIndexed { i, e -> assertEquals("$msg[$i].d", e.jsonObject.getValue("d").jsonPrimitive.long, act[i].d); assertEquals("$msg[$i].v", e.jsonObject.getValue("v").d(), act[i].v, eps) }
    }

    private fun assertRows(msg: String, exp: JsonArray, act: List<AnRow>) {
        assertEquals("$msg rows", exp.map { it.jsonObject.s("label") to it.jsonObject.s("color") }, act.map { it.label to it.color })
        exp.forEachIndexed { i, e -> assertEquals("$msg[$i].v", e.jsonObject.getValue("v").d(), act[i].v, eps) }
    }

    private fun assertLabelVals(msg: String, exp: JsonArray, act: List<LabelVal>) {
        assertEquals("$msg labels", exp.map { it.jsonObject.s("label") }, act.map { it.label })
        exp.forEachIndexed { i, e -> assertEquals("$msg[$i]", e.jsonObject.getValue("v").d(), act[i].v, eps) }
    }

    @Test
    fun `anAllEvents - reps, repCounted, scored and completed per history point`() {
        val exp = golden.getValue("events").jsonArray
        val act = data.events
        assertEquals(exp.size, act.size)
        exp.forEachIndexed { i, e ->
            val o = e.jsonObject; val a = act[i]
            assertEquals("taskId $i", o.s("taskId"), a.taskId)
            assertEquals("date $i", o.getValue("date").jsonPrimitive.long, a.date)
            assertEquals("reps $i", o.getValue("reps").d(), a.reps, eps)
            assertEquals("repCounted $i", o.getValue("repCounted").jsonPrimitive.boolean, a.repCounted)
            assertEquals("scored $i", o.getValue("scored").jsonPrimitive.boolean, a.scored)
            assertEquals("scoredUp $i", o.getValue("scoredUp").jsonPrimitive.int, a.scoredUp)
            assertEquals("completed $i", (o["completed"] as? JsonPrimitive)?.booleanOrNull, a.completed)
        }
    }

    @Test
    fun `anSpan, repsPerTap and heatColor`() {
        val span = golden.getValue("span").jsonArray
        assertEquals(span[0].jsonPrimitive.long to span[1].jsonPrimitive.long, AnalyticsCalc.span(data.events, now))
        assertEquals(golden.getValue("repsPerTap").jsonArray.map { it.jsonPrimitive.int },
            listOf("Kliky 20", "Read 2 chapters of book 3", "none", "x10", "").map { AnalyticsCalc.repsPerTap(it) })
        assertEquals(golden.getValue("heatColors").jsonArray.map { it.jsonPrimitive.content },
            listOf(0, 1, 2, 3, 4, 5, 7, 10).map { AnalyticsCalc.heatColor(it.toDouble(), 10.0) })
    }

    @Test
    fun `anWindow for every snap and custom range`() {
        val mn = AnalyticsCalc.span(data.events, now).first
        for ((name, pc) in cases()) {
            val c = golden.getValue("cases").jsonObject.getValue(name).jsonObject
            val p = prefsOf(pc)
            assertEquals(name, c.getValue("from").jsonPrimitive.long to c.getValue("to").jsonPrimitive.long, AnalyticsCalc.window(p, mn, now))
            assertEquals("$name feed", c.getValue("feedFrom").jsonPrimitive.long to c.getValue("feedTo").jsonPrimitive.long, AnalyticsCalc.window(p, mn, now, noFloor = true))
        }
    }

    @Test
    fun `reps metrics - cumulative, series, breakdowns, totals, compare`() {
        for ((name, _) in cases()) {
            val c = golden.getValue("cases").jsonObject.getValue(name).jsonObject
            val from = c.getValue("from").jsonPrimitive.long; val to = c.getValue("to").jsonPrimitive.long
            metrics.forEachIndexed { i, m ->
                val r = AnalyticsCalc.cumulativeReps(data.events, m, from, to)
                val e = c.getValue("reps").jsonArray[i].jsonObject
                assertEquals("$name/${m.id} total", e.getValue("total").d(), r.total, eps)
                assertEquals("$name/${m.id} taps", e.getValue("taps").jsonPrimitive.int, r.taps)
                assertEquals("$name/${m.id} missing", e.getValue("missingTapPts").jsonPrimitive.int, r.missingTapPts)
                assertEquals("$name/${m.id} activity", e.getValue("activityDays").jsonPrimitive.int, r.activityDays)
                assertEquals("$name/${m.id} matched", e.getValue("matched").jsonPrimitive.int, r.matched)
                assertEquals("$name/${m.id} byTask", e.getValue("byTask").jsonObject.mapValues { it.value.d() }, r.byTask.toMap())
                assertDayVals("$name/${m.id} series", c.getValue("series").jsonArray[i].jsonArray, AnalyticsCalc.repsSeries(data.events, m, from, to, zone))
                assertLabelVals("$name/${m.id} week", c.getValue("breakdownWeek").jsonArray[i].jsonArray, AnalyticsCalc.breakdown(data.events, m, from, to, "week", zone))
                assertLabelVals("$name/${m.id} month", c.getValue("breakdownMonth").jsonArray[i].jsonArray, AnalyticsCalc.breakdown(data.events, m, from, to, "month", zone))
            }
            val cmp = AnalyticsCalc.compareSeries(data.events, metrics, from, to, zone)
            val ec = c.getValue("compare").jsonObject
            assertEquals("$name compare days", ec.getValue("days").jsonArray.map { it.jsonPrimitive.long }, cmp.days)
            metrics.forEach { m ->
                assertEquals("$name compare ${m.id}", ec.getValue("per").jsonObject.getValue(m.id).jsonObject.map { it.key.toLong() to it.value.d() }.toMap(), cmp.per.getValue(m.id))
            }
        }
    }

    @Test
    fun `milestones, rollups and streaks (all-time)`() {
        metrics.forEachIndexed { i, m ->
            val e = golden.getValue("milestones").jsonArray[i].jsonObject
            val a = AnalyticsCalc.milestones(data.events, m, now, zone)
            assertEquals("${m.id} total", e.getValue("total").d(), a.total, eps)
            assertEquals("${m.id} taps", e.getValue("taps").jsonPrimitive.int, a.taps)
            val big = e.getValue("biggestDay").jsonObject
            assertEquals("${m.id} big v", big.getValue("v").d(), a.biggestV, eps)
            assertEquals("${m.id} big d", (big["d"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long, a.biggestDay?.d)
            assertEquals("${m.id} activity", e.getValue("activityDays").jsonPrimitive.int, a.activityDays)
            assertEquals("${m.id} repDays", e.getValue("repDays").jsonPrimitive.int, a.repDays)
            assertEquals("${m.id} first", (e["firstDate"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long, a.firstDate)
            assertLabelVals("${m.id} rollup", golden.getValue("rollup").jsonArray[i].jsonArray, AnalyticsCalc.rollup(data.events, m, "year", now, zone))
        }
        assertEquals(golden.getValue("streaks").jsonArray.map { it.jsonObject.s("title") to it.jsonObject.getValue("streak").jsonPrimitive.int }, AnalyticsCalc.streaks(tasks))
    }

    @Test
    fun `adherence, intensity and the heatmap cells`() {
        for ((name, _) in cases()) {
            val c = golden.getValue("cases").jsonObject.getValue(name).jsonObject
            val from = c.getValue("from").jsonPrimitive.long; val to = c.getValue("to").jsonPrimitive.long
            val adh = AnalyticsCalc.adherence(data.events, from, to, zone)
            val ea = c.getValue("adherence").jsonArray
            assertEquals("$name adh size", ea.size, adh.size)
            ea.forEachIndexed { i, e ->
                val o = e.jsonObject
                assertEquals(o.getValue("d").jsonPrimitive.long, adh[i].d); assertEquals(o.getValue("pct").d(), adh[i].pct, eps)
                assertEquals(o.getValue("done").jsonPrimitive.int, adh[i].done); assertEquals(o.getValue("tot").jsonPrimitive.int, adh[i].tot)
            }
            val inten = AnalyticsCalc.intensity(data.events, from, to, zone)
            assertEquals("$name intensity", c.getValue("intensity").jsonObject.map { it.key.toLong() to it.value.d() }.toMap(), inten)
            val maxI = maxOf(1.0, inten.values.maxOrNull() ?: 0.0)
            val cols = heatColumns(AnalyticsCalc.heatmapCells(from, to, inten, maxI, zone)).map { col -> col.map { it.color to it.tip } }
            val exp = c.getValue("heat").jsonArray.map { col -> col.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content } }
            assertEquals("$name heat", exp, cols)
        }
    }

    private fun htmlCards(html: String): List<Triple<String, String, String>> =
        Regex("""<div class="k">([^<]*)</div><div class="v">([^<]*)</div><div class="sub">([^<]*)</div>""").findAll(html).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()

    @Test
    fun `every view renders Opti's bucket - all sources, groups and charts`() {
        for ((name, pc) in cases()) {
            val c = golden.getValue("cases").jsonObject.getValue(name).jsonObject
            val from = c.getValue("from").jsonPrimitive.long; val to = c.getValue("to").jsonPrimitive.long
            val p = prefsOf(pc)
            c.getValue("views").jsonArray.forEach { ve ->
                val o = ve.jsonObject
                val v = views.first { it.id == o.s("id") }
                val msg = "$name/${v.id}(${v.source}/${v.group}/${v.chart})"
                if (v.source == "overview") {
                    val cards = (AnalyticsCalc.renderView(v, data, p, from, to, now, zone) as ViewBody.Cards).cards
                    assertEquals(msg, htmlCards(o.s("html")!!), cards.map { Triple(it.k, it.v, it.sub) })
                    return@forEach
                }
                if (v.source == "tagsummary") {
                    val ts = AnalyticsCalc.renderView(v, data, p, from, to, now, zone) as ViewBody.TagSummary
                    val rows = Regex("""anListLbl">([^<]*)</span><span class="anListVal">([^<]*)</span>""").findAll(o.s("html")!!).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    assertEquals(msg, rows, ts.rows.map { it.name to "${it.count} items · ${it.comp} done · ${it.created} new" })
                    return@forEach
                }
                val b = o.getValue("bucket").jsonObject
                val act = if (v.source in AnalyticsCalc.V_SNAPSHOT) AnalyticsCalc.snapshotHistoryBucket(v, data, from, to, now, zone)
                    else AnalyticsCalc.bucket(AnalyticsCalc.sourceEvents(v, data, p), v.group, from, to, tags, zone)
                if (b.s("kind") == "day") assertDayVals(msg, b.getValue("series").jsonArray, (act as AnBucket.Day).series)
                else assertRows(msg, b.getValue("rows").jsonArray, (act as AnBucket.Cat).rows)
            }
        }
    }

    @Test
    fun `view meta labels`() {
        val p = AnPrefs(activeMetric = "m1")
        assertEquals(golden.getValue("metaLabels").jsonArray.map { it.jsonPrimitive.content }, views.map { AnalyticsCalc.viewMetaLabel(it, metrics, p) })
    }

    @Test
    fun `svgBars, svgSpark and svgCompare geometry equals Opti's path data`() {
        val bars = golden.getValue("bars").jsonPrimitive.content
        val expD = Regex("""class="bar" data-i="\d+" d="([^"]+)"""").findAll(bars).map { it.groupValues[1] }.toList()
        assertEquals(expD, AnalyticsCalc.barsGeom(listOf(3.0, 0.0, 0.01, 12.0)).map { it.d() })
        assertTrue(bars.contains("data-tip=\"📊 a\n🔁 3 reps\""))
        assertEquals("📊 a\n🔁 3 reps", AnalyticsCalc.barTip("a", 3.0, Locale.US))

        val spark = golden.getValue("spark").jsonPrimitive.content
        val sd = Regex(""" d="([^"]+)"""").findAll(spark).map { it.groupValues[1] }.toList()
        val g = AnalyticsCalc.sparkGeom(listOf(2.0, 5.0, 0.0), cumulative = true, h = 70)!!
        assertEquals(listOf(g.areaD(), g.lineD()), sd)
        val s1 = Regex(""" d="([^"]+)"""").findAll(golden.getValue("spark1").jsonPrimitive.content).map { it.groupValues[1] }.toList()
        val g1 = AnalyticsCalc.sparkGeom(listOf(2.0), cumulative = false, h = 60)!!
        assertEquals(listOf(g1.areaD(), g1.lineD()), s1)

        val cmpSvg = golden.getValue("compare").jsonPrimitive.content
        val cd = Regex("""<path d="([^"]+)" fill="none" stroke="([^"]+)"""").findAll(cmpSvg).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val from = java.time.LocalDate.of(2026, 9, 1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lines = AnalyticsCalc.compareGeom(AnalyticsCalc.compareSeries(data.events, metrics, from, now, zone))!!
        assertEquals(cd, lines.map { (color, pts) -> pts.mapIndexed { i, (x, y) -> (if (i > 0) "L" else "M") + AnalyticsCalc.fixed1(x) + " " + AnalyticsCalc.fixed1(y) + " " }.joinToString("") to color })
    }

    @Test
    fun `renderView degrades a category bucket's line or heatmap chart to bars, and a day list is newest first`() {
        val c = golden.getValue("cases").jsonObject.getValue("90d").jsonObject
        val from = c.getValue("from").jsonPrimitive.long; val to = c.getValue("to").jsonPrimitive.long
        val p = AnPrefs(activeMetric = "m1")
        assertTrue(AnalyticsCalc.renderView(views.first { it.id == "v17" }, data, p, from, to, now, zone) is ViewBody.Bars)   // week + line
        assertTrue(AnalyticsCalc.renderView(views.first { it.id == "v18" }, data, p, from, to, now, zone) is ViewBody.Bars)   // month + heatmap
        assertTrue(AnalyticsCalc.renderView(views.first { it.id == "v12" }, data, p, from, to, now, zone) is ViewBody.Heatmap)
        val list = AnalyticsCalc.renderView(views.first { it.id == "v16" }, data, p, from, to, now, zone) as ViewBody.ListRows
        assertEquals(list.rows.map { it.label }.sortedDescending(), list.rows.map { it.label })
        // streaks + list + tag group: the per-daily streak list
        val st = AnalyticsCalc.renderView(views.first { it.id == "v10" }, data, p, from, to, now, zone) as ViewBody.ListRows
        assertEquals(listOf("Meditate" to 10.0, "Morning run" to 4.0), st.rows.map { it.label to it.v })
        // the empty-state texts
        assertEquals(ViewBody.Note("No tags yet. Add tags to tasks (in the task editor) to see per-tag analytics."),
            AnalyticsCalc.renderView(views.first { it.id == "v02" }, data.copy(tags = emptyList()), p, from, to, now, zone))
    }
}
