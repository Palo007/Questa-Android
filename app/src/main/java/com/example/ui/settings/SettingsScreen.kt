package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.launcher.QuickLogStatus
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.ImportMode
import com.example.data.sync.Section
import com.example.data.sync.backup.BkTier
import com.example.ui.theme.OptiRootTokens
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * WP-8.14: what the Settings sheet can ask for. [SettingsViewModel] implements it; every method
 * defaults to a no-op so screen tests can record only what they need.
 */
interface SettingsActions {
    fun close() {}
    fun setCharName(v: String) {}
    fun setCharFace(v: String) {}
    fun connect() {}
    fun openAppKeyDialog() {}
    fun dismissAppKeyDialog() {}
    fun saveAppKey(key: String) {}
    fun requestConnectForForcePush() {}
    fun syncNow() {}
    fun setDeviceName(v: String) {}
    fun requestDisconnect() {}
    fun requestForcePush() {}
    fun requestForcePull() {}
    fun requestForcePushEvents() {}
    fun setTier(tier: BkTier, on: Boolean) {}
    fun setHaptics(on: Boolean) {}
    fun setSaveBtnTop(on: Boolean) {}
    fun setPaused(on: Boolean) {}
    fun setNotificationsEnabled(on: Boolean) {}
    fun requestNotificationPermission() {}
    fun requestExactAlarms() {}
    fun testNotification() {}
    fun openCategory(key: String?) {}
    fun openOption(key: String?) {}
    fun openExportPicker() {}
    fun openImportPicker() {}
    fun togglePickerSection(s: Section, on: Boolean) {}
    fun setPickerAll(on: Boolean) {}
    fun setImportMode(mode: ImportMode) {}
    fun pickerGo() {}
    fun cancelPicker() {}
    fun requestReset() {}
    fun tapVersion() {}
    fun confirm(op: ConfirmOp) {}
    fun dismissConfirm() {}
    fun dismissAlert() {}
    fun chooseFirstConnect(c: FirstConnectChoice) {}
    fun dismissFirstConnect() {}
    fun openFirstConnect() {}
    // WP-8.15
    fun setWidth(px: Int) {}
    fun setNotesLines(n: Int) {}
    fun setCardThick(px: Int) {}
    fun setShowStreaks(on: Boolean) {}
    fun setHideSyncDiag(on: Boolean) {}
    fun setHideConflictDecisions(on: Boolean) {}
    fun chooseHaptics(on: Boolean) {}
    fun closeMenus() {}
    fun testVibration(): HapticsDiag = HapticsDiag()
    fun browseAvatarImage() {}
    fun removeAvatarImage() {}
    // WP-8.14-R1
    fun closeDiagnostics() {}
    fun exportDiagnostics() {}
}

private val Red = OptiRootTokens.Red
private val BackupErrorOrange = Color(0xFFF7A24E) // Opti lastBackupError #f7a24e (app.js:6548)

