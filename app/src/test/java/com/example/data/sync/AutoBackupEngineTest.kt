package com.example.data.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * WP-5.2 (spec §7 D9) -- plain-JVM tests against [AutoBackupEngine]'s pure functions (no Android/
 * Room/network dependency needed; same convention as [EventSyncEngineTest]). See `AutoBackupTest`
 * (app/src/test/java/com/example/) for the Robolectric + FakeDropboxServer integration coverage of
 * [SyncManager.maybeAutoExport] itself.
 */
class AutoBackupEngineTest {

    private fun calendar(year: Int, month1Based: Int, day: Int, hour: Int, minute: Int): Calendar {
        // Fixed time zone so this test is deterministic regardless of the JVM's default TZ --
        // AutoBackupEngine itself is deliberately local-calendar (device-default), but the actual
        // zone used doesn't matter for these field-formatting assertions as long as the Calendar
        // passed in already reflects the intended local fields.
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        cal.clear()
        cal.set(year, month1Based - 1, day, hour, minute, 0)
        return cal
    }

    // -----------------------------------------------------------------------------------------
    // localMonthKey
    // -----------------------------------------------------------------------------------------

    @Test
    fun localMonthKey_formatsAsYearDashZeroPaddedMonth() {
        assertEquals("2026-07", AutoBackupEngine.localMonthKey(calendar(2026, 7, 19, 10, 30)))
    }

    @Test
    fun localMonthKey_zeroPadsSingleDigitMonth() {
        assertEquals("2026-01", AutoBackupEngine.localMonthKey(calendar(2026, 1, 5, 0, 0)))
    }

    // -----------------------------------------------------------------------------------------
    // monthlyBackupFilename
    // -----------------------------------------------------------------------------------------

    @Test
    fun monthlyBackupFilename_matchesPwaStampFormat_YYYYMMDD_dash_HHMM() {
        assertEquals(
            "questa-backup-20260719-0930.json",
            AutoBackupEngine.monthlyBackupFilename(calendar(2026, 7, 19, 9, 30))
        )
    }

    @Test
    fun monthlyBackupFilename_zeroPadsAllComponents() {
        assertEquals(
            "questa-backup-20260103-0405.json",
            AutoBackupEngine.monthlyBackupFilename(calendar(2026, 1, 3, 4, 5))
        )
    }

    // -----------------------------------------------------------------------------------------
    // isMonthlyBackupNeeded
    // -----------------------------------------------------------------------------------------

    @Test
    fun isMonthlyBackupNeeded_true_whenMonthKeyAbsent() {
        assertTrue(AutoBackupEngine.isMonthlyBackupNeeded("2026-07", listOf("2026-05", "2026-06")))
    }

    @Test
    fun isMonthlyBackupNeeded_false_whenMonthKeyAlreadyPresent() {
        assertFalse(AutoBackupEngine.isMonthlyBackupNeeded("2026-07", listOf("2026-06", "2026-07")))
    }

    @Test
    fun isMonthlyBackupNeeded_true_whenListEmpty() {
        assertTrue(AutoBackupEngine.isMonthlyBackupNeeded("2026-07", emptyList()))
    }

    // -----------------------------------------------------------------------------------------
    // isIntervalBackupNeeded
    // -----------------------------------------------------------------------------------------

    @Test
    fun isIntervalBackupNeeded_false_whenExportIntervalDaysIsZero() {
        // Mirrors sync.js:1350-1351's early return -- this pure function stays false-safe even
        // when called directly (bypassing SyncManager.maybeAutoExport's own early-return).
        val now = 100_000_000L
        assertFalse(AutoBackupEngine.isIntervalBackupNeeded(now, lastExportTs = 0L, exportIntervalDays = 0))
    }

    @Test
    fun isIntervalBackupNeeded_false_whenElapsedLessThanIntervalDays() {
        val days = 3
        val now = 10_000_000_000L
        val lastExportTs = now - (2L * 86_400_000L) // 2 days elapsed, need 3
        assertFalse(AutoBackupEngine.isIntervalBackupNeeded(now, lastExportTs, days))
    }

    @Test
    fun isIntervalBackupNeeded_true_whenElapsedExactlyEqualsIntervalDays() {
        val days = 3
        val now = 10_000_000_000L
        val lastExportTs = now - (3L * 86_400_000L)
        assertTrue(AutoBackupEngine.isIntervalBackupNeeded(now, lastExportTs, days))
    }

    @Test
    fun isIntervalBackupNeeded_true_whenElapsedExceedsIntervalDays() {
        val days = 1
        val now = 10_000_000_000L
        val lastExportTs = now - (5L * 86_400_000L)
        assertTrue(AutoBackupEngine.isIntervalBackupNeeded(now, lastExportTs, days))
    }
}
