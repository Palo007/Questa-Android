package com.example.launcher

import android.text.format.DateUtils
import com.example.data.sync.auth.AuthState

/**
 * WP-8.22-B: the Bubblewrap quick-log status rows (Questa-Bubblewrap
 * `quicklog/SettingsActivity.kt:115-121, :145-146`, `ago` :169-171) that review 34 item 3 found
 * missing. Same texts and formats; Settings shows them in the Sync block and the Notifications
 * option. Read-only: no row shows a token or starts anything.
 */
object QuickLogStatus {
    const val DROPBOX_NOT_CONNECTED = "Dropbox: not connected"
    const val DROPBOX_LOGIN_EXPIRED = "Dropbox: login expired. Connect again."
    const val DROPBOX_CONNECTED = "Dropbox: connected"

    /**
     * Bubblewrap `!connected` / `broken` / else. Review 44 item 1: expired first (invalid_grant
     * clears DROPBOX_ENABLED together with RECONNECT_REQUIRED), then [connected], the same flag
     * as the Connect / Disconnect button, so a pre-8.1 install (enabled, no AUTH_STATE key, read
     * as NOT_LINKED) shows "connected".
     */
    fun dropbox(connected: Boolean, auth: AuthState): String = when {
        auth == AuthState.RECONNECT_REQUIRED -> DROPBOX_LOGIN_EXPIRED
        !connected -> DROPBOX_NOT_CONNECTED
        else -> DROPBOX_CONNECTED
    }

    fun waitingToUpload(n: Int): String = "Waiting to upload: $n"

    fun reminders(n: Int): String = "Reminders: $n scheduled"

    fun lastChecked(ms: Long, nowMs: Long): String = "Last checked: " + ago(ms, nowMs)

    /** Bubblewrap `ago`: "never" at <= 0, else Android's minute-resolution relative time. */
    fun ago(ms: Long, nowMs: Long): String =
        if (ms <= 0) "never"
        else DateUtils.getRelativeTimeSpanString(ms, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()

    /**
     * "Waiting to upload: N". Bubblewrap counts tap files not yet in Dropbox. Here a tap is an
     * event at once, so N = this device's events the next push would send
     * ([uploadableEvents] = `EventLogDao.countUploadableSince`, the push's own
     * [com.example.data.sync.events.EvtUploadPolicy.uploadable] rule past the event watermark)
     * + taps still in `PENDING_TAPS` (gated or failed, so not an event yet). Review 34 item 3:
     * not `LocalTapQueue.pending().size` alone.
     */
    fun uploadBacklog(uploadableEvents: Int, pendingTaps: Int): Int = uploadableEvents + pendingTaps
}
