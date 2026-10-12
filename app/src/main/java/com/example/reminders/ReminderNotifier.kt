package com.example.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.R
import com.example.data.sync.SyncKeys
import com.example.data.sync.syncDataStore
import kotlinx.coroutines.flow.first

/**
 * WP-8.11 (PX-75): the reminder channel, as in Bubblewrap `ReminderReceiver.ensureChannel`:
 * id "reminders", name "Reminders", IMPORTANCE_DEFAULT, created once on API 26+.
 */
object NotificationChannels {
    const val REMINDERS = "reminders"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(REMINDERS) != null) return
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}

/** Posts one reminder notification. The single notification path (no dedupe layer, E5). */
fun interface ReminderNotifier {
    fun post(payload: ReminderPayload)
}

/**
 * Opti's gate in `checkReminders` (app.js:2166-2167): nothing fires and nothing is marked when
 * notifications cannot be shown.
 */
fun interface NotificationGate {
    fun canNotify(): Boolean
}

/**
 * WP-8.14: Opti's full gate `if (!S.prefs || !S.prefs.notificationsEnabled) return;` (app.js:2166)
 * then the permission (app.js:2167). [enabled] is the Settings pref (default false); a read error
 * counts as off.
 */
class PrefNotificationGate(
    private val enabled: () -> Boolean,
    private val os: NotificationGate,
) : NotificationGate {
    override fun canNotify(): Boolean {
        val on = try { enabled() } catch (e: Exception) { false }
        return on && os.canNotify()
    }

    companion object {
        /** The app's pref, read from the DataStore (the checker runs off the main thread). */
        fun forApp(ctx: Context): PrefNotificationGate {
            val app = ctx.applicationContext
            return PrefNotificationGate(
                enabled = {
                    kotlinx.coroutines.runBlocking {
                        app.syncDataStore.data.first()[SyncKeys.NOTIFICATIONS_ENABLED] ?: false
                    }
                },
                os = AndroidNotificationGate(app),
            )
        }
    }
}

class AndroidNotificationGate(private val ctx: Context) : NotificationGate {
    override fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
}

/**
 * Builds the notification like Bubblewrap (content intent = launch activity, FLAG_IMMUTABLE,
 * auto-cancel, PRIORITY_DEFAULT) and posts it under Opti's tag `questa-<taskId>`, so a newer
 * reminder of the same task replaces the older one, as a web Notification with the same tag does.
 */
class AndroidReminderNotifier(private val ctx: Context) : ReminderNotifier {
    override fun post(payload: ReminderPayload) {
        NotificationChannels.ensure(ctx)
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val contentPi = launch?.let {
            PendingIntent.getActivity(
                ctx, payload.tag.hashCode(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val n = NotificationCompat.Builder(ctx, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(payload.title)
            .setContentText(payload.body.ifEmpty { null })
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(payload.tag, NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Permission revoked between the gate and here: skip, as Bubblewrap does.
        }
    }

    companion object {
        const val NOTIFICATION_ID = 0
    }
}
