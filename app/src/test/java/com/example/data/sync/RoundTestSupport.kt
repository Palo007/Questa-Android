package com.example.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.clock.HlcClock
import com.example.data.clock.StartupBarrier
import com.example.data.local.AppDatabase
import com.example.data.sync.auth.DropboxConfigRule
import com.example.data.sync.auth.InMemorySecureTokenStore
import com.example.data.sync.merge.SnapshotMerger
import com.example.data.sync.merge.FakeStableMerger
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * WP-8.6 test harness: one "device" (its own DataStore file, in-memory Room, token store and HLC)
 * talking to a shared [FakeDropboxServer]. No real network: DropboxService.client is the fake.
 */
class RoundDevice(
    context: Context,
    val deviceId: String,
    merger: SnapshotMerger = FakeStableMerger(),
    hlcSeed: Long = 0L,
    roundSteps: List<SyncRoundStep>? = emptyList(),
    sleeper: suspend (Long) -> Unit = {},
    mergeClock: () -> Long = SyncClocks.mergeNow,
    baseStoreOverride: BaseStore? = null,
) {
    val tempFile: File = File.createTempFile("ds_$deviceId", ".preferences_pb").also { it.delete() }
    val syncDataStore = SyncDataStore(context, PreferenceDataStoreFactory.create(produceFile = { tempFile }))
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val hlc: HlcClock = DefaultHlcClock(hlcSeed) { }
    val repository = RpgRepository(
        db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
        db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
        db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
        syncDataStore, db, hlc
    )
    val tokens = InMemorySecureTokenStore()
    val manager: SyncManager = SyncManager(
        context, repository, syncDataStore, null, tokens,
        merger = merger, baseStore = baseStoreOverride, hlcClock = hlc, roundSteps = roundSteps, sleeper = sleeper, mergeClock = mergeClock,
    )

    suspend fun link() {
        StartupBarrier.markDone()
        FakeDropboxServer.seedScratchTokens(syncDataStore, tokens, "access-$deviceId", "refresh-$deviceId")
        syncDataStore.saveLocalDeviceId(deviceId)
        FakeDropboxServer.linkScratchAccount(syncDataStore)
        syncDataStore.markLinked(FakeDropboxServer.SCRATCH_ACCOUNT_ID)
    }

    suspend fun seedBase(rev: String, stateJson: String) {
        db.syncBaseDao().writeAtomic(DropboxConfigRule.TEST_APP_KEY, FakeDropboxServer.SCRATCH_ACCOUNT_ID, rev, stateJson)
    }

    suspend fun base() = db.syncBaseDao().read()

    suspend fun conflictEvents(): Int = repository.allLogs.first().count { it.kind == "conflictResolved" }

    fun close() {
        db.close()
        tempFile.delete()
    }
}
