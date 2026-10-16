package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CR-KT-015: v7 -> v8 keeps every task and gives it an empty attachments list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration7to8Test {

    private val testDb = "migration-7-8-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // Room 2.8 MigrationTestHelper wants the absolute path of the db file (same file as testDb).
    private fun dbPath(): String = context().getDatabasePath(testDb).absolutePath

    @After fun tearDown() { context().deleteDatabase(testDb) }

    @Test fun tasksSurvive_withEmptyAttachments_andUploadTableExists() = runBlocking {
        helper.createDatabase(dbPath(), 7).apply {
            execSQL(
                "INSERT INTO tasks (id, type, title, notes, difficulty, value, done, checklist, tags, history, " +
                    "createdAt, updatedAt, cUp, cDown, extraJson, orderIndex) VALUES " +
                    "('t1','todo','Bike','','easy',0,0,'[]','[]','[]',1,2,0,0,'{\"id\":\"t1\",\"zz\":1}',0)"
            )
            close()
        }
        helper.runMigrationsAndValidate(dbPath(), 8, true, AppDatabase.MIGRATION_7_8).close()
        val db = Room.databaseBuilder(context(), AppDatabase::class.java, testDb)
            .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val t = db.taskDao().getTaskByIdSync("t1")!!
            assertEquals("Bike", t.title)
            assertTrue(t.attachments.isEmpty())
            assertEquals("{\"id\":\"t1\",\"zz\":1}", t.extraJson)
            assertTrue(db.attachmentUploadDao().pending().isEmpty())
        } finally {
            db.close()
        }
    }
}
