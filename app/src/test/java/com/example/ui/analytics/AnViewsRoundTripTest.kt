package com.example.ui.analytics

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.sync.CanonicalJson
import com.example.data.sync.LocalWireState
import com.example.data.sync.SerializationEngine
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncSubset
import com.example.data.sync.SyncSubsetGoldenTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Shared Room + VM setup for the WP-8.20 view-model tests. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AnalyticsVmTestBase {
    protected lateinit var db: AppDatabase
    protected lateinit var repo: RpgRepository
    protected lateinit var hlc: DefaultHlcClock
    protected lateinit var vm: AnalyticsViewModel
    protected var clock = 1_800_000_000_000L
    protected val store = InMemoryAnPrefsStore()

    @Before
    fun setUpBase() {
        AppBootQuiesce.quiesce()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(same).setTransactionExecutor(same).build()
        hlc = DefaultHlcClock(0L) { }
        repo = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(), db.tagDao(), db.deviceDao(),
            db.charHistoryDao(), db.tombstoneDao(), db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            SyncDataStore(ctx), hlc = hlc
        )
    }

    protected fun newVm(): AnalyticsViewModel =
        AnalyticsViewModel(repo, store, nowMs = { clock }, zone = { java.time.ZoneId.of("Europe/Bratislava") }, appVersion = "vTEST").also { vm = it }

    @After
    fun tearDownBase() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
        AppBootQuiesce.quiesce()
    }

    protected fun idle() = runBlocking { vm.awaitWrites() }

    /** The `an` object Opti's `syncSubset()` would carry for the current Room state (after the queued writes). */
    protected fun wireAn(): JsonObject = runBlocking {
        if (::vm.isInitialized) vm.awaitWrites()
        val wire = LocalWireState.fromEntities(null, emptyList(), emptyList(), emptyList(), emptyList(), 0, JsonArray(emptyList()), emptyList(),
            repo.getAllCustomViewsSync(), SerializationEngine.parseJsonArray(repo.getMetricsExtrasJson()), null, emptyList(), emptyList())
        SyncSubset.build(wire).getValue("an").jsonObject
    }

    /** An `updatedAt` / tombstone stamp that came from the HLC during the call. */
    protected fun assertHlc(before: Long, stamp: Long) {
        assertTrue("stamp $stamp after $before", stamp > before)
        assertTrue("stamp $stamp issued by the HLC (last ${hlc.lastIssued.value})", stamp <= hlc.lastIssued.value)
    }

    protected fun seedG1(name: String): JsonObject = runBlocking {
        val an = Json.parseToJsonElement(SyncSubsetGoldenTest.fixture("G1/$name/state.json")).jsonObject.getValue("state").jsonObject.getValue("an").jsonObject
        an.getValue("views").jsonArray.forEach { repo.insertCustomView(SerializationEngine.parseViewEntity(it.jsonObject)) }
        repo.saveMetricsExtrasJson(SerializationEngine.encodeJsonArray(an.getValue("metrics").jsonArray))
        an
    }
}