/** Opti `d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], {hour, minute})`. */
internal fun localDateTime(ms: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms)) + " " +
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/**
 * WP-8.14: Opti's Settings sheet (`openSettings()`, app.js:6487-6619 at 7d5d4f3), in Opti's order:
 * head, character name + avatar, the three category buttons, Sync, Backup & transfer, the clock
 * warning, then Reset everything and the version. [section] = Dropbox scrolls to Sync.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, section: SettingsRoute = SettingsRoute.Root, onForceCron: () -> Unit = {}, onRepairHistory: () -> Unit = {}) {
    val scroll = rememberScrollState()
    val syncBlock = remember { BringIntoViewRequester() }
    val backupBlock = remember { BringIntoViewRequester() }
    LaunchedEffect(section) {
        if (section == SettingsRoute.Dropbox) syncBlock.bringIntoView()
        if (section == SettingsRoute.Backup) backupBlock.bringIntoView()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OptiRootTokens.Bg, RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .verticalScroll(scroll)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            // Opti .settingsHead .btn: width 33%, padding 8px 10px, 13px.
            OptiButton(
                "Close", Kind.PRIMARY, Modifier.fillMaxWidth(0.33f).testTag("settings_close"),
                padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) { actions.close() }
        }
        CharacterRow(state, actions)
        // Opti .catBtnRow: wrap, gap 10px; .catBtn flex 1 1 30%, min-width 140px.
        OptiFlexRow(Modifier.fillMaxWidth().testTag("cat_row"), basis = 0.3f, gap = 10.dp, minItemWidth = 140.dp) {
            CatButton("🎨", "Appearance", "cat_appearance") { actions.openCategory("appearance") }
            CatButton("⚙️", "Interaction", "cat_interaction") { actions.openCategory("interaction") }
            CatButton("📋", "Activity Feed", "cat_activityFeed") { actions.openCategory("activityFeed") }
        }

        Column(Modifier.bringIntoViewRequester(syncBlock)) {
            ColTitle("Sync", "Sync\n" + SettingsTexts.SYNC_TIP)
        }
        // WP-8.22-B: Bubblewrap SettingsActivity.kt:115-121 status lines, first in the block.
        Text(QuickLogStatus.dropbox(state.connected, state.authState), fontSize = 12.sp, color = OptiRootTokens.Ink, modifier = Modifier.testTag("dropbox_status_row"))
        Text(QuickLogStatus.waitingToUpload(state.waitingToUpload), fontSize = 11.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("waiting_upload_row"))
        if (state.startupFailed) {
            Text(SettingsTexts.STARTUP_FAILED, fontSize = 12.sp, color = Red, modifier = Modifier.testTag("startup_failed_row"))
        }
        if (state.appKey.isEmpty()) {
            // No app key stored: connecting cannot work yet, so offer the key setup instead.
            SettingRow("Dropbox app key", "Set up", "app_key_setup_row") { actions.openAppKeyDialog() }
        } else {
            SettingRow("Dropbox app key", state.appKey, "app_key_row") { actions.openAppKeyDialog() }
            Text(APP_KEY_WARNING, fontSize = 11.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("app_key_warning"))
        }
        // The Connect and linked rows show only once a key is stored.
        if (state.appKey.isEmpty()) Unit
        else if (!state.connected) {
            if (state.reconnectRequired) {
                Text(SettingsTexts.RECONNECT_REQUIRED, fontSize = 12.sp, color = Red, modifier = Modifier.testTag("reconnect_required_row"))
            }
            // Opti: a .settingsRow with inline `display:flex; gap:8px` and `flex:1` buttons (one line).
            OptiFlexRow(Modifier.fillMaxWidth().testTag("connect_row")) {
                OptiButton("Connect Dropbox", Kind.GHOST, Modifier.testTag("connect_dropbox"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.connect() }
                OptiButton("Connect & Force Push", Kind.DANGER, Modifier.testTag("connect_force_push"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.requestConnectForForcePush() }
            }
        } else {
            ConnectedBlock(state, actions)
        }

        Column(Modifier.bringIntoViewRequester(backupBlock)) {
            ColTitle("Backup & transfer", "Backup & transfer\n" + SettingsTexts.BACKUP_TIP)
        }
        OptiFlexRow(Modifier.fillMaxWidth().testTag("backup_row"), basis = 0.5f) {
            OptiButton("Export", Kind.GHOST, Modifier.testTag("export_backup_button"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.openExportPicker() }
            OptiButton("Import", Kind.GHOST, Modifier.testTag("import_backup_button"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.openImportPicker() }
        }
        Text(
            "Last export: " + (if (state.lastExportTs > 0) localDateTime(state.lastExportTs) else "None"),
            fontSize = 11.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("last_export_row")
        )
        if (state.clockAhead) {
            Text(SettingsTexts.CLOCK_AHEAD, fontSize = 12.sp, color = Red, modifier = Modifier.testTag("clock_ahead_row"))
        }
        // Opti .resetRow: the small bordered "Reset everything" left, the version centred (absolute).
        Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                "Reset everything", color = Red.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(BorderStroke(1.dp, Red.copy(alpha = 0.7f)), RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)).clickable { actions.requestReset() }
                    .padding(horizontal = 10.dp, vertical = 5.dp).testTag("reset_everything")
            )
            Text(
                state.appVersion, color = OptiRootTokens.Muted.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 0.5.sp,
                // Letters level with the Reset button's top rim: top-aligned, no top padding, and -7 dp for the
                // font's own space above the glyphs (measured on the phone). The tap area grows downward.
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-7).dp).clickable { actions.tapVersion() }
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp).testTag("app_version")
            )
        }
    }

    state.category?.let { CategorySheet(it, state, actions) }
    state.option?.let { OptionMenu(it, state, actions) }
    state.picker?.let { SectionPickerDialog(it, actions) }
    state.diagnostics?.let { DiagnosticsDialog(it, actions, onForceCron, onRepairHistory) }
    SettingsModalDialogs(state, actions)
    if (state.appKeyDialog) {
        AppKeyDialog(
            current = state.appKey,
            redirectUri = com.example.data.sync.auth.DropboxConfig.REDIRECT_URI,
            onSave = { actions.saveAppKey(it) },
            onDismiss = { actions.dismissAppKeyDialog() },
        )
    }
}

private const val APP_KEY_WARNING = "Changing the key disconnects Dropbox and resets sync cursors."

/** Enter or change the Dropbox app key, with short help on creating the Dropbox app. */
@Composable
fun AppKeyDialog(current: String, redirectUri: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    var help by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(OptiRootTokens.Bg, RoundedCornerShape(16.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(16.dp)).padding(20.dp)
                .testTag("app_key_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Dropbox app key", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = OptiRootTokens.Ink),
                modifier = Modifier.fillMaxWidth().background(OptiRootTokens.Panel, RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(8.dp)).padding(10.dp)
                    .testTag("app_key_field")
            )
            if (current.isNotEmpty()) Text(APP_KEY_WARNING, fontSize = 11.sp, color = OptiRootTokens.Muted)
            Text(
                (if (help) "▾ " else "▸ ") + "How do I get one?", fontSize = 12.sp, color = OptiRootTokens.Accent,
                modifier = Modifier.clickable(role = Role.Button) { help = !help }.testTag("app_key_help_toggle")
            )
            if (help) {
                Text(
                    "1. At dropbox.com/developers, create an app with \"App folder\" access, not \"Full Dropbox\". " +
                        "Questa keeps its files inside its own app folder.\n" +
                        "2. Add this redirect URI: $redirectUri\n" +
                        "3. Under Permissions, turn on files.content.read and files.content.write.\n" +
                        "Then copy the App key from the app's Settings page.",
                    fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("app_key_help")
                )
            }
            OptiButton("Save", Kind.PRIMARY, Modifier.fillMaxWidth().testTag("app_key_save")) { onSave(text) }
            OptiButton("Cancel", Kind.GHOST, Modifier.fillMaxWidth().testTag("app_key_cancel")) { onDismiss() }
        }
    }
}

