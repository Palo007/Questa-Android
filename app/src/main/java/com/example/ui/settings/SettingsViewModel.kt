package com.example.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.QuestaApplication
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.FirstConnect
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.ImportMode
import com.example.data.sync.ImportResult
import com.example.data.sync.Section
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncResult
import com.example.data.sync.UnknownFieldCodeException
import com.example.data.sync.auth.AuthState
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.BkTier
import com.example.ui.CardPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A parsed backup file for the import picker (Opti `detectExportSections` + `sec.count`). */
data class ImportPreview(
    val detected: List<Section>,
    val counts: Map<Section, Int>,
    val partial: Boolean,
)

/**
 * WP-8.14: everything Settings reads and does, over the app's engines (production:
 * [AppSettingsBackend]). Tests replace it.
 */
interface SettingsBackend {
    val character: Flow<CharacterEntity?>
    val connected: Flow<Boolean>
    val authState: Flow<AuthState>
    val lastSyncAt: Flow<Long>
    val lastSyncError: Flow<String?>
    val lastBackupError: Flow<String?>
    val devices: Flow<List<DeviceEntity>>
    val haptics: Flow<Boolean>
    val saveBtnTop: Flow<Boolean>
    val notificationsEnabled: Flow<Boolean>
    val lastExportTs: Flow<Long>
    /** [com.example.startup.StartupMigrations.status]. */
    val startupStatus: Flow<String?>
    /** CR-KT-018: [com.example.data.sync.SyncDataStore.localEditAt]. */
    val localEditAt: Flow<Long?> get() = flowOf(null)
    /** CR-KT-018: the badge's "now", on the same clock as [lastSyncAt] and [localEditAt]. */
    fun syncClockMs(): Long = nowMs()

    fun nowMs(): Long
    fun appVersion(): String
    /** Opti `hlcSkewMs()`: lastIssued − physical now. */
    fun hlcSkewMs(): Long
    fun notificationPermission(): String
    fun exactAlarmsAllowed(): Boolean?

    suspend fun deviceId(): String
    suspend fun paused(): Boolean
    suspend fun autoBackup(): AutoBackupEnabled
    suspend fun eventCount(): Int?
    suspend fun blockedEventFiles(): Int
    /** Linked, but no base valid for (APP_KEY, account): the next round stops at NeedsFirstConnectChoice. */
    suspend fun firstConnectPending(): Boolean
    /** True once after the EXPORT_INTERVAL_DAYS=14 migration (Opti toasts once, app.js:257-259). */
    suspend fun takeMigratedToast(): Boolean

    suspend fun saveCharacter(c: CharacterEntity)
    /** Clears (plain connect) or sets (Connect & Force Push) the pending intent, then the auth URL. */
    suspend fun authorizationUri(forForcePush: Boolean): String
    suspend fun sync(): SyncResult?
    suspend fun setDeviceName(name: String)
    suspend fun disconnect()
    /** The stored Dropbox app key, or "" when none is stored. */
    fun appKey(): String = ""
    suspend fun saveAppKey(key: String): SaveAppKeyResult = SaveAppKeyResult.NotReady
    suspend fun forcePush(): SyncResult
    suspend fun forcePull(): SyncResult
    suspend fun forcePushEvents(): SyncResult
    suspend fun setAutoBackup(v: AutoBackupEnabled)
    suspend fun setHaptics(on: Boolean)
    suspend fun setSaveBtnTop(on: Boolean)
    suspend fun setNotificationsEnabled(on: Boolean)
    suspend fun setPaused(on: Boolean)
    suspend fun firstConnectPreview(): FirstConnectPreview
    suspend fun resolveFirstConnect(c: FirstConnectChoice): SyncResult
    suspend fun resetEverything()
    fun postTestNotification()
    /** Throws on a file that is not JSON / not a backup / has a bad hash / an unknown field code. */
    suspend fun previewImport(text: String): ImportPreview
    suspend fun importBackup(text: String, sections: Set<Section>, mode: ImportMode): ImportResult
    /** After an import: Opti runs `startDay()` (app.js:8088). */
    suspend fun startDay()
    /** The local counts for the export picker (`sec.count(S)`); events may be null. */
    suspend fun exportCounts(): Map<Section, Int?>
    /** The file text for the picked sections (all = the normal full backup). */
    suspend fun exportText(sections: Set<Section>): String

