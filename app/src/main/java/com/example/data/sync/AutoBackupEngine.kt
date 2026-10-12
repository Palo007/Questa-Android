package com.example.data.sync

import java.util.Calendar
import java.util.Locale

// WP-5.2 (spec §7 D9) — auto-backup pure decision logic, port of PWA's `syncMaybeAutoExport`
// (`legacy_pwa/sync.js:1348-1396`). Holds only pure functions (no Dropbox I/O, no DataStore I/O,
// no Room I/O) so it is unit-testable without Robolectric/FakeDropboxServer — mirrors the existing
// SerializationEngine/CanonicalJson and EventSyncEngine split: I/O orchestration lives in
// [SyncManager.maybeAutoExport], wire/decision logic lives here.
//
// Deliberately uses the DEVICE DEFAULT [Calendar] (local calendar time), NOT UTC — this is the
// opposite convention from [EventSyncEngine.evtMonthKey], which is intentionally UTC for the
// `/events/*.json` month-file pipeline. PWA's `syncMaybeAutoExport` uses plain `new Date()` /
// `getFullYear()`/`getMonth()`/`getDate()`/`getHours()`/`getMinutes()` — all local-time accessors
// (sync.js:1354-1356, 1365-1366) — so reusing `evtMonthKey`'s UTC math here would silently diverge
// from the PWA reference for any device not in UTC.
object AutoBackupEngine {

    // PWA `monthKey = \`${year}-${month}\`` (sync.js:1355-1357): local-calendar year, 1-based
    // month zero-padded to 2 digits, e.g. `"2026-07"`.
    fun localMonthKey(cal: Calendar): String {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%d-%02d", year, month)
    }

    // PWA monthly-backup filename (sync.js:1365-1367): `'questa-backup-' + YYYYMMDD + '-' + HHMM
    // + '.json'`, all local-time, zero-padded.
    fun monthlyBackupFilename(cal: Calendar): String {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return String.format(Locale.US, "questa-backup-%04d%02d%02d-%02d%02d.json", year, month, day, hour, minute)
    }

    // PWA `monthlyBackupNeeded = !S.monthlyBackups.includes(monthKey)` (sync.js:1360). Also covers
    // the `exportIntervalDays == 0` early-return implicitly: [SyncManager.maybeAutoExport] never
    // calls this when the interval pref is off, per sync.js:1348-1351.
    fun isMonthlyBackupNeeded(monthKey: String, monthlyBackups: List<String>): Boolean =
        monthKey !in monthlyBackups

    // PWA `regularBackupNeeded = Date.now() - last >= days * 86400000` (sync.js:1361-1362).
    // `exportIntervalDays <= 0` always evaluates false here too (redundant with the caller's own
    // early-return, but keeps this pure function correct/testable in isolation - see
    // AutoBackupTest's "interval-off (0) skips both paths" case, which exercises this function
    // directly without going through the early-return at all).
    fun isIntervalBackupNeeded(now: Long, lastExportTs: Long, exportIntervalDays: Int): Boolean =
        exportIntervalDays > 0 && (now - lastExportTs) >= exportIntervalDays.toLong() * 86_400_000L
}