/**
 * WP-8.20 (PX-110): `an.views` / `an.metrics` keep Opti's wire format. Opening the tab writes
 * nothing (the round trip equals G1), and each edit leaves the object Opti's saveView /
 * saveMetricEditor / importViews would leave, stamped from the HLC; deletes write a tombstone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnViewsRoundTripTest : AnalyticsVmTestBase() {

    @Test
    fun `opening the tab leaves every G1 an unchanged`() {
        for (name in SyncSubsetGoldenTest.CASES) {
            runBlocking { repo.getAllCustomViewsSync().forEach { repo.deleteCustomViewById(it.id) } }
            val expected = seedG1(name)
            newVm().apply { reloadMetrics(); normalize(); selectSnap("30d") }
            idle()
            assertEquals(name, CanonicalJson.stringify(expected), CanonicalJson.stringify(wireAn()))
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `editing a view keeps its keys and order and stamps updatedAt from the HLC`() {
        val g1 = seedG1("typical").getValue("views").jsonArray[0].jsonObject
        newVm()
        val draft = vm.editDraft("gx-view-01")!!
        idle(); val before = hlc.lastIssued.value
        assertTrue(vm.saveView(draft.copy(name = "Weekly 2", chart = "line")))
        val out = wireAn().getValue("views").jsonArray.single().jsonObject
        assertEquals(g1.keys.toList(), out.keys.toList())
        assertEquals(JsonPrimitive("Weekly 2"), out["name"])
        assertEquals(JsonPrimitive("line"), out["chart"])
        assertEquals(g1["createdAt"], out["createdAt"])
        assertHlc(before, out.getValue("updatedAt").jsonPrimitive.long)
        for (k in listOf("id", "source", "group", "tags", "types", "metricId")) assertEquals(k, g1[k], out[k])
    }

    @Test
    fun `a new view is Opti's saveView object - no metricId unless the source was set to metric`() {
        seedG1("typical")
        newVm()
        idle(); val before = hlc.lastIssued.value
        assertTrue(vm.saveView(ViewDraft(name = "  Done per day  ")))
        val views = wireAn().getValue("views").jsonArray.map { it.jsonObject }
        val v = views.last()
        assertEquals(listOf("id", "name", "source", "group", "chart", "tags", "types", "updatedAt", "createdAt"), v.keys.toList())
        assertEquals(JsonPrimitive("Done per day"), v["name"])
        assertEquals(JsonPrimitive(clock), v["createdAt"])
        assertHlc(before, v.getValue("updatedAt").jsonPrimitive.long)

        // Opti vSet('source','metric') adds the key even with no metric to point at.
        clock += 1000
        val md = vm.draftSet(ViewDraft(name = "M"), "source", "metric")
        assertTrue(md.metricKey)
        assertTrue(vm.saveView(md))
        val m = wireAn().getValue("views").jsonArray.last().jsonObject
        assertTrue(m.containsKey("metricId"))
        assertEquals(listOf("id", "name", "source", "group", "chart", "tags", "types", "metricId", "updatedAt", "createdAt"), m.keys.toList())
    }

    @Test
    fun `deleting a view writes an HLC tombstone and drops it from the wire`() = runBlocking {
        seedG1("typical")
        newVm()
        idle(); val before = hlc.lastIssued.value
        vm.deleteView("gx-view-01")
        assertEquals(0, wireAn().getValue("views").jsonArray.size)
        val t = repo.getAllTombstonesSync().single { it.id == "gx-view-01" }
        assertHlc(before, t.at)
    }

    @Test
    fun `metrics - create, edit keeps unknown keys and order, delete tombstones`() = runBlocking {
        repo.saveMetricsExtrasJson("""[{"id":"m1","name":"Kliky","keyword":"klik","zz":{"keep":1},"createdAt":5,"updatedAt":6}]""")
        newVm(); vm.reloadMetrics()
        assertTrue(vm.saveMetric(MetricDraft(name = " Zdvihy ", keyword = " zdvih ")))
        idle()
        var ms = wireAn().getValue("metrics").jsonArray.map { it.jsonObject }
        val nm = ms[1]
        assertEquals(listOf("id", "name", "keyword", "exact", "habits", "createdAt", "updatedAt"), nm.keys.toList())
        assertEquals(JsonPrimitive("Zdvihy"), nm["name"]); assertEquals(JsonPrimitive("zdvih"), nm["keyword"])
        assertEquals(JsonPrimitive(false), nm["exact"]); assertEquals(JsonArray(emptyList()), nm["habits"])
        assertEquals(nm.getValue("id").jsonPrimitive.content, vm.prefs.value.activeMetric)

        idle(); val before = hlc.lastIssued.value
        assertTrue(vm.saveMetric(MetricDraft(mid = "m1", name = "Kliky", keyword = "old", exact = true, habits = listOf("h1" to "", "h2" to "5"))))
        ms = wireAn().getValue("metrics").jsonArray.map { it.jsonObject }
        val e = ms[0]
        assertEquals(listOf("id", "name", "keyword", "zz", "createdAt", "updatedAt", "exact", "habits"), e.keys.toList())
        assertEquals("""[{"id":"h1","reps":null},{"id":"h2","reps":5}]""", CanonicalJson.stringify(e.getValue("habits")))
        assertEquals(JsonPrimitive(5), e["createdAt"])
        assertHlc(before, e.getValue("updatedAt").jsonPrimitive.long)
        assertEquals("""{"keep":1}""", CanonicalJson.stringify(e.getValue("zz")))

        val b2 = hlc.lastIssued.value
        vm.deleteMetric("m1")
        assertEquals(1, wireAn().getValue("metrics").jsonArray.size)
        assertHlc(b2, repo.getAllTombstonesSync().single { it.id == "m1" }.at)
    }

    @Test
    fun `metric validation toasts match Opti and save nothing`() = runBlocking {
        repo.saveMetricsExtrasJson("[]")
        newVm(); vm.reloadMetrics(); idle()
        assertFalse(vm.saveMetric(MetricDraft(name = " ")))
        assertEquals("Name required", vm.messages.value)
        assertFalse(vm.saveMetric(MetricDraft(name = "A", keyword = "  ")))
        assertEquals("Keyword required", vm.messages.value)
        assertFalse(vm.saveMetric(MetricDraft(name = "A", exact = true)))
        assertEquals("Pick at least one habit", vm.messages.value)
        assertFalse(vm.saveView(ViewDraft(name = "")))
        assertEquals("Name required", vm.messages.value)
        assertEquals("[]", repo.getMetricsExtrasJson())
    }

    @Test
    fun `import writes Opti's importViews objects with new ids and caps at 20`() {
        seedG1("typical")
        newVm()
        val file = """{"_questaViews":1,"views":[{"id":"x","name":"A","source":"reps","tags":["t"]},{"name":"skip"},{"source":"completed","metricId":""}]}"""
        assertEquals(2 to 0, vm.importViewsJson(file))
        val views = wireAn().getValue("views").jsonArray.map { it.jsonObject }
        assertEquals(3, views.size)
        val a = views.single { it["name"] == JsonPrimitive("A") }
        assertEquals(listOf("id", "name", "source", "group", "chart", "tags", "types", "metricId", "createdAt", "updatedAt"), a.keys.toList())
        assertFalse(a["id"] == JsonPrimitive("x"))
        assertEquals(JsonPrimitive("day"), a["group"]); assertEquals(JsonPrimitive("line"), a["chart"]); assertEquals(JsonNull, a["metricId"])
        assertEquals(1, views.count { it["name"] == JsonPrimitive("Imported view") })
        assertNull(vm.importViewsJson("not json"))
        assertNull(vm.importViewsJson("""{"views":[]}"""))

        val many = "[" + (1..30).joinToString(",") { """{"name":"V$it","source":"reps"}""" } + "]"
        assertEquals(17 to 13, vm.importViewsJson(many))
        assertEquals(20, wireAn().getValue("views").jsonArray.size)
    }

    @Test
    fun `export is Opti's _questaViews payload`() {
        seedG1("typical")
        newVm()
        val out = Json.parseToJsonElement(vm.exportViewsJson()).jsonObject
        assertEquals(listOf("_questaViews", "appVersion", "exportedAt", "views"), out.keys.toList())
        assertEquals(JsonPrimitive(1), out["_questaViews"]); assertEquals(JsonPrimitive("vTEST"), out["appVersion"])
        assertTrue(out.getValue("exportedAt").jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        assertEquals(CanonicalJson.stringify(wireAn().getValue("views")), CanonicalJson.stringify(out.getValue("views")))
        assertTrue(vm.exportViewsJson().contains("\n  \"views\": ["))
    }

    private fun restart() {
        vm.viewModelScope.cancel()
        newVm(); vm.reloadMetrics(); idle()
    }

    @Test
    fun `review 36 - a view on a default metric keeps a metric that is on the wire, across a restart`() = runBlocking {
        newVm(); vm.reloadMetrics(); idle()
        val md = vm.draftSet(ViewDraft(name = "Kliky view"), "source", "metric")
        val kliky = vm.data.value.metrics.first { it.name == "Kliky" }.id
        assertEquals(kliky, md.metricId)
        assertTrue(vm.saveView(md))
        val an = wireAn()
        val v = an.getValue("views").jsonArray.map { it.jsonObject }.single { it["name"] == JsonPrimitive("Kliky view") }
        assertEquals(JsonPrimitive(kliky), v["metricId"])
        val wireMetrics = an.getValue("metrics").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Kliky", "Zdvihy"), wireMetrics.map { it.getValue("name").jsonPrimitive.content })
        assertTrue("the view's metric is in the synced an.metrics", wireMetrics.any { it["id"] == JsonPrimitive(kliky) })

        restart()
        val d = vm.data.value
        val view = d.views.single { it.name == "Kliky view" }
        assertEquals(kliky, AnalyticsCalc.viewMetric(view, d.metrics, vm.prefs.value)?.id)
        assertEquals(CanonicalJson.stringify(an), CanonicalJson.stringify(wireAn()))
    }

    @Test
    fun `review 36 - the active default metric survives a restart`() = runBlocking {
        newVm(); vm.reloadMetrics(); idle()
        val zdvihy = vm.data.value.metrics.first { it.name == "Zdvihy" }.id
        vm.selectMetric(zdvihy); idle()
        assertTrue(wireAn().getValue("metrics").jsonArray.any { it.jsonObject["id"] == JsonPrimitive(zdvihy) })
        restart()
        assertEquals("Zdvihy", AnalyticsCalc.activeMetric(vm.data.value.metrics, vm.prefs.value)?.name)
    }

    @Test
    fun `review 36 - default metric ids are stable before anything is written`() = runBlocking {
        newVm(); vm.reloadMetrics(); idle()
        val ids = vm.data.value.metrics.map { it.id }
        restart()
        assertEquals(ids, vm.data.value.metrics.map { it.id })
        assertNull(repo.getMetricsExtrasJson())
    }

    @Test
    fun `a fresh install shows Opti's default views and metrics without writing them`() = runBlocking {
        newVm(); vm.reloadMetrics(); idle()
        val d = vm.data.value
        assertEquals(listOf("Overview", "By tag", "Completions / day", "Created by tag", "Open items by type", "Activity heatmap"), d.views.map { it.name })
        assertEquals(listOf("overview" to "list", "tagsummary" to "bar", "completed" to "line", "created" to "bar", "incomplete" to "bar", "reps" to "heatmap"), d.views.map { it.source to it.chart })
        assertEquals(listOf("Kliky" to "klik", "Zdvihy" to "zdvih"), d.metrics.map { it.name to it.keyword })
        assertTrue(repo.getAllCustomViewsSync().isEmpty())
        assertNull(repo.getMetricsExtrasJson())

        // the first write keeps them (Opti persisted them on first open)
        vm.moveView(d.views[1].id, -1)
        idle()
        val rows = repo.getAllCustomViewsSync()
        assertEquals(6, rows.size)
        assertEquals(listOf("By tag", "Overview"), vm.prefs.value.viewOrder.take(2).map { id -> d.views.single { it.id == id }.name })
        // Room re-emits on its invalidation thread; the rendered list follows.
        val shown = kotlinx.coroutines.withTimeout(5000) { vm.data.first { v -> v.views.size == 6 && v.views[0].name == "By tag" } }
        assertEquals(listOf("By tag", "Overview"), shown.views.take(2).map { it.name })
        val w = wireAn().getValue("views").jsonArray.first().jsonObject
        assertEquals(listOf("id", "name", "source", "group", "chart", "tags", "types", "updatedAt"), w.keys.toList())
        assertNotNull(vm.data.value.views.firstOrNull())
    }
}
