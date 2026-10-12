package com.example.data.clock

import android.content.Context
import com.example.BuildConfig

/**
 * WP-8.12-T1 -- a DEBUG-ONLY app-level clock offset, so live scenario 8 part b (a day change while
 * paused) can run without touching the phone's system clock.
 *
 * [PhysicalClock.nowMs] adds [offsetMs] to the real time, so every app-logic read that goes
 * through it shifts together: HLC stamps, the boot gate / cron day key, RPG, merge "now", taps,
 * tombstones, backup stamps. Transport timing (token expiry, Retry-After, throttles, Dropbox HTTP)
 * uses [PhysicalClock.realNowMs] and stays on real time: Dropbox's server clock is real, and a
 * shifted expiry would make a valid token look stale (or a stale one valid). Reminder alarms also
 * stay real: AlarmManager fires on the real RTC.
 *
 * Known debug-only effects (review 40, F7, not fixed): the Settings screen's `nowMs()` is the real
 * transport clock while `lastExportTs` is offset-stamped, so "last backup" can read as future
 * during a fake day; the reminder editor draft uses the real day. Event push watermark/age checks
 * DO read the offset clock (EventsStep `pushClock`, F3).
 *
 * Release builds compile with `DEBUG_CLOCK_ENABLED = false`: [offsetMs] is always 0 there, [set]
 * is refused and [load] reads nothing. The only writer is the debug-source-set adb receiver.
 * The value is kept in SharedPreferences (commit, not apply) so it survives `am force-stop`.
 */
object DebugClockOffset {
    const val PREFS = "debug_clock"
    const val KEY_OFFSET_MS = "offsetMs"

    /** A test seam; production value is the build flag. */
    @Volatile internal var enabled: Boolean = BuildConfig.DEBUG_CLOCK_ENABLED

    @Volatile private var offset: Long = 0L

    fun offsetMs(): Long = if (enabled) offset else 0L

    /** Called first thing in `QuestaApplication.onCreate` (before any clock read). */
    fun load(context: Context) {
        if (!enabled) return
        offset = try { prefs(context).getLong(KEY_OFFSET_MS, 0L) } catch (e: Exception) { 0L }
    }

    /** Persists and applies [ms] (0 clears). Returns false (and does nothing) when disabled. */
    fun set(context: Context, ms: Long): Boolean {
        if (!enabled) return false
        val ok = prefs(context).edit().putLong(KEY_OFFSET_MS, ms).commit()
        if (ok) offset = ms
        return ok
    }

    internal fun resetInMemoryForTest() { offset = 0L }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
