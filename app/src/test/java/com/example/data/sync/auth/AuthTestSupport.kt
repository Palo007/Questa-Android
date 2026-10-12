package com.example.data.sync.auth

import android.content.Context
import androidx.room.Room
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncKeys
import com.example.data.sync.syncDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File

/** WP-8.1 test helpers: a fake Dropbox token/revoke endpoint and DataStore inspection. */
class FakeTokenEndpoint {
    data class Canned(val code: Int, val body: String)

    /** Answers for `/oauth2/token`, FIFO. Empty -> 500. */
    val tokenAnswers = ArrayDeque<Canned>()
    val seen = mutableListOf<Request>()
    var revokeCode = 200

    fun tokenCalls() = seen.count { it.url.toString() == DropboxOAuth.TOKEN_URL }
    fun revokeCalls() = seen.filter { it.url.toString() == DropboxOAuth.REVOKE_URL }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val r = chain.request()
        seen.add(r)
        val canned = when (r.url.toString()) {
            DropboxOAuth.TOKEN_URL -> tokenAnswers.removeFirstOrNull() ?: Canned(500, "{}")
            DropboxOAuth.REVOKE_URL -> Canned(revokeCode, "null")
            else -> Canned(501, "{}")
        }
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(canned.code).message("x")
            .body(canned.body.toResponseBody("application/json".toMediaType())).build()
    }.build()
}

object AuthTestSupport {
    /**
     * 8.I (W3 close): Robolectric runs QuestaApplication.onCreate for every test, which launches the
     * real StartupMigrations.run on the shared DataStore and token files. Join it before a test
     * seeds those files, so the app's own run cannot race the test.
     */
    fun awaitAppStartup(context: Context) = kotlinx.coroutines.runBlocking {
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        Unit
    }

    fun newRepository(context: Context, ds: SyncDataStore, hlc: com.example.data.clock.HlcClock? = null): Pair<AppDatabase, RpgRepository> {
        awaitAppStartup(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            ds, db, hlc
        )
        // WP-8.6: rounds go through SyncLock, which awaits the (process-wide, one-shot) barrier.
        com.example.data.clock.StartupBarrier.markDone()
        return db to repo
    }

    /**
     * WP-8.6: a base valid for (the test app key, [accountId]), so a seeded remote is merged, not gated.
     * Suites using this must declare `@get:Rule val config = DropboxConfigRule()` so the runtime
     * app key matches [DropboxConfigRule.TEST_APP_KEY].
     */
    suspend fun seedValidBase(db: AppDatabase, rev: String, stateJson: String, accountId: String? = com.example.data.sync.FakeDropboxServer.SCRATCH_ACCOUNT_ID) {
        db.syncBaseDao().writeAtomic(DropboxConfigRule.TEST_APP_KEY, accountId, rev, stateJson)
    }

    /** The shared Robolectric DataStore file survives between methods; reset what these tests read. */
    suspend fun resetSyncPrefs(context: Context) {
        (context.applicationContext as? com.example.QuestaApplication)?.startupMigrationsJob?.join()
        context.syncDataStore.edit { it.clear() }
    }

    /** Every value in the DataStore, plus the raw file bytes, as one searchable string. */
    suspend fun dataStoreDump(context: Context): String {
        val values = context.syncDataStore.data.first().asMap().entries.joinToString("\n") { "${it.key.name}=${it.value}" }
        val file = File(context.filesDir, "datastore/questa_sync_preferences.preferences_pb")
        val raw = if (file.exists()) String(file.readBytes(), Charsets.ISO_8859_1) else ""
        return values + "\n" + raw
    }

    suspend fun setMarker(context: Context, value: String) {
        context.syncDataStore.edit { it[SyncKeys.APP_KEY_OF_DATA] = value }
    }

    suspend fun marker(context: Context): String? = context.syncDataStore.data.first()[SyncKeys.APP_KEY_OF_DATA]
}
