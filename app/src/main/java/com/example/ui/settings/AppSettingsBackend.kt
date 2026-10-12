package com.example.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.BuildConfig
import com.example.QuestaApplication
import com.example.data.boot.BootGate
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.CanonicalJson
import com.example.data.sync.ExportSections
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.ImportMode
import com.example.data.sync.ImportResult
import com.example.data.sync.LiveBackupSource
import com.example.data.sync.Section
import com.example.data.sync.SyncClocks
import com.example.data.sync.SyncKeys
import com.example.data.sync.SyncResult
import com.example.data.sync.SyncSubset
import com.example.data.sync.auth.AuthState
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.BackupPrefs
import com.example.data.sync.backup.UiPrefsOverlay
import com.example.data.sync.SectionImporter
import com.example.data.sync.events.DataStoreEvtCfgStore
import com.example.data.sync.syncDataStore
import com.example.reminders.AndroidReminderNotifier
import com.example.reminders.ReminderPayload
import com.example.startup.StartupMigrations
import com.example.ui.CardPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** WP-8.14: [SettingsBackend] over the engines on [QuestaApplication] (8.9 / 8.I W4 hand-off). */
class AppSettingsBackend(private val app: QuestaApplication) : SettingsBackend {

    private val prefs = app.applicationContext.syncDataStore
    private val store = app.syncDataStore
    private val repo = app.repository
    private val backupPrefs = BackupPrefs(prefs)
    private val uiPrefs = UiPrefsOverlay(prefs)
    // WP-8.15: the backup prefs overlay carries the local UI prefs (export and import).
    private val live = LiveBackupSource(repo, store, backupPrefs, uiPrefs)
    private val importer = SectionImporter(repo, store, backupPrefs, uiPrefs)
    private val avatar = AvatarStore.forApp(app)

    override val character: Flow<CharacterEntity?> = repo.character
    override val connected: Flow<Boolean> = store.isDropboxEnabled
    override val authState: Flow<AuthState> = store.authState
    override val lastSyncAt: Flow<Long> = store.lastSyncAt
    override val lastSyncError: Flow<String?> = store.lastSyncError
    override val lastBackupError: Flow<String?> = store.lastBackupError
    override val devices: Flow<List<DeviceEntity>> = repo.allDevices
    override val haptics: Flow<Boolean> = store.hapticsEnabled
    override val saveBtnTop: Flow<Boolean> = store.isSaveButtonTop
    override val notificationsEnabled: Flow<Boolean> = prefs.data.map { it[SyncKeys.NOTIFICATIONS_ENABLED] ?: false }
    override val lastExportTs: Flow<Long> = store.lastExportTs
    override val startupStatus: Flow<String?> = StartupMigrations.status
    override val localEditAt: Flow<Long?> = store.localEditAt

    override fun nowMs(): Long = SyncClocks.transport()
    // CR-KT-018: lastSyncAt and localEditAt are both stamped with wrapperSavedAt.
    override fun syncClockMs(): Long = SyncClocks.wrapperSavedAt()
    override fun appVersion(): String = "v" + BuildConfig.VERSION_NAME
    // WP-8.12-T1: against the HLC's own (offset) physical clock, so a debug clock offset is not shown as skew.
    override fun hlcSkewMs(): Long = repo.hlcClock.lastIssued.value - SyncClocks.mergeNow()

