package com.example.ui.settings

import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncLock
import com.example.data.sync.SyncTrigger
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.SecureTokenStore
import kotlinx.coroutines.withTimeoutOrNull

/** What happened when the user saved a Dropbox app key. */
enum class SaveAppKeyResult {
    /** The key was stored (or was already stored); no reset was needed. */
    Saved,
    /** The key changed: Dropbox was disconnected and the sync cursors were reset. */
    SavedAndReset,
    /** An empty key was refused because Dropbox is still connected. */
    DisconnectFirst,
    /** Startup has not finished, so nothing was written. */
    NotReady,
}

/**
 * Saves the Dropbox app key from Settings and runs the key-switch reset at once, under the sync
 * lock, so no sync round can see the new key with the old app's tokens and cursors.
 *
 * Clearing the key while a token is stored is refused: the next sync would treat the token as
 * belonging to another app and ask to reconnect, even if the same key were entered again.
 */
class AppKeySaver(
    private val store: SyncDataStore,
    private val tokens: SecureTokenStore,
    private val appKeyMigration: suspend () -> Boolean,
    private val startupTimeoutMs: Long = 10_000,
) {
    suspend fun save(key: String): SaveAppKeyResult {
        val k = key.trim()
        // Wait for startup first, with a limit: if startup failed, the lock would wait forever.
        withTimeoutOrNull(startupTimeoutMs) { SyncLock.awaitBarrier() } ?: return SaveAppKeyResult.NotReady
        return SyncLock.singleFlight(SyncTrigger.SETTINGS) {
            if (k.isEmpty() && tokens.read() != null) return@singleFlight SaveAppKeyResult.DisconnectFirst
            if (k == store.dropboxAppKey()) return@singleFlight SaveAppKeyResult.Saved
            store.saveDropboxAppKey(k)
            DropboxConfig.refresh()
            if (appKeyMigration()) SaveAppKeyResult.SavedAndReset else SaveAppKeyResult.Saved
        } ?: SaveAppKeyResult.NotReady
    }
}
