package com.example.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.2 (Wave 1) -- §5 card "DAO Flow tests": [TaskDao.observeQuickLogHabits] and
 * [TaskDao.observeTasksWithReminders], plus [TaskDao.deleteWithTombstone] (the new caller-supplied
 * `at` signature, §4.4 clock-stamp-site rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskDaoQuickLogAndRemindersTest {

    private lateinit var db: AppDatabase
    private lateinit var taskDao: TaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskDao = db.taskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeQuickLogHabits returns only habits with a non-null quickLog`() = runBlocking {
        taskDao.insertTask(TaskEntity(id = "h1", type = "habit", title = "Push-ups", quickLog = QuickLogMode.BOTH, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.insertTask(TaskEntity(id = "h2", type = "habit", title = "Snacking", quickLog = null, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.insertTask(TaskEntity(id = "d1", type = "daily", title = "Gym", quickLog = QuickLogMode.UP, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.insertTask(TaskEntity(id = "t1", type = "todo", title = "Ticket", quickLog = QuickLogMode.UP, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))

        val result = taskDao.observeQuickLogHabits().first()
        assertEquals(setOf("h1"), result.map { it.id }.toSet())
    }

    @Test
    fun `observeTasksWithReminders returns only tasks with a non-empty reminders list`() = runBlocking {
        val spec = ReminderSpec(id = "r1", time = "08:00")
        taskDao.insertTask(TaskEntity(id = "t1", type = "daily", title = "Meds", reminders = listOf(spec), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.insertTask(TaskEntity(id = "t2", type = "daily", title = "No reminders", reminders = null, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.insertTask(TaskEntity(id = "t3", type = "daily", title = "Empty list", reminders = emptyList(), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))

        val result = taskDao.observeTasksWithReminders().first()
        assertEquals(listOf("t1"), result.map { it.id })
        assertEquals("08:00", result.first().reminders?.first()?.time)
    }

    @Test
    fun `deleteWithTombstone removes the row and writes a tombstone at the caller-supplied timestamp`() = runBlocking {
        taskDao.insertTask(TaskEntity(id = "gone", type = "habit", title = "Bye", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        taskDao.deleteWithTombstone("gone", at = 123_456L)

        assertEquals(null, taskDao.getTaskByIdSync("gone"))
        val tombstone = db.tombstoneDao().getTombstoneById("gone")
        assertTrue(tombstone != null)
        assertEquals(123_456L, tombstone!!.at)
    }
}
