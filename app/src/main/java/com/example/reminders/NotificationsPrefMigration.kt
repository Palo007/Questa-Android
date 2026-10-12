package com.example.reminders

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncKeys

/**
 * WP-8.14 review fix 2: the one-time default for Opti's `notificationsEnabled` pref.
 *
 * Opti's default is false (app.js:220) and its gate needs the pref (app.js:2166), so a FRESH
 * install stays silent until the user turns Notifications On. A Kotlin device that is upgraded
 * into WP-8.14 already had working reminders with no such pref; writing false there would silence
 * them with no warning. So, only when the key is missing: an upgrade (the D21 install class,
 * [upgrade]) or a device with armed reminders ([armedCount] > 0) gets true; anything else gets
 * false. A present key is never touched.
 */
object NotificationsPrefMigration {
    /** Returns the value written, or null when the key was already present. */
    suspend fun runIfNeeded(prefs: DataStore<Preferences>, upgrade: Boolean, armedCount: () -> Int): Boolean? {
        var written: Boolean? = null
        prefs.edit { p ->
            if (p[SyncKeys.NOTIFICATIONS_ENABLED] == null) {
                val armed = try { armedCount() } catch (e: Exception) { 0 }
                val v = upgrade || armed > 0 || !p[SyncKeys.REM_ARMED].isNullOrBlank()
                p[SyncKeys.NOTIFICATIONS_ENABLED] = v
                written = v
            }
        }
        return written
    }
}
