package com.example.ui.gestures

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Opti `buzz(ms)` (app.js:2991): `navigator.vibrate(ms)` unless `S.prefs.haptics === false`.
 * Only the call sites Opti has buzz; see [GestureSpec] for the durations.
 */
fun interface Buzz {
    fun buzz(ms: Long)
}

val LocalBuzz = staticCompositionLocalOf { Buzz { } }

/**
 * The device buzz. With the VIBRATE permission it is a one-shot vibration of exactly [ms], as
 * `navigator.vibrate(ms)`. Without it (the manifest does not declare it yet), it falls back to the
 * view's haptic feedback: a light tick for the 15 ms pickup buzz, a long-press buzz otherwise.
 */
class DeviceBuzz(
    private val context: Context,
    private val view: View?,
    private val enabled: () -> Boolean,
) : Buzz {
    override fun buzz(ms: Long) {
        if (!enabled()) return
        try {
            if (context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vib != null && vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") vib.vibrate(ms)
                    return
                }
            }
            view?.performHapticFeedback(
                if (ms <= GestureSpec.BUZZ_PICKUP_MS) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.LONG_PRESS
            )
        } catch (e: Exception) {
            android.util.Log.w("OptiBuzz", "buzz($ms) failed", e)
        }
    }
}

/**
 * The OS "haptic feedback" switch (`Settings.System.HAPTIC_FEEDBACK_ENABLED`). When it is off —
 * the factory default on MIUI/HyperOS and some OEM skins — the framework silently scales the
 * TOUCH/UNKNOWN vibration usages to zero, so every buzz is accepted but never played (the
 * vibrator service logs it as `ignored_for_settings`). Apps cannot override this; they can only
 * detect it and point the user at the system setting.
 */
@Suppress("DEPRECATION") // the replacement API (VibratorManager introspection) cannot read this switch
fun systemHapticFeedbackEnabled(context: Context): Boolean =
    try {
        android.provider.Settings.System.getInt(
            context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
        ) != 0
    } catch (e: Exception) {
        android.util.Log.w("OptiBuzz", "HAPTIC_FEEDBACK_ENABLED unreadable", e)
        true
    }

@Composable
fun rememberDeviceBuzz(hapticsEnabled: Boolean): Buzz {
    val context = LocalContext.current
    val view = LocalView.current
    val on by rememberUpdatedState(hapticsEnabled)
    return remember(context, view) { DeviceBuzz(context, view) { on } }
}
