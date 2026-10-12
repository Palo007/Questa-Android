package com.example.reminders

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.dao.TaskDao
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared in-memory Room setup for the reminder tests that touch the task table. */
abstract class ReminderDbTestBase {
    protected lateinit var db: AppDatabase
    protected lateinit var dao: TaskDao

    @Before
    fun openDb() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.taskDao()
    }

    @After
    fun closeDb() = db.close()

    protected fun checker(notifier: ReminderNotifier, gate: Boolean = true) = ReminderChecker(
        loadTasks = { dao.getAllTasksSync() },
        repository = ReminderRepository(db),
        notifier = notifier,
        gate = { gate },
        tz = { UTC }
    )

    protected fun habit(id: String, time: String, lastFiredKey: String = "", updatedAt: Long = 1000L) = TaskEntity(
        id = id, type = "habit", title = "H-$id", updatedAt = updatedAt, createdAt = 1L,
        reminders = listOf(ReminderSpec(id = "r-$id", enabled = true, kind = "weekly", time = time, lastFiredKey = lastFiredKey, days = List(7) { true }))
    )
}

/** D10 / app.js:2198: markFired rewrites reminders[idx].lastFiredKey and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkFiredNoBumpTest : ReminderDbTestBase() {

    @Test
    fun `markFired changes only lastFiredKey and keeps updatedAt`() = runBlocking {
        val t = habit("a", "09:00", updatedAt = 4242L).let {
            it.copy(reminders = it.reminders!! + ReminderSpec(id = "second", enabled = true, time = "10:00"))
        }
        dao.insertTask(t)
        assertTrue(ReminderRepository(db).markFired("a", 1, "20260924-10:00"))
        val after = dao.getTaskByIdSync("a")!!
        assertEquals(4242L, after.updatedAt)
        assertEquals("", after.reminders!![0].lastFiredKey)
        assertEquals("20260924-10:00", after.reminders!![1].lastFiredKey)
        assertEquals(t.copy(reminders = null), after.copy(reminders = null, extraJson = t.extraJson))
    }

    @Test
    fun `markFired on a missing task or index is a no-op`() = runBlocking {
        dao.insertTask(habit("a", "09:00"))
        val repo = ReminderRepository(db)
        assertFalse(repo.markFired("nope", 0, "k"))
        assertFalse(repo.markFired("a", 5, "k"))
        assertEquals("", dao.getTaskByIdSync("a")!!.reminders!![0].lastFiredKey)
    }

    /**
     * opus review (FIX): markFired used to write the whole row it had read. A UI tap (or a sync
     * pull) that lands between that read and the write was lost. Here the tap is injected right
     * after markFired's read; it must survive, and the reminder key must still be written.
     */
    @Test
    fun `a counter update between markFired read and write survives`() = runBlocking {
        dao.insertTask(habit("a", "09:00", updatedAt = 1000L).copy(cUp = 5))
        var injected = false
        val racing = object : TaskDao by dao {
            override suspend fun getTaskByIdSync(id: String): TaskEntity? {
                val read = dao.getTaskByIdSync(id)
                if (!injected && read != null) {
                    injected = true
                    dao.updateTask(read.copy(cUp = 6, updatedAt = 2000L)) // the user taps +
                }
                return read
            }
        }
        assertTrue(ReminderRepository(db, racing).markFired("a", 0, "20260924-09:00"))
        assertTrue(injected)
        val after = dao.getTaskByIdSync("a")!!
        assertEquals(6, after.cUp)
        assertEquals(2000L, after.updatedAt)
        assertEquals("20260924-09:00", after.reminders!![0].lastFiredKey)
    }
}