    // ---- WP-8.15 (PX-88..90): Appearance, Activity Feed and avatar rows ----
    val cardPrefs: Flow<CardPrefs>
    val feedFilters: Flow<FeedFilters>
    val hasAvatarImage: Flow<Boolean>
    suspend fun setWidth(px: Int)
    suspend fun setNotesLines(n: Int)
    suspend fun setCardThick(px: Int)
    suspend fun setShowStreaks(on: Boolean)
    suspend fun setHideSyncDiag(on: Boolean)
    suspend fun setHideConflictDecisions(on: Boolean)
    /** Opti `uploadFace`: fails with [AvatarRejectedException] (Opti's alert text). */
    suspend fun setAvatarImage(uri: Uri): Result<Unit>
    suspend fun removeAvatarImage()

    // ---- WP-8.22-B: Bubblewrap quick-log rows (read-only) ----
    /** Own events the next push would send + taps not yet applied ([com.example.launcher.QuickLogStatus.uploadBacklog]). */
    suspend fun waitingToUpload(): Int = 0
    /** [com.example.reminders.ReminderScheduler.armedCount]. */
    suspend fun remindersScheduled(): Int = 0
    /** [com.example.reminders.ReminderScheduler.lastCheckedAt]; 0 = never. */
    suspend fun remindersLastChecked(): Long = 0L

    /** WP-8.14-R1: the Diagnostics screen's data (read-only). */
    suspend fun diagnostics(): DiagnosticsCapture = DiagnosticsCapture(DiagnosticsSnapshot(generatedAt = nowMs()), emptyList())
}

/**
 * WP-8.14 (PX-82..87, PX-111 UI): the Settings sheet's state and actions, with Opti's labels,
 * order, defaults and confirm texts (app.js:6487-7004, sync.js:3656-3808 at 7d5d4f3).
 */
class SettingsViewModel(private val backend: SettingsBackend) : ViewModel(), SettingsActions {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** An auth URL to open in the browser (Connect / Connect & Force Push). */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    /** Settings asks its host to close (Opti `closeSheet()` after a reset). */
    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    /** Asks the host for the POST_NOTIFICATIONS runtime permission. */
    private val _permissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val permissionRequests: SharedFlow<Unit> = _permissionRequests.asSharedFlow()

