package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KT-01 / fix packet P1: Room must never silently wipe a user database that it cannot migrate.
 *
 * Only the pre-repo versions 1..3 may still be dropped (no repo build ever shipped them; KT-44).
 * Any other unknown path -- a downgrade (scenario b) or a future version bump that forgot its
 * Migration (scenario a) -- must throw at open and leave the file untouched.
 *
 * The singleton `AppDatabase.INSTANCE` is private and `TestQuestaApplication.onCreate` has already
 * opened `questa_database`, so the tests reset it by reflection (Momus P1 item 5), before and after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseNoWipeTest {

    companion object {
        private const val PROD_DB = "questa_database"
        private const val SCENARIO_A_DB = "kt01-missing-migration"
        private const val CHAIN_DB = "kt01-chain"
        private const val SCHEMA_ASSET_DIR = "com.example.data.local.AppDatabase"
        private const val CURRENT_VERSION = 8
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun resetSingleton() {
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        (field.get(null) as AppDatabase?)?.close()
        field.set(null, null)
    }

    @Before
    fun setUp() {
        resetSingleton()
        listOf(PROD_DB, SCENARIO_A_DB, CHAIN_DB).forEach { context().deleteDatabase(it) }
    }

    @After
    fun tearDown() {
        resetSingleton()
        listOf(PROD_DB, SCENARIO_A_DB, CHAIN_DB).forEach { context().deleteDatabase(it) }
    }

    /** A raw SQLite file at [version] that holds one sentinel row Room knows nothing about. */
    private fun plantFile(name: String, version: Int) {
        val file = context().getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sentinel (v TEXT NOT NULL)")
            db.execSQL("INSERT INTO sentinel (v) VALUES ('keep-me')")
            db.version = version
        }
    }

    private fun sentinelRows(name: String): Int {
        val file = context().getDatabasePath(name)
        if (!file.exists()) return -1
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sentinel'", null
            ).use { c -> c.moveToFirst(); if (c.getInt(0) == 0) return 0 }
            db.rawQuery("SELECT COUNT(*) FROM sentinel WHERE v='keep-me'", null).use { c ->
                c.moveToFirst(); return c.getInt(0)
            }
        }
    }

    private fun fileVersion(name: String): Int =
        SQLiteDatabase.openDatabase(
            context().getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY
        ).use { it.version }

    /** Design test (KT-01 scenario b): downgrade from an unknown newer version. */
    @Test
    fun unknownVersion_keepsRows_andThrows() {
        plantFile(PROD_DB, 9)
        val db = AppDatabase.getDatabase(context())
        try {
            db.openHelper.writableDatabase
            fail("open of a v9 file must throw, not wipe")
        } catch (e: IllegalStateException) {
            // expected: no migration 9 -> 8 and no destructive fallback for 9
        } finally {
            db.close()
        }
        assertEquals("sentinel row must survive", 1, sentinelRows(PROD_DB))
        assertEquals("file version must be unchanged", 9, fileVersion(PROD_DB))
    }

    /**
     * Momus P1 item 4 (KT-01 scenario a): an upgrade whose Migration is missing. A v5 file opened
     * with the production fallback policy but without the 4->5 / 5->6 steps must throw, not wipe.
     */
    @Test
    fun missingUpgradeMigration_keepsRows_andThrows() {
        plantFile(SCENARIO_A_DB, 5)
        val db = with(AppDatabase) {
            Room.databaseBuilder(context(), AppDatabase::class.java, SCENARIO_A_DB)
                .withQuestaMigrationPolicy(MIGRATION_6_7)
                .build()
        }
        try {
            db.openHelper.writableDatabase
            fail("an upgrade with no migration path must throw, not wipe")
        } catch (e: IllegalStateException) {
            // expected
        } finally {
            db.close()
        }
        assertEquals("sentinel row must survive", 1, sentinelRows(SCENARIO_A_DB))
        assertEquals("file version must be unchanged", 5, fileVersion(SCENARIO_A_DB))
    }

    /** Pre-repo versions 1..3 (never shipped from this repo, KT-44) are still recreated. */
    @Test
    fun preRepoVersion3_isRecreated() {
        plantFile(PROD_DB, 3)
        val db = AppDatabase.getDatabase(context())
        db.openHelper.writableDatabase
        db.close()
        assertEquals("pre-repo v3 file is dropped and recreated", 0, sentinelRows(PROD_DB))
        assertEquals(CURRENT_VERSION, fileVersion(PROD_DB))
    }

    /** Guard: a real v6 file (6.json) opened by the production builder keeps its rows. */
    @Test
    fun openV6FileWithProductionBuilder_keepsRows() {
        helper.createDatabase(PROD_DB, 6).apply {
            execSQL(
                "INSERT INTO tasks (id, type, title, notes, difficulty, value, done, streak, " +
                    "checklist, tags, history, createdAt, updatedAt, cUp, cDown, extraJson, orderIndex) " +
                    "VALUES ('t-keep', 'todo', 'Keep', '', 'easy', 0, 0, 0, '[]', '[]', '[]', 1, 1, 0, 0, NULL, 0)"
            )
            close()
        }
        val db = AppDatabase.getDatabase(context())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM tasks WHERE id='t-keep'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.close()
        assertEquals(CURRENT_VERSION, fileVersion(PROD_DB))
    }

    /**
     * Guard (MigrationTestHelper, L5): every exported schema below the current version has a
     * validated migration path to the current version with the production migration list.
     */
    @Test
    fun everyExportedSchema_migratesToCurrent() {
        val versions = context().assets.list(SCHEMA_ASSET_DIR).orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()
        assertTrue("current schema $CURRENT_VERSION.json must be exported", CURRENT_VERSION in versions)
        val older = versions.filter { it < CURRENT_VERSION }
        assertFalse("at least one older schema to check", older.isEmpty())
        for (v in older) {
            context().deleteDatabase(CHAIN_DB)
            helper.createDatabase(CHAIN_DB, v).close()
            helper.runMigrationsAndValidate(
                CHAIN_DB, CURRENT_VERSION, true, *AppDatabase.QUESTA_MIGRATIONS
            ).close()
        }
    }
}
