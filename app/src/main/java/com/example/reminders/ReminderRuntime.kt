package com.example.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.data.local.AppDatabase
import com.example.data.local.dao.TaskDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * WP-8.11: what the receivers and the lifecycle observer need. [ReminderRuntime.provider] builds
 * the real one from [AppDatabase]; tests replace it.
 */
class ReminderRuntime(
    private val dao: TaskDao,
    val scheduler: ReminderScheduler,
    val checker: ReminderChecker,
    val clock: ReminderClock = SystemReminderClock
) {
    /** Re-derive every alarm from the stored tasks (boot, time/timezone change, after a fire). */
    suspend fun rearmAll(nowMs: Long = clock.nowMs()) {
        scheduler.rearm(ReminderEngine.items(dao.getAllTasksSync()), nowMs)
    }

    /** An alarm fired: run Opti's `checkReminders`, then arm the next slots. */
    suspend fun onAlarm(nowMs: Long = clock.nowMs()) {
        checker.checkReminders(nowMs)
        rearmAll(nowMs)
    }

    companion object {
        @Volatile private var instance: ReminderRuntime? = null

        /** Overridable in tests. */
        @Volatile var provider: (Context) -> ReminderRuntime = { ctx -> instance ?: build(ctx).also { instance = it } }

        fun build(ctx: Context): ReminderRuntime {
            val app = ctx.applicationContext
            val dao = AppDatabase.getDatabase(app).taskDao()
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val checker = ReminderChecker(
                loadTasks = { dao.getAllTasksSync() },
                repository = ReminderRepository(AppDatabase.getDatabase(app)),
                notifier = AndroidReminderNotifier(app),
                gate = PrefNotificationGate.forApp(app) // WP-8.14: Opti's notificationsEnabled pref
            )
            return ReminderRuntime(dao, ReminderScheduler(app, am), checker)
        }
    }
}

/**
 * Room observer: every change to the tasks that carry reminders re-arms the alarms (replaces
 * Bubblewrap's reminders.json polling, O4). A task whose reminders were removed leaves the flow,
 * so its keys become stale and [ReminderScheduler.rearm] cancels them.
 */
class ReminderObserver(private val dao: TaskDao, private val scheduler: ReminderScheduler) {
    /**
     * WP-8.22-D item 3: runs in the handler-less `applicationScope`, so an exception here (a DB
     * that cannot open) used to crash the app on every launch. It now logs and stops observing;
     * cancellation still propagates.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            dao.observeTasksWithReminders()
                .map { ReminderEngine.items(it) }
                .distinctUntilChanged()
                .collect { scheduler.rearm(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logReminderFailure("reminder observer stopped", e)
        }
    }
}

/**
 * (r9, m-R8-3) `ProcessLifecycleOwner.ON_START` -> [ReminderChecker.checkMissed], Opti's
 * visibilitychange-visible -> `checkReminders()` (app.js:8597-8601).
 */
class ReminderForegroundObserver(
    private val checker: ReminderChecker,
    private val scope: CoroutineScope,
    private val clock: ReminderClock = SystemReminderClock
) : DefaultLifecycleObserver {
    @Volatile var lastJob: Job? = null
        private set

    override fun onStart(owner: LifecycleOwner) {
        // WP-8.22-D item 3: same scope as ReminderObserver; a failed check logs, never crashes.
        lastJob = scope.launch {
            try {
                checker.checkMissed(clock.nowMs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logReminderFailure("missed-reminder check failed", e)
            }
        }
    }
}

private fun logReminderFailure(msg: String, e: Exception) {
    try {
        android.util.Log.e("Questa.reminders", msg, e)
    } catch (_: Throwable) {
        // android.util.Log is a stub in plain JVM tests.
    }
}
