package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.events.EvtUploadPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * CR-KT-013: local event pruning, parity with Opti `pruneEvents` (app.js:2043-2074) as confirmed
 * in RE-PWA-011. Golden fixture: `fixtures/CR-KT-013/prune.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventPrunerTest {

    private lateinit var db: AppDatabase

    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("fixtures/CR-KT-013/prune.json")!!.readText()
        ).jsonObject
    }

    @Before
    fun setUp() {
        db = newDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun scenario(name: String): JsonObject =
        fixture["scenarios"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    private suspend fun load(d: AppDatabase, s: JsonObject) {
        s["events"]!!.jsonArray.map { it.jsonObject }.forEach { e ->
            d.eventLogDao().insertLog(
                EventLogEntity(
                    id = e["id"]!!.jsonPrimitive.long,
                    uid = e["uid"]!!.jsonPrimitive.content,
                    timestamp = e["ts"]!!.jsonPrimitive.long,
                    kind = e["kind"]!!.jsonPrimitive.content,
                    synthetic = e["synthetic"]!!.jsonPrimitive.boolean,
                )
            )
        }
    }

    private fun keptRows(d: AppDatabase): List<Pair<Long, String?>> {
        val out = mutableListOf<Pair<Long, String?>>()
        d.openHelper.readableDatabase.query("SELECT id, uid FROM event_logs ORDER BY id ASC").use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        }
        return out
    }

    private fun runScenario(name: String) = runBlocking {
        val s = scenario(name)
        load(db, s)
        EventPruner(
            db,
            now = { fixture["now"]!!.jsonPrimitive.long },
            ageLimitMs = fixture["ageLimitMs"]!!.jsonPrimitive.long,
            cap = s["cap"]!!.jsonPrimitive.int,
        ).prune()
        val rows = keptRows(db)
        assertEquals(name, s["expectedKeptIds"]!!.jsonArray.map { it.jsonPrimitive.long }, rows.map { it.first })
        assertEquals(name, s["expectedKeptUids"]!!.jsonArray.map { it.jsonPrimitive.content }, rows.map { it.second })
    }

    @Test fun fixture_constantsMatchKotlin() {
        assertEquals(EvtUploadPolicy.EVENT_AGE_LIMIT_MS, fixture["ageLimitMs"]!!.jsonPrimitive.long)
        assertEquals(18L * 30L * 86_400_000L, EvtUploadPolicy.EVENT_AGE_LIMIT_MS)
        assertEquals(EventPruner.EVENT_HARD_CAP, fixture["productionCap"]!!.jsonPrimitive.int)
        assertEquals(200_000, EventPruner.EVENT_HARD_CAP)
    }

    @Test fun fixture_ageOnly_productionCap() = runScenario("age_only_production_cap")

    @Test fun fixture_capAfterAge_smallCap() = runScenario("cap_after_age_small_cap")

    @Test fun fixture_countEqualsCap_smallCap() = runScenario("count_equals_cap_small_cap")

    @Test fun defaults_useProductionLimits() = runBlocking {
        // Default ageLimitMs / cap: only the age pass deletes a 3-row store.
        val now = fixture["now"]!!.jsonPrimitive.long
        val cutoff = fixture["cutoff"]!!.jsonPrimitive.long
        db.eventLogDao().insertLog(EventLogEntity(id = 1, uid = "x1", timestamp = cutoff - 1, kind = "complete"))
        db.eventLogDao().insertLog(EventLogEntity(id = 2, uid = "x2", timestamp = cutoff, kind = "complete"))
        db.eventLogDao().insertLog(EventLogEntity(id = 3, uid = "x3", timestamp = now, kind = "complete"))
        EventPruner(db, now = { now }).prune()
        assertEquals(listOf(2L, 3L), keptRows(db).map { it.first })
    }

    /** Every table except event_logs, as text. The prune must not change any of them. */
    private fun otherTablesSnapshot(d: AppDatabase): Map<String, String> {
        val sql = d.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name != 'event_logs' ORDER BY name").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        return tables.associateWith { t ->
            val sb = StringBuilder()
            sql.query("SELECT * FROM `$t`").use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) sb.append(if (c.isNull(i)) "null" else c.getString(i)).append('|')
                    sb.append('\n')
                }
            }
            sb.toString()
        }
    }

    @Test fun prune_writesNoTombstone_noSyncRow_noLogEvent() = runBlocking {
        val s = scenario("cap_after_age_small_cap")
        load(db, s)
        db.tombstoneDao().insertTombstone(TombstoneEntity(id = "pre-existing", at = 1L))
        val before = otherTablesSnapshot(db)
        EventPruner(db, now = { fixture["now"]!!.jsonPrimitive.long }, cap = s["cap"]!!.jsonPrimitive.int).prune()
        assertEquals("no other table changes (tombstones, sync_base, devices, ...)", before, otherTablesSnapshot(db))
        // No prune log event: the store holds exactly the kept rows, nothing new.
        assertEquals(s["expectedKeptIds"]!!.jsonArray.size, keptRows(db).size)
        assertEquals(listOf("pre-existing"), db.tombstoneDao().getAllTombstonesSync().map { it.id })
    }

    @Test fun trigger_runsOncePerSession() = runBlocking {
        val trigger = EventPruneTrigger()
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = trigger.scheduleOnce(scope) { calls.incrementAndGet() }
        val second = trigger.scheduleOnce(scope) { calls.incrementAndGet() }
        assertNotNull(first)
        assertNull(second)
        first!!.join()
        assertEquals(1, calls.get())
    }

    @Test fun trigger_swallowsErrors() = runBlocking {
        val handlerSaw = AtomicInteger(0)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> handlerSaw.incrementAndGet() }
        )
        val job = EventPruneTrigger().scheduleOnce(scope) { throw IllegalStateException("boom") }!!
        job.join()
        assertTrue("job completes normally", job.isCompleted && !job.isCancelled)
        assertEquals("exception never reaches the scope", 0, handlerSaw.get())
    }

    @Test fun trigger_swallowsDbErrors_realPruner() = runBlocking {
        val closed = newDb()
        closed.close()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> throw AssertionError("leaked") }
        )
        val job = EventPruneTrigger().scheduleOnce(scope) { EventPruner(closed, now = { 0L }).prune() }!!
        job.join()
        assertTrue(job.isCompleted && !job.isCancelled)
    }

    @Test fun debugClockOffset_skipsPrune() = runBlocking {
        val s = scenario("cap_after_age_small_cap")
        load(db, s)
        val all = keptRows(db)
        EventPruner(
            db, now = { fixture["now"]!!.jsonPrimitive.long }, cap = s["cap"]!!.jsonPrimitive.int,
            clockOffsetMs = { 86_400_000L },
        ).prune()
        assertEquals("a debug clock offset deletes nothing", all, keptRows(db))
    }

    @Test fun ageDeleteThrows_capStepSkipped_noExceptionEscapes() = runBlocking {
        val s = scenario("cap_after_age_small_cap")
        load(db, s)
        val all = keptRows(db)
        val capCalls = AtomicInteger(0)
        val real = db.eventLogDao()
        val failing = object : com.example.data.local.dao.EventLogDao by real {
            override suspend fun deleteOlderThan(cutoff: Long): Int = throw IllegalStateException("age pass failed")
            override suspend fun countAll(): Int { capCalls.incrementAndGet(); return real.countAll() }
            override suspend fun deleteOldest(n: Int): Int { capCalls.incrementAndGet(); return real.deleteOldest(n) }
        }
        val leaked = AtomicInteger(0)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> leaked.incrementAndGet() }
        )
        val job = EventPruneTrigger().scheduleOnce(scope) {
            EventPruner(
                db, now = { fixture["now"]!!.jsonPrimitive.long }, cap = s["cap"]!!.jsonPrimitive.int, dao = failing,
            ).prune()
        }!!
        job.join()
        assertTrue(job.isCompleted && !job.isCancelled)
        assertEquals("no exception escapes", 0, leaked.get())
        assertEquals("cap step does not run", 0, capCalls.get())
        assertEquals("nothing deleted", all, keptRows(db))
    }
}