/** The confirm and alert dialogs (also shown by the host while Settings is closed, e.g. first connect). */
@Composable
fun SettingsModalDialogs(state: SettingsUiState, actions: SettingsActions) {
    state.confirm?.let { c ->
        OptiDialog(c.title, c.text, onDismiss = { actions.dismissConfirm() }) {
            OptiButton(c.yes, Kind.PRIMARY, Modifier.testTag("confirm_yes")) { actions.confirm(c.op) }
            OptiButton(c.no, Kind.GHOST, Modifier.testTag("confirm_no")) { actions.dismissConfirm() }
        }
    }
    state.alert?.let { a ->
        OptiDialog(a.title, a.text, onDismiss = { actions.dismissAlert() }, summary = a.summary) {
            OptiButton("OK", Kind.PRIMARY, Modifier.testTag("alert_ok")) { actions.dismissAlert() }
        }
    }
}

@Composable
private fun CharacterRow(state: SettingsUiState, actions: SettingsActions) {
    // Opti .charAvatarRow: no wrap, gap 6px, top-aligned; the name column takes the rest, the
    // avatar column its natural width (flex 0 0 auto).
    // Both label rows share one height (the taller), so the two fields line up.
    var showTip by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Character name", fontSize = 12.sp, color = OptiRootTokens.Muted)
            }
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            CommitField(state.charName, "", Modifier.fillMaxWidth().height(38.dp).testTag("set_name")) { actions.setCharName(it) }
        }
        AvatarColumn(state, actions) { showTip = !showTip }
    }
    if (showTip) Text(AppearanceTexts.AVATAR_TIP, fontSize = 11.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("avatar_tip_text"))
}

/** Opti's avatar column (app.js:6501-6516): label, "Remove" when an image is set, the tip, the emoji field and "Browse image". */
@Composable
private fun AvatarColumn(state: SettingsUiState, actions: SettingsActions, onTip: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Avatar", fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = Modifier.weight(1f))
            if (state.hasAvatarImage) {
                Text(
                    "Remove", fontSize = 12.sp, color = Red,
                    modifier = Modifier.clickable(role = Role.Button) { actions.removeAvatarImage() }.padding(horizontal = 6.dp).testTag("avatar_remove")
                )
            }
            Text("ⓘ", fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = Modifier.clickable(role = Role.Button, onClick = onTip).semantics { contentDescription = "More info" }.testTag("avatar_tip"))
        }
        Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
        // Opti .caAvatarCtl: gap 6px; the emoji field is the "Avatar" label's width + 6px; both 38px high.
        Row(Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CommitField(state.charFace, "emoji", Modifier.width(52.dp).fillMaxHeight().testTag("set_face"), maxLen = 2, center = true) { actions.setCharFace(it) }
            OptiButton("Browse image", Kind.GHOST, Modifier.fillMaxHeight().testTag("avatar_browse"), padding = PaddingValues(horizontal = 8.dp)) { actions.browseAvatarImage() }
        }
    }
}

/**
 * Opti's connected Sync block (app.js:6536-6566): the `.devNameWrap` header (status | "Device
 * name" | "Device xxxxxx" over Disconnect) and row (Sync now | field | Disconnect), then one
 * `.settingsRow` with the three red buttons (two per line below 480 px: Force Push Events wraps
 * to its own full line), then the Auto-backup row.
 */
