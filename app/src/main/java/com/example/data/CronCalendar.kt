package com.example.data

import com.example.data.clock.DayStamp
import com.example.data.local.entities.SyncPrefsEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * WP-8.10 (PX-65..68): local-calendar helpers ported from Opti app.js (7d5d4f3). Every function
 * takes the instant and the zone explicitly; none reads a clock.
 */
object LocalDay {
    /** app.js:2025 `dayStamp(d)` = local YYYYMMDD. */
    fun dayStamp(ms: Long, zone: ZoneId): DayStamp {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        return z.year * 10000 + z.monthValue * 100 + z.dayOfMonth
    }

    /**
     * app.js:2030-2033 `localDayDateAtOffset(baseMs, dayOffset)`: local NOON of the calendar day
     * `dayOffset` days from the day containing [baseMs] (noon, so a 23 h / 25 h DST day cannot
     * move the result onto another date).
     */
    fun localDayDateAtOffset(baseMs: Long, dayOffset: Int, zone: ZoneId): Long {
        val d = Instant.ofEpochMilli(baseMs).atZone(zone).toLocalDate().plusDays(dayOffset.toLong())
        return d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /** JS `Date.getDay()`: 0 = Sunday .. 6 = Saturday. */
    fun getDay(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek.value % 7

    /**
     * JS `new Date(ly, lm-1, ld)` for a YYYYMMDD int (app.js:2624-2625), including JS overflow
     * (month 0 / day 0 roll back) and the two-digit-year rule (0..99 means 1900..1999), so a
     * fresh account's `lastCron = 0` becomes 30 Nov 1899 exactly as in Opti.
     */
    internal fun jsDateOfStamp(stamp: DayStamp): LocalDate {
        val ly = Math.floorDiv(stamp, 10000)
        val lm = Math.floorMod(Math.floorDiv(stamp, 100), 100)
        val ld = Math.floorMod(stamp, 100)
        val year = if (ly in 0..99) 1900 + ly else ly
        return LocalDate.of(year, 1, 1).plusMonths((lm - 1).toLong()).plusDays((ld - 1).toLong())
    }
}

/** WP-8.10 (PX-67, G9): app.js:2622-2635. */
object Week {
    /**
     * `periodBoundaryCrossed(freq, lastStamp, now)`. weekly: the Monday-anchored week of now is
     * later than the one of [last] (Monday = `(getDay()+6)%7 == 0`; no Locale, no WEEK_OF_YEAR);
     * monthly: `year*12+month` is later; any other value (daily or unknown): always true.
     */
    fun periodBoundaryCrossed(freq: String, last: DayStamp, nowMs: Long, tz: ZoneId): Boolean {
        val lastDate = LocalDay.jsDateOfStamp(last)
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(tz).toLocalDate()
        if (freq == "weekly") {
            fun monday(d: LocalDate): Long {
                val day = (d.dayOfWeek.value % 7 + 6) % 7
                return d.minusDays(day.toLong()).atStartOfDay(tz).toInstant().toEpochMilli()
            }
            return monday(nowDate) > monday(lastDate)
        }
        if (freq == "monthly") {
            return nowDate.year * 12 + (nowDate.monthValue - 1) > lastDate.year * 12 + (lastDate.monthValue - 1)
        }
        return true
    }
}

/** WP-8.10 (PX-65): Opti `S.prefs.paused` / `S.prefs.pausedDays` as the cron reads them. */
data class PauseState(val paused: Boolean, val pausedDays: List<DayStamp>) {
    companion object {
        val NONE = PauseState(false, emptyList())
        fun of(p: SyncPrefsEntity?): PauseState =
            if (p == null) NONE else PauseState(p.paused == true, p.pausedDays ?: emptyList())
    }
}
