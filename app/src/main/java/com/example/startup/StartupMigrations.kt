package com.example.startup

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.QuestaApplication
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase
import com.example.data.sync.BaseStoreMigration
import com.example.data.sync.RoomBaseStore
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.auth.AndroidKeystoreTokenStore
import com.example.data.sync.auth.AuthState
import com.example.data.sync.auth.DropboxConfig
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.SecureTokenStore
import com.example.data.sync.auth.TokenMigration
import com.example.data.sync.syncDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** §5.0 W1 step 0: what existed on disk before the first DB or DataStore access (D21). */
data class StartupProbe(val dbExisted: Boolean, val dsHadKeys: Boolean)

/**
 * WP-8.21 (PX-112; D8, D16, D21; §5.0 W1). The startup migrations, in this order:
 *
 *  1. open the Room database (forces MIGRATION_6_7);
 *  2. decide and write the D16 marker `APP_KEY_OF_DATA` (only when absent);
 *     2b. [TokenMigration.moveFromDataStore] (legacy plaintext tokens -> SecureTokenStore);
 *     2c. restore reset (guess G3): only on the first boot of a copy restored from an Android backup;
 *  3. [BaseStoreMigration.runIfNeeded] with the marker as the base's app key;
 *  4. [AppKeyMigration.runIfNeeded];
 *  5. [StartupBarrier.markDone].
 *
 * Any exception: status [STATUS_FAILED], the barrier stays closed on this process (no round runs).
 * Every step is idempotent, so a crash between two steps resumes on the next launch.
 */
object StartupMigrations {
    const val DATABASE_NAME = "questa_database"
    const val SYNC_PREFS_NAME = "questa_sync_preferences"
    const val STATUS_FAILED = "startup_migration_failed"
    const val STATUS_DONE = "done"

    private val _status = MutableStateFlow<String?>(null)

    /** null while running, [STATUS_DONE] after step 5, [STATUS_FAILED] after an exception. */
    val status: StateFlow<String?> = _status

    /** Fire-and-forget work that must not hold the barrier (the legacy-grant revoke, guess G5). */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * §5.0 W1 step 0, called synchronously by `QuestaApplication.onCreate` before any DB or
     * DataStore access. File checks only. An empty DataStore file holds no key (an empty
     * Preferences proto is 0 bytes), so `length() > 0` is "the legacy DataStore has any key".
     */
    fun captureStep0(context: Context): StartupProbe = captureStep0(
        dbFile = context.getDatabasePath(DATABASE_NAME),
        dsFile = context.preferencesDataStoreFile(SYNC_PREFS_NAME)
    )

    fun captureStep0(dbFile: File, dsFile: File): StartupProbe =
        StartupProbe(dbExisted = dbFile.exists(), dsHadKeys = dsFile.exists() && dsFile.length() > 0L)

    /** §5.0 W1 step 5. Launched by `QuestaApplication.onCreate` (8.I) after `bootStartDay`. */
    suspend fun run(app: Application, dbExisted: Boolean, dsHadKeys: Boolean): Boolean =
        forApp(app).run(dbExisted, dsHadKeys)

    /**
     * The app key switch, as startup step 4 runs it. Settings runs the same check right after the
     * user saves a key.
     */
    fun appKeyMigration(
        app: Application,
        prefs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = app.syncDataStore,
        tokens: SecureTokenStore = AndroidKeystoreTokenStore(app),
    ): AppKeyMigration {
        return AppKeyMigration(
            prefs = prefs,
            tokens = tokens,
            exporter = SyncManagerBackupExporter { (app as QuestaApplication).syncManager.exportSnapshotToFile() },
            exportDir = app.filesDir,
            nowPhysical = { com.example.data.clock.PhysicalClock.nowMs() }, // clock-ok:backup-local-stamp
            onLegacyGrantCleared = { grant -> backgroundScope.launch { DropboxOAuth().revokeGrant(grant) } }
        )
    }

    /** Production wiring. The exporter reuses SyncManager's full export (8.3 bytes). */
    fun forApp(app: Application): StartupMigrationRunner {
        val db = AppDatabase.getDatabase(app)
        val prefs = app.syncDataStore
        val ds = SyncDataStore(app)
        val tokens: SecureTokenStore = AndroidKeystoreTokenStore(app)
        val baseStore = RoomBaseStore(db.syncBaseDao())
        val appKey = appKeyMigration(app, prefs, tokens)
        return StartupMigrationRunner(
            openDatabase = { withContext(Dispatchers.IO) { db.openHelper.writableDatabase } },
            prefs = prefs,
            tokenMigration = { TokenMigration.moveFromDataStore(ds, tokens) },
            baseStoreMigration = { key -> BaseStoreMigration(ds, baseStore, db.tombstoneDao()).runIfNeeded(key) },
            appKeyMigration = { appKey.runIfNeeded() },
            tokens = tokens,
            installStamp = InstallStamp(File(app.noBackupFilesDir, InstallStamp.FILE_NAME)),
            markDone = { StartupBarrier.markDone() },
            onStatus = { publish(it) },
            notificationsDefault = { cls ->
                com.example.reminders.NotificationsPrefMigration.runIfNeeded(
                    prefs, upgrade = cls == InstallStamp.LEGACY,
                    armedCount = { com.example.reminders.PrefsArmedKeyStore(app).read().size },
                )
            },
            appKeySeed = (app as? QuestaApplication)?.appKeySeed ?: "",
            syncDataStore = ds
        )
    }