@Composable
private fun ConnectedBlock(state: SettingsUiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.firstConnectPending) {
            Text(SettingsTexts.FC_PENDING, fontSize = 12.sp, color = BackupErrorOrange, modifier = Modifier.testTag("fc_pending_row"))
            OptiButton(SettingsTexts.FC_OPEN, Kind.PRIMARY, Modifier.fillMaxWidth().testTag("fc_open")) { actions.openFirstConnect() }
        }
        DeviceNameLayout(Modifier.fillMaxWidth().testTag("dev_name_block")) {
            Column {
                Text(
                    "Last sync: " + SettingsTexts.relativeTime(state.lastSyncAt, state.nowMs),
                    fontSize = 11.sp, color = OptiRootTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("last_sync_row")
                )
                state.lastSyncError?.let {
                    Text(it, fontSize = 11.sp, color = Red, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("last_sync_error"))
                }
                state.lastBackupError?.let {
                    Text(it, fontSize = 11.sp, color = BackupErrorOrange, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("last_backup_error"))
                }
            }
            Text("Device name", fontSize = 12.sp, color = OptiRootTokens.Muted, maxLines = 1, softWrap = false, modifier = Modifier.testTag("dev_name_label"))
            Text("Device " + state.deviceShort, fontSize = 11.sp, color = OptiRootTokens.Muted, maxLines = 1, softWrap = false, modifier = Modifier.testTag("dev_short_label"))
            OptiButton("Sync now", Kind.GHOST, Modifier.testTag("sync_now_button"), padding = PaddingValues(10.dp)) { actions.syncNow() }
            CommitField(state.deviceName, state.deviceShort, Modifier.testTag("set_device_name")) { actions.setDeviceName(it) }
            OptiButton("Disconnect", Kind.GHOST, Modifier.testTag("disconnect_button"), padding = PaddingValues(10.dp)) { actions.requestDisconnect() }
        }
        OptiFlexRow(Modifier.fillMaxWidth().testTag("force_row"), basis = 0.5f) {
            OptiButton("Force push", Kind.DANGER, Modifier.testTag("force_push"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.requestForcePush() }
            OptiButton("Force pull", Kind.DANGER, Modifier.testTag("force_pull"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.requestForcePull() }
            OptiButton("Force Push Events", Kind.DANGER, Modifier.testTag("force_push_events"), fontSize = RowBtnFont, padding = RowBtnPadding) { actions.requestForcePushEvents() }
        }
        SettingRow("Auto-backup tiers", state.autoBackupSummary, "auto_backup_row") { actions.openOption("autoBackup") }
    }
}

