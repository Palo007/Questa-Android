package com.example.data.sync.auth

import androidx.annotation.VisibleForTesting
import com.example.BuildConfig
import com.example.data.sync.SyncDataStore

/**
 * Dropbox app settings. The app key is a public PKCE client_id, entered by the user at runtime
 * and kept in [SyncDataStore]; [appKey] reads a cached copy so callers stay synchronous.
 *
 * A builder may put `dropboxAppKey=<key>` in the gitignored `local.properties` (or set the
 * `DROPBOX_APP_KEY` env var). That value only seeds the builder's own install once, at first
 * start when no key is stored. A public build carries no key and asks the user for one.
 *
 * [LEGACY_SENTINEL] is never a real key: it marks grants and sync metadata written before
 * runtime keys existed, so `AppKeyMigration` can tell an upgrade from a fresh install.
 */
object DropboxConfig {
    @Volatile private var cached: String = ""

    /** The runtime app key, empty when nobody has supplied one. */
    fun appKey(): String = cached

    /** Reloads the cached key from [store]. Called at startup and after every key save. */
    suspend fun refresh() { cached = store?.dropboxAppKey() ?: "" }

    /** A key is stored. This is not the same as "linked to a Dropbox account". */
    val isConfigured: Boolean get() = cached.isNotEmpty()

    /** Never a real app key: marks a grant made before runtime keys, or a non-fresh install. */
    const val LEGACY_SENTINEL = "questa-legacy-grant"

    @Volatile internal var store: SyncDataStore? = null

    @VisibleForTesting internal fun setCacheForTests(key: String) { cached = key }

    /**
     * The redirect URI is per-flavor -- `questa://oauth` (release, no suffix) and
     * `questa-dev://oauth` (`.dev` builds), both set by `buildConfigField` in
     * `app/build.gradle.kts` so the daily-phone dev install and the release
     * install use two different OAuth schemes, registered separately on the Dropbox app (P-1).
     */
    val REDIRECT_URI: String get() = BuildConfig.OAUTH_REDIRECT_URI

    /**
     * Whether MainActivity runs a normal sync right after a successful connect.
     * Release: yes (unchanged). Debug: no -- the dev app's fresh local data would otherwise be
     * merged into the real `Apps/<app>/state.json` and uploaded. The debug build
     * only connects; a manual sync stays possible.
     */
    fun autoSyncAfterConnect(debug: Boolean = BuildConfig.DEBUG): Boolean = !debug
}
