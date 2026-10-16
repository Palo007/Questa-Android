package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.DataStoreCompletionBackfillStore
import com.example.data.local.RepsReplayStore
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.sync.SyncDataStore
import com.example.ui.analytics.AnData
import com.example.ui.analytics.AnPrefs
import com.example.ui.analytics.AnView
import com.example.ui.analytics.AnalyticsCalc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * CR-KT-023 on a real Room database: [RpgRepository.addHabitReps] (the fixed bulk-reps path) and
 * [RpgRepository.backfillRepsHistory] (the one-shot replay). Pins: only THIS device's own
 * `habitReps` rows are replayed (pulled/imported rows never), add-only (updatedAt/cUp/cDown/value
 * untouched), one local save only when something changed, a re-run replays nothing, the fixed
 * path pins the boundary BEFORE its row so the replay never double counts it, the event row is
 * unchanged, and Analytics' reps metric now sees bulk reps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrKt023RunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 86_400_000L
    /** 2026-10-16T10:00:00Z. */
    private val nowMs = 1_792_144_800_000L
    private val myDev = "dev-mine0001"

    /** In-memory [RepsReplayStore] (the DataStore one is round-tripped separately). */
    private class MemStore : RepsReplayStore {
        var boundary: Long? = null
        var done = false
        override suspend fun pinBoundary(candidate: Long): Long = boundary ?: candidate.also { boundary = it }
        override suspend fun isReplayDone(): Boolean = done
        override suspend fun setReplayDone(done: Boolean) { this.done = done }
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var store: SyncDataStore
    private lateinit var replayStore: MemStore

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(same).setTransactionExecutor(same).build()
        store = SyncDataStore(context)
        replayStore = MemStore()
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            store, database = db, hlc = DefaultHlcClock(1_000L) { }, repsReplayStore = replayStore,
        )
        runBlocking {
            store.saveLocalDeviceId(myDev)
            repository.insertCharacter(
                CharacterEntity(lvl = 2, xp = 100, gold = 50.0, mp = 5, hp = 50.0, maxHp = 50.0, updatedAt = 0L)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        AppBootQuiesce.quiesce()
    }

    private fun habit(id: String, title: String, history: List<TaskHistoryPoint> = emptyList(), cUp: Int = 0, cDown: Int = 0) =
        TaskEntity(
            id = id, type = "habit", title = title, difficulty = "log", value = 0.0,
            cUp = cUp, cDown = cDown, history = history, createdAt = 1, updatedAt = 111
        )

    private fun stored(id: String): TaskEntity = runBlocking { db.taskDao().getTaskByIdSync(id) }!!

    /** The row the pre-fix MainViewModel.addHabitReps wrote (insertLog stamps uid/dev = this device). */
    private fun buggyRow(ts: Long, taskId: String, n: Int) = runBlocking {
        repository.insertLog(
            EventLogEntity(
                timestamp = ts, kind = "habitReps", direction = if (n > 0) 1 else -1,
                taskId = taskId, taskTitle = taskId, reps = kotlin.math.abs(n), value = 0.0
            )
        )
    }

    @Test
    fun `replay restores only this device's rows, add-only, once`() {
        val tap = TaskHistoryPoint(date = nowMs - day - 3_600_000, value = 0.0, reps = 1, repCounted = true, scored = false)
        runBlocking { repository.insertTask(habit("h1", "Pushups", listOf(tap), cUp = 13, cDown = 3)) }
        buggyRow(nowMs - day, "h1", 12)          // merges into the tap point (same UTC day, later)
        buggyRow(nowMs - 2 * day, "h1", -3)      // a new, earlier point
        runBlocking {
            // Pulled from the PWA (EventPuller) and from a PWA backup import: their points already exist.
            repository.insertImportedLog(EventLogEntity(timestamp = nowMs - 3 * day, kind = "habitReps", direction = 1, taskId = "h1", reps = 8, value = 0.0, uid = "u1", dev = "d-pwa"))
            repository.insertImportedLog(EventLogEntity(timestamp = nowMs - 4 * day, kind = "habitReps", direction = -1, taskId = "h1", reps = -2, value = 0.0, uid = "u2", dev = "d-pwa", imported = true))
        }

        val savedBefore = runBlocking { store.localSavedAt.first() }
        val report = runBlocking { repository.backfillRepsHistory() }
        assertEquals(RepsReplayReport(replayed = 2, tasks = 1), report)

        val h1 = stored("h1")
        assertEquals(
            listOf(
                TaskHistoryPoint(date = nowMs - 2 * day, value = 0.0, reps = -3, repCounted = true, scored = false),
                TaskHistoryPoint(date = nowMs - day, value = 0.0, reps = 13, repCounted = true, scored = false),
            ),
            h1.history
        )
        assertEquals("updatedAt untouched", 111L, h1.updatedAt)
        assertEquals("cUp untouched", 13, h1.cUp)
        assertEquals("cDown untouched", 3, h1.cDown)
        assertEquals(100, runBlocking { repository.getCharacterSync() }.xp)
        assertTrue("one local save", runBlocking { store.localSavedAt.first() } > savedBefore)
        assertTrue(replayStore.done)

        // Re-run (boot or Diagnostics): nothing, no save.
        val savedAfter = runBlocking { store.localSavedAt.first() }
        assertEquals(RepsReplayReport(0, 0), runBlocking { repository.backfillRepsHistory() })
        assertEquals(h1, stored("h1"))
        assertEquals(savedAfter, runBlocking { store.localSavedAt.first() })
    }

    @Test
    fun `nothing to replay - no save, flag set`() {
        runBlocking { repository.insertTask(habit("h2", "Squats")) }
        val savedBefore = runBlocking { store.localSavedAt.first() }
        assertEquals(RepsReplayReport(0, 0), runBlocking { repository.backfillRepsHistory() })
        assertEquals(savedBefore, runBlocking { store.localSavedAt.first() })
        assertTrue(replayStore.done)
    }

    @Test
    fun `fixed addHabitReps writes the point, keeps the event row, pins the boundary before its row`() {
        runBlocking { repository.insertTask(habit("h3", "Squats", cUp = 12)) }
        buggyRow(nowMs - 2 * day, "h3", 12)      // pre-fix row, id 1
        val out = runBlocking { repository.addHabitReps("h3", -4) }!!

        val h3 = stored("h3")
        assertEquals(12, h3.cUp)
        assertEquals(4, h3.cDown)
        assertEquals(1, h3.history.size)
        val p = h3.history.single()
        assertEquals(TaskHistoryPoint(date = p.date, value = 0.0, reps = -4, repCounted = true, scored = false), p)
        assertEquals(out.updatedAt, h3.updatedAt)
        assertTrue("HLC updatedAt bumped", h3.updatedAt > 111L)
        assertEquals("boundary pinned at MAX(id) before the fixed row", 1L, replayStore.boundary)

        val rows = runBlocking { db.eventLogDao().getHabitRepsUpToSync(Long.MAX_VALUE) }
        assertEquals(2, rows.size)
        val fixed = rows.single { it.id == 2L }
        assertEquals("habitReps", fixed.kind)
        assertEquals(-1, fixed.direction)
        assertEquals("|n| as before CR-KT-023", 4, fixed.reps)
        assertEquals(0.0, fixed.value!!, 0.0)
        assertEquals(myDev, fixed.dev)
        assertEquals(p.date, fixed.timestamp)

        // The replay restores only the pre-fix row; the fixed row's point is never doubled.
        assertEquals(RepsReplayReport(replayed = 1, tasks = 1), runBlocking { repository.backfillRepsHistory() })
        val after = stored("h3").history
        assertEquals(listOf(12, -4), after.map { it.reps })

        // Analytics' reps metric now sees bulk reps (it reads only history points).
        val tasks = listOf(stored("h3"))
        val metric = AnalyticsCalc.parseMetric(Json.parseToJsonElement("""{"id":"m","name":"Squats","keyword":"squats","exact":false,"habits":[]}""").jsonObject)
        val view = AnView(id = "v", name = "v", source = "metric", group = "day", chart = "bar", metricId = "m")
        val ev = AnalyticsCalc.sourceEvents(view, AnData(tasks = tasks, events = AnalyticsCalc.allEvents(tasks), metrics = listOf(metric)), AnPrefs())
        assertEquals(listOf(12.0, -4.0), ev.sortedBy { it.ts }.map { it.v })
    }

    @Test
    fun `replay first, then fixed rows stay above the boundary`() {
        runBlocking { repository.insertTask(habit("h4", "Dips")) }
        buggyRow(nowMs - day, "h4", 5)
        assertEquals(RepsReplayReport(1, 1), runBlocking { repository.backfillRepsHistory() })
        assertEquals(1L, replayStore.boundary)
        runBlocking { repository.addHabitReps("h4", 3) }
        assertEquals("the later pin keeps the first boundary", 1L, replayStore.boundary)
        replayStore.done = false // even a lost done flag cannot pick up the fixed row (id 2 > boundary 1)
        assertEquals(1, runBlocking { db.eventLogDao().getHabitRepsUpToSync(replayStore.boundary!!) }.size)
    }

    // ---- the device-only store (same file as CR-KT-022: excluded from backup and transfer) ----

    @Test
    fun `datastore store pins once and round-trips the done flag`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("rr.preferences_pb").also { it.delete() } })
        val s: RepsReplayStore = DataStoreCompletionBackfillStore(ds)
        assertEquals(7L, s.pinBoundary(7L))
        assertEquals("the first pin wins", 7L, s.pinBoundary(42L))
        assertFalse(s.isReplayDone())
        s.setReplayDone(true)
        assertTrue(s.isReplayDone())
        s.setReplayDone(false)
        assertFalse(s.isReplayDone())
    }

    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test
    fun `store file is excluded from backup and no sync code reads it`() {
        val path = "datastore/${DataStoreCompletionBackfillStore.FILE_NAME}.preferences_pb"
        assertTrue(file("src/main/res/xml/backup_rules.xml").readText().contains("<exclude domain=\"file\" path=\"$path\" />"))
        assertEquals(2, Regex(Regex.escape("<exclude domain=\"file\" path=\"$path\" />"))
            .findAll(file("src/main/res/xml/data_extraction_rules.xml").readText()).count())
        val hits = file("src/main/java/com/example/data/sync").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("RepsReplayStore") || it.contains("reps_replay") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