/** Opti `CATS` (app.js:6775-6801); the Appearance rows and the two Activity Feed hide toggles are WP-8.15's. */
@Composable
private fun CategorySheet(key: String, state: SettingsUiState, actions: SettingsActions) {
    val title = when (key) {
        "appearance" -> "🎨 Appearance"
        "interaction" -> "⚙️ Interaction"
        else -> "📋 Activity Feed"
    }
    Dialog(onDismissRequest = { actions.openCategory(null) }) {
        Column(
            Modifier.fillMaxWidth().background(OptiRootTokens.Bg, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(14.dp)).padding(16.dp)
                .testTag("category_$key"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            when (key) {
                "appearance" -> {
                    MultiSetting(AppearanceTexts.WIDTH_LABEL, AppearanceTexts.WIDTH_DESC, AppearanceTexts.widthLabel(state.card.widthPx), "multi_width") { actions.openOption("width") }
                    MultiSetting(AppearanceTexts.NOTES_LABEL, AppearanceTexts.NOTES_DESC, AppearanceTexts.notesLabel(state.card.notesLines), "multi_notes") { actions.openOption("notes") }
                    CardThickSetting(state.card.cardThick) { actions.setCardThick(it) }
                    ToggleSetting(AppearanceTexts.STREAKS_LABEL, AppearanceTexts.STREAKS_DESC, state.card.showStreaks, "toggle_showStreaks") { actions.setShowStreaks(it) }
                }
                "interaction" -> {
                    // WP-8.15: tapping the label opens Opti's Haptics menu (diagnostics + Test vibration).
                    ToggleSetting("Haptics", "Vibration on taps and completions.", state.haptics, "toggle_haptics", onLabelClick = { actions.openOption("haptics") }) { actions.setHaptics(it) }
                    ToggleSetting("Save button position", "Top = centered next to title; Bottom = at foot of sheet.", state.saveBtnTop, "toggle_saveBtnTop") { actions.setSaveBtnTop(it) }
                    MultiSetting("Notifications", "Browser-based notification permission and status.", if (state.notificationsEnabled) "On" else "Off", "multi_notifications") { actions.openOption("notifications") }
                }
                "activityFeed" -> {
                    ToggleSetting(AppearanceTexts.HIDE_DIAG_LABEL, AppearanceTexts.HIDE_DIAG_DESC, state.feed.hideSyncDiag, "toggle_hideSyncDiag") { actions.setHideSyncDiag(it) }
                    ToggleSetting(AppearanceTexts.HIDE_CONFLICTS_LABEL, AppearanceTexts.HIDE_CONFLICTS_DESC, state.feed.hideConflictDecisions, "toggle_hideConflictDecisions") { actions.setHideConflictDecisions(it) }
                    ToggleSetting("Pause tracking", "Prevent HP loss and streak breaks when away. Dailies still give rewards.", state.paused, "toggle_pause") { actions.setPaused(it) }
                }
                else -> Unit
            }
            OptiButton("Done", Kind.GHOST, Modifier.fillMaxWidth().testTag("category_done")) { actions.openCategory(null) }
        }
    }
}

/** Opti `openOpt(key)` (app.js:6872-6998) for the keys 8.14 owns. */
@Composable
private fun OptionMenu(key: String, state: SettingsUiState, actions: SettingsActions) {
    Dialog(onDismissRequest = { actions.openOption(null) }) {
        Column(
            Modifier.fillMaxWidth().background(OptiRootTokens.Bg, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(14.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()).testTag("option_$key"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (key) {
                "notifications" -> {
                    Text("Notifications", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                    Hint("Local reminders for habits, dailies, and to-dos.")
                    OptiFlexRow(Modifier.fillMaxWidth()) {
                        Choice("On", state.notificationsEnabled, "notif_on") { actions.setNotificationsEnabled(true) }
                        Choice("Off", !state.notificationsEnabled, "notif_off") { actions.setNotificationsEnabled(false) }
                    }
                    Hint("System Permission: " + state.notificationPermission, Modifier.testTag("notif_permission"))
                    if (state.notificationPermission != "granted") {
                        OptiButton("Request Permission", Kind.PRIMARY, Modifier.fillMaxWidth().testTag("notif_request")) { actions.requestNotificationPermission() }
                    } else {
                        OptiButton("Send Test Notification", Kind.GHOST, Modifier.fillMaxWidth().testTag("notif_test")) { actions.testNotification() }
                    }
                    // WP-8.22-B: Bubblewrap SettingsActivity.kt:145-146, just above "Exact alarms" as there.
                    Hint(QuickLogStatus.reminders(state.remindersScheduled), Modifier.testTag("reminders_row"))
                    Hint(QuickLogStatus.lastChecked(state.remindersLastChecked, state.nowMs), Modifier.testTag("reminders_last_checked"))
                    state.exactAlarmsAllowed?.let { ok ->
                        Hint("Exact alarms: " + (if (ok) "allowed" else "not allowed"), Modifier.testTag("exact_alarm_status"))
                        if (!ok) OptiButton("Allow exact alarms", Kind.GHOST, Modifier.fillMaxWidth().testTag("exact_alarm_request")) { actions.requestExactAlarms() }
                    }
                }
                "autoBackup" -> {
                    Text("Auto-backup to Dropbox", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                    Hint("A full backup (same as Export) goes to Dropbox on each cadence you check. Each tier keeps its own cycling window on this device. Multiple devices each keep separate files (by device id). Missed windows don’t stack.")
                    listOf(
                        Triple(BkTier.FOUR_HOUR, "4-hourly", "Fires at most once per sync, ~4h apart" to "keeps last 10"),
                        Triple(BkTier.DAILY, "Daily", "Runs on first sync after local midnight" to "keeps last 7"),
                        Triple(BkTier.WEEKLY, "Weekly", "Runs on first sync after Monday midnight" to "keeps last 4"),
                        Triple(BkTier.MONTHLY, "Monthly", "Runs on first sync after 1st-of-month" to "keeps last 4"),
                    ).forEach { (tier, label, dm) ->
                        val on = state.autoBackup.isOn(tier)
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { actions.setTier(tier, !on) }.semantics { toggleableState = ToggleableState(on) }
                            .padding(vertical = 4.dp).testTag("tier_${tier.key}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (on) "☑" else "☐", fontSize = 16.sp, color = OptiRootTokens.Ink, modifier = Modifier.clearAndSetSemantics {})
                                Spacer(Modifier.width(6.dp))
                                Text(label, fontSize = 14.sp, color = OptiRootTokens.Ink)
                                Spacer(Modifier.width(6.dp))
                                Text("(" + dm.second + ")", fontSize = 12.sp, color = OptiRootTokens.Muted)
                            }
                            Text(dm.first, fontSize = 12.sp, color = OptiRootTokens.Muted)
                        }
                    }
                }
                "width" -> {
                    Text(AppearanceTexts.WIDTH_TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                    Hint(AppearanceTexts.WIDTH_HINT)
                    OptiFlexRow(Modifier.fillMaxWidth(), gap = 6.dp) {
                        WIDTH_OPTIONS.forEach { (label, px) -> Choice(label, state.card.widthPx == px, "width_$px") { actions.setWidth(px) } }
                    }
                }
                "notes" -> {
                    Text(AppearanceTexts.NOTES_TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                    Hint(AppearanceTexts.NOTES_HINT)
                    NOTES_OPTIONS.chunked(3).forEach { row ->
                        OptiFlexRow(Modifier.fillMaxWidth(), gap = 6.dp) {
                            row.forEach { (label, n) -> Choice(label, state.card.notesLines == n, "notes_$n") { actions.setNotesLines(n) } }
                        }
                    }
                }
                "haptics" -> {
                    Text("Haptics", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                    Hint(AppearanceTexts.HAPTICS_HINT)
                    OptiFlexRow(Modifier.fillMaxWidth()) {
                        Choice("On", state.haptics, "haptics_on") { actions.chooseHaptics(true) }
                        Choice("Off", !state.haptics, "haptics_off") { actions.chooseHaptics(false) }
                    }
                    Hint(AppearanceTexts.HAPTICS_DND)
                    Text(AppearanceTexts.hapticsDiagLine(state.hapticsDiag), fontSize = 11.sp, color = OptiRootTokens.Ink, modifier = Modifier.testTag("haptics_diag"))
                    OptiButton("Test vibration", Kind.GHOST, Modifier.testTag("haptics_test")) { actions.testVibration() }
                }
                else -> Unit
            }
            OptiButton("Done", Kind.GHOST, Modifier.fillMaxWidth().testTag("option_done")) { actions.openOption(null) }
        }
    }
}

/** Opti `showExportSectionPicker` / `showImportSectionPicker` (app.js:7798-7862, 8095-8173). */
@Composable
private fun SectionPickerDialog(p: SectionPickerState, actions: SettingsActions) {
    Dialog(onDismissRequest = { actions.cancelPicker() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).background(OptiRootTokens.Bg, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(14.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()).testTag(if (p.import) "import_picker" else "export_picker"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (p.import) "What do you want to restore?" else "What do you want to export?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            Hint(
                if (p.import) (if (p.partialFile) "This is a partial export. " else "This is a full backup. ") +
                    "Only the parts found in the file are listed. Everything is ticked; untick anything you want to keep as it is now."
                else "Everything is ticked, which writes a normal full backup. Untick a box to leave it out."
            )
            p.rows.forEach { r ->
                val on = r.section in p.selected
                Row(
                    Modifier.fillMaxWidth().clickable { actions.togglePickerSection(r.section, !on) }.testTag("pick_${r.section.key}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = on, onCheckedChange = { actions.togglePickerSection(r.section, it) })
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                        Text(r.hint, fontSize = 11.sp, color = OptiRootTokens.Muted)
                    }
                    Text(r.count?.toString() ?: "…", fontSize = 11.sp, color = OptiRootTokens.Muted)
                }
            }
            if (p.import) {
                if (p.missing.isNotEmpty()) {
                    Hint("Not in this file, so it will not be touched: " + p.missing.joinToString(", ") { SettingsTexts.sectionLabel(it) } + ".")
                }
                Text("How should it be applied?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                ModeRow(p.mode == ImportMode.REPLACE, "Replace", "Each ticked part is swapped for the copy in the file. Unticked parts stay exactly as they are now.", "mode_replace") { actions.setImportMode(ImportMode.REPLACE) }
                ModeRow(p.mode == ImportMode.MERGE, "Merge", "Nothing is removed. Items are matched by id and the newer one wins; anything that exists only on this device is kept.", "mode_merge") { actions.setImportMode(ImportMode.MERGE) }
            }
            OptiFlexRow(Modifier.fillMaxWidth().testTag("pick_buttons"), gap = 6.dp) {
                OptiButton("All", Kind.GHOST, Modifier.testTag("pick_all")) { actions.setPickerAll(true) }
                OptiButton("None", Kind.GHOST, Modifier.testTag("pick_none")) { actions.setPickerAll(false) }
                OptiButton(if (p.import) "Import" else "Continue", Kind.GHOST, Modifier.testTag("pick_go"), enabled = p.picked.isNotEmpty()) { actions.pickerGo() }
                OptiButton("Cancel", Kind.GHOST, Modifier.testTag("pick_cancel")) { actions.cancelPicker() }
            }
            Hint(p.note, Modifier.testTag("pick_note"))
        }
    }
}

/**
 * WP-8.14 (PX-111): the first-connect choice on [com.example.data.sync.SyncResult.NeedsFirstConnectChoice],
 * with 8.9's preview. Push is disabled (and refused by the engine with EMPTY_LOCAL) when
 * ![FirstConnectPreview.pushAllowed]. The Push confirm is the ViewModel's.
 */
@Composable
fun FirstConnectDialog(p: FirstConnectPreview, onChoice: (FirstConnectChoice) -> Unit, onDismiss: () -> Unit = {}) {
    val saved = if (p.remoteSavedAt > 0) localDateTime(p.remoteSavedAt) else "unknown"
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(OptiRootTokens.Bg, RoundedCornerShape(16.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(16.dp)).padding(20.dp)
                .testTag("first_connect_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(SettingsTexts.FC_TITLE, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            OptiButton(
                SettingsTexts.FC_PULL.format(p.remoteTasks, p.remoteLevel), Kind.PRIMARY,
                Modifier.fillMaxWidth().testTag("fc_pull")
            ) { onChoice(FirstConnectChoice.PullFromDropbox) }
            OptiButton(
                SettingsTexts.FC_PUSH.format(p.remoteTasks, p.remoteLevel, saved, p.localTasks, p.localLevel), Kind.DANGER,
                Modifier.fillMaxWidth().testTag("fc_push"), enabled = p.pushAllowed
            ) { onChoice(FirstConnectChoice.PushThisDevice) }
            if (!p.pushAllowed) {
                Text(SettingsTexts.FC_DISABLED, fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("fc_disabled_note"))
            }
            OptiButton("Cancel", Kind.GHOST, Modifier.fillMaxWidth().testTag("fc_cancel")) { onDismiss() }
        }
    }
}

// ---- small building blocks (Opti .btn / .catBtn / .setItem / .catSetting) ----

internal enum class Kind { PRIMARY, GHOST, DANGER }

/** Opti `.btn` (index.html:451-480): padding 10px 8px, 13px bold, radius 9px. */
internal val BtnPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)

/** Opti `#sheet .settingsRow .btn` below 480 px (index.html:1023-1034): 11px, padding 10px 6px, min-height 40px. */
internal val RowBtnPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
internal val RowBtnFont = 11.sp

/**
 * Opti `.btn`. The label wraps only at spaces: every caller either gives the button its full
 * width or sits it in an [OptiFlexRow] / [DeviceNameLayout], which never makes a button narrower
 * than its longest word (WP-8.14-R1).
 */
@Composable
internal fun OptiButton(
    text: String,
    kind: Kind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 13.sp,
    padding: PaddingValues = BtnPadding,
    onClick: () -> Unit,
) {
    val (bg, fg) = when (kind) {
        Kind.PRIMARY -> OptiRootTokens.Accent to Color(0xFF1D0F38)
        Kind.GHOST -> OptiRootTokens.Panel to OptiRootTokens.Ink
        Kind.DANGER -> OptiRootTokens.Red to Color.White
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        border = if (kind == Kind.GHOST) BorderStroke(1.dp, OptiRootTokens.Line) else null,
        contentPadding = padding,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.45f), disabledContentColor = fg.copy(alpha = 0.6f)),
    ) {
        Text(
            text, fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            lineHeight = fontSize * 1.25f,
        )
    }
}

/** Opti `.catBtn` (index.html:399-403): padding 12px 14px, gap 12px, 15px semibold label, 20px icon, 18px chevron. */
@Composable
private fun CatButton(icon: String, label: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.background(OptiRootTokens.Panel, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, fontSize = 20.sp)
        Text(
            label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp * 1.25f,
            color = OptiRootTokens.Ink, modifier = Modifier.weight(1f)
        )
        Text("›", fontSize = 18.sp, color = OptiRootTokens.Muted)
    }
}

@Composable
private fun ColTitle(title: String, tip: String) {
    var showTip by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
        Spacer(Modifier.width(6.dp))
        Text("ⓘ", fontSize = 13.sp, color = OptiRootTokens.Muted, modifier = Modifier.clickable(role = Role.Button) { showTip = !showTip }.semantics { contentDescription = "More info" })
    }
    if (showTip) Text(tip.substringAfter('\n'), fontSize = 11.sp, color = OptiRootTokens.Muted)
}

@Composable
private fun SettingRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OptiRootTokens.Panel, RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = OptiRootTokens.Ink, modifier = Modifier.weight(1f))
        Text("$value ›", fontSize = 12.sp, color = OptiRootTokens.Muted)
    }
}

