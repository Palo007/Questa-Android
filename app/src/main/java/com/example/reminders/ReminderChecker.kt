package com.example.reminders

import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone

/**
 * WP-8.11: Opti `checkReminders` (app.js:2165-2205), run by the alarm receiver and by the
 * foreground catch-up ([checkMissed], app.js:8597-8601 visibilitychange-visible).
 *
 * For every task and every reminder: `due` -> post the normal payload; else `missed` -> post the
 * "Missed at HH:MM - " payload; then set `lastFiredKey = fireKey(r, now)` (no updatedAt bump).
 * The gate (notifications allowed) closed -> nothing fires and nothing is marked.
 *
 * The fire key is the only "already shown" guard (E5: no ReminderDedupe). One process-wide
 * mutex keeps the alarm path and the ON_START path from both posting the same slot.
 */
class ReminderChecker(
    private val loadTasks: suspend () -> List<TaskEntity>,
    private val repository: ReminderRepository,
    private val notifier: ReminderNotifier,
    private val gate: NotificationGate,
    private val tz: () -> TimeZone = { TimeZone.getDefault() }
) {
    /** Returns how many notifications were posted. */
    suspend fun checkReminders(nowMs: Long): Int = LOCK.withLock {
        if (!gate.canNotify()) return@withLock 0
        val zone = tz()
        var fired = 0
        for (t in loadTasks()) {
            val rs = t.reminders ?: continue
            rs.forEachIndexed { idx, r ->
                val due = ReminderEngine.isDue(t, r, nowMs, zone)
                val missed = !due && ReminderEngine.isMissed(t, r, nowMs, zone)
                if (!due && !missed) return@forEachIndexed
                notifier.post(ReminderEngine.payload(t, r, missed))
                repository.markFired(t.id, idx, ReminderEngine.fireKey(r, nowMs, zone))
                fired++
            }
        }
        fired
    }

    /**
     * (r9, m-R8-3) ON_START catch-up: Opti runs the same `checkReminders()` on
     * visibilitychange-visible, so a slot AlarmManager missed (doze, kill, clock change) fires
     * once, marked "Missed".
     */
    suspend fun checkMissed(nowMs: Long): Int = checkReminders(nowMs)

    private companion object {
        val LOCK = Mutex()
    }
}
