package com.example.startup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.sync.BackupExporter
import com.example.data.sync.CanonicalJson
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.SecureTokenStore
import com.example.data.sync.auth.TokenSet
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Runs only when the marker `APP_KEY_OF_DATA` differs from the current Dropbox app key
 * ([DropboxConfig.appKey], read at runtime). In practice: an install whose sync metadata belongs
 * to another app's Dropbox folder, either an old Kotlin install (marker
 * [DropboxConfig.LEGACY_SENTINEL]) or a build that ran under a different app key. In this order:
 *
 *  1. safety export of the full local state to `files/pre-appkey-switch-<ts>.json`;
 *  2. a grant minted under any key other than the current one (including the legacy sentinel) is
 *     handed to [onLegacyGrantCleared] for a best-effort revoke that never holds startup; a grant
 *     under the current key is never revoked. Then `tokens.clear()`;
 *  3. one DataStore edit: EVT_FILE_COUNTS, EVT_FILE_REVS, EVT_LAST_UPLOAD_TS, EVT_LAST_PULL_AT and
 *     EVT_BAD_REVS reset, DROPBOX_ENABLED := false, AUTH_STATE := NOT_LINKED, MONTHLY_BACKUPS := []
 *     (the list names months of the old folder);
 *  4. APP_KEY_OF_DATA := the current key, last, so a crash anywhere before it re-runs every step
 *     safely.
 *
 * The base is not touched: it is bound to the old app key, so `BaseStore.validFor(currentKey, …)`
 * is null and the next connect asks the user to make the first-connect choice.
 *
 * Takes the raw `DataStore<Preferences>` instead of SyncDataStore, which has no accessor for
 * APP_KEY_OF_DATA, EVT_FILE_COUNTS or EVT_BAD_REVS, and no BaseStore (it would be unused).
 */
class AppKeyMigration(
    private val prefs: DataStore<Preferences>,
    private val tokens: SecureTokenStore,
    private val exporter: BackupExporter,
    private val exportDir: File,
    private val nowPhysical: () -> Long,
    private val onLegacyGrantCleared: suspend (TokenSet) -> Unit = {}
) {
    /** true when the switch ran; false when the marker already equals the current key (no-op). */
    suspend fun runIfNeeded(): Boolean {
        val marker = prefs.data.first()[SyncKeys.APP_KEY_OF_DATA]
        val current = DropboxConfig.appKey()
        // No key stored: there is nothing to migrate to. The marker stays as it is.
        if (current.isEmpty()) return false
        // No marker yet: only a fresh install gets here, so there is no old data to reset.
        // Record the key and stop.
        if (marker == null) {
            prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = current }
            return false
        }
        if (marker == current) return false
        // A real switch (including the legacy sentinel): run the full reset below.

        writeExport()

        // Only a grant minted under another key belongs to the old app. A grant under the current
        // key (e.g. a build linked before this ran) is a live grant and is never revoked. The
        // local copy is still cleared unconditionally, because the edit below sets
        // AUTH_STATE := NOT_LINKED, which a kept token would contradict.
        // The revoke is handed off BEFORE the clear, so a crash inside
        // clear() re-runs with the grant still stored (a second revoke is harmless, best effort).
        val grant = tokens.read()
        if (grant != null && grant.appKey != DropboxConfig.appKey()) {
            try {
                onLegacyGrantCleared(grant)
            } catch (_: Exception) {
                // Best effort, as Opti's revoke on disconnect.
            }
        }
        tokens.clear()

        prefs.edit { p ->
            p.remove(SyncKeys.EVT_FILE_COUNTS)
            p.remove(SyncDataStore.EVT_FILE_REVS)
            p.remove(SyncDataStore.EVT_LAST_UPLOAD_TS)
            p.remove(SyncDataStore.EVT_LAST_PULL_AT)
            p.remove(SyncKeys.EVT_BAD_REVS)
            p[SyncDataStore.DROPBOX_ENABLED] = false
            p[SyncKeys.AUTH_STATE] = AuthState.NOT_LINKED.name
            p[SyncDataStore.MONTHLY_BACKUPS] = "[]"
        }

        // Last step (D16: the only other marker write).
        prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = current }
        return true
    }

    private suspend fun writeExport() {
        val text = CanonicalJson.stringifyPretty(exporter.export(null))
        exportDir.mkdirs()
        val ts = nowPhysical()
        val target = File(exportDir, "$EXPORT_PREFIX$ts.json")
        val tmp = File(exportDir, "$EXPORT_PREFIX$ts.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("pre-switch export rename failed")
        }
    }

    companion object {
        const val EXPORT_PREFIX = "pre-appkey-switch-"

        /** The finished safety exports in [dir] (temp files excluded). */
        fun exports(dir: File): List<File> =
            dir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) && f.name.endsWith(".json") }?.sortedBy { it.name }
                ?: emptyList()
    }
}