/** Opti checkReminders (app.js:2165-2205) over Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderCheckerTest : ReminderDbTestBase() {

    @Test
    fun `due fires normal, past fires missed, both marked, second run fires nothing`() = runBlocking {
        dao.insertTask(habit("due", "12:00"))
        dao.insertTask(habit("past", "08:00"))
        dao.insertTask(habit("future", "18:00"))
        val n = RecordingNotifier()
        val c = checker(n)
        assertEquals(2, c.checkReminders(FIXED_NOW_MS))
        assertEquals(setOf("Time to score your habit!", "Missed at 08:00 - Time to score your habit!"), n.posted.map { it.body }.toSet())
        assertEquals("20260924-12:00", dao.getTaskByIdSync("due")!!.reminders!![0].lastFiredKey)
        assertEquals("20260924-08:00", dao.getTaskByIdSync("past")!!.reminders!![0].lastFiredKey)
        assertEquals(1000L, dao.getTaskByIdSync("past")!!.updatedAt)
        assertEquals(0, c.checkReminders(FIXED_NOW_MS + 30_000))
        assertEquals(2, n.posted.size)
    }

    @Test
    fun `closed gate fires and marks nothing`() = runBlocking {
        dao.insertTask(habit("past", "08:00"))
        val n = RecordingNotifier()
        assertEquals(0, checker(n, gate = false).checkReminders(FIXED_NOW_MS))
        assertTrue(n.posted.isEmpty())
        assertEquals("", dao.getTaskByIdSync("past")!!.reminders!![0].lastFiredKey)
    }

    @Test
    fun `a slot another device already fired (synced key) does not fire again`() = runBlocking {
        dao.insertTask(habit("synced", "12:00", lastFiredKey = "20260924-12:00"))
        val n = RecordingNotifier()
        assertEquals(0, checker(n).checkReminders(FIXED_NOW_MS))
    }
}

/** (r9, m-R8-3) ON_START -> checkMissed fires what AlarmManager missed while backgrounded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundCatchUpTest : ReminderDbTestBase() {

    @Test
    fun `ON_START runs checkMissed and fires the missed slot once`() = runBlocking {
        dao.insertTask(habit("missed", "07:30"))
        val n = RecordingNotifier()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observer = ReminderForegroundObserver(checker(n), scope, clock = { FIXED_NOW_MS })
        val owner = object : LifecycleOwner {
            val reg = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = reg
        }
        owner.lifecycle.addObserver(observer)
        owner.reg.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        assertEquals(null, observer.lastJob)
        owner.reg.handleLifecycleEvent(Lifecycle.Event.ON_START)
        observer.lastJob!!.join()
        assertEquals(listOf("Missed at 07:30 - Time to score your habit!"), n.posted.map { it.body })
        // Background, then foreground again: already marked, nothing new.
        owner.reg.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.reg.handleLifecycleEvent(Lifecycle.Event.ON_START)
        observer.lastJob!!.join()
        assertEquals(1, n.posted.size)
        scope.cancel()
    }
}

/** PX-74: boot / time / timezone / package-replaced re-arm; armedCount is equal after boot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootRearmTest : ReminderDbTestBase() {

    @Test
    fun `after boot armedCount is unchanged and only system actions re-arm`() = runBlocking {
        dao.insertTask(habit("a", "13:00"))
        dao.insertTask(habit("b", "06:00"))
        dao.insertTask(TaskEntity(id = "off", type = "habit", title = "x", reminders = listOf(ReminderSpec(enabled = false, time = "10:00")), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val store = MemoryArmedKeyStore()
        fun runtime(api: FakeAlarmApi) = ReminderRuntime(
            dao, ReminderScheduler(api, store, sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { UTC }),
            checker(RecordingNotifier()), clock = { FIXED_NOW_MS }
        )
        val before = FakeAlarmApi()
        runtime(before).rearmAll()
        assertEquals(2, runtime(before).scheduler.armedCount())

        val afterBoot = FakeAlarmApi() // a reboot drops every alarm
        assertFalse(ReminderBootReceiver.handle("com.example.OTHER", runtime(afterBoot)))
        assertTrue(afterBoot.armed.isEmpty())
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            assertTrue(action, ReminderBootReceiver.handle(action, runtime(afterBoot)))
        }
        assertEquals(before.armed, afterBoot.armed)
        assertEquals(2, runtime(afterBoot).scheduler.armedCount())
        assertEquals("android.intent.action.TIME_SET", Intent.ACTION_TIME_CHANGED)
    }

    @Test
    fun `alarm fire posts, marks and arms the next day`() = runBlocking {
        dao.insertTask(habit("a", "12:00"))
        val api = FakeAlarmApi()
        val n = RecordingNotifier()
        val rt = ReminderRuntime(
            dao, ReminderScheduler(api, MemoryArmedKeyStore(), sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { UTC }),
            checker(n), clock = { FIXED_NOW_MS }
        )
        ReminderReceiver.handle(rt, FIXED_NOW_MS + 500)
        assertEquals(1, n.posted.size)
        assertEquals(utcMs(2026, 9, 25, 12, 0), api.armed["a#0"])
    }
}
