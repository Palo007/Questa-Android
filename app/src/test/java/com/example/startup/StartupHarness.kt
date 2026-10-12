package com.example.startup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.BaseStoreMigration
import com.example.data.sync.RoomBaseStore
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.data.sync.auth.AuthTestSupport
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.FakeTokenEndpoint
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.auth.TokenMigration
import com.example.data.sync.auth.TokenSet
import com.example.data.sync.syncDataStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files

/**
 * WP-8.21 test harness: the real steps (Room in memory, the shared Robolectric DataStore file,
 * InMemorySecureTokenStore, SyncManager's real export) over one persistent "device". [launch]
 * builds a NEW runner over the same state, as a process restart would. [failOnce] makes the
 * named step throw on its next call only (a crash between steps).
 */
class StartupHarness(val context: Context) {
    val prefs = context.syncDataStore
    val ds = SyncDataStore(context)
    val tokens = InMemorySecureTokenStore()
    val endpoint = FakeTokenEndpoint()
    val db: AppDatabase
    val repo: RpgRepository
    val manager: SyncManager
    val baseStore: RoomBaseStore
    val exportDir: File = Files.createTempDirectory("wp821-exports").toFile()
    val noBackupDir: File = Files.createTempDirectory("wp821-nobackup").toFile()
    val stamp = InstallStamp(File(noBackupDir, InstallStamp.FILE_NAME))
    val revoked = mutableListOf<TokenSet>()
    val order = mutableListOf<String>()
    val traces = mutableListOf<String>()
    var barrierOpened = 0
    var lastStatus: String? = null
    var clock = 1_700_000_000_000L
    private val failOnce = mutableSetOf<String>()

    init {
        val (d, r) = AuthTestSupport.newRepository(context, ds)
        db = d
        repo = r
        baseStore = RoomBaseStore(db.syncBaseDao())
        manager = SyncManager(context, repo, ds, null, tokens, DropboxOAuth(http = endpoint.client))
    }

    fun failOnce(step: String) {
        failOnce += step
    }

    private fun maybeFail(step: String) {
        if (failOnce.remove(step)) throw IllegalStateException("injected crash at $step")
    }

    fun appKeyMigration() = AppKeyMigration(
        prefs = prefs,
        tokens = tokens,
        exporter = object : com.example.data.sync.BackupExporter {
            override suspend fun export(sections: Set<com.example.data.sync.Section>?) =
                SyncManagerBackupExporter { manager.exportSnapshotToFile() }.export(sections).also { maybeFail("export") }
        },
        exportDir = exportDir,
        nowPhysical = { clock++ },
        onLegacyGrantCleared = { revoked += it }
    )

    /** WP-8.14: the runner's notificationsEnabled default hook (production: NotificationsPrefMigration). */
    var notificationsDefault: suspend (String) -> Unit = {}

    /** The build-time app key seed the runner applies once at startup. */
    var appKeySeed: String = ""

    fun runner() = StartupMigrationRunner(
        openDatabase = { maybeFail("db"); db.openHelper.writableDatabase; order += "1" },
        prefs = prefs,
        tokenMigration = { maybeFail("tokens"); TokenMigration.moveFromDataStore(ds, tokens); order += "2b" },
        baseStoreMigration = { key -> maybeFail("base"); BaseStoreMigration(ds, baseStore, db.tombstoneDao()).runIfNeeded(key); order += "3" },
        appKeyMigration = { maybeFail("appKey"); appKeyMigration().runIfNeeded().also { order += "4" } },
        tokens = tokens,
        installStamp = stamp,
        markDone = { barrierOpened++ },
        onStatus = { lastStatus = it },
        trace = {
            traces += it
            if (it.startsWith("2:")) order += "2" else if (it.startsWith("2c")) order += "2c" else if (it.startsWith("5")) order += "5"
        },
        notificationsDefault = { notificationsDefault(it) },
        appKeySeed = appKeySeed,
        syncDataStore = ds
    )

    suspend fun launch(dbExisted: Boolean, dsHadKeys: Boolean): Boolean = runner().run(dbExisted, dsHadKeys)

    fun exports(): List<File> = AppKeyMigration.exports(exportDir)

    suspend fun p(): Preferences = prefs.data.first()

    /**
     * Clears the shared DataStore, then stores the key the test rule pinned, so the runner's
     * startup refresh keeps that key as the runtime key.
     */
    suspend fun reset() {
        prefs.edit { it.clear() }
        val key = com.example.data.sync.auth.DropboxConfig.appKey()
        if (key.isNotEmpty()) ds.saveDropboxAppKey(key)
    }

    fun close() {
        db.close()
        exportDir.deleteRecursively()
        noBackupDir.deleteRecursively()
    }
}
