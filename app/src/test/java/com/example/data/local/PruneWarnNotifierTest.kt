package com.example.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.reminders.NotificationGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CR-KT-014 MAJOR-4: a blocked "Data maintenance" channel falls back to the in-app banner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PruneWarnNotifierTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private val t0 = 1_790_000_000_000L

    @Test fun channelBlocked_returnsFalse_noNotification() = runBlocking {
        nm.createNotificationChannel(NotificationChannel(AndroidPruneWarner.CHANNEL, "Data maintenance", NotificationManager.IMPORTANCE_NONE))
        assertFalse(AndroidPruneWarner(ctx, NotificationGate { true }).warn(PruneNotice(t0, t0 - 1000)))
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun channelOn_posts() = runBlocking {
        assertTrue(AndroidPruneWarner(ctx, NotificationGate { true }).warn(PruneNotice(t0, t0 - 1000)))
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test fun notificationsOff_returnsFalse() = runBlocking {
        assertFalse(AndroidPruneWarner(ctx, NotificationGate { false }).warn(PruneNotice(t0, t0 - 1000)))
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