    internal fun publish(status: String) {
        // WP-8.22-D item 1: a known failure lets SyncWorker / the OAuth redirect stop waiting.
        if (status == STATUS_FAILED) StartupBarrier.markFailed()
        _status.value = status
    }

    /** Test hook (review 46 F6): clears a published status and the barrier's failed flag. */
    internal fun resetStatusForTest() {
        _status.value = null
        StartupBarrier.clearFailedForTest()
    }
}

/**
 * The per-device install stamp (guess G3). It lives in `noBackupFilesDir`, which Android never
 * backs up or transfers, so a data copy restored onto a new phone arrives WITHOUT it.
 * Values: [FRESH] / [LEGACY] (the D21 class, kept for a crash before the marker write),
 * [RESTORE_PENDING] (a restored copy whose reset has not finished) and [RESTORED].
 */
class InstallStamp(private val file: File) {
    fun read(): String? = if (file.exists()) file.readText().trim().ifEmpty { null } else null

    /** Atomic: write a temp file, then rename, so a crash never leaves a half-written stamp. */
    fun write(value: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("install stamp rename failed")
        }
    }

    companion object {
        const val FILE_NAME = "questa_install_stamp"
        const val FRESH = "fresh"
        const val LEGACY = "legacy"
        const val RESTORE_PENDING = "restore-pending"
        const val RESTORED = "restored"
    }
}

/**
 * The testable body of [StartupMigrations]. Each step is injected, so StartupOrderTest can record
 * the order and CrashMidMigrationTest can make any step throw once.
 */
