package com.example.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** PX-75: channel "reminders"/"Reminders", Opti tag, content intent, permission gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNotificationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `channel is created once with Opti-facing name`() {
        NotificationChannels.ensure(app)
        NotificationChannels.ensure(app)
        val ch = nm.getNotificationChannel(NotificationChannels.REMINDERS)
        assertEquals("Reminders", ch.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, ch.importance)
        assertEquals(1, nm.notificationChannels.size)
    }

    @Test
    fun `post shows title, body and tag in the reminders channel with a content intent`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidReminderNotifier(app).post(ReminderPayload("Water", "Time to score your habit!", "questa-h1"))
        val active = nm.activeNotifications.single()
        assertEquals("questa-h1", active.tag)
        val n = active.notification
        assertEquals(NotificationChannels.REMINDERS, n.channelId)
        assertEquals("Water", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Time to score your habit!", n.extras.getString(NotificationCompat.EXTRA_TEXT))
        assertNotNull(n.contentIntent)
        // Same task tag replaces the earlier notification, as a web Notification tag does.
        AndroidReminderNotifier(app).post(ReminderPayload("Water", "Missed at 08:00 - x", "questa-h1"))
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun `gate is closed without POST_NOTIFICATIONS on API 33+`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AndroidNotificationGate(app).canNotify())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AndroidNotificationGate(app).canNotify())
    }
}
