package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.entities.QuickLogMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-8.2 (Wave 1) -- §5 card `Migration6to7Test`: "50 tasks, 1 char and 20 events holding every
 * field in extraJson/payloadJson: 0 rows lost, 100% backfilled, and streak null exactly where it
 * is absent." Extended for review r1 major 8: seeded from the WP-8.0 fixture
 * `fixtures/opti/G1/pause_abs_cAbs_doneDay_cResetOn/state.json` wherever it has directly-usable
 * task JSON, plus explicit stale-streak, malformed-JSON, and reminders:null/non-array cases.
 *
 * Uses the real `androidx.room.testing.MigrationTestHelper` (8.B CR-1, `plans/stage8/GRADLE_CR.md`),
 * which also validates the post-migration schema's identity hash against the committed
 * `app/schemas/com.example.data.local.AppDatabase/7.json` -- a hand-rolled comparison (the
 * pre-CR-1 `LegacyAppDatabaseV6` workaround) cannot catch a schema drift `MigrationTestHelper`
 * would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration6to7Test {

    companion object {
        private const val TEST_DB = "migration-6-7-test"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context().deleteDatabase(TEST_DB)
    }

    private fun insertV6Task(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        type: String = "habit",
        title: String = "T",
        streak: Int = 0,
        extraJson: String?
    ) {
        db.execSQL(
            "INSERT INTO tasks (id, type, title, notes, difficulty, value, done, streak, " +
                "checklist, tags, history, createdAt, updatedAt, cUp, cDown, extraJson, orderIndex) " +
                "VALUES (?, ?, ?, '', 'easy', 0, 0, ?, '[]', '[]', '[]', 1, 1, 0, 0, ?, 0)",
            arrayOf<Any?>(id, type, title, streak, extraJson)
        )
    }

    @Test
    fun `0 rows lost, 100 percent backfilled, streak null exactly where absent`() = kotlinx.coroutines.runBlocking {
        val dbV6 = helper.createDatabase(TEST_DB, 6)

        // --- Fixture-seeded rows (fixtures/opti/G1/pause_abs_cAbs_doneDay_cResetOn/state.json,
        // WP-8.0): each task object's own verbatim JSON becomes that row's extraJson, exactly as
        // SerializationEngine.parseTaskEntity's "unknown-field passthrough" convention stores it. ---

        // gx-habit-abs: extraJson has NO "streak" key at all -> must backfill to null. Also
        // carries a SCALAR "cAbs":3 (not the real device-keyed object shape) -- review r1 blocker
        // 4's malformed-shape case: must drop to null, not misread as a count.
        insertV6Task(
            dbV6, id = "gx-habit-abs", streak = 0,
            extraJson = """{"id":"gx-habit-abs","type":"habit","title":"Read","value":1,"done":false,"up":true,"down":false,"abs":12.5,"cAbs":3,"tags":[],"createdAt":1,"updatedAt":1790251200000}"""
        )

        // gx-daily-paused: extraJson DOES carry "streak":0 -- but the LIVE column is 5 (review r1
        // blocker 1's stale-streak case: a local completion since the extraJson snapshot was
        // taken advanced the real streak; extraJson's 0 is stale and must NOT win).
        insertV6Task(
            dbV6, id = "gx-daily-paused", type = "daily", streak = 5,
            extraJson = """{"id":"gx-daily-paused","type":"daily","title":"Stretch","value":0,"done":false,"doneDay":20260920,"cResetOn":20260920,"streak":0,"missedOn":20260921,"tags":[],"createdAt":1,"updatedAt":1790251200000}"""
        )

        // --- quickLogMode(t) cases (app.js:2408-2414): false | true | 'down' | 'both' | absent ---
        insertV6Task(dbV6, id = "ql-true", extraJson = """{"quickLog":true}""")
        insertV6Task(dbV6, id = "ql-down", extraJson = """{"quickLog":"down"}""")
        insertV6Task(dbV6, id = "ql-both", extraJson = """{"quickLog":"both"}""")
        insertV6Task(dbV6, id = "ql-false", extraJson = """{"quickLog":false}""")
        insertV6Task(dbV6, id = "ql-absent", extraJson = """{"notes":"no quickLog key here"}""")

        // --- reminders:null / non-array (review r1 major 8) ---
        insertV6Task(dbV6, id = "rem-null", extraJson = """{"reminders":null}""")
        insertV6Task(dbV6, id = "rem-nonarray", extraJson = """{"reminders":"oops"}""")
        insertV6Task(
            dbV6, id = "rem-valid",
            extraJson = """{"reminders":[{"id":"r1","time":"08:00","enabled":true}]}"""
        )

        // --- cAbs the REAL device-keyed object shape (sync.js:1859-1872) ---
        insertV6Task(
            dbV6, id = "cabs-object",
            extraJson = """{"cAbs":{"devA":{"ua":5,"cUp":2,"cDown":1}}}"""
        )

        // --- malformed JSON entirely (not valid syntax): treated exactly like "no extraJson" --
        // the live column is authoritative, nothing crashes.
        insertV6Task(dbV6, id = "malformed-json", streak = 9, extraJson = """{not: valid json!!!""")

        // --- extraJson genuinely NULL (a task created fresh in Kotlin, no PWA original at all) --
        insertV6Task(dbV6, id = "extraJson-null", streak = 7, extraJson = null)

        // review r2 minor 5: extraJson present but with NO "streak" key, and the live column is
        // NONZERO -- must be KEPT, not nulled (only a 0 column nulls on an absent key).
        insertV6Task(dbV6, id = "streak-absent-nonzero", streak = 4, extraJson = """{"notes":"no streak key"}""")
        // ...and the counterpart: extraJson present, no "streak" key, live column IS 0 -> null.
        insertV6Task(dbV6, id = "streak-absent-zero", streak = 0, extraJson = """{"notes":"no streak key"}""")

        // --- bulk volume coverage: 50 tasks with every backfillable field present ---
        for (i in 1..50) {
            insertV6Task(
                dbV6, id = "bulk-$i", streak = i,
                extraJson = """{"streak":$i,"up":${i % 3 == 0},"down":false,"quickLog":"both","completedAt":${1_700_000_000_000L + i},"doneDay":${20260101 + i},"cResetOn":${20260101 + i}}"""
            )
        }

        // --- character (1 row): the real device-keyed abs object shape (sync.js:1699-1712) ---
        dbV6.execSQL(
            "INSERT INTO character (id, name, face, cls, lvl, xp, hp, maxHp, mp, gold, " +
                "needsDeathAcknowledge, lastCron, dayStart, updatedAt, extraJson) VALUES " +
                "(1, 'A', 'f', 'c', 1, 0, 50, 50, 0, 0, 0, '', 0, 1, ?)",
            arrayOf<Any?>("""{"abs":{"devA":{"ua":10,"xp":100,"gold":5.5,"mp":2}}}""")
        )

        // --- events (20): payloadJson-driven imported/republish backfill, half with a payload ---
        for (i in 1..20) {
            val payload = if (i % 2 == 0) """{"imported":${i % 3 == 0},"republish":${i % 7 == 0}}""" else null
            dbV6.execSQL(
                "INSERT INTO event_logs (timestamp, kind, synthetic, payloadJson) VALUES (?, 'complete', 0, ?)",
                arrayOf<Any?>(1_700_000_000_000L + i, payload)
            )
        }

        dbV6.close()

        // --- Act: run the real production migration, validated against the committed 7.json ---
        helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)

        val migratedDb = Room.databaseBuilder(context(), AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        // --- Assert: 0 rows lost ---
        val tasks = migratedDb.taskDao().getAllTasksSync().associateBy { it.id }
        assertEquals(15 + 50, tasks.size) // 15 targeted + 50 bulk
        assertTrue(migratedDb.characterDao().getCharacterSync() != null)
        assertEquals(20, migratedDb.eventLogDao().getLogsInRangeSync(0L, Long.MAX_VALUE).size)

        // --- Fixture-seeded assertions ---
        val habitAbs = tasks.getValue("gx-habit-abs")
        assertNull("no streak key in extraJson -> null", habitAbs.streak)
        assertEquals(true, habitAbs.up)
        assertEquals(false, habitAbs.down)
        assertNull("cAbs:3 is a scalar, not the device-keyed object shape -> dropped", habitAbs.cAbs)

        val dailyPaused = tasks.getValue("gx-daily-paused")
        assertEquals("stale extraJson streak (0) must not overwrite the live column (5)", 5, dailyPaused.streak)
        assertEquals(20260920, dailyPaused.doneDay)
        assertEquals(20260920, dailyPaused.cResetOn)

        // --- quickLogMode(t) assertions ---
        assertEquals(QuickLogMode.UP, tasks.getValue("ql-true").quickLog)
        assertEquals(QuickLogMode.DOWN, tasks.getValue("ql-down").quickLog)
        assertEquals(QuickLogMode.BOTH, tasks.getValue("ql-both").quickLog)
        assertEquals("false present must round-trip as OFF, distinct from absent", QuickLogMode.OFF, tasks.getValue("ql-false").quickLog)
        assertNull("no quickLog key at all -> Room NULL (absent), not OFF", tasks.getValue("ql-absent").quickLog)

        // --- reminders assertions ---
        assertNull(tasks.getValue("rem-null").reminders)
        assertNull(tasks.getValue("rem-nonarray").reminders)
        assertEquals(1, tasks.getValue("rem-valid").reminders?.size)
        assertEquals("r1", tasks.getValue("rem-valid").reminders?.first()?.id)

        // --- cAbs real object shape ---
        val cAbsMap = tasks.getValue("cabs-object").cAbs
        assertEquals(1, cAbsMap?.size)
        assertEquals(2, cAbsMap?.get("devA")?.cUp)
        assertEquals(1, cAbsMap?.get("devA")?.cDown)

        // --- malformed JSON / null extraJson: streak unchanged, nothing crashes ---
        assertEquals(9, tasks.getValue("malformed-json").streak)
        assertEquals(7, tasks.getValue("extraJson-null").streak)

        // review r2 minor 5: an absent streak key only nulls a column that is already 0; a
        // nonzero live column is kept as-is.
        assertEquals("nonzero live streak must be kept when the key is absent", 4, tasks.getValue("streak-absent-nonzero").streak)
        assertNull("a 0 live streak with an absent key still nulls out", tasks.getValue("streak-absent-zero").streak)

        // --- bulk: 100% backfilled ---
        for (i in 1..50) {
            val t = tasks.getValue("bulk-$i")
            assertEquals(i, t.streak)
            assertEquals(i % 3 == 0, t.up)
            assertEquals(false, t.down)
            assertEquals(QuickLogMode.BOTH, t.quickLog)
            assertEquals(1_700_000_000_000L + i, t.completedAt)
            assertEquals(20260101 + i, t.doneDay)
            assertEquals(20260101 + i, t.cResetOn)
        }

        // --- character abs ---
        val char = migratedDb.characterDao().getCharacterSync()
        assertEquals(1, char?.abs?.size)
        assertEquals(100.0, char?.abs?.get("devA")?.xp ?: -1.0, 0.0001)
        assertEquals(5.5, char?.abs?.get("devA")?.gold ?: -1.0, 0.0001)

        // --- events: 100% backfilled ---
        val events = migratedDb.eventLogDao().getLogsInRangeSync(0L, Long.MAX_VALUE)
        for (e in events) {
            val idx = ((e.timestamp - 1_700_000_000_000L)).toInt()
            val hasPayload = idx % 2 == 0
            if (!hasPayload) {
                assertNull(e.imported)
                assertNull(e.republish)
            } else {
                assertEquals(idx % 3 == 0, e.imported)
                assertEquals(idx % 7 == 0, e.republish)
            }
        }

        migratedDb.close()
    }

    @Test
    fun `character abs malformed scalar shape backfills to null, not a guess`() = kotlinx.coroutines.runBlocking {
        val dbV6 = helper.createDatabase(TEST_DB, 6)
        dbV6.execSQL(
            "INSERT INTO character (id, name, face, cls, lvl, xp, hp, maxHp, mp, gold, " +
                "needsDeathAcknowledge, lastCron, dayStart, updatedAt, extraJson) VALUES " +
                "(1, 'A', 'f', 'c', 1, 0, 50, 50, 0, 0, 0, '', 0, 1, ?)",
            arrayOf<Any?>("""{"abs":12.5}""") // matches the WP-8.0 fixture's scalar shape
        )
        dbV6.close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        val migratedDb = Room.databaseBuilder(context(), AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        assertNull(migratedDb.characterDao().getCharacterSync()?.abs)
        migratedDb.close()
    }

    // WP-8.22-E review 43 F1: the migration copies the raw abs text, so a string "Infinity" loads
    // as Infinity in memory; the next write of that char must not throw, and stores 0 (Opti's
    // value after a reload: JSON.stringify(Infinity) is null, `_num(null)` is 0).
    @Test
    fun `character abs Infinity migrates, and the next char write stores 0 without throwing`() = kotlinx.coroutines.runBlocking {
        val dbV6 = helper.createDatabase(TEST_DB, 6)
        dbV6.execSQL(
            "INSERT INTO character (id, name, face, cls, lvl, xp, hp, maxHp, mp, gold, " +
                "needsDeathAcknowledge, lastCron, dayStart, updatedAt, extraJson) VALUES " +
                "(1, 'A', 'f', 'c', 1, 0, 50, 50, 0, 0, 0, '', 0, 1, ?)",
            arrayOf<Any?>("""{"abs":{"devA":{"ua":10,"xp":"Infinity","gold":5.5,"mp":2}}}""")
        )
        dbV6.close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        val migratedDb = Room.databaseBuilder(context(), AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        val char = migratedDb.characterDao().getCharacterSync()!!
        assertEquals(Double.POSITIVE_INFINITY, char.abs!!.getValue("devA").xp, 0.0)
        migratedDb.characterDao().updateCharacter(char.copy(gold = 1.0))
        val back = migratedDb.characterDao().getCharacterSync()!!
        assertEquals(0.0, back.abs!!.getValue("devA").xp, 0.0)
        assertEquals(5.5, back.abs!!.getValue("devA").gold, 0.0)
        migratedDb.close()
    }

    @Test
    fun `sync_base and sync_prefs tables exist and start empty after migration`() {
        val dbV6 = helper.createDatabase(TEST_DB, 6)
        dbV6.close()

        val migratedRaw = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        val cursor = migratedRaw.query("SELECT COUNT(*) FROM sync_base")
        cursor.use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }
}