@Composable
private fun ToggleSetting(label: String, desc: String, value: Boolean, tag: String, onLabelClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val labelMod = if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick).testTag(tag + "_label") else Modifier
            Text(label, fontSize = 14.sp, color = OptiRootTokens.Ink, modifier = Modifier.weight(1f).then(labelMod))
            Switch(
                checked = value, onCheckedChange = onChange, modifier = Modifier.testTag(tag).semantics { contentDescription = label },
                colors = SwitchDefaults.colors(checkedThumbColor = OptiRootTokens.Accent, checkedTrackColor = OptiRootTokens.Accent.copy(alpha = 0.3f))
            )
        }
        Text(desc, fontSize = 11.sp, color = OptiRootTokens.Muted)
    }
}

/**
 * WP-8.15: Opti's inline Card thickness slider (0..60, step 1, app.js:6841-6852). Every step is
 * written at once (Opti `oninput`: `S.prefs.cardThick = +v; applyCardThick()`), so the cards
 * (WP-8.18, through [com.example.ui.LocalCardPrefs]) preview it live.
 */
@Composable
private fun CardThickSetting(value: Int, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppearanceTexts.THICK_LABEL, fontSize = 14.sp, color = OptiRootTokens.Ink, modifier = Modifier.weight(1f))
            Slider(
                value = local,
                onValueChange = { v ->
                    local = v
                    onChange(v.roundToInt())
                },
                valueRange = 0f..60f,
                steps = 59,
                colors = SliderDefaults.colors(thumbColor = OptiRootTokens.Accent, activeTrackColor = OptiRootTokens.Accent),
                modifier = Modifier.width(140.dp).testTag("slider_cardThick"),
            )
            Text(
                AppearanceTexts.cardThickLabel(local.roundToInt()), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = OptiRootTokens.Accent, modifier = Modifier.width(56.dp).testTag("cardThick_value")
            )
        }
        Text(AppearanceTexts.THICK_DESC, fontSize = 11.sp, color = OptiRootTokens.Muted)
    }
}

