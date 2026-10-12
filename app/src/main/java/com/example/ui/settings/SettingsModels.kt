package com.example.ui.settings

import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.ImportMode
import com.example.data.sync.Section
import com.example.data.sync.auth.AuthState
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.ui.CardPrefs

/** WP-8.14: which part of Settings to show. [Dropbox] scrolls to the Sync block, [Backup] (CR-KT-014) to "Backup & transfer". */
enum class SettingsRoute { Root, Dropbox, Backup }

/** Intent extra that opens Settings (value [OPEN_SETTINGS_DROPBOX] opens the Sync block). */
const val EXTRA_OPEN_SETTINGS = "openSettings"
const val OPEN_SETTINGS_DROPBOX = "dropbox"
/** CR-KT-014: the prune warning's "Back up now". */
const val OPEN_SETTINGS_BACKUP = "backup"

/** The route for an [EXTRA_OPEN_SETTINGS] value: null = no extra, "dropbox" = Dropbox, else Root. */
fun settingsRouteForExtra(value: String?): SettingsRoute? = when (value) {
    null -> null
    OPEN_SETTINGS_DROPBOX -> SettingsRoute.Dropbox
    OPEN_SETTINGS_BACKUP -> SettingsRoute.Backup
    else -> SettingsRoute.Root
}

/** A confirm dialog the ViewModel asked for (Opti `confirmDialog(title, text)`: "Yes" / "Cancel"). */
data class SettingsConfirm(
    val op: ConfirmOp,
    val title: String,
    val text: String,
    val yes: String = "Yes",
    val no: String = "Cancel",
)

enum class ConfirmOp { DISCONNECT, FORCE_PUSH, FORCE_PULL, FORCE_PUSH_EVENTS, CONNECT_FORCE_PUSH, RESET, FIRST_CONNECT_PUSH, IMPORT }

/** Opti `alertDialog(title, text)`: one "OK" button. */
/** [summary]: Opti `eventImportSummaryHTML` table under the text (import dialogs only). */
data class SettingsAlert(val title: String, val text: String, val summary: com.example.data.sync.ImportSummary? = null)

/** One row of Opti's export/import section picker (`_ioRowsHTML`). */
data class SectionRow(val section: Section, val label: String, val hint: String, val count: Int?)

/** Opti `showImportSectionPicker` / `showExportSectionPicker` state. */
data class SectionPickerState(
    val import: Boolean,
    val rows: List<SectionRow>,
    val selected: Set<Section>,
    val mode: ImportMode = ImportMode.REPLACE,
    /** Import only: `_backup.partial` of the file. */
    val partialFile: Boolean = false,
    /** Import only: sections not in the file (Opti `missing`). */
    val missing: List<Section> = emptyList(),
) {
    val picked: List<Section> get() = rows.map { it.section }.filter { it in selected }

    /** Opti's `refresh()` note under the buttons. */
    val note: String get() {
        val p = picked
        if (import) {
            if (p.isEmpty()) return "Tick at least one part."
            var t = "${p.size}" + (if (p.size == 1) " part" else " parts") + " · " + (if (mode == ImportMode.MERGE) "Merge" else "Replace") + "."
            if (Section.EVENTS in selected) t += " Your event log is only added to, never cleared."
            return t
        }
        return when {
            p.isEmpty() -> "Tick at least one box."
            Section.entries.all { it in p } -> "Full backup · questa-backup-….json"
            else -> "Partial export · questa-partial-….json · restores only the ticked boxes."
        }
    }
}

