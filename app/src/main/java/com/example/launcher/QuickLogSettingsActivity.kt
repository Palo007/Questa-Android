package com.example.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.MainActivity
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.OPEN_SETTINGS_DROPBOX

/**
 * WP-8.13: Bubblewrap reaches its quick-log settings from Android Settings > Apps > Questa (the gear,
 * `APPLICATION_PREFERENCES`, Bubblewrap AndroidManifest.xml:222-232) and from the connect item. In
 * Kotlin those controls live in the app's own Settings (8.14), so this no-UI trampoline opens
 * MainActivity on Settings -> Dropbox and finishes. Manifest: 8.I patch I-1.
 */
class QuickLogSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { startActivity(settingsIntent(this)) } catch (e: Exception) { }
        finish()
    }

    companion object {
        /** The same target as the connect shortcut ([ShortcutPublisher.CONNECT_ID]). */
        fun settingsIntent(ctx: Context): Intent = Intent(Intent.ACTION_VIEW)
            .setClass(ctx, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SETTINGS, OPEN_SETTINGS_DROPBOX)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
