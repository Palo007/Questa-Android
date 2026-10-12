package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * WP-8.11: one armable reminder slot, the exact shape of an item in Opti's
 * `syncInboxWriteReminders` (sync.js:685-714): `{key,taskId,type,title,body,time,date,days}`.
 * `days` is 7 booleans (index 0 = Sunday) or null (= every day); `date` is set only for kind "once".
 */
data class ReminderItem(
    val key: String,
    val taskId: String,
    val type: String,
    val title: String,
    val body: String,
    val time: String,
    val date: String?,
    val days: List<Boolean>?
)

/** Opti `getReminderNotificationPayload` result (app.js:2127-2145). */
data class ReminderPayload(val title: String, val body: String, val tag: String)

/** Wall-clock source for reminder code. Tests pass a fake. */
fun interface ReminderClock {
    fun nowMs(): Long
}

/**
 * Real time on purpose (WP-8.12-T1): the fire times computed from this go to AlarmManager, which
 * fires on the real RTC, so the debug clock offset (DebugClockOffset) must not shift them.
 */
object SystemReminderClock : ReminderClock {
    override fun nowMs(): Long = System.currentTimeMillis() // clock-ok:alarm-trigger
}

/** Local calendar fields of an instant, read the way Opti's `Date` getters read them. */
internal data class LocalNow(
    val year: Int,
    val month: Int, // 1..12
    val day: Int,
    val dow: Int, // 0 = Sunday (JS getDay)
    val hour: Int,
    val minute: Int
) {
    val hhmm: String get() = "${pad2(hour)}:${pad2(minute)}"
    val dateDash: String get() = "$year-${pad2(month)}-${pad2(day)}"
    val dateCompact: String get() = "$year${pad2(month)}${pad2(day)}"

    companion object {
        fun of(nowMs: Long, tz: TimeZone): LocalNow {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = nowMs
            return LocalNow(
                year = c.get(Calendar.YEAR),
                month = c.get(Calendar.MONTH) + 1,
                day = c.get(Calendar.DAY_OF_MONTH),
                dow = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY,
                hour = c.get(Calendar.HOUR_OF_DAY),
                minute = c.get(Calendar.MINUTE)
            )
        }

        // Locale-free padding (String.format would use locale digits).
        internal fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
    }
}

/**
 * WP-8.11: 1:1 port of Opti's reminder helpers (app.js:2068-2146, BEGIN/END_REMINDER_HELPERS at
 * 7d5d4f3) plus the item builder of `syncInboxWriteReminders` (sync.js:685-714) and Bubblewrap's
 * `nextFireMs` (quicklog/ReminderList.kt) for AlarmManager.
 *
 * All time math is phone-local in [TimeZone] `tz` (Opti uses `Date` local getters).
 */
object ReminderEngine {

    private val ALL_DAYS: List<Boolean> = List(7) { true }

    /** app.js:2069 `normalizeTaskReminders`: a missing/non-array `reminders` reads as []. */
    fun normalize(t: TaskEntity): List<ReminderSpec> = t.reminders ?: emptyList()

    /** sync.js:699: `String(t.id) + "#" + idx`. */
    fun key(taskId: String, idx: Int): String = "$taskId#$idx"

