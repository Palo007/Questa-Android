package com.example.data.sync

import com.example.data.clock.PhysicalClock

/**
 * WP-8.6 (§4.4) -- the physical-clock reads of the sync round, one tagged line per category, so
 * `SyncManager.kt`, `SyncDataStore.kt` and `RpgRepository.kt` carry no clock site of their own
 * (their baseline entries are 0). Callers take these as injectable defaults.
 */
internal object SyncClocks {
    /**
     * Token expiry and throttles (Opti `Date.now()` in syncToken / evt pull throttle). WP-8.12-T1:
     * real time, NOT the debug clock offset (Dropbox's clock is real; see DebugClockOffset).
     */
    val transport: () -> Long = { PhysicalClock.realNowMs() } // clock-ok:transport-timer

    /** merge()'s `Date.now()` (sync.js:2453 via _futureCeil / tombstone GC). */
    val mergeNow: () -> Long = { PhysicalClock.nowMs() } // clock-ok:merge-now-physical

    /** `wrap()`'s `savedAt: Date.now()` (sync.js:2326) and `lastSyncAt: Date.now()`. */
    val wrapperSavedAt: () -> Long = { PhysicalClock.nowMs() } // clock-ok:wrapper-savedAt

    /** The legacy LOCAL_DELETIONS stamp (pre-v7 deletion record). */
    val tombstone: () -> Long = { PhysicalClock.nowMs() } // clock-ok:tombstone-gc

    /** The physical now of a tap drain (D23: a tap is judged on the wall-clock day). */
    val tap: () -> Long = { PhysicalClock.nowMs() } // clock-ok:tap-physical

    /** Local backup file stamps (auto-export month / interval check). */
    val backupLocal: () -> Long = { PhysicalClock.nowMs() } // clock-ok:backup-local-stamp
}
