package com.example.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CR-KT-018: [syncBadgeFrom] / [staleSyncLabel] are ports of Opti `syncStatusFrom` +
 * `updateSyncBadge` priority + `staleSyncLabel` (app.js:7409-7495). Cases T7, T10, T11 mirror
 * Opti tests/durability-ux.test.js.
 */
class SyncBadgeTest {

    private val t = 1_790_000_000_000L
    private val h = 3_600_000L
    private val d = 86_400_000L

    private fun mode(enabled: Boolean = true, edit: Long?, last: Long?, err: String? = null, now: Long) =
        syncBadgeFrom(enabled, edit, last, err, now).mode

    @Test fun staleMs_isOneHour() = assertEquals(3_600_000L, SYNC_STALE_MS)

    // ---- syncBadgeFrom ----

    @Test fun disabled_isHidden_evenWithEditErrorAndStale() {
        assertEquals(SyncBadgeMode.HIDDEN, mode(enabled = false, edit = t + 1, last = t, err = "boom", now = t + 2))
        assertEquals("T10i sync off -> nothing", SyncBadgeMode.HIDDEN, mode(enabled = false, edit = null, last = null, now = t))
    }

    @Test fun noEdit_fresh_isHidden() = assertEquals(SyncBadgeMode.HIDDEN, mode(edit = null, last = t, now = t + 1000))

    @Test fun editZero_countsAsNoEdit() = assertEquals(SyncBadgeMode.HIDDEN, mode(edit = 0L, last = t, now = t + 1000))

    @Test fun editBeforeLast_isHidden() = assertEquals("T7b", SyncBadgeMode.HIDDEN, mode(edit = t - 1, last = t, now = t + 2))

    @Test fun editEqualLast_isHidden() = assertEquals(SyncBadgeMode.HIDDEN, mode(edit = t, last = t, now = t + 2))

    @Test fun editAfterLast_noError_isOrange() = assertEquals("T7a", SyncBadgeMode.UNSYNCED, mode(edit = t + 1, last = t, now = t + 2))

    @Test fun editAfterLast_withError_isRed() = assertEquals("T10g", SyncBadgeMode.ERROR, mode(edit = t + 1, last = t, err = "boom", now = t + 2))

    @Test fun emptyError_isNotAnError() = assertEquals(SyncBadgeMode.UNSYNCED, mode(edit = t + 1, last = t, err = "", now = t + 2))

    @Test fun errorOnly_fresh_isHidden_noDot() = assertEquals(SyncBadgeMode.HIDDEN, mode(edit = t - 1, last = t, err = "boom", now = t + 2))

    @Test fun errorOnly_stale_isStaleLabel_noDot() {
        val s = syncBadgeFrom(true, t - 1, t, "boom", t + 2 * h)
        assertEquals(SyncBadgeMode.STALE, s.mode)
        assertEquals("Last synced 2 hours ago", s.label)
    }

    @Test fun neverSynced_withEdit_isOrange() = assertEquals("T7d", SyncBadgeMode.UNSYNCED, mode(edit = t, last = null, now = t + 2))

    @Test fun neverSynced_withEditAndError_isRed() = assertEquals(SyncBadgeMode.ERROR, mode(edit = t, last = null, err = "x", now = t + 2))

    @Test fun neverSynced_noEdit_isStale_neverSynced() {
        val s = syncBadgeFrom(true, null, null, null, t)
        assertEquals(SyncBadgeMode.STALE, s.mode)
        assertEquals("Never synced", s.label)
        assertEquals(null, s.lastSyncAt)
    }

    @Test fun lastSyncAtZero_isNeverSynced() {
        // Opti `cfg.lastSyncAt ? Number(..) : null`; Kotlin's SYNC_LAST_AT defaults to 0.
        val s = syncBadgeFrom(true, null, 0L, null, t)
        assertEquals(SyncBadgeMode.STALE, s.mode)
        assertEquals("Never synced", s.label)
        assertEquals(null, s.lastSyncAt)
        assertEquals(SyncBadgeMode.UNSYNCED, mode(edit = t, last = 0L, now = t + 1))
    }