    /**
     * CR-KT-018: the header sync badge (Opti `updateSyncBadge`, app.js:7473-7495). Re-evaluated on
     * every change of its four inputs and every [SYNC_BADGE_POLL_MS] (Opti's 5 s poll). The poll
     * lives in the upstream of a `WhileSubscribed(0)` share, so it runs only while a
     * lifecycle-aware collector is active (app in the foreground; Opti: page visible); a new
     * subscription on resume re-evaluates at once (Opti: visibilitychange). Accepted difference:
     * Opti polls only while the badge is shown; this also polls while it is hidden, so a fresh
     * sync turns into "Last synced 1 hour ago" without waiting for a resume.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncBadge: StateFlow<SyncBadgeState> =
        combine(backend.connected, backend.localEditAt, backend.lastSyncAt, backend.lastSyncError) { c, e, at, err ->
            SyncBadgeInputs(c, e, at, err)
        }
            .transformLatest { i ->
                while (true) {
                    emit(syncBadgeFrom(i.enabled, i.localEditAt, i.lastSyncAt, i.lastError, backend.syncClockMs()))
                    delay(SYNC_BADGE_POLL_MS)
                }
            }
            .distinctUntilChanged()
            .catch { e -> if (e is CancellationException) throw e }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), SyncBadgeState.HIDDEN)

    private var pendingImportText: String? = null
    private var versionTaps = 0
    private var versionTapAt = 0L

    init {
        val sync = combine(backend.connected, backend.authState, backend.lastSyncAt, backend.lastSyncError, backend.lastBackupError) { c, a, at, e, be ->
            arrayOf<Any?>(c, a, at, e, be)
        }
        val local = combine(backend.character, backend.devices, backend.haptics, backend.saveBtnTop, backend.notificationsEnabled) { ch, d, h, s, n ->
            arrayOf<Any?>(ch, d, h, s, n)
        }
        viewModelScope.launch {
            combine(sync, local, backend.lastExportTs, backend.startupStatus) { s, l, exp, st -> Triple(s, l, exp to st) }
                // A storage read error must not crash the app from viewModelScope; the rows keep their last values.
                .catch { e -> if (e is CancellationException) throw e }
                .collect { (s, l, rest) ->
                    val ch = l[0] as CharacterEntity?
                    @Suppress("UNCHECKED_CAST") val devs = l[1] as List<DeviceEntity>
                    val devId = safe("") { backend.deviceId() }
                    val pending = safe(false) { backend.firstConnectPending() }
                    val waiting = safe(0) { backend.waitingToUpload() }
                    _state.update {
                        it.copy(
                            connected = s[0] as Boolean,
                            firstConnectPending = pending,
                            reconnectRequired = s[1] == AuthState.RECONNECT_REQUIRED,
                            authState = s[1] as AuthState,
                            waitingToUpload = waiting,
                            lastSyncAt = s[2] as Long,
                            lastSyncError = s[3] as String?,
                            lastBackupError = s[4] as String?,
                            charName = ch?.name ?: "Adventurer",
                            charFace = ch?.face ?: "🧙",
                            deviceId = devId,
                            deviceName = devs.firstOrNull { d -> d.id == devId }?.name ?: "",
                            haptics = l[2] as Boolean,
                            saveBtnTop = l[3] as Boolean,
                            notificationsEnabled = l[4] as Boolean,
                            lastExportTs = rest.first,
                            startupFailed = rest.second == com.example.startup.StartupMigrations.STATUS_FAILED,
                            nowMs = backend.nowMs(),
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(backend.cardPrefs, backend.feedFilters, backend.hasAvatarImage) { c, f, a -> Triple(c, f, a) }
                .catch { e -> if (e is CancellationException) throw e }
                .collect { (c, f, a) -> _state.update { it.copy(card = c, feed = f, hasAvatarImage = a) } }
        }
        refresh()
    }

    /** Re-reads the one-shot values (on open and after each action), as Opti re-renders the sheet. */
    fun refresh() {
        viewModelScope.launch {
            val paused = safe(false) { backend.paused() }
            val tiers = safe(AutoBackupEnabled()) { backend.autoBackup() }
            val pending = safe(false) { backend.firstConnectPending() }
            val waiting = safe(0) { backend.waitingToUpload() }
            val armed = safe(0) { backend.remindersScheduled() }
            val checked = safe(0L) { backend.remindersLastChecked() }
            _state.update {
                it.copy(
                    waitingToUpload = waiting,
                    remindersScheduled = armed,
                    remindersLastChecked = checked,
                    paused = paused,
                    autoBackup = tiers,
                    firstConnectPending = pending,
                    clockAhead = safe(0L) { backend.hlcSkewMs() } > com.example.data.clock.HlcClock.HLC_RATCHET_TOLERANCE_MS,
                    notificationPermission = backend.notificationPermission(),
                    exactAlarmsAllowed = backend.exactAlarmsAllowed(),
                    appVersion = backend.appVersion(),
                    nowMs = backend.nowMs(),
                    appKey = backend.appKey(),
                )
            }
            if (safe(false) { backend.takeMigratedToast() }) _toasts.tryEmit("Auto-backup upgraded to Monthly tier")
        }
    }

    // ---- Character (Opti setCharName / setCharFace) ----

    override fun setCharName(v: String) = launchSafe {
        val c = backend.character.firstOrNullValue() ?: return@launchSafe
        backend.saveCharacter(c.copy(name = v.trim().ifEmpty { "Adventurer" }))
    }

    override fun setCharFace(v: String) = launchSafe {
        val c = backend.character.firstOrNullValue() ?: return@launchSafe
        backend.saveCharacter(c.copy(face = v.ifEmpty { "🧙" }))
    }

    // ---- Sync block ----

    /** Opti `syncConnect()`: a plain Connect clears a pending force push (sync.js:141-143). */
    override fun connect() = launchSafe(onError = { _toasts.tryEmit("Error: ${it.localizedMessage}") }) {
        _openUrl.tryEmit(backend.authorizationUri(forForcePush = false))
    }

    override fun requestConnectForForcePush() = ask(ConfirmOp.CONNECT_FORCE_PUSH, SettingsTexts.CONNECT_PUSH_TITLE, SettingsTexts.CONNECT_PUSH_TEXT)

    override fun syncNow() = launchSafe {
        handleRound(backend.sync())
        refresh()
    }

