package com.example.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.clock.DebugClockOffset

/**
 * WP-8.12-T1 -- debug source set only (never in a release APK). Sets / reads the app clock offset
 * over adb; the manifest entry requires android.permission.DUMP, which adb shell holds and normal
 * apps cannot get.
 *
 *   adb shell am broadcast -a io.github.palo007.questa.dev.DEBUG_SET_CLOCK_OFFSET --el offsetMs 86400000 --include-stopped-packages -n io.github.palo007.questa.dev/com.example.debug.DebugClockReceiver
 *   adb shell am broadcast -a io.github.palo007.questa.dev.DEBUG_GET_CLOCK_OFFSET --include-stopped-packages -n io.github.palo007.questa.dev/com.example.debug.DebugClockReceiver
 *
 * `--include-stopped-packages` is required after `am force-stop` (FLAG_EXCLUDE_STOPPED_PACKAGES
 * applies to explicit broadcasts too).
 * offsetMs 0 clears it. The result line (`data="offsetMs=..."`) and logcat tag QuestaDebugClock
 * show the value in effect. The running process sees it at once, but the day is re-evaluated on
 * the next boot/foreground: force-stop and relaunch the app after setting it.
 */
class DebugClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (code, data) = handle(context, intent)
        Log.i(TAG, data)
        // `am broadcast` waits with a result receiver (an ordered send), so it prints this data.
        if (isOrderedBroadcast) setResult(code, data, null)
    }

    companion object {
        const val TAG = "QuestaDebugClock"
        const val ACTION_SET_SUFFIX = ".DEBUG_SET_CLOCK_OFFSET"
        const val ACTION_GET_SUFFIX = ".DEBUG_GET_CLOCK_OFFSET"
        const val EXTRA_OFFSET_MS = "offsetMs"

        /** Returns (resultCode, resultData): 1 = ok, -1 = refused. */
        fun handle(context: Context, intent: Intent): Pair<Int, String> {
            val pkg = context.packageName
            return when (intent.action) {
                pkg + ACTION_SET_SUFFIX -> {
                    if (!intent.hasExtra(EXTRA_OFFSET_MS)) return -1 to "error: missing --el $EXTRA_OFFSET_MS"
                    val ms = intent.getLongExtra(EXTRA_OFFSET_MS, 0L)
                    if (!DebugClockOffset.set(context, ms)) return -1 to "error: debug clock disabled"
                    1 to "offsetMs=${DebugClockOffset.offsetMs()}"
                }
                pkg + ACTION_GET_SUFFIX -> 1 to "offsetMs=${DebugClockOffset.offsetMs()}"
                else -> -1 to "error: unknown action ${intent.action}"
            }
        }
    }
}