@Composable
private fun MultiSetting(label: String, desc: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(label, fontSize = 14.sp, color = OptiRootTokens.Ink, modifier = Modifier.weight(1f))
            Text("$value ›", fontSize = 13.sp, color = OptiRootTokens.Muted)
        }
        Text(desc, fontSize = 11.sp, color = OptiRootTokens.Muted)
    }
}

@Composable
private fun Choice(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    OptiButton(label, if (on) Kind.PRIMARY else Kind.GHOST, Modifier.testTag(tag).semantics { selected = on }, onClick = onClick)
}

@Composable
private fun ModeRow(selected: Boolean, title: String, desc: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
            Text(desc, fontSize = 11.sp, color = OptiRootTokens.Muted)
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = modifier)
}

/**
 * Opti `input[type=text]` (index.html:346-347): panel background, 1px line border (accent when
 * focused), radius 8px, padding 9px 10px, 14px text. Opti's `onchange`: the value is committed
 * on Done or when the field loses focus.
 */
@Composable
private fun CommitField(
    value: String,
    placeholder: String,
    modifier: Modifier,
    maxLen: Int = Int.MAX_VALUE,
    center: Boolean = false,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val align = if (center) TextAlign.Center else TextAlign.Start
    BasicTextField(
        value = text,
        onValueChange = { if (it.length <= maxLen) text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = OptiRootTokens.Ink, textAlign = align),
        cursorBrush = SolidColor(OptiRootTokens.Accent),
        // WP-8.16-R1: Blink's default autocapitalize for <input type=text> is "sentences".
        // Done drops focus, which closes the keyboard and commits once via onFocusChanged below
        // (Opti's onchange fires on blur); a custom onDone would otherwise keep the keyboard up.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
        keyboardActions = KeyboardActions(onDone = { if (focused) focusManager.clearFocus() else if (text != value) onCommit(text) }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused && text != value) onCommit(text)
            focused = it.isFocused
        },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().background(OptiRootTokens.Panel, RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, if (focused) OptiRootTokens.Accent else OptiRootTokens.Line), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = 14.sp, color = OptiRootTokens.Muted, maxLines = 1, textAlign = align, modifier = Modifier.fillMaxWidth())
                }
                inner()
            }
        },
    )
}