    /**
     * A user-visible round ended (Sync now, or the OAuth return tail). NeedsFirstConnectChoice
     * opens the first-connect dialog (PX-111) with 8.9's preview; nothing merges until a choice.
     */
    fun handleRound(r: SyncResult?) {
        when (r) {
            SyncResult.NeedsFirstConnectChoice -> launchSafe(onError = { _toasts.tryEmit("Sync failed: ${it.localizedMessage}") }) {
                val p = backend.firstConnectPreview()
                _state.update { it.copy(firstConnect = p) }
            }
            SyncResult.InterlockRefused -> _toasts.tryEmit(SyncManager.INTERLOCK_REFUSED_MESSAGE)
            else -> Unit
        }
    }

    /** The persistent "Choose Pull or Push" button (review fix 1): the same dialog as a round's NeedsFirstConnectChoice. */
    override fun openFirstConnect() = handleRound(SyncResult.NeedsFirstConnectChoice)

    override fun setDeviceName(v: String) = launchSafe { backend.setDeviceName(v) }

    override fun requestDisconnect() = ask(ConfirmOp.DISCONNECT, SettingsTexts.DISCONNECT_TITLE, SettingsTexts.DISCONNECT_TEXT)

    override fun requestForcePush() = launchSafe {
        val n = try { backend.eventCount() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        show(SettingsConfirm(ConfirmOp.FORCE_PUSH, SettingsTexts.FORCE_PUSH_TITLE, SettingsTexts.forcePushText(n)))
    }

    override fun requestForcePull() = ask(ConfirmOp.FORCE_PULL, SettingsTexts.FORCE_PULL_TITLE, SettingsTexts.FORCE_PULL_TEXT)

    override fun requestForcePushEvents() = launchSafe {
        val blocked = safe(0) { backend.blockedEventFiles() }
        show(SettingsConfirm(ConfirmOp.FORCE_PUSH_EVENTS, SettingsTexts.EVENTS_PUSH_TITLE, SettingsTexts.eventsPushText(blocked)))
    }

    override fun setTier(tier: BkTier, on: Boolean) = launchSafe {
        backend.setAutoBackup(_state.value.autoBackup.with(tier, on))
        refresh()
    }

    // ---- Interaction / Activity Feed rows ----

    override fun setHaptics(on: Boolean) = launchSafe { backend.setHaptics(on) }
    override fun setSaveBtnTop(on: Boolean) = launchSafe { backend.setSaveBtnTop(on) }

    override fun setPaused(on: Boolean) = launchSafe {
        backend.setPaused(on)
        refresh()
    }

    /** Opti `setNotificationsPref(enabled)`. */
    override fun setNotificationsEnabled(on: Boolean) = launchSafe { backend.setNotificationsEnabled(on); refresh() }

    /** Opti `requestNotificationPermission()`: the host shows the OS prompt. */
    override fun requestNotificationPermission() {
        _permissionRequests.tryEmit(Unit)
    }

    /** The OS prompt answered: granted -> "Permission granted!" and On; else "Permission: denied" and Off. */
    fun onNotificationPermissionResult(granted: Boolean) = launchSafe {
        if (granted) {
            _toasts.tryEmit("Permission granted!")
            backend.setNotificationsEnabled(true)
        } else {
            _toasts.tryEmit("Permission: " + backend.notificationPermission())
            backend.setNotificationsEnabled(false)
        }
        refresh()
    }

    /** Opti `testNotification()`. */
    override fun testNotification() {
        if (backend.notificationPermission() != "granted") {
            _toasts.tryEmit("Notification permission not granted")
            return
        }
        backend.postTestNotification()
        _toasts.tryEmit("Test notification sent")
    }

    // ---- WP-8.15: Appearance (Opti openCat('appearance') / openOpt, app.js:6686-6692, 6872-6894) ----

    /** Opti `setWidth(px)`: saves, then `closeOpt(); openSettings()` (every menu closes). */
    override fun setWidth(px: Int) = launchSafe { backend.setWidth(px); closeMenus() }

    /** Opti `setNotesLines(n)`: saves and closes every menu. */
    override fun setNotesLines(n: Int) = launchSafe { backend.setNotesLines(n); closeMenus() }

    /** The inline slider (`oninput`: the value applies at once, the category stays open). Clamped 0..60. */
    override fun setCardThick(px: Int) = launchSafe { backend.setCardThick(px.coerceIn(0, 60)) }

    /** Toggles re-open their category (`setCatToggle(...); openCat(catKey)`). */
    override fun setShowStreaks(on: Boolean) = launchSafe { backend.setShowStreaks(on) }
    override fun setHideSyncDiag(on: Boolean) = launchSafe { backend.setHideSyncDiag(on) }
    override fun setHideConflictDecisions(on: Boolean) = launchSafe { backend.setHideConflictDecisions(on) }

    /** Opti `setHaptics(n)` from the Haptics menu: saves and closes every menu. */
    override fun chooseHaptics(on: Boolean) = launchSafe { backend.setHaptics(on); closeMenus() }

    override fun closeMenus() = _state.update { it.copy(option = null, category = null) }

    // ---- WP-8.15 (PX-91): haptics test (Opti `buzz(50)` + toast, app.js:2991, :6967) ----

    private var buzz: (() -> Boolean)? = null
    private var buzzCount = 0
    private var buzzLast: Boolean? = null
    private var systemHapticsOff = false

    /** The host binds the app's buzz (`View.performHapticFeedback`, the Dashboard's haptic). */
    fun bindHaptics(perform: (() -> Boolean)?, systemHapticsOff: Boolean = false) {
        buzz = perform
        this.systemHapticsOff = systemHapticsOff
        _state.update { it.copy(hapticsDiag = diag()) }
    }

    private fun diag() = HapticsDiag(if (buzz != null) HAPTICS_API else "undefined", buzzLast, buzzCount, systemHapticsOff)

    /** Opti "Test vibration": `buzz(50)` (count, then off when haptics are off), toast, re-open the menu. */
    override fun testVibration(): HapticsDiag {
        buzzCount++
        val perform = buzz
        val r = if (perform != null && _state.value.haptics) safe(false) { perform() } else false
        buzzLast = r
        _toasts.tryEmit("Vibrate returned: $r")
        val d = diag()
        _state.update { it.copy(hapticsDiag = d, option = "haptics") }
        return d
    }

    // ---- WP-8.15 (PX-90): avatar image (Opti uploadFace / removeFace, app.js:8419-8428) ----

    private val _avatarRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    /** Asks the host for the image picker (Opti `faceFile.click()`). */
    val avatarRequests: SharedFlow<Unit> = _avatarRequests.asSharedFlow()

    override fun browseAvatarImage() {
        _avatarRequests.tryEmit(Unit)
    }

    /** The picked image: Opti's alert on a refused file, else "Avatar image set". */
    fun setAvatarImage(uri: Uri) = launchSafe {
        val r = backend.setAvatarImage(uri)
        val e = r.exceptionOrNull()
        if (e == null) _toasts.tryEmit(AvatarStore.SET_TOAST)
        else _state.update { it.copy(alert = SettingsAlert("Error", (e as? AvatarRejectedException)?.message ?: AvatarStore.READ_ERROR)) }
    }

    override fun removeAvatarImage() = launchSafe {
        backend.removeAvatarImage()
        _toasts.tryEmit(AvatarStore.REMOVED_TOAST)
    }

    override fun openCategory(key: String?) = _state.update { it.copy(category = key) }
    override fun openOption(key: String?) = _state.update { it.copy(option = key) }

    // ---- Backup & transfer ----

    /** Opti `showExportSectionPicker()`: everything ticked. */
    override fun openExportPicker() = launchSafe {
        val counts = backend.exportCounts()
        val rows = Section.entries.map { SectionRow(it, SettingsTexts.sectionLabel(it), SettingsTexts.sectionHint(it), counts[it]) }
        _state.update { it.copy(picker = SectionPickerState(import = false, rows = rows, selected = Section.entries.toSet())) }
    }

    /** Opti `importData(ev)`: parse, detect, hash gate, then the section picker; errors as Opti's alerts. */
    fun beginImport(text: String) = launchSafe {
        val p = try {
            backend.previewImport(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(alert = importErrorAlert(text, e)) }
            return@launchSafe
        }
        if (p.detected.isEmpty()) {
            _state.update { it.copy(alert = SettingsAlert("Error", "That file does not look like a valid Questa backup.")) }
            return@launchSafe
        }
        pendingImportText = text
        val rows = p.detected.map { SectionRow(it, SettingsTexts.sectionLabel(it), SettingsTexts.sectionHint(it), p.counts[it] ?: 0) }
        _state.update {
            it.copy(picker = SectionPickerState(
                import = true, rows = rows, selected = p.detected.toSet(), partialFile = p.partial,
                missing = Section.entries.filter { s -> s !in p.detected },
            ))
        }
    }

    override fun togglePickerSection(s: Section, on: Boolean) = _state.update {
        val pk = it.picker ?: return@update it
        it.copy(picker = pk.copy(selected = if (on) pk.selected + s else pk.selected - s))
    }

    override fun setPickerAll(on: Boolean) = _state.update {
        val pk = it.picker ?: return@update it
        it.copy(picker = pk.copy(selected = if (on) pk.rows.map { r -> r.section }.toSet() else emptySet()))
    }

    override fun setImportMode(mode: ImportMode) = _state.update {
        val pk = it.picker ?: return@update it
        it.copy(picker = pk.copy(mode = mode))
    }

    override fun cancelPicker() {
        pendingImportText = null
        _state.update { it.copy(picker = null) }
    }

    /** Import: Opti's 'Import' confirm; export: the picked sections go to the host's format step. */
    override fun pickerGo() {
        val pk = _state.value.picker ?: return
        val picked = pk.picked
        if (picked.isEmpty()) return
        if (pk.import) {
            show(SettingsConfirm(ConfirmOp.IMPORT, "Import", SettingsTexts.importConfirmText(pk.mode, picked.size)))
            return
        }
        _state.update { it.copy(picker = null) }
        _exportRequests.tryEmit(picked.toSet())
    }

    /** The export picker's Continue: the host offers Save file / Share for these sections. */
    private val _exportRequests = MutableSharedFlow<Set<Section>>(extraBufferCapacity = 2)
    val exportRequests: SharedFlow<Set<Section>> = _exportRequests.asSharedFlow()

    /** The backup file text for [sections] (all = the normal full backup). */
    suspend fun exportText(sections: Set<Section>): String = backend.exportText(sections)

    /** Asks the host for the exact-alarm settings screen (API 31+, L14). */
    private val _exactAlarmRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val exactAlarmRequests: SharedFlow<Unit> = _exactAlarmRequests.asSharedFlow()

    override fun requestExactAlarms() {
        _exactAlarmRequests.tryEmit(Unit)
    }

    /** Opti `importFile.click()`: the host opens the document picker, then calls [beginImport]. */
    private val _importRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val importRequests: SharedFlow<Unit> = _importRequests.asSharedFlow()

    override fun openImportPicker() {
        _importRequests.tryEmit(Unit)
    }

    /** Opti `closeSheet()`. */
    override fun close() {
        _state.update { it.copy(category = null, option = null) }
        _closeRequests.tryEmit(Unit)
    }

    // ---- Reset / version ----

    override fun requestReset() = ask(ConfirmOp.RESET, SettingsTexts.RESET_TITLE, SettingsTexts.RESET_TEXT)

    /** Opti `tapVersionDebug()`: 5 taps within 3 s open the debug screen (WP-8.14-R1: Diagnostics). */
    override fun tapVersion() {
        val now = backend.nowMs()
        if (now - versionTapAt > 3000) versionTaps = 0
        versionTapAt = now
        versionTaps++
        if (versionTaps >= 5) {
            versionTaps = 0
            openDiagnostics()
        }
    }

    // ---- WP-8.14-R1: Diagnostics (Opti showSyncDebugOverlay / downloadFullDiagnostic) ----

    private var diagCapture: DiagnosticsCapture? = null

    fun openDiagnostics() = launchSafe {
        val c = backend.diagnostics()
        diagCapture = c
        _state.update {
            it.copy(diagnostics = DiagnosticsView(
                Diagnostics.rows(c), Diagnostics.ringNewestFirst(c), Diagnostics.fileName(c.snapshot.generatedAt),
                Diagnostics.exportText(c),
            ))
        }
    }

    override fun closeDiagnostics() {
        diagCapture = null
        _state.update { it.copy(diagnostics = null) }
    }

    /** Asks the host for a Save dialog with Opti's file name; the host then writes [diagnosticsExportText]. */
    private val _diagExportRequests = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val diagExportRequests: SharedFlow<String> = _diagExportRequests.asSharedFlow()

    override fun exportDiagnostics() {
        val v = _state.value.diagnostics ?: return
        _diagExportRequests.tryEmit(v.fileName)
    }

    /** The scrubbed JSON bundle (no tokens, verifier or account id), or null when the screen is closed. */
    fun diagnosticsExportText(): String? = diagCapture?.let { Diagnostics.exportText(it) }

    // ---- First connect (PX-111) ----

    override fun chooseFirstConnect(c: FirstConnectChoice) {
        val p = _state.value.firstConnect ?: return
        when (c) {
            FirstConnectChoice.PullFromDropbox -> {
                _state.update { it.copy(firstConnect = null) }
                runFirstConnect(c)
            }
            FirstConnectChoice.PushThisDevice -> {
                if (!p.pushAllowed) {
                    _toasts.tryEmit(SettingsTexts.FC_DISABLED)
                    return
                }
                show(SettingsConfirm(ConfirmOp.FIRST_CONNECT_PUSH, SettingsTexts.FC_TITLE, SettingsTexts.FC_PUSH_CONFIRM, yes = "Replace", no = "Cancel"))
            }
        }
    }

    override fun dismissFirstConnect() = _state.update { it.copy(firstConnect = null) }

    private fun runFirstConnect(c: FirstConnectChoice) = launchSafe {
        val r = backend.resolveFirstConnect(c)
        if (FirstConnect.isEmptyLocalRefusal(r)) _toasts.tryEmit(SettingsTexts.FC_DISABLED)
        else if (r is SyncResult.Error) _toasts.tryEmit("Sync failed: ${r.msg}")
        else if (r == SyncResult.InterlockRefused) _toasts.tryEmit(SyncManager.INTERLOCK_REFUSED_MESSAGE)
        refresh()
    }

    // ---- Dropbox app key ----

    override fun openAppKeyDialog() = _state.update { it.copy(appKeyDialog = true) }
    override fun dismissAppKeyDialog() = _state.update { it.copy(appKeyDialog = false) }

    override fun saveAppKey(key: String) = launchSafe {
        when (backend.saveAppKey(key)) {
            SaveAppKeyResult.Saved -> {
                _state.update { it.copy(appKeyDialog = false) }
                _toasts.tryEmit("App key saved")
            }
            SaveAppKeyResult.SavedAndReset -> {
                _state.update { it.copy(appKeyDialog = false) }
                _toasts.tryEmit("App key changed. Dropbox was disconnected and sync was reset.")
            }
            SaveAppKeyResult.DisconnectFirst -> _toasts.tryEmit("Disconnect Dropbox first.")
            SaveAppKeyResult.NotReady -> _toasts.tryEmit("Sync is not ready, restart the app.")
        }
        refresh()
    }

    // ---- Confirm / alert plumbing ----

    override fun dismissConfirm() = _state.update { it.copy(confirm = null) }
    override fun dismissAlert() = _state.update { it.copy(alert = null) }

    override fun confirm(op: ConfirmOp) {
        val c = _state.value.confirm
        if (c == null || c.op != op) return
        _state.update { it.copy(confirm = null) }
        when (op) {
            ConfirmOp.DISCONNECT -> launchSafe {
                backend.disconnect()
                _toasts.tryEmit("Dropbox disconnected")
                refresh()
            }
            ConfirmOp.FORCE_PUSH -> launchSafe {
                _toasts.tryEmit("Pushing this device's data to Dropbox…")
                reportForce(backend.forcePush())
            }
            ConfirmOp.FORCE_PULL -> launchSafe {
                _toasts.tryEmit("Pulling Dropbox's data onto this device…")
                reportForce(backend.forcePull())
            }
            ConfirmOp.FORCE_PUSH_EVENTS -> launchSafe {
                _toasts.tryEmit("Re-uploading events to Dropbox…")
                val r = try { backend.forcePushEvents() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    SyncResult.Error(com.example.data.sync.ErrorKind.INTERNAL, e.message ?: e.toString())
                }
                if (r is SyncResult.Error) {
                    // The engine records "event force push failed: <msg>" (Opti syncCfgSave lastError).
                    _toasts.tryEmit("Force push failed: " + r.msg.removePrefix("event force push failed: "))
                } else if (r == SyncResult.InterlockRefused) {
                    _toasts.tryEmit("Force push failed: " + SyncManager.INTERLOCK_REFUSED_MESSAGE)
                } else {
                    _toasts.tryEmit("Event history re-uploaded to Dropbox.")
                }
                refresh()
            }
            // Opti confirmConnectForForcePush: the intent flag is set BEFORE the redirect (WP-8.I W4).
            ConfirmOp.CONNECT_FORCE_PUSH -> launchSafe(onError = { _toasts.tryEmit("Could not start connect (storage unavailable).") }) {
                val uri = backend.authorizationUri(forForcePush = true)
                _toasts.tryEmit("Connecting to Dropbox…")
                _openUrl.tryEmit(uri)
            }
            ConfirmOp.RESET -> viewModelScope.launch {
                try {
                    backend.resetEverything()
                    _closeRequests.tryEmit(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e.toString()
                    _state.update { it.copy(alert = SettingsAlert("Reset Error",
                        "The reset did not finish: $msg\n\nSome data may already have been erased. Check Settings before continuing.")) }
                }
                refresh()
            }
            ConfirmOp.FIRST_CONNECT_PUSH -> {
                _state.update { it.copy(firstConnect = null) }
                runFirstConnect(FirstConnectChoice.PushThisDevice)
            }
            ConfirmOp.IMPORT -> runImport()
        }
    }

    private fun runImport() {
        val pk = _state.value.picker ?: return
        val text = pendingImportText ?: return
        val picked = pk.picked
        _state.update { it.copy(picker = null) }
        pendingImportText = null
        viewModelScope.launch {
            try {
                val r = backend.importBackup(text, picked.toSet(), pk.mode)
                if (Section.EVENTS in r.sections) {
                    _toasts.tryEmit("Imported · ${r.eventsAdded} events restored")
                    // Opti app.js:8578-8586.
                    val alert = if (r.eventsFailed > 0) SettingsAlert("Import incomplete",
                        "The sections you picked were restored, but only ${r.eventsAdded} of ${r.eventsAdded + r.eventsFailed}" +
                            " events could be written to this device. Keep your backup file and free up storage, then import again.", r.summary)
                    else SettingsAlert("Import complete", "${r.eventsAdded} events restored to this device.", r.summary)
                    _state.update { it.copy(alert = alert) }
                } else {
                    _toasts.tryEmit("Imported")
                    if (!r.full) {
                        _state.update { it.copy(alert = SettingsAlert("Import complete",
                            "Restored: " + r.sections.joinToString(", ") { s -> SettingsTexts.sectionLabel(s) } +
                                ".\n\nEverything else on this device was left as it was.")) }
                    }
                }
                try { backend.startDay() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                _state.update { it.copy(alert = SettingsAlert("Import Error",
                    "The import did not finish: $msg\n\nSome of your data may already have been replaced. Keep your backup file and try importing it again.")) }
            }
            refresh()
        }
    }

    private fun reportForce(r: SyncResult) {
        when (r) {
            is SyncResult.Error -> _toasts.tryEmit("Sync failed: ${r.msg}")
            SyncResult.InterlockRefused -> _toasts.tryEmit(SyncManager.INTERLOCK_REFUSED_MESSAGE)
            else -> Unit
        }
        refresh()
    }

    private fun ask(op: ConfirmOp, title: String, text: String) = show(SettingsConfirm(op, title, text))

    private fun show(c: SettingsConfirm) = _state.update { it.copy(confirm = c) }

    private fun launchSafe(onError: (Exception) -> Unit = { _toasts.tryEmit("Error: ${it.localizedMessage}") }, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { default }

    private suspend fun <T> Flow<T>.firstOrNullValue(): T = first()

    companion object {
        /** The [HapticsDiag.type] once the host bound a buzz. */
        const val HAPTICS_API = "performHapticFeedback"

        /** CR-KT-018: Opti `setInterval(updateSyncBadge, 5000)` (app.js:7491). */
        const val SYNC_BADGE_POLL_MS = 5000L

        /** Opti's import error alerts (app.js:8220-8246). */
        fun importErrorAlert(text: String, e: Throwable): SettingsAlert {
            if (e is UnknownFieldCodeException) {
                return SettingsAlert("Import Error", "This backup was made by a newer version of" +
                    " Questa. It uses a field this build does not understand (\"" + e.fieldCode + "\"). Update Questa, then import it again.")
            }
            if ((e.message ?: "").contains("hash mismatch")) {
                return SettingsAlert("Import Error", "This file appears to be corrupted or tampered with (hash mismatch). Import cancelled.")
            }
            val head = text.removePrefix("﻿").trimStart().firstOrNull()
            return if (head != null && head != '{') {
                SettingsAlert("Error", "That looks like a CSV or Markdown export. Those are for other apps and cannot be imported. Only a backup .json file can be imported.")
            } else {
                SettingsAlert("Error", "That file does not look like a valid Questa backup.")
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as QuestaApplication
                SettingsViewModel(AppSettingsBackend(app))
            }
        }
    }
}

/** CR-KT-018: the four inputs of [syncBadgeFrom], minus the clock. */
private data class SyncBadgeInputs(val enabled: Boolean, val localEditAt: Long?, val lastSyncAt: Long, val lastError: String?)

/** Parse helper kept here so the backend stays thin. */
internal fun parseBackupJson(text: String): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("That file does not look like a valid Questa backup.")
