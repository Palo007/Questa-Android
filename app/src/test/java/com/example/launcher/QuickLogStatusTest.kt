package com.example.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.sync.auth.AuthState
import com.example.reminders.PrefsArmedKeyStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.22-B: the Bubblewrap quick-log status rows (SettingsActivity.kt:115-121, :145-146, `ago`
 * :169-171) with their exact texts and formats, and the "Waiting to upload" count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogStatusTest {

    @Test
    fun `dropbox line has the three Bubblewrap texts`() {
        assertEquals("Dropbox: not connected", QuickLogStatus.dropbox(false, AuthState.NOT_LINKED))
        assertEquals("Dropbox: login expired. Connect again.", QuickLogStatus.dropbox(false, AuthState.RECONNECT_REQUIRED))
        assertEquals("Dropbox: connected", QuickLogStatus.dropbox(true, AuthState.LINKED))
    }

    /** Review 44 item 1: expired first; then the same `connected` flag as the Connect / Disconnect button. */
    @Test
    fun `dropbox line - pre-8_1 install enabled with no saved auth state reads connected`() {
        assertEquals("Dropbox: connected", QuickLogStatus.dropbox(true, AuthState.NOT_LINKED))
        assertEquals("Dropbox: login expired. Connect again.", QuickLogStatus.dropbox(true, AuthState.RECONNECT_REQUIRED))
        assertEquals("Dropbox: not connected", QuickLogStatus.dropbox(false, AuthState.LINKED))
    }

    @Test
    fun `count rows use the Bubblewrap formats`() {
        assertEquals("Waiting to upload: 0", QuickLogStatus.waitingToUpload(0))
        assertEquals("Waiting to upload: 7", QuickLogStatus.waitingToUpload(7))
        assertEquals("Reminders: 0 scheduled", QuickLogStatus.reminders(0))
        assertEquals("Reminders: 3 scheduled", QuickLogStatus.reminders(3))
    }

    @Test
    fun `last checked is never at 0 and a minute-resolution relative time otherwise`() {
        val now = 1_790_000_000_000L
        assertEquals("Last checked: never", QuickLogStatus.lastChecked(0L, now))
        assertEquals("Last checked: never", QuickLogStatus.lastChecked(-5L, now))
        assertEquals("Last checked: 5 minutes ago", QuickLogStatus.lastChecked(now - 5 * 60_000L, now))
        assertEquals("Last checked: 0 minutes ago", QuickLogStatus.lastChecked(now - 20_000L, now))
    }

    /** The event half is `EventLogDao.countUploadableSince` (parity: UploadBacklogCountTest). */
    @Test
    fun `waiting to upload is uploadable events plus unapplied taps`() {
        assertEquals(2, QuickLogStatus.uploadBacklog(uploadableEvents = 2, pendingTaps = 0))
        assertEquals(5, QuickLogStatus.uploadBacklog(uploadableEvents = 2, pendingTaps = 3))
        assertEquals(0, QuickLogStatus.uploadBacklog(uploadableEvents = 0, pendingTaps = 0))
    }

    @Test
    fun `prefs armed-key store keeps the last check time`() {
        val store = PrefsArmedKeyStore(ApplicationProvider.getApplicationContext<Application>())
        assertEquals(0L, store.lastCheckedAt())
        store.markChecked(1234L)
        assertEquals(1234L, PrefsArmedKeyStore(ApplicationProvider.getApplicationContext<Application>()).lastCheckedAt())
    }
}
