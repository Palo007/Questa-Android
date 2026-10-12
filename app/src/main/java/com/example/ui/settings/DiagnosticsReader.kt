package com.example.ui.settings

import android.os.Build
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.BuildConfig
import com.example.QuestaApplication
import com.example.data.local.AppDatabase
import com.example.data.sync.SyncKeys
import com.example.data.sync.SyncDataStore
import com.example.data.sync.events.DataStoreEvtCfgStore
import com.example.data.sync.events.DiagRing
import com.example.data.sync.syncDataStore
import com.example.reminders.PrefsArmedKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.14-R1: reads [DiagnosticsSnapshot] from the stores the app already has. Read-only: no
 * write, no network, no clock (the caller passes [nowMs]). Every part is guarded, so one broken
 * store cannot hide the rest. The token values are read only to scrub the export.
 */
internal class DiagnosticsReader(private val app: QuestaApplication) {

    suspend fun read(nowMs: Long, autoBackupSummary: String): DiagnosticsCapture = withContext(Dispatchers.IO) {
        val prefs = app.applicationContext.syncDataStore
        val store: SyncDataStore = app.syncDataStore
        val repo = app.repository
        val p = guard(null) { prefs.data.first() }
        val tokens = guard(null) { app.syncManager.tokenStore.read() }
        val base = guard(null) { repo.baseStore?.read() }
        val evt = guard(null) { DataStoreEvtCfgStore(prefs).read() }
        val devId = guard("") { store.getOrCreateDeviceId() }
        // WP-8.22-C: the Settings source (repo.allDevices + the same device id), with its placeholder.
        val devName = guard(Diagnostics.shownDeviceName(emptyList(), devId)) { Diagnostics.shownDeviceName(repo.allDevices.first(), devId) }
        val legacyAccess = p?.get(SyncDataStore.DROPBOX_ACCESS_TOKEN)
        val legacyRefresh = p?.get(SyncDataStore.DROPBOX_REFRESH_TOKEN)
        val db = AppDatabase.getDatabase(app)
        val snap = DiagnosticsSnapshot(
            generatedAt = nowMs,
            appVersion = "v" + BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            sdkInt = Build.VERSION.SDK_INT,
            realAccountUnlocked = BuildConfig.REAL_ACCOUNT_UNLOCKED,
            authState = guard("?") { store.authState.first().name },
            dropboxEnabled = p?.get(SyncDataStore.DROPBOX_ENABLED) ?: false,
            refreshTokenPresent = !tokens?.refreshToken.isNullOrEmpty() || !legacyRefresh.isNullOrEmpty(),
            accessTokenPresent = !tokens?.accessToken.isNullOrEmpty() || !legacyAccess.isNullOrEmpty(),
            lastSyncAt = p?.get(SyncKeys.SYNC_LAST_AT) ?: 0L,
            lastSyncError = p?.get(SyncKeys.SYNC_LAST_ERROR),
            lastBackupError = p?.get(SyncKeys.SYNC_LAST_BACKUP_ERROR),
            deviceId = devId,
            deviceName = devName,
            baseRev = base?.rev,
            baseAppKey = base?.appKey,
            appKeyOfData = p?.get(SyncKeys.APP_KEY_OF_DATA),
            hlcLastIssued = guard(0L) { repo.hlcClock.lastIssued.value },
            roomVersion = guard(0) { db.openHelper.readableDatabase.version },
            rowCounts = guard(emptyMap()) { rowCounts(db) },
            evtFileCounts = evt?.fileCounts?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } ?: emptyMap(),
            evtFileRevCount = evt?.fileRevs?.size ?: 0,
            evtPushBlocked = p?.get(SyncKeys.EVT_PUSH_BLOCKED) ?: false,
            evtPushBlockedFiles = evt?.pushBlocked?.keys?.toList() ?: emptyList(),
            evtBadRevs = evt?.badRevs?.keys?.toList() ?: emptyList(),
            evtLastUploadTs = p?.get(SyncDataStore.EVT_LAST_UPLOAD_TS) ?: 0L,
            evtLastPullAt = evt?.lastPullAt ?: 0L,
            evtFullScanAt = evt?.fullScanAt ?: 0L,
            evtFullPushAt = evt?.fullPushAt ?: 0L,
            inboxLastRunAt = p?.get(SyncKeys.INBOX_LAST_RUN_AT) ?: 0L,
            inboxHabitsHash = p?.get(SyncKeys.INBOX_HABITS_HASH),
            inboxRemindersHash = p?.get(SyncKeys.INBOX_REMINDERS_HASH),
            pendingTaps = guard(0) { (p?.get(SyncKeys.PENDING_TAPS)?.let { Json.parseToJsonElement(it) } as? JsonArray)?.size ?: 0 },
            armedReminders = guard(emptyList()) { PrefsArmedKeyStore(app).read().sorted() },
            autoBackupTiers = autoBackupSummary,
            diagRing = DiagRing.process.snapshot(),
            clockOffsetMs = com.example.data.clock.DebugClockOffset.offsetMs(),
        )
        val secrets = listOfNotNull(
            tokens?.accessToken, tokens?.refreshToken, tokens?.accountId,
            legacyAccess, legacyRefresh,
            p?.get(SyncDataStore.OAUTH_VERIFIER), p?.get(SyncKeys.ACCOUNT_ID), base?.accountId,
        ).filter { it.isNotEmpty() }
        DiagnosticsCapture(snap, secrets)
    }

    private fun rowCounts(db: AppDatabase): Map<String, Int> {
        val names = mutableListOf<String>()
        db.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name")).use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        return names.associateWith { n ->
            db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM `" + n.replace("`", "") + "`")).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }

    private inline fun <T> guard(default: T, block: () -> T): T = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { default }
}