    override fun notificationPermission(): String {
        val ctx: Context = app
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return "default"
        return if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) "granted" else "denied"
    }

    override fun exactAlarmsAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return null
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
        return am.canScheduleExactAlarms()
    }

    override suspend fun deviceId(): String = store.getOrCreateDeviceId()
    override suspend fun paused(): Boolean = repo.pauseState().paused
    override suspend fun autoBackup(): AutoBackupEnabled = backupPrefs.autoBackupEnabled()
    override suspend fun eventCount(): Int? = repo.allLogs.first().size
    override suspend fun firstConnectPending(): Boolean {
        if (!store.isDropboxEnabled.first()) return false
        val bases = repo.baseStore ?: return false
        // The round's own rule (SyncManager: remote present && no base valid for APP_KEY + account).
        return bases.validFor(com.example.data.sync.auth.DropboxConfig.appKey(), store.getAccountId()) == null
    }

    // ---- WP-8.22-B: Bubblewrap quick-log rows; reads only, no sync is started ----
    // Review 44 item 2: one COUNT(*) (no rows loaded) and the prefs reads, all on IO.
    override suspend fun waitingToUpload(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val since = com.example.data.sync.DataStoreEventWatermark(store).get()
        val events = com.example.data.local.AppDatabase.getDatabase(app).eventLogDao()
            .countUploadableSince(since, store.getOrCreateDeviceId())
        val taps = com.example.data.LocalTapQueue(store, repo).pending().size
        com.example.launcher.QuickLogStatus.uploadBacklog(events, taps)
    }
    override suspend fun remindersScheduled(): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reminders.PrefsArmedKeyStore(app).read().size }
    override suspend fun remindersLastChecked(): Long =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reminders.PrefsArmedKeyStore(app).lastCheckedAt() }

    override suspend fun blockedEventFiles(): Int = DataStoreEvtCfgStore(prefs).read().pushBlocked.size

    override suspend fun takeMigratedToast(): Boolean {
        if (!backupPrefs.migratedToastOwed()) return false
        var owed = false
        prefs.edit { p ->
            if (p[MIGRATED_TOAST_SHOWN] != true) {
                p[MIGRATED_TOAST_SHOWN] = true
                owed = true
            }
        }
        return owed
    }

    override suspend fun saveCharacter(c: CharacterEntity) = repo.insertCharacter(c)

    override suspend fun authorizationUri(forForcePush: Boolean): String {
        // WP-8.I W4: Connect & Force Push marks the intent BEFORE the auth URL; a plain Connect clears it.
        if (forForcePush) app.forceOps.markConnectForForcePush() else app.forceOps.clearPendingForcePush()
        return app.syncManager.getAuthorizationUri()
    }

    override suspend fun sync(): SyncResult? = app.syncManager.sync()
    override suspend fun setDeviceName(name: String) { repo.setDeviceName(name) }
    override suspend fun disconnect() { app.syncManager.disconnect() }
    override fun appKey(): String = com.example.data.sync.auth.DropboxConfig.appKey()
    override suspend fun saveAppKey(key: String): SaveAppKeyResult =
        AppKeySaver(store, app.syncManager.tokenStore, app.appKeyMigration).save(key)
    override suspend fun forcePush(): SyncResult = app.forceOps.forcePush()
    override suspend fun forcePull(): SyncResult = app.forceOps.forcePull()
    override suspend fun forcePushEvents(): SyncResult = app.forceOps.forcePushEvents()
    override suspend fun setAutoBackup(v: AutoBackupEnabled) = backupPrefs.setAutoBackupEnabled(v)
    override suspend fun setHaptics(on: Boolean) = store.setHapticsEnabled(on)
    override suspend fun setSaveBtnTop(on: Boolean) = store.setSaveButtonTop(on)

    override suspend fun setNotificationsEnabled(on: Boolean) {
        prefs.edit { it[SyncKeys.NOTIFICATIONS_ENABLED] = on }
    }

    override suspend fun setPaused(on: Boolean) = repo.setPause(on)
    override suspend fun firstConnectPreview(): FirstConnectPreview = app.firstConnect.firstConnectPreview()
    override suspend fun resolveFirstConnect(c: FirstConnectChoice): SyncResult = app.firstConnect.resolveFirstConnect(c)
    override suspend fun resetEverything() = app.resetEngine.resetEverything()

    override fun postTestNotification() {
        AndroidReminderNotifier(app).post(ReminderPayload("Questa Test", "Notifications are working! ⚔️", "questa-test"))
    }

    override suspend fun previewImport(text: String): ImportPreview {
        val data = importer.decode(parseBackupJson(text))
        val detected = ExportSections.detect(data)
        val partial = (data["_backup"] as? JsonObject)?.get("partial")?.let { SyncSubset.jsTruthy(it) } == true
        return ImportPreview(detected, detected.associateWith { ExportSections.count(it, data) }, partial)
    }

    override suspend fun importBackup(text: String, sections: Set<Section>, mode: ImportMode): ImportResult =
        importer.importBackupText(text, sections, mode)

    override suspend fun startDay() {
        // Same body and lock as BootGate B3 (RYA opener when bound, else the real startDay).
        BootGate.dayMutex.withLock { repo.startDayBinding()() }
    }

    override suspend fun exportCounts(): Map<Section, Int?> {
        val s = live.fullLocalState().toSnapshot()
        val events = try { repo.allLogs.first().size } catch (e: Exception) { null }
        return Section.entries.associateWith { if (it == Section.EVENTS) events else ExportSections.count(it, s) }
    }

    override suspend fun exportText(sections: Set<Section>): String =
        CanonicalJson.stringifyPretty(live.exporter().export(sections.takeUnless { ExportSections.isFull(it) }))

    // ---- WP-8.15 ----
    override val cardPrefs: Flow<CardPrefs> = CardPrefs.flow(prefs.data)
    override val feedFilters: Flow<FeedFilters> = FeedFilters.flow(prefs.data)
    override val hasAvatarImage: Flow<Boolean> = avatar.image.map { it != null }
    override suspend fun setWidth(px: Int) = uiPrefs.setWidth(px)
    override suspend fun setNotesLines(n: Int) = uiPrefs.setNotesLines(n)
    override suspend fun setCardThick(px: Int) = uiPrefs.setCardThick(px)
    override suspend fun setShowStreaks(on: Boolean) = uiPrefs.setShowStreaks(on)
    override suspend fun setHideSyncDiag(on: Boolean) = uiPrefs.setHideSyncDiag(on)
    override suspend fun setHideConflictDecisions(on: Boolean) = uiPrefs.setHideConflictDecisions(on)

    override suspend fun setAvatarImage(uri: Uri): Result<Unit> {
        val cr = app.contentResolver
        val mime = try { cr.getType(uri) } catch (e: Exception) { null }
        val size = try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (e: Exception) { null }
        return avatar.set(mime, size) { cr.openInputStream(uri) }
    }

    override suspend fun removeAvatarImage() = avatar.remove()

    // ---- WP-8.14-R1 ----
    override suspend fun diagnostics(): DiagnosticsCapture {
        val tiers = SettingsUiState(autoBackup = try { backupPrefs.autoBackupEnabled() } catch (e: Exception) { AutoBackupEnabled() }).autoBackupSummary
        return DiagnosticsReader(app).read(nowMs(), tiers)
    }

    companion object {
        /** The one-time "Auto-backup upgraded" toast was shown (Opti's flag doubles as that marker). */
        val MIGRATED_TOAST_SHOWN = booleanPreferencesKey("auto_backup_migrated_toast_shown")
    }
}