    @Test fun ageExactlyOneHour_isNotStale() = assertEquals(SyncBadgeMode.HIDDEN, mode(edit = null, last = t, now = t + h))

    @Test fun ageOneHourPlusOneMs_isStale() {
        val s = syncBadgeFrom(true, null, t, null, t + h + 1)
        assertEquals(SyncBadgeMode.STALE, s.mode)
        assertEquals("Last synced 1 hour ago", s.label)
        assertEquals(t, s.lastSyncAt)
    }

    @Test fun unsynced_winsOverStale() = assertEquals(SyncBadgeMode.UNSYNCED, mode(edit = t + 1, last = t, now = t + 48 * h))

    @Test fun t7f_stale_after2h_t7g_fresh_after10min() {
        assertEquals(SyncBadgeMode.STALE, mode(edit = null, last = t, now = t + 2 * h))
        assertEquals(SyncBadgeMode.HIDDEN, mode(edit = null, last = t, now = t + 10 * 60_000))
    }

    @Test fun t10h_staleAfter48h_labelTwoDays() {
        val now = t
        val s = syncBadgeFrom(true, now - 49 * h, now - 48 * h, null, now)
        assertEquals(SyncBadgeMode.STALE, s.mode)
        assertEquals("Last synced 2 days ago", s.label)
    }

    @Test fun dotModes_haveNoLabel() {
        assertEquals("", syncBadgeFrom(true, t + 1, t, null, t + 48 * h).label)
        assertEquals("", syncBadgeFrom(true, t + 1, t, "x", t + 48 * h).label)
    }

    // ---- tooltips (Opti title / aria-label) ----

    @Test fun tooltips_areThePwaStrings() {
        assertEquals("Sync problem. Tap to see details.", syncBadgeFrom(true, t + 1, t, "x", t + 2).tooltip)
        assertEquals("Not synced yet. Your latest changes are only on this device.", syncBadgeFrom(true, t + 1, t, null, t + 2).tooltip)
        assertEquals("Last synced 2 hours ago", syncBadgeFrom(true, null, t, null, t + 2 * h).tooltip)
        assertEquals("", SyncBadgeState.HIDDEN.tooltip)
    }

    // ---- staleSyncLabel (T11 + rounding) ----

    @Test fun t11a_48h_twoDays() = assertEquals("Last synced 2 days ago", staleSyncLabel(t - 48 * h, t))
    @Test fun t11b_30min_empty() = assertEquals("", staleSyncLabel(t - 30 * 60_000, t))
    @Test fun t11c_null_never() = assertEquals("Never synced", staleSyncLabel(null, t))
    @Test fun t11d_2h_twoHours() = assertEquals("Last synced 2 hours ago", staleSyncLabel(t - 2 * h, t))

    @Test fun zero_isNever() = assertEquals("Never synced", staleSyncLabel(0L, t))
    @Test fun exactlyOneHour_isEmpty() = assertEquals("", staleSyncLabel(t - h, t))
    @Test fun oneHourPlus1ms_isOneHour_singular() = assertEquals("Last synced 1 hour ago", staleSyncLabel(t - h - 1, t))
    @Test fun hoursFloor() = assertEquals("Last synced 23 hours ago", staleSyncLabel(t - 24 * h + 1, t))
    @Test fun oneDay_singular() = assertEquals("Last synced 1 day ago", staleSyncLabel(t - 24 * h, t))
    @Test fun daysFloor() = assertEquals("Last synced 1 day ago", staleSyncLabel(t - 2 * d + 1, t))
    @Test fun manyDays() = assertEquals("Last synced 200 days ago", staleSyncLabel(t - 200 * d, t))
}
