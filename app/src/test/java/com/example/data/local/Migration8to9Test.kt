package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.sync.SerializationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CR-KT-020: v8 -> v9 adds the nullable `tasks.histCreated` column and backfills it from a
 * `_histCreated` boolean an existing row's `extraJson` PWA original already carried
 * (MIGRATION_6_7 convention: the backfill reads the JSON text in Kotlin). NULL keeps meaning
 * "absent" (§5): a non-boolean original value is never guessed at, and the raw-SQL read proves the
 * COLUMN itself (not just the JSON text) got the value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration8to9Test {

    private val testDb = "migration-8-9-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // Room 2.8 MigrationTestHelper wants the absolute path of the db file (same file as testDb).
    private fun dbPath(): String = context().getDatabasePath(testDb).absolutePath

    @After fun tearDown() { context().deleteDatabase(testDb) }

    private fun insert(db: SupportSQLiteDatabase, id: String, extraJson: String) {
        db.execSQL(
            "INSERT INTO tasks (id, type, title, notes, difficulty, value, done, checklist, tags, history, " +
                "createdAt, updatedAt, cUp, cDown, extraJson, orderIndex) VALUES " +
                "('$id','daily','T','','easy',0,0,'[]','[]','[]',1,2,0,0,'$extraJson',0)"
        )
    }

    @Test fun histCreated_backfilledFromBoolean_unmodelledShapesNotGuessed() = runBlocking {
        helper.createDatabase(dbPath(), 8).apply {
            insert(this, "t-none", "{\"id\":\"t-none\",\"zz\":1}")
            insert(this, "t-true", "{\"id\":\"t-true\",\"_histCreated\":true}")
            insert(this, "t-false", "{\"id\":\"t-false\",\"_histCreated\":false}")
            insert(this, "t-shape", "{\"id\":\"t-shape\",\"_histCreated\":\"yes\"}")
            close()
        }

        helper.runMigrationsAndValidate(dbPath(), 9, true, AppDatabase.MIGRATION_8_9).close()

        // Raw column evidence, independent of the entity/DAO layer.
        SQLiteDatabase.openDatabase(dbPath(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, histCreated FROM tasks ORDER BY id", null).use { c ->
                val got = mutableMapOf<String, Int?>()
                while (c.moveToNext()) got[c.getString(0)] = if (c.isNull(1)) null else c.getInt(1)
                assertEquals(
                    mapOf("t-false" to 0, "t-none" to null, "t-shape" to null, "t-true" to 1),
                    got
                )
            }
        }

        val db = Room.databaseBuilder(context(), AppDatabase::class.java, testDb)
            .addMigrations(AppDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val none = db.taskDao().getTaskByIdSync("t-none")!!
            val t = db.taskDao().getTaskByIdSync("t-true")!!
            val f = db.taskDao().getTaskByIdSync("t-false")!!
            val shape = db.taskDao().getTaskByIdSync("t-shape")!!
            assertEquals("rows survive with their title", "T", none.title)
            assertNull(none.histCreated)
            assertEquals(true, t.histCreated)
            assertEquals(false, f.histCreated)
            assertNull("a non-boolean original is never guessed at", shape.histCreated)

            val wireNone = SerializationEngine.buildTaskJson(none)
            val wireTrue = SerializationEngine.buildTaskJson(t)
            val wireFalse = SerializationEngine.buildTaskJson(f)
            val wireShape = SerializationEngine.buildTaskJson(shape)
            assertFalse("NULL = absent on the wire", wireNone.containsKey("_histCreated"))
            assertEquals(true, wireTrue["_histCreated"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(false, wireFalse["_histCreated"]?.jsonPrimitive?.booleanOrNull)
            // An unmodeled shape passes through verbatim (the same keep-or-typed rule as `up`).
            assertEquals(JsonPrimitive("yes"), wireShape["_histCreated"])
        } finally {
            db.close()
        }
    }
}