class StartupMigrationRunner(
    private val openDatabase: suspend () -> Unit,
    private val prefs: DataStore<Preferences>,
    private val tokenMigration: suspend () -> Unit,
    private val baseStoreMigration: suspend (dataAppKey: String) -> Unit,
    private val appKeyMigration: suspend () -> Boolean,
    private val tokens: SecureTokenStore,
    private val installStamp: InstallStamp,
    private val markDone: () -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val trace: (String) -> Unit = {},
    private val newDeviceId: () -> String = {
        "dev-" + UUID.randomUUID().toString().replace("-", "").take(8)
    },
    /** WP-8.14 review fix 2: the notificationsEnabled default, given the install class (best effort). */
    private val notificationsDefault: suspend (installClass: String) -> Unit = {},
    /** The build-time app key, written into the runtime store once when none is stored. */
    private val appKeySeed: String = "",
    /** The runtime key store. Null: leave DropboxConfig's store and cache as they are. */
    private val syncDataStore: SyncDataStore? = null
) {
    /** true = every step ran and the barrier is open; false = failed, barrier stays closed. */
    suspend fun run(dbExisted: Boolean, dsHadKeys: Boolean): Boolean = try {
        runSteps(dbExisted, dsHadKeys)
        markDone()
        trace("5:markDone")
        onStatus(StartupMigrations.STATUS_DONE)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // §5.0: the barrier stays closed forever on this process; no round runs.
        onStatus(StartupMigrations.STATUS_FAILED)
        try {
            prefs.edit { it[SyncKeys.SYNC_LAST_ERROR] = StartupMigrations.STATUS_FAILED }
        } catch (_: Exception) {
            // The DataStore may be what failed; the in-memory status still says so.
        }
        try {
            Log.e("Questa.startup", StartupMigrations.STATUS_FAILED, e)
        } catch (_: Throwable) {
            // android.util.Log is a stub in plain JVM tests.
        }
        false
    }

    private suspend fun runSteps(dbExisted: Boolean, dsHadKeys: Boolean) {
        // Load the runtime app key first, so it is set even when a later step fails. The install
        // class below comes from the file probe taken before any DataStore access, so this write
        // cannot turn a fresh install into a legacy one. Best effort: never closes the barrier.
        if (syncDataStore != null) {
            try {
                DropboxConfig.store = syncDataStore
                syncDataStore.applyAppKeySeedOnce(appKeySeed)
                DropboxConfig.refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        // Step 0b (file + one DataStore read, no decision): classify this install once, and
        // remember it in the no-backup stamp BEFORE step 1 creates the DB file. A crash between
        // step 1 and the marker write then cannot turn a fresh install into a "legacy" one.
        var stamp = installStamp.read()
        if (stamp == null) {
            val prior = prefs.data.first()
            stamp = when {
                prior[SyncKeys.APP_KEY_OF_DATA] != null -> InstallStamp.RESTORE_PENDING   // data came from another install
                // P5 (KT-43, guess G-W2-1): a pre-WP-8.21 backup has no marker but carries the old
                // phone's device id. Cost: one relink on an in-place upgrade from such a build.
                // dsHadKeys (file probe before any DataStore access) keeps a fresh install FRESH
                // even if some early code wrote a device id before this step.
                dsHadKeys && prior[SyncDataStore.DEVICE_ID] != null -> InstallStamp.RESTORE_PENDING
                dbExisted || dsHadKeys -> InstallStamp.LEGACY          // D21
                else -> InstallStamp.FRESH
            }
            installStamp.write(stamp)
        }

        // 1. DB open (MIGRATION_6_7).
        openDatabase()
        trace("1:db")

        // 2. The D16 marker: written here only when absent (D16: step 2 sets the initial value).
        val existing = prefs.data.first()[SyncKeys.APP_KEY_OF_DATA]
        // A fresh install takes the stored key, or stays unmarked until one is entered. Any other
        // install gets the sentinel, so its first real key runs the full reset.
        val marker: String? = existing ?: run {
            val initial = if (stamp == InstallStamp.FRESH) DropboxConfig.appKey().takeIf { it.isNotEmpty() }
                          else DropboxConfig.LEGACY_SENTINEL
            initial?.also { v -> prefs.edit { it[SyncKeys.APP_KEY_OF_DATA] = v } }
        }
        trace("2:marker=$marker")

        // 2b. Legacy plaintext tokens out of the backed-up DataStore file, as early as possible.
        tokenMigration()
        trace("2b:tokens")

        // WP-8.14 (review fix 2): the notificationsEnabled default. Best effort, no trace step:
        // it must never close the barrier.
        try {
            notificationsDefault(stamp)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // 2c. Restore reset (guess G3).
        if (stamp == InstallStamp.RESTORE_PENDING) {
            restoreReset()
            installStamp.write(InstallStamp.RESTORED)
            trace("2c:restore")
        }

        // 3. Base migration, bound to the data's app key.
        // No marker means a fresh install: no legacy base to move, and a base bound to "" never
        // matches a real key.
        baseStoreMigration(marker ?: "")
        trace("3:base")

        // 4. App-key switch (no-op unless the marker differs from APP_KEY).
        appKeyMigration()
        trace("4:appKey")

        // A failure status left by an earlier launch is stale once every step has passed.
        prefs.edit { p ->
            if (p[SyncKeys.SYNC_LAST_ERROR] == StartupMigrations.STATUS_FAILED) p.remove(SyncKeys.SYNC_LAST_ERROR)
        }
    }

    /**
     * A copy restored on a new phone: the encrypted tokens and the Keystore key did not come
     * along, and the old phone may still sync under the restored device id. So this copy is
     * unlinked and gets its own device id. Kept: the base and the marker (D8, D16), user data,
     * HLC, prefs and the local tap queue. Idempotent (a re-run only picks another fresh id).
     */
    private suspend fun restoreReset() {
        tokens.clear()
        val id = newDeviceId()
        prefs.edit { p ->
            p[SyncDataStore.DEVICE_ID] = id
            p.remove(SyncDataStore.DROPBOX_ACCESS_TOKEN)
            p.remove(SyncDataStore.DROPBOX_REFRESH_TOKEN)
            p.remove(SyncDataStore.DROPBOX_EXPIRES_AT)
            p.remove(SyncDataStore.OAUTH_VERIFIER)
            p[SyncDataStore.DROPBOX_ENABLED] = false
            p.remove(SyncKeys.ACCOUNT_ID)
            p[SyncKeys.AUTH_STATE] = AuthState.NOT_LINKED.name
            // Cursors of the OLD device's event files: the new id starts clean.
            p.remove(SyncDataStore.EVT_LAST_UPLOAD_TS)
            p.remove(SyncDataStore.EVT_FILE_REVS)
            p.remove(SyncDataStore.EVT_LAST_PULL_AT)
            p.remove(SyncKeys.EVT_FULL_SCAN_AT)
            p.remove(SyncKeys.EVT_BAD_REVS)
            p.remove(SyncKeys.EVT_FULL_PUSH_AT)
            p.remove(SyncKeys.EVT_FILE_COUNTS)
            p.remove(SyncKeys.EVT_PUSH_BLOCKED)
            // A pending purge names the OLD id, whose files the old phone may still own.
            p.remove(SyncKeys.PENDING_PURGE_DEV)
        }
    }
}

/** [com.example.data.sync.BackupExporter] over an existing full export that returns the file text. */
class SyncManagerBackupExporter(
    private val exportText: suspend () -> Result<String>
) : com.example.data.sync.BackupExporter {
    override suspend fun export(sections: Set<com.example.data.sync.Section>?): kotlinx.serialization.json.JsonObject {
        require(sections.isNullOrEmpty()) { "the pre-switch export is always full" }
        val text = exportText().getOrThrow()
        return kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
    }
}