@Composable
private fun OptiDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    summary: com.example.data.sync.ImportSummary? = null,
    buttons: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(OptiRootTokens.Bg, RoundedCornerShape(16.dp))
                .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(16.dp)).padding(24.dp)
                .verticalScroll(rememberScrollState()).testTag("opti_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 18.sp, color = OptiRootTokens.Ink, modifier = Modifier.testTag("dialog_title"))
            Spacer(Modifier.height(14.dp))
            Text(text, fontSize = 13.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("dialog_text"))
            if (summary != null) {
                Spacer(Modifier.height(12.dp))
                ImportSummaryTable(summary)
            }
            Spacer(Modifier.height(20.dp))
            OptiFlexRow(Modifier.fillMaxWidth().testTag("dialog_buttons"), content = buttons)
        }
    }
}

/**
 * Opti `eventImportSummaryHTML` (app.js:1569-1590): stored / visible / hidden rows, the per-category
 * line, and the note's first sentence. The second sentence names "Republish imported events",
 * which Kotlin does not have, so it is left out.
 */
@Composable
private fun ImportSummaryTable(sum: com.example.data.sync.ImportSummary) {
    Column(Modifier.fillMaxWidth().testTag("import_summary")) {
        for ((k, v) in listOf("Stored total" to sum.total, "Visible in feed" to sum.visible, "System / diagnostic hidden" to sum.diagnostic)) {
            Row(Modifier.fillMaxWidth()) {
                Text(k, fontSize = 13.sp, color = OptiRootTokens.Muted, modifier = Modifier.weight(1f))
                Text("$v", fontSize = 13.sp, color = OptiRootTokens.Ink)
            }
        }
        Spacer(Modifier.height(6.dp))
        val c = sum.byCat
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                listOf("Habits" to c["habit"], "Dailies" to c["daily"], "To-dos" to c["todo"], "System" to c["system"]).forEachIndexed { i, (k, v) ->
                    if (i > 0) append("   ")
                    append("$k ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                    append("${v ?: 0}")
                    pop()
                }
            },
            fontSize = 13.sp, color = OptiRootTokens.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "The feed hides ${sum.diagnostic} system/diagnostic events and follows the date window, so its count " +
                "(under “All”) may be lower than stored.",
            fontSize = 12.sp, color = OptiRootTokens.Muted,
        )
    }
}