    /**
     * app.js:2075 `reminderFireKey`: `YYYYMMDD-<r.time>` for the local day of `nowMs`.
     * A missing time renders as JS would render `undefined`.
     */
    fun fireKey(r: ReminderSpec, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String =
        "${LocalNow.of(nowMs, tz).dateCompact}-${r.time ?: "undefined"}"

    /** app.js:2082 `reminderAppliesToday`. */
    fun appliesToday(t: TaskEntity, r: ReminderSpec, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (!r.enabled) return false
        if (t.type == "todo" && t.done) return false
        if (r.time.isNullOrEmpty()) return false
        val now = LocalNow.of(nowMs, tz)
        if (r.kind == "once") {
            if (r.date != now.dateDash) return false
        } else {
            // JS `arr && !arr[d]`: a present array whose slot is not true blocks the day.
            if (t.type == "daily") {
                val rep = t.repeat
                if (rep != null && rep.getOrNull(now.dow) != true) return false
            } else {
                val days = r.days
                if (days != null && days.getOrNull(now.dow) != true) return false
            }
        }
        return r.lastFiredKey != fireKey(r, nowMs, tz)
    }

    /** app.js:2105 `isReminderDue`: applies today and `r.time` is exactly the current minute. */
    fun isDue(t: TaskEntity, r: ReminderSpec, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (!appliesToday(t, r, nowMs, tz)) return false
        return r.time == LocalNow.of(nowMs, tz).hhmm
    }

    /** app.js:2118 `isReminderMissed`: applies today and `r.time < HH:MM` (JS string compare). */
    fun isMissed(t: TaskEntity, r: ReminderSpec, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (!appliesToday(t, r, nowMs, tz)) return false
        return (r.time ?: return false) < LocalNow.of(nowMs, tz).hhmm
    }

    /** app.js:2127 `getReminderNotificationPayload`. */
    fun payload(t: TaskEntity, r: ReminderSpec, missed: Boolean): ReminderPayload {
        val title = t.title.ifEmpty { "Questa Reminder" }
        val notes = t.notes
        var body = when (t.type) {
            "habit" -> if (notes.isNotEmpty()) "Nudge: $notes" else "Time to score your habit!"
            "daily" -> if (notes.isNotEmpty()) "Daily reminder: $notes" else "Check off your daily task!"
            else -> if (notes.isNotEmpty()) "To-Do due: $notes" else "Complete your to-do!"
        }
        if (missed) body = "Missed at ${r.time ?: "undefined"} - $body"
        return ReminderPayload(title = title, body = body, tag = "questa-${t.id}")
    }

    /** sync.js:681 `_inboxNormalizeDays`. */
    private fun normalizeDays(days: List<Boolean>?): List<Boolean>? =
        if (days == null || days.size != 7) null else days.toList()

    /**
     * sync.js:688-707: skip tasks with no id, done to-dos, and reminders that are disabled or have
     * no time. Title/body come from [payload] with `missed = false`; a daily takes its days from
     * `t.repeat`, anything else from `r.days`; a "once" carries `date` and `days = null`.
     * CR-PWA-001: a "once" with no/empty `date` is skipped (keeps the index of the others).
     */
    fun items(tasks: List<TaskEntity>): List<ReminderItem> {
        val out = ArrayList<ReminderItem>()
        for (t in tasks) {
            if (t.id.isEmpty()) continue
            val rs = t.reminders ?: continue
            if (t.type == "todo" && t.done) continue
            rs.forEachIndexed { idx, r ->
                if (!r.enabled || r.time.isNullOrEmpty()) return@forEachIndexed
                val isOnce = r.kind == "once"
                // CR-PWA-001 (CR-TWA-004): an undated once never fires in Opti (app.js:2087-2092), but
                // as {date:null, days:null} nextFireMs would fire it daily. Skip it; `idx` is kept, so
                // the other keys stay `<taskId>#<idx>`.
                if (isOnce && r.date.isNullOrEmpty()) return@forEachIndexed
                val p = payload(t, r, missed = false)
                out.add(
                    ReminderItem(
                        key = key(t.id, idx),
                        taskId = t.id,
                        type = t.type,
                        title = p.title,
                        body = p.body,
                        time = r.time,
                        date = if (isOnce) r.date else null,
                        days = if (isOnce) null else normalizeDays(if (t.type == "daily") t.repeat else r.days)
                    )
                )
            }
        }
        return out
    }

    /** Bubblewrap ReminderList.kt `parseTime`: "H:MM"/"HH:MM" with 0..23 / 0..59, else null. */
    internal fun parseTime(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /**
     * Bubblewrap ReminderList.kt `nextFireMs`: the next slot strictly after `nowMs`, local wall
     * clock in `tz`. A "once" whose slot is not in the future returns null; a recurring item scans
     * today..today+7 for the first allowed weekday; 7 false days never fire.
     */
    fun nextFireMs(item: ReminderItem, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val (hour, minute) = parseTime(item.time) ?: return null
        if (item.date != null) {
            val parts = item.date.split("-")
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val mo = parts[1].toIntOrNull() ?: return null
            val d = parts[2].toIntOrNull() ?: return null
            val cal = Calendar.getInstance(tz)
            cal.timeInMillis = nowMs
            cal.set(Calendar.YEAR, y)
            cal.set(Calendar.MONTH, mo - 1)
            cal.set(Calendar.DAY_OF_MONTH, d)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return if (cal.timeInMillis > nowMs) cal.timeInMillis else null
        }
        for (offset in 0..7) {
            val c = Calendar.getInstance(tz)
            c.timeInMillis = nowMs
            c.add(Calendar.DAY_OF_YEAR, offset)
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= nowMs) continue
            val dow = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            if (item.days == null || item.days.getOrElse(dow) { false }) return c.timeInMillis
        }
        return null
    }

    internal fun allDays(): List<Boolean> = ALL_DAYS
}
