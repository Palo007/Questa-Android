package com.example.ui.settings

/**
 * CR-KT-018: the header sync badge read-model. Pure port of Opti `syncStatusFrom` (app.js:7428-7443),
 * the `updateSyncBadge` priority (app.js:7473-7495) and `staleSyncLabel` / `_agoText`
 * (app.js:7444-7456). Both sides of the unsynced rule are this device's wall clock
 * (`localEditAt` and `lastSyncAt`, see [com.example.data.sync.SyncDataStore.stampLocalEdit]).
 *
 * [DEFERRED_OPEN_QUESTION] Opti app.js:7426: an edit made while a round is uploading can read as
 * synced until the rerun that edit queued finishes. Same logic kept on purpose.
 */

/** Opti `SYNC_STALE_MS` (app.js:7409): after this the badge shows "Last synced ...". */
const val SYNC_STALE_MS = 3_600_000L

enum class SyncBadgeMode { HIDDEN, UNSYNCED, ERROR, STALE }

/**
 * [lastSyncAt] is Opti's normalized `lastSyncAt` (null when never synced; Kotlin's 0 default maps
 * to null). [label] is the STALE text (Opti `#syncBadgeLbl`), "" for every other mode.
 */
data class SyncBadgeState(val mode: SyncBadgeMode, val lastSyncAt: Long?, val label: String = "") {
    /** Opti `el.title` / `aria-label` text: the fixed tip for a dot, the label for STALE. */
    val tooltip: String
        get() = when (mode) {
            SyncBadgeMode.ERROR -> SyncBadgeTexts.ERROR
            SyncBadgeMode.UNSYNCED -> SyncBadgeTexts.UNSYNCED
            SyncBadgeMode.STALE -> label
            SyncBadgeMode.HIDDEN -> ""
        }

    companion object {
        val HIDDEN = SyncBadgeState(SyncBadgeMode.HIDDEN, null)
    }
}

object SyncBadgeTexts {
    const val ERROR = "Sync problem. Tap to see details."
    const val UNSYNCED = "Not synced yet. Your latest changes are only on this device."
    const val NEVER = "Never synced"
}

/**
 * [enabled] = Dropbox sync on (Settings' `connected`). [lastError] is truthy when non-null and
 * non-empty (Opti `cfg.lastError`). Priority: unsynced+error (red) > unsynced (orange) > stale
 * (label only) > hidden. An error alone shows no dot.
 */
fun syncBadgeFrom(enabled: Boolean, localEditAt: Long?, lastSyncAt: Long?, lastError: String?, nowMs: Long): SyncBadgeState {
    val last = lastSyncAt?.takeIf { it != 0L }
    if (!enabled) return SyncBadgeState(SyncBadgeMode.HIDDEN, last)
    val edit = localEditAt ?: 0L
    val unsynced = edit > 0 && (last == null || edit > last)
    val error = !lastError.isNullOrEmpty()
    val stale = last == null || nowMs - last > SYNC_STALE_MS
    return when {
        unsynced && error -> SyncBadgeState(SyncBadgeMode.ERROR, last)
        unsynced -> SyncBadgeState(SyncBadgeMode.UNSYNCED, last)
        stale -> SyncBadgeState(SyncBadgeMode.STALE, last, staleSyncLabel(last, nowMs))
        else -> SyncBadgeState(SyncBadgeMode.HIDDEN, last)
    }
}

/** Opti `staleSyncLabel`: "Never synced", "" while fresh (1 h or less), else "Last synced <ago>". */
fun staleSyncLabel(lastSyncAt: Long?, nowMs: Long): String {
    if (lastSyncAt == null || lastSyncAt == 0L) return SyncBadgeTexts.NEVER
    if (nowMs - lastSyncAt <= SYNC_STALE_MS) return ""
    return "Last synced " + agoText(lastSyncAt, nowMs)
}

/** Opti `_agoText` (app.js:7444-7450): floor at each unit, singular at 1. */
private fun agoText(ts: Long, nowMs: Long): String {
    val s = maxOf(0L, Math.floorDiv(nowMs - ts, 1000L))
    if (s < 60) return "just now"
    val m = s / 60
    if (m < 60) return m.toString() + (if (m == 1L) " minute" else " minutes") + " ago"
    val h = m / 60
    if (h < 24) return h.toString() + (if (h == 1L) " hour" else " hours") + " ago"
    val d = h / 24
    return d.toString() + (if (d == 1L) " day" else " days") + " ago"
}
