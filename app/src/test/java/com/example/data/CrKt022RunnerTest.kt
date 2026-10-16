package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AppBootQuiesce
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.DataStoreCompletionBackfillStore
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.GrantedReward
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * CR-KT-022: `RpgRepository.backfillCompletionHistory()` end-to-end on a real Room database.
 * Drives the same shapes the golden fixture pins (fresh chain, cron-miss merge, dedup, todo,
 * `late:true` skip, same-millisecond `id` tiebreak), then checks: a changed row gets a fresh HLC
 * `updatedAt` and an unchanged one keeps its stamp, no `_gr`/`_histCreated`/`completedAt` touch
 * (add-only), the character is untouched,
 * the run signals one local save (so the next sync carries the repaired history), and a second
 * run is a no-op (idempotent re-run after sync).
 *
 * Also pins the device-only flag store, the Android backup rules exclusion (the CR-KT-014
 * pattern: never synced, never exported), and the report policy the boot hook's stamp keys on:
 * an empty log reports `hadEvents = false`, so the volume never gets a boot-time flag write
 * until the log has content (the empty case the one-shot stamp deliberately skips).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrKt022RunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-10-16T00:00:00Z — the UTC day boundary all events stay on (with [t0] below). */
    private val nowMs = 1_792_144_800_000L

    /** 2026-10-16T01:00:00Z: every event is `t0 - n` so the whole run is inside one UTC day. */
    private val t0 = nowMs + 3_600_000

    private val all7 = List(7) { true }

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var store: SyncDataStore

    @Before
    fun setUp() {
        AppBootQuiesce.quiesce()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val same = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(same).setTransactionExecutor(same).build()
        store = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            store, database = db, hlc = DefaultHlcClock(1_000L) { }
        )
        runBlocking {
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

    private fun daily(id: String, updatedAt: Long, history: List<TaskHistoryPoint> = emptyList()) =
        TaskEntity(
            id = id, type = "daily", title = id, difficulty = "easy", value = 1.0,
            streak = 4, repeat = all7, history = history, createdAt = 1, updatedAt = updatedAt
        )

    private fun stored(id: String): TaskEntity = runBlocking { db.taskDao().getTaskByIdSync(id) }!!

    private fun log(timestamp: Long, kind: String, taskId: String, value: Double, payloadJson: String? = null) =
        runBlocking {
            repository.insertLog(
                EventLogEntity(
                    timestamp = timestamp, kind = kind, taskType = "daily", taskId = taskId,
                    taskTitle = taskId, value = value, payloadJson = payloadJson
                )
            )
        }

    @Test
    fun `runner restores the standing completions once, add-only, and a second run is a no-op`() {
        val miss = TaskHistoryPoint(date = t0 - 1_200_000, value = 1.4, completed = false, isDue = true, repeat = all7)
        val existing = TaskHistoryPoint(
            date = t0 - 1_800_000, value = 2.0, completed = true, isDue = true, repeat = all7,
            reward = GrantedReward(xp = 5, gold = 0.8, mp = 1, delta = 0.7)
        )
        runBlocking {
            repository.insertTask(daily("d1", updatedAt = 111))
            repository.insertTask(daily("d2", updatedAt = 222, history = listOf(miss)))
            repository.insertTask(daily("d3", updatedAt = 333, history = listOf(existing)))
            repository.insertTask(
                TaskEntity(id = "t1", type = "todo", title = "t1", difficulty = "easy", value = 1.0, createdAt = 1, updatedAt = 444)
            )
            repository.insertTask(daily("d4", updatedAt = 555))
            repository.insertTask(daily("d5", updatedAt = 666))
        }
        // d1: complete -> un-tick -> complete again: the last completion stands (1 point).
        log(t0 - 1_800_000, "complete", "d1", 1.9)
        log(t0 - 900_000, "uncomplete", "d1", 1.0)
        log(t0 - 600_000, "complete", "d1", 1.9)
        // d2: merges onto today's cron-miss point in place.
        log(t0, "complete", "d2", 2.4)
        // d3: same UTC day already carries a completed:true point -> never modified.
        log(t0 - 1_800_000, "complete", "d3", 2.0)
        // t1: a todo completion becomes a point; completedAt is NOT fabricated.
        log(t0 - 300_000, "complete", "t1", 1.5)
        // d4: creditYesterday's {late:true} row is skipped (it wrote its own backdated point).
        log(t0 - 1_000, "complete", "d4", 1.9, payloadJson = """{"reward":{"xp":5,"gold":0.9,"mp":1,"delta":0.9},"late":true}""")
        // d5: same-millisecond pair inserted [uncomplete, complete]: the id tiebreak leaves the completion standing.
        log(t0 - 2_000, "uncomplete", "d5", 1.0)
        log(t0 - 2_000, "complete", "d5", 1.7)

        val savedBefore = runBlocking { store.localSavedAt.first() }
        val report = runBlocking { repository.backfillCompletionHistory() }
        assertTrue("the scan saw the completion log", report.hadEvents)
        assertEquals("d1 + d2 + t1 + d5", 4, report.restored)

        val d1 = stored("d1")
        assertEquals(
            listOf(TaskHistoryPoint(date = t0 - 600_000, value = 1.9, completed = true, isDue = true, repeat = all7)),
            d1.history
        )
        val d2 = stored("d2")
        assertEquals(
            listOf(TaskHistoryPoint(date = t0, value = 2.4, completed = true, isDue = true, repeat = all7)),
            d2.history
        )
        assertEquals("deduped day is untouched", listOf(existing), stored("d3").history)
        val t1 = stored("t1")
        assertEquals(listOf(TaskHistoryPoint(date = t0 - 300_000, value = 1.5, completed = true)), t1.history)
        assertNull("the repair never fabricates completedAt", t1.completedAt)
        assertEquals("late row skipped", emptyList<TaskHistoryPoint>(), stored("d4").history)
        assertEquals(
            listOf(TaskHistoryPoint(date = t0 - 2_000, value = 1.7, completed = true, isDue = true, repeat = all7)),
            stored("d5").history
        )

        // A changed task gets a fresh HLC updatedAt (so the sync's whole-task LWW keeps the restored
        // points); an unchanged one keeps its stamp. No _gr/_histCreated/completedAt invention.
        assertTrue("d1 stamped", d1.updatedAt > 111L)
        assertTrue("d2 stamped", stored("d2").updatedAt > 222L)
        assertEquals("deduped d3 unchanged", 333L, stored("d3").updatedAt)
        assertTrue("t1 stamped", t1.updatedAt > 444L)
        assertEquals("late-skipped d4 unchanged", 555L, stored("d4").updatedAt)
        assertTrue("d5 stamped", stored("d5").updatedAt > 666L)
        listOf(d1, stored("d2"), stored("d3"), t1, stored("d4"), stored("d5")).forEach {
            assertNull(it.gr)
            assertNull(it.histCreated)
            assertNull(it.completedAt)
        }
        assertEquals(100, runBlocking { repository.getCharacterSync() }.xp)

        // The repair is a durable local write: the next sync round carries it (one save signal).
        assertTrue("runner stamped a local save", runBlocking { store.localSavedAt.first() } > savedBefore)

        // Idempotent re-run (the manual "re-repair after sync" path): nothing changes, no new save.
        val before = listOf("d1", "d2", "d3", "d4", "d5").associateWith { stored(it) }
        val savedAfterFirst = runBlocking { store.localSavedAt.first() }
        val rerun = runBlocking { repository.backfillCompletionHistory() }
        assertEquals(0, rerun.restored)
        assertTrue("the log still has rows", rerun.hadEvents)
        before.forEach { (id, t) -> assertEquals("$id unchanged on re-run", t, stored(id)) }
        assertEquals(savedAfterFirst, runBlocking { store.localSavedAt.first() })
    }

    @Test
    fun `backfill stamps a changed todo with a fresh HLC updatedAt and leaves an unchanged todo alone`() {
        val counted = TaskHistoryPoint(date = t0 - 1_800_000, value = 1.2, completed = true)
        runBlocking {
            repository.insertTask(
                TaskEntity(id = "tChanged", type = "todo", title = "tChanged", difficulty = "easy", value = 1.0, createdAt = 1, updatedAt = 777)
            )
            repository.insertTask(
                TaskEntity(id = "tSame", type = "todo", title = "tSame", difficulty = "easy", value = 1.0, history = listOf(counted), createdAt = 1, updatedAt = 888)
            )
        }
        log(t0 - 300_000, "complete", "tChanged", 1.5)
        log(t0 - 1_800_000, "complete", "tSame", 1.2) // its day already has a completed point

        val report = runBlocking { repository.backfillCompletionHistory() }
        assertEquals(1, report.restored)

        val changed = stored("tChanged")
        assertEquals(listOf(TaskHistoryPoint(date = t0 - 300_000, value = 1.5, completed = true)), changed.history)
        // Whole-task LWW (OptiSnapshotMerger, both changed): only a newer updatedAt keeps the point.
        assertTrue("changed todo gets a fresh HLC updatedAt", changed.updatedAt > 777L)
        assertEquals("unchanged todo keeps its updatedAt", 888L, stored("tSame").updatedAt)
        assertEquals(listOf(counted), stored("tSame").history)

        // A re-run changes nothing, so it stamps nothing.
        val stamp = changed.updatedAt
        runBlocking { repository.backfillCompletionHistory() }
        assertEquals(stamp, stored("tChanged").updatedAt)
    }

    // ---- the device-only flag store + the never-exported guarantees (CR-KT-014 pattern) ----

    @Test
    fun `empty completion log - restored 0 and hadEvents false, so the boot flag stays unset`() {
        val report = runBlocking { repository.backfillCompletionHistory() }
        assertEquals(0, report.restored)
        assertFalse("nothing logged: the hook leaves the one-shot flag unset and re-checks next boot", report.hadEvents)
    }

    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test
    fun `flag store round-trips`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("cb.preferences_pb").also { it.delete() } })
        val s = DataStoreCompletionBackfillStore(ds)
        assertFalse(s.hasRun())
        s.markRun()
        assertTrue(s.hasRun())
        assertEquals("questa_completion_backfill", DataStoreCompletionBackfillStore.FILE_NAME)
    }

    @Test
    fun `android backup rules exclude the store file`() {
        val path = "datastore/${DataStoreCompletionBackfillStore.FILE_NAME}.preferences_pb"
        val full = file("src/main/res/xml/backup_rules.xml").readText()
        val extraction = file("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(full.contains("<exclude domain=\"file\" path=\"$path\" />"))
        assertEquals("cloud-backup and device-transfer", 2,
            Regex(Regex.escape("<exclude domain=\"file\" path=\"$path\" />")).findAll(extraction).count())
    }

    @Test
    fun `no sync or export code reads the store`() {
        val dir = file("src/main/java/com/example/data/sync")
        val hits = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("CompletionBackfill") || it.contains(DataStoreCompletionBackfillStore.FILE_NAME) || it.contains("completion_backfill_done") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
