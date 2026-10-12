package com.example.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.R
import com.example.reminders.AndroidNotificationGate
import com.example.reminders.NotificationGate
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.OPEN_SETTINGS_BACKUP

/**
 * CR-KT-014: the prune warning as an Android notification on its own "Data maintenance" channel
 * (pattern of reminders/ReminderNotifier.kt). "Back up now" opens Settings at "Backup & transfer".
 * Returns false (the caller then uses the in-app banner) when notifications are not allowed, the
 * "Data maintenance" channel is blocked, or posting fails.
 */
class AndroidPruneWarner(
    private val ctx: Context,
    private val gate: NotificationGate = AndroidNotificationGate(ctx),
) : PruneWarner {

    override suspend fun warn(notice: PruneNotice): Boolean =
        try { gate.canNotify() && post(notice) } catch (e: Exception) { false }

    private fun post(notice: PruneNotice): Boolean {
        ensureChannel(ctx)
        if (!channelEnabled(ctx)) return false
        val backup = backupIntent(ctx)
        val pi = PendingIntent.getActivity(
            ctx, TAG.hashCode(), backup,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(PruneWarnText.title(notice))
            .setContentText(PruneWarnText.BODY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(PruneWarnText.title(notice) + " " + PruneWarnText.BODY))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, PruneWarnText.ACTION, pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(TAG, NOTIFICATION_ID, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL = "data_maintenance"
        const val TAG = "questa-prune-warning"
        const val NOTIFICATION_ID = 0

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Data maintenance", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        /** False when the user blocked only the "Data maintenance" channel (importance NONE). */
        fun channelEnabled(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return true
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = nm.getNotificationChannel(CHANNEL) ?: return false
            return ch.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Launch intent that opens Settings at the "Backup & transfer" block. */
        fun backupIntent(ctx: Context): Intent =
            (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent())
                .setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_SETTINGS, OPEN_SETTINGS_BACKUP)
    }
}
