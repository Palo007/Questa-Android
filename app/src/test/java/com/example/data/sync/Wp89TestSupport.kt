package com.example.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.clock.HlcClock
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.merge.FakeStableMerger
import com.example.data.sync.merge.SnapshotMerger
import kotlinx.coroutines.flow.first
import org.junit.After
import java.io.File

/**
 * WP-8.9 test harness: one device with the WP-8.9 engines wired as 8.I will wire them, talking
 * to the shared [FakeDropboxServer] of [RoundTestBase]. No real network.
 */
class Wp89Device(
    context: Context,
    val deviceId: String,
    merger: SnapshotMerger = FakeStableMerger(),
    eventsForcePull: (suspend () -> Unit)? = null,
    eventsForcePush: (suspend () -> Unit)? = null,
) {
    val tempFile: File = File.createTempFile("ds89_$deviceId", ".preferences_pb").also { it.delete() }
    val prefsStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(produceFile = { tempFile })
    val syncDataStore = SyncDataStore(context, prefsStore)
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val hlc: HlcClock = DefaultHlcClock(0L) { }
    val repository = RpgRepository(
        db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
        db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
        db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
        syncDataStore, db, hlc
    )
    val tokens = InMemorySecureTokenStore()
    val manager = SyncManager(context, repository, syncDataStore, null, tokens, merger = merger, hlcClock = hlc, roundSteps = emptyList())
    val backupPrefs = BackupPrefs(prefsStore)
    var normalSyncCalls = 0
    val force = ForceOpsEngine(
        internals = manager,
        syncDataStore = syncDataStore,
        localSubset = { manager.buildLocalSnapshot() },
        isConfigured = { syncDataStore.isDropboxEnabled.first() && tokens.read()?.refreshToken?.isNotEmpty() == true },
        ratchet = { hlc.ratchet(it) },
        eventsForcePull = eventsForcePull,
        eventsForcePush = eventsForcePush,
        normalSync = { normalSyncCalls++; manager.sync(SyncTrigger.CONNECT) },
        backupPrefs = backupPrefs,
    )
    val firstConnect = FirstConnect(manager, force, { manager.buildLocalSnapshot() }, LocalContent(repository)::isEffectivelyEmpty)
    val importer = SectionImporter(repository, syncDataStore, backupPrefs)
    val live = LiveBackupSource(repository, syncDataStore, backupPrefs)

    suspend fun link() {
        StartupBarrier.markDone()
        FakeDropboxServer.seedScratchTokens(syncDataStore, tokens, "access-$deviceId", "refresh-$deviceId")
        syncDataStore.saveLocalDeviceId(deviceId)
        FakeDropboxServer.linkScratchAccount(syncDataStore)
        syncDataStore.markLinked(FakeDropboxServer.SCRATCH_ACCOUNT_ID)
    }

    suspend fun base() = db.syncBaseDao().read()

    fun close() {
        db.close()
        tempFile.delete()
    }
}

/** [RoundTestBase] plus [Wp89Device]s, closed after each test. */
abstract class Wp89TestBase : RoundTestBase() {
    private val wp89 = mutableListOf<Wp89Device>()

    /** 8.I W3 note: join the app's startup migrations so they never race this test's I/O. */
    @org.junit.Before fun awaitAppStartup() = kotlinx.coroutines.runBlocking {
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    protected fun device89(
        id: String = "dev-a",
        eventsForcePull: (suspend () -> Unit)? = null,
        eventsForcePush: (suspend () -> Unit)? = null,
    ) = Wp89Device(context, id, eventsForcePull = eventsForcePull, eventsForcePush = eventsForcePush).also { wp89 += it }

    @After fun closeWp89() {
        wp89.forEach { it.close() }
    }
}
