package com.example.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "QuestaReminder"
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "reminder receiver failed", t)
        } finally {
            pending?.finish()
        }
    }
}

/**
 * WP-8.11: one reminder alarm fired. Not exported (only our own PendingIntents start it). Runs
 * Opti's `checkReminders` over the stored tasks (which calls [ReminderRepository.markFired]),
 * then re-arms, all inside goAsync.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val runtime = ReminderRuntime.provider(context.applicationContext)
        runAsync { handle(runtime) }
    }

    companion object {
        suspend fun handle(runtime: ReminderRuntime, nowMs: Long = runtime.clock.nowMs()) = runtime.onAlarm(nowMs)
    }
}

/**
 * WP-8.11 (PX-74): exported, reacts only to boot, wall-clock change, timezone change and
 * package replaced (Bubblewrap `ReminderBootReceiver`); re-arms from the database, no network.
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val runtime = ReminderRuntime.provider(context.applicationContext)
        runAsync { handle(intent.action, runtime) }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED, // "android.intent.action.TIME_SET"
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        /** Returns true when a re-arm ran. */
        suspend fun handle(action: String?, runtime: ReminderRuntime, nowMs: Long = runtime.clock.nowMs()): Boolean {
            if (action !in ACTIONS) return false
            runtime.rearmAll(nowMs)
            return true
        }
    }
}

/**
 * P14 / CR-TWA-005: marker only. The Questa TWA looks up the action [ACTION] via PackageManager;
 * if this receiver exists, the TWA asks the user once to let this app own reminders and turn its
 * own off, so only one app fires each reminder. Exported with no permission so the TWA can see
 * it. [onReceive] does nothing on purpose. The action string is fixed and does not depend on
 * applicationId. No UI hint here (DEC-20).
 */
class ReminderOwnerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit

    companion object {
        const val ACTION = "io.github.palo007.questa.action.REMINDER_OWNER"
    }
}
