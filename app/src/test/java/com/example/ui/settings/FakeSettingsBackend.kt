package com.example.ui.settings

import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.ImportMode
import com.example.data.sync.ImportResult
import com.example.data.sync.Section
import com.example.data.sync.SyncResult
import com.example.data.sync.auth.AuthState
import com.example.data.sync.backup.AutoBackupEnabled
import android.net.Uri
import com.example.ui.CardPrefs
import kotlinx.coroutines.flow.MutableStateFlow

/** WP-8.14: a recording [SettingsBackend] for the ViewModel and screen tests. */
class FakeSettingsBackend : SettingsBackend {
    val calls = mutableListOf<String>()

    override val character = MutableStateFlow<CharacterEntity?>(CharacterEntity(name = "Hero", face = "🐉", updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
    override val connected = MutableStateFlow(false)
    override val authState = MutableStateFlow(AuthState.NOT_LINKED)
    override val lastSyncAt = MutableStateFlow(0L)
    override val lastSyncError = MutableStateFlow<String?>(null)
    override val lastBackupError = MutableStateFlow<String?>(null)
    override val devices = MutableStateFlow<List<DeviceEntity>>(emptyList())
    override val haptics = MutableStateFlow(true)
    override val saveBtnTop = MutableStateFlow(false)
    override val notificationsEnabled = MutableStateFlow(false)
    override val lastExportTs = MutableStateFlow(0L)
    override val startupStatus = MutableStateFlow<String?>(null)
    // CR-KT-018: the badge's localEditAt; syncClockMs() falls back to nowMs() (= [now]).
    override val localEditAt = MutableStateFlow<Long?>(null)

    var now = 1_000_000_000L
    var skew = 0L
    var permission = "default"
    var exact: Boolean? = true
    var devId = "abcdef123456"
    var pausedValue = false
    var tiers = AutoBackupEnabled()
    var events: Int? = 120
    var blocked = 0
    var migratedToast = false
    var syncResult: SyncResult? = SyncResult.Ok
    var forceResult: SyncResult = SyncResult.Ok
    var eventsResult: SyncResult = SyncResult.Ok
    var preview = FirstConnectPreview(3, 1, 2, false, 10, 2, 5, false, 1_700_000_000_000L)
    var resolveResult: SyncResult = SyncResult.Ok
    var resetError: Exception? = null
    var importPreview: ImportPreview? = ImportPreview(listOf(Section.CHAR, Section.HABITS, Section.EVENTS), mapOf(Section.CHAR to 1, Section.HABITS to 4, Section.EVENTS to 9), partial = false)
    var previewError: Exception? = null
    var importResult: ImportResult? = null
    var importError: Exception? = null
    var lastImport: Triple<String, Set<Section>, ImportMode>? = null
    var exported: Set<Section>? = null
    var diag: DiagnosticsCapture? = null
    override suspend fun diagnostics(): DiagnosticsCapture = diag ?: super.diagnostics()

    override fun nowMs() = now
    override fun appVersion() = "v1.0"
    override fun hlcSkewMs() = skew
    override fun notificationPermission() = permission
    override fun exactAlarmsAllowed() = exact

    // WP-8.22-B: the Bubblewrap quick-log rows.
    var waiting = 0
    var armed = 0
    var remLastChecked = 0L
    override suspend fun waitingToUpload() = waiting
    override suspend fun remindersScheduled() = armed
    override suspend fun remindersLastChecked() = remLastChecked

    override suspend fun deviceId() = devId
    override suspend fun paused() = pausedValue
    override suspend fun autoBackup() = tiers
    override suspend fun eventCount() = events
    override suspend fun blockedEventFiles() = blocked
    var pending = false
    override suspend fun firstConnectPending() = connected.value && pending
    override suspend fun takeMigratedToast(): Boolean { val v = migratedToast; migratedToast = false; return v }

    override suspend fun saveCharacter(c: CharacterEntity) { calls += "saveCharacter:${c.name}|${c.face}"; character.value = c }
    override suspend fun authorizationUri(forForcePush: Boolean): String { calls += "auth:$forForcePush"; return "https://dropbox/auth" }
    override suspend fun sync(): SyncResult? { calls += "sync"; return syncResult }
    override suspend fun setDeviceName(name: String) { calls += "deviceName:$name" }
    override suspend fun disconnect() { calls += "disconnect" }
    var appKey = ""
    var saveAppKeyResult = SaveAppKeyResult.Saved
    override fun appKey() = appKey
    override suspend fun saveAppKey(key: String): SaveAppKeyResult {
        calls += "saveAppKey:$key"
        if (saveAppKeyResult == SaveAppKeyResult.Saved || saveAppKeyResult == SaveAppKeyResult.SavedAndReset) appKey = key.trim()
        return saveAppKeyResult
    }
    override suspend fun forcePush(): SyncResult { calls += "forcePush"; return forceResult }
    override suspend fun forcePull(): SyncResult { calls += "forcePull"; return forceResult }
    override suspend fun forcePushEvents(): SyncResult { calls += "forcePushEvents"; return eventsResult }
    override suspend fun setAutoBackup(v: AutoBackupEnabled) { calls += "tiers:$v"; tiers = v }
    override suspend fun setHaptics(on: Boolean) { calls += "haptics:$on"; haptics.value = on }
    override suspend fun setSaveBtnTop(on: Boolean) { calls += "saveBtnTop:$on"; saveBtnTop.value = on }
    override suspend fun setNotificationsEnabled(on: Boolean) { calls += "notif:$on"; notificationsEnabled.value = on }
    override suspend fun setPaused(on: Boolean) { calls += "pause:$on"; pausedValue = on }
    override suspend fun firstConnectPreview(): FirstConnectPreview { calls += "preview"; return preview }
    override suspend fun resolveFirstConnect(c: FirstConnectChoice): SyncResult { calls += "resolve:$c"; return resolveResult }
    override suspend fun resetEverything() { calls += "reset"; resetError?.let { throw it } }
    override fun postTestNotification() { calls += "testNotification" }
    override suspend fun previewImport(text: String): ImportPreview { previewError?.let { throw it }; return importPreview!! }
    override suspend fun importBackup(text: String, sections: Set<Section>, mode: ImportMode): ImportResult {
        calls += "import"
        lastImport = Triple(text, sections, mode)
        importError?.let { throw it }
        return importResult ?: ImportResult(Section.entries.filter { it in sections }, Section.entries.all { it in sections }, mode, true, 0, 0)
    }
    override suspend fun startDay() { calls += "startDay" }
    override suspend fun exportCounts(): Map<Section, Int?> = Section.entries.associateWith { if (it == Section.EVENTS) null else 2 }
    override suspend fun exportText(sections: Set<Section>): String { exported = sections; return "{}" }

    // WP-8.15
    override val cardPrefs = MutableStateFlow(CardPrefs())
    override val feedFilters = MutableStateFlow(FeedFilters())
    override val hasAvatarImage = MutableStateFlow(false)
    var avatarResult: Result<Unit> = Result.success(Unit)
    override suspend fun setWidth(px: Int) { calls += "width:$px"; cardPrefs.value = cardPrefs.value.copy(widthPx = px) }
    override suspend fun setNotesLines(n: Int) { calls += "notes:$n"; cardPrefs.value = cardPrefs.value.copy(notesLines = n) }
    override suspend fun setCardThick(px: Int) { calls += "cardThick:$px"; cardPrefs.value = cardPrefs.value.copy(cardThick = px) }
    override suspend fun setShowStreaks(on: Boolean) { calls += "showStreaks:$on"; cardPrefs.value = cardPrefs.value.copy(showStreaks = on) }
    override suspend fun setHideSyncDiag(on: Boolean) { calls += "hideSyncDiag:$on"; feedFilters.value = feedFilters.value.copy(hideSyncDiag = on) }
    override suspend fun setHideConflictDecisions(on: Boolean) { calls += "hideConflicts:$on"; feedFilters.value = feedFilters.value.copy(hideConflictDecisions = on) }
    override suspend fun setAvatarImage(uri: Uri): Result<Unit> { calls += "avatar"; if (avatarResult.isSuccess) hasAvatarImage.value = true; return avatarResult }
    override suspend fun removeAvatarImage() { calls += "avatarRemove"; hasAvatarImage.value = false }
}