/** Everything the Settings sheet renders. */
data class SettingsUiState(
    val charName: String = "Adventurer",
    val charFace: String = "🧙",
    val connected: Boolean = false,
    /** The stored Dropbox app key, or "" when none is stored. */
    val appKey: String = "",
    /** The app key dialog is open. */
    val appKeyDialog: Boolean = false,
    val reconnectRequired: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val lastBackupError: String? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val autoBackup: AutoBackupEnabled = AutoBackupEnabled(),
    val haptics: Boolean = true,
    val saveBtnTop: Boolean = false,
    val notificationsEnabled: Boolean = false,
    /** Opti `Notification.permission`: "granted", "denied" or "default". */
    val notificationPermission: String = "default",
    /** null below API 31 (no exact-alarm permission there). */
    val exactAlarmsAllowed: Boolean? = null,
    val paused: Boolean = false,
    val lastExportTs: Long = 0L,
    /** Opti `hlcSkewMs() > HLC_RATCHET_TOLERANCE_MS`. */
    val clockAhead: Boolean = false,
    /** The start-up migrations failed on this launch (8.I W3 review): data may be missing. */
    val startupFailed: Boolean = false,
    /** Linked, no valid base: nothing syncs until the first-connect choice (review fix 1). */
    val firstConnectPending: Boolean = false,
    val appVersion: String = "",
    val nowMs: Long = 0L,
    // WP-8.22-B: Bubblewrap quick-log rows (com.example.launcher.QuickLogStatus).
    val authState: AuthState = AuthState.NOT_LINKED,
    val waitingToUpload: Int = 0,
    val remindersScheduled: Int = 0,
    val remindersLastChecked: Long = 0L,
    val confirm: SettingsConfirm? = null,
    val alert: SettingsAlert? = null,
    val firstConnect: FirstConnectPreview? = null,
    val picker: SectionPickerState? = null,
    /** The Level-2 category sheet ("interaction", "activityFeed", "appearance") or null. */
    val category: String? = null,
    /** The foreground option menu (Opti `openOpt(key)`) or null. */
    val option: String? = null,
    /** WP-8.15: the Appearance values (the same DataStore keys [com.example.ui.LocalCardPrefs] reads). */
    val card: CardPrefs = CardPrefs(),
    /** WP-8.15: the two Activity Feed hide toggles. */
    val feed: FeedFilters = FeedFilters(),
    /** WP-8.15: an uploaded avatar image exists (Opti `S.char.faceImg`): shows "Remove". */
    val hasAvatarImage: Boolean = false,
    /** WP-8.15: Opti `getBuzzDiag()`. */
    val hapticsDiag: HapticsDiag = HapticsDiag(),
    /** WP-8.14-R1: the Diagnostics screen (5 version taps) or null. */
    val diagnostics: DiagnosticsView? = null,
) {
    /** Opti `devId.slice(0, 6)`. */
    val deviceShort: String get() = deviceId.take(6)

    /** Opti's auto-backup row value: the enabled tiers, or "Off". */
    val autoBackupSummary: String get() {
        val l = mutableListOf<String>()
        if (autoBackup.fourHour) l.add("4-hourly")
        if (autoBackup.daily) l.add("Daily")
        if (autoBackup.weekly) l.add("Weekly")
        if (autoBackup.monthly) l.add("Monthly")
        return if (l.isEmpty()) "Off" else l.joinToString(", ")
    }
}

/** Opti's exact user-facing strings (app.js / sync.js at 7d5d4f3) and the card's first-connect strings. */
object SettingsTexts {
    const val SYNC_TIP = "Sync via Dropbox (your account, no server) keeps this device and your other devices up to date automatically."
    const val BACKUP_TIP = "Your progress lives only on this device. Export a file to back up or move to another phone, then import it there to continue. Export now includes your full event log (subtask/tap/completion history), so one file is a complete backup."
    const val CLOCK_AHEAD = "This device’s clock is ahead of your other devices. Edits made here may lose to them until you correct the clock in your device settings."
    const val RECONNECT_REQUIRED = "reconnect required"
    const val STARTUP_FAILED = "Start-up failed: some data may not be shown. Restart the app; if this stays, export a backup before anything else."

    const val DISCONNECT_TITLE = "Disconnect Dropbox"
    const val DISCONNECT_TEXT = "Stop syncing this device? Local data is kept; other devices keep their own copy."
    const val FORCE_PUSH_TITLE = "Force push — overwrite Dropbox?"
    const val FORCE_PUSH_TEXT = "This replaces the data in Dropbox with what's on THIS device. Anything saved on other devices that hasn't synced yet will be permanently lost. This cannot be undone."
    const val FORCE_PULL_TITLE = "Force pull — overwrite this device?"
    const val FORCE_PULL_TEXT = "This replaces what's on THIS device with whatever is in Dropbox. Anything on this device that hasn't synced yet will be permanently lost. This cannot be undone.\n\nNote: normal sync now protects newer edits on each device, so a Force Push from another device will NOT overwrite a newer change you made here. If you want this device to show ONLY the exact state that is in Dropbox, use this Force Pull; to make EVERY device match Dropbox exactly, run Force Pull on each device (or do a full app reset — clear the app's site data / reinstall — which wipes all local data and re-pulls from Dropbox)."
    const val EVENTS_PUSH_TITLE = "Force Push Events?"
    const val EVENTS_PUSH_TEXT = "This re-uploads this device's event history to Dropbox, overriding a blocked push. Event files only ever get added together, so nothing on your other device can be deleted by this."
    const val CONNECT_PUSH_TITLE = "Connect and overwrite Dropbox?"
    const val CONNECT_PUSH_TEXT = "This connects to Dropbox and IMMEDIATELY overwrites the remote backup with THIS device's data, skipping the normal merge. Anything already saved in Dropbox will be permanently lost. This cannot be undone."
    const val RESET_TITLE = "Reset Everything"
    const val RESET_TEXT = "Erase ALL progress on this device? This cannot be undone."

