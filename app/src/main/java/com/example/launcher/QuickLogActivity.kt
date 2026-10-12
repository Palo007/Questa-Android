package com.example.launcher

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * WP-8.13 (PX-78): target of a long-press habit item, as Bubblewrap `quicklog/QuickLogActivity.kt`.
 * No UI: apply (or queue) the tap, show a toast, finish without a transition. It never starts
 * MainActivity. The tap time is taken here, so a log made while offline keeps its real time.
 *
 * Manifest (8.I patch I-1): not exported, `Theme.Translucent.NoTitleBar`, `excludeFromRecents`,
 * `noHistory`, `taskAffinity=""` (Bubblewrap AndroidManifest.xml:215-220).
 */
class QuickLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Review 34 item 1: a recreate (dark mode, fold, locale) while the tap still applies must not
        // queue a second TapRecord; the first tap keeps running in the app scope and shows its toast.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val habitId = intent?.getStringExtra(EXTRA_HABIT_ID)
        val title = QuickLog.normTitle(intent?.getStringExtra(EXTRA_TITLE))
        val dir = QuickLog.normDir(intent?.getIntExtra(EXTRA_DIR, 1) ?: 1)
        val ts = com.example.data.clock.PhysicalClock.nowMs() // clock-ok:tap-physical
        val tz = TimeZone.getDefault()
        val deps = try { QuickLogRuntime.provider(application) } catch (e: Exception) { null }
        val appCtx = applicationContext
        QuickLogRuntime.scope.launch {
            val msg = if (deps == null) {
                if (habitId.isNullOrEmpty()) QuickLog.NO_HABIT else QuickLog.failed(title)
            } else {
                QuickLog.handleTap(habitId, title, dir, ts, tz, deps)
            }
            Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
            if (!isFinishing) finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val ACTION = "com.example.QUICK_LOG"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIR = "dir"
    }
}