    const val FC_TITLE = "First sync with this Dropbox"
    const val FC_PULL = "Pull from Dropbox (recommended) — replaces this device's data with the Dropbox copy (%1\$d tasks, level %2\$d)."
    const val FC_PUSH = "Push this device — replaces the Dropbox copy (%1\$d tasks, level %2\$d, saved %3\$s) with this device's data (%4\$d tasks, level %5\$d). Other devices will receive this device's data."
    const val FC_DISABLED = "This device has no data. Pushing would erase the Dropbox copy. Choose Pull."
    /** Kotlin wording (Opti has no first-connect choice): SyncManager.FIRST_CONNECT_MESSAGE. */
    const val FC_PENDING = "Dropbox already has data: choose Pull or Push first"
    const val FC_OPEN = "Choose Pull or Push"
    const val FC_PUSH_CONFIRM = "Replace the Dropbox copy? This cannot be undone from this device."

    /** Opti confirmForcePush's event-count sentence. */
    fun forcePushText(events: Int?): String {
        var t = FORCE_PUSH_TEXT
        if (events != null) {
            t += " This device has " + events + " local event" + (if (events == 1) "" else "s") + "."
            if (events < 50) t += " If that looks low, it may not be the most up-to-date device."
        }
        return t
    }

    /** Opti confirmEventsForcePush's blocked-files sentence. */
    fun eventsPushText(blocked: Int): String =
        if (blocked > 0) EVENTS_PUSH_TEXT + " " + blocked + " month file" + (if (blocked == 1) " is" else "s are") + " blocked right now."
        else EVENTS_PUSH_TEXT

    fun importConfirmText(mode: ImportMode, picked: Int): String {
        val label = "$picked" + if (picked == 1) " part" else " parts"
        return if (mode == ImportMode.MERGE) "Merge $label from this file into your current data? Nothing will be removed."
        else "Replace $label with the copy in this file? Anything you did not tick stays as it is."
    }

    /** Opti `syncRelativeTime(ms)` (sync.js:3656). */
    fun relativeTime(ms: Long, nowMs: Long): String {
        if (ms == 0L) return "never"
        val s = maxOf(0L, (nowMs - ms) / 1000)
        if (s < 10) return "just now"
        if (s < 60) return "${s}s ago"
        val m = s / 60
        if (m < 60) return "${m}m ago"
        val h = m / 60
        if (h < 24) return "${h}h ago"
        return "${h / 24}d ago"
    }

    /** Opti IO_SECTIONS label + hint (app.js:7270-7349). */
    fun sectionLabel(s: Section): String = when (s) {
        Section.CHAR -> "Character"
        Section.HABITS -> "Habits"
        Section.DAILIES -> "Dailies"
        Section.TODOS -> "To-dos"
        Section.REWARDS -> "Rewards"
        Section.TAGS -> "Tags"
        Section.PREFS -> "Settings"
        Section.HISTORY -> "History"
        Section.DEVICES -> "Devices"
        Section.EVENTS -> "Event log"
    }

    fun sectionHint(s: Section): String = when (s) {
        Section.CHAR -> "Level, XP, gold, HP and class."
        Section.HABITS -> "Repeatable good/bad habit buttons."
        Section.DAILIES -> "Tasks that repeat on a schedule, with streaks."
        Section.TODOS -> "One-off tasks and their checklists."
        Section.REWARDS -> "Things you spend gold on."
        Section.TAGS -> "Labels you sort tasks by."
        Section.PREFS -> "Layout, sync options and app preferences."
        Section.HISTORY -> "Daily/character history and monthly backup records."
        Section.DEVICES -> "Known devices and deletion tombstones."
        Section.EVENTS -> "Every tap, completion and subtask change."
    }
}

/** WP-8.14-R1: what the Diagnostics screen shows (already scrubbed of secrets). */
data class DiagnosticsView(
    val rows: List<Pair<String, String>>,
    /** 8.7's diag ring, newest first. */
    val ring: List<String>,
    val fileName: String,
    /** The scrubbed JSON bundle, for Copy and Share (same text as the saved file). */
    val exportText: String = "",
)
