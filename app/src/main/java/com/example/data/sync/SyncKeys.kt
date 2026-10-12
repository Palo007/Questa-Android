package com.example.data.sync

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * WP-8.2 (Wave 1, "Schema v7, clock and persistence foundation") -- §4.3 "Persistence home per
 * key": the single canonical registry of every DataStore key named in that table, both the 19
 * pre-existing [SyncDataStore] keys (kept there, re-listed here so §4.3 has one authoritative
 * source) and every *new* key later waves need (declared here first so those waves' DI wiring has
 * something to depend on without redefining the same string constant twice).
 *
 * WP-8.2 owns declaration only -- the Flow/save accessor methods for keys already backed by
 * [SyncDataStore] stay there; a *new* key's read/write behavior belongs to its §4.3 "Owner WP"
 * column (e.g. `PENDING_TAPS` is written by WP-8.6's `LocalTapQueue`, not by this WP).
 */
object SyncKeys {
    // --- Existing keys (SyncDataStore.kt, re-declared here for the single-registry contract;
    // the canonical DataStore.Preferences.Key<*> instances stay on SyncDataStore's companion so
    // existing call sites, which are outside this WP's W1 file list, keep compiling unchanged) ---
    val DROPBOX_ACCESS_TOKEN = SyncDataStore.DROPBOX_ACCESS_TOKEN
    val DROPBOX_REFRESH_TOKEN = SyncDataStore.DROPBOX_REFRESH_TOKEN
    val DROPBOX_EXPIRES_AT = SyncDataStore.DROPBOX_EXPIRES_AT
    val DROPBOX_ENABLED = SyncDataStore.DROPBOX_ENABLED
    val DROPBOX_LAST_REV = SyncDataStore.DROPBOX_LAST_REV
    val DROPBOX_BASE_SNAPSHOT = SyncDataStore.DROPBOX_BASE_SNAPSHOT
    val LOCAL_DELETIONS = SyncDataStore.LOCAL_DELETIONS
    val OAUTH_VERIFIER = SyncDataStore.OAUTH_VERIFIER
    val DEVICE_ID = SyncDataStore.DEVICE_ID
    val LOCAL_SAVED_AT = SyncDataStore.LOCAL_SAVED_AT
    val HLC_LAST_ISSUED = SyncDataStore.HLC_LAST_ISSUED
    val SAVE_BUTTON_TOP = SyncDataStore.SAVE_BUTTON_TOP
    val HAPTICS_ENABLED = SyncDataStore.HAPTICS_ENABLED
    val EVT_FILE_REVS = SyncDataStore.EVT_FILE_REVS
    val EVT_LAST_UPLOAD_TS = SyncDataStore.EVT_LAST_UPLOAD_TS
    val EVT_LAST_PULL_AT = SyncDataStore.EVT_LAST_PULL_AT
    val EXPORT_INTERVAL_DAYS = SyncDataStore.EXPORT_INTERVAL_DAYS
    val LAST_EXPORT_TS = SyncDataStore.LAST_EXPORT_TS
    val MONTHLY_BACKUPS = SyncDataStore.MONTHLY_BACKUPS

    // --- New keys (§4.3), declared here for later waves to consume ---

    // D16: the app key whose Dropbox folder the local sync metadata belongs to. Owner WP-8.21.
    val APP_KEY_OF_DATA = stringPreferencesKey("app_key_of_data")

    /** The Dropbox app key entered at runtime. Absent means not configured. Local only, never exported. */
    val DROPBOX_APP_KEY = stringPreferencesKey("dropbox_app_key")

    /** Set once the build seed (BuildConfig.DROPBOX_APP_KEY_SEED) was applied; it is never applied again. */
    val DROPBOX_APP_KEY_SEEDED = booleanPreferencesKey("dropbox_app_key_seeded")

    // WP-8.1: interlock / AuthState.
    val ACCOUNT_ID = stringPreferencesKey("account_id")
    val AUTH_STATE = stringPreferencesKey("auth_state")

    // WP-8.6: LocalTapQueue (JSON list of TapRecord).
    val PENDING_TAPS = stringPreferencesKey("pending_taps")

    // WP-8.7 (8.9 sets purge): Opti syncCfg equivalents.
    val EVT_FILE_COUNTS = stringPreferencesKey("evt_file_counts")
    val EVT_BAD_REVS = stringPreferencesKey("evt_bad_revs")
    val EVT_FULL_SCAN_AT = longPreferencesKey("evt_full_scan_at")
    val EVT_FULL_PUSH_AT = longPreferencesKey("evt_full_push_at")
    val EVT_PUSH_BLOCKED = booleanPreferencesKey("evt_push_blocked")
    val PENDING_PURGE_DEV = stringPreferencesKey("pending_purge_dev")

    // WP-8.8: sync.js:711-714.
    val INBOX_HABITS_HASH = stringPreferencesKey("inbox_habits_hash")
    val INBOX_REMINDERS_HASH = stringPreferencesKey("inbox_reminders_hash")
    val INBOX_LAST_RUN_AT = longPreferencesKey("inbox_last_run_at")

    // WP-8.6: round status.
    val SYNC_LAST_AT = longPreferencesKey("sync_last_at")
    val SYNC_LAST_ERROR = stringPreferencesKey("sync_last_error")
    val SYNC_LAST_BACKUP_ERROR = stringPreferencesKey("sync_last_backup_error")

    // CR-KT-018: Opti `localEditAt` ("questa.localEditAt.v1", app.js:335-342). Device-only: the
    // wall-clock ms of the last USER write (never a merge apply). Not exported, never uploaded.
    val LOCAL_EDIT_AT = longPreferencesKey("local_edit_at")

    // WP-8.9: backup prefs overlay.
    val AUTO_BACKUP_TIERS = stringPreferencesKey("auto_backup_tiers")

    // WP-8.15: appearance prefs overlay.
    // WP-8.14: NOTES_LINES / CARD_THICK / WIDTH_PX are Opti numbers (notesLines 3, cardThick 0-60,
    // width px), so they are Int keys; the W1 declaration (string / boolean) had no reader or writer.
    val NOTES_LINES = intPreferencesKey("notes_lines")
    val CARD_THICK = intPreferencesKey("card_thick")
    val SHOW_STREAKS = booleanPreferencesKey("show_streaks")
    val WIDTH_PX = intPreferencesKey("width_px")
    val HIDE_SYNC_DIAG = booleanPreferencesKey("hide_sync_diag")
    val HIDE_CONFLICTS = booleanPreferencesKey("hide_conflicts")

    // WP-8.11: reminders.
    val REM_ARMED = stringPreferencesKey("rem_armed")
    val REM_LAST_CHECK = longPreferencesKey("rem_last_check")

    // WP-8.14: Opti `S.prefs.notificationsEnabled` (default false, app.js:220); gates reminders.
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")

    // WP-8.13: launcher shortcuts.
    val SHORTCUT_SIG = stringPreferencesKey("shortcut_sig")

    // --- UI state persistence (filters, sort, tab, scroll positions) ---

    // Filter/sort state (all string keys; empty/null = default)
    val UI_SEARCH_QUERY = stringPreferencesKey("ui_search_query")
    val UI_SELECTED_TAG = stringPreferencesKey("ui_selected_tag")
    val UI_DAILY_FILTER = stringPreferencesKey("ui_daily_filter")
    val UI_HABIT_FILTER = stringPreferencesKey("ui_habit_filter")
    val UI_TODO_FILTER = stringPreferencesKey("ui_todo_filter")
    val UI_HABIT_SORT = stringPreferencesKey("ui_habit_sort")
    val UI_DAILY_SORT = stringPreferencesKey("ui_daily_sort")
    val UI_TODO_SORT = stringPreferencesKey("ui_todo_sort")
    val UI_REWARD_SORT = stringPreferencesKey("ui_reward_sort")

    // Selected tab index
    val UI_SELECTED_TAB = intPreferencesKey("ui_selected_tab")

    // Per-tab scroll positions (firstVisibleItemIndex + firstVisibleItemScrollOffset)
    val UI_SCROLL_HABITS_INDEX = intPreferencesKey("ui_scroll_habits_index")
    val UI_SCROLL_HABITS_OFFSET = intPreferencesKey("ui_scroll_habits_offset")
    val UI_SCROLL_DAILIES_INDEX = intPreferencesKey("ui_scroll_dailies_index")
    val UI_SCROLL_DAILIES_OFFSET = intPreferencesKey("ui_scroll_dailies_offset")
    val UI_SCROLL_TODOS_INDEX = intPreferencesKey("ui_scroll_todos_index")
    val UI_SCROLL_TODOS_OFFSET = intPreferencesKey("ui_scroll_todos_offset")
    val UI_SCROLL_REWARDS_INDEX = intPreferencesKey("ui_scroll_rewards_index")
    val UI_SCROLL_REWARDS_OFFSET = intPreferencesKey("ui_scroll_rewards_offset")
    val UI_SCROLL_ANALYTICS_INDEX = intPreferencesKey("ui_scroll_analytics_index")
    val UI_SCROLL_ANALYTICS_OFFSET = intPreferencesKey("ui_scroll_analytics_offset")
    val UI_SCROLL_LOGS_INDEX = intPreferencesKey("ui_scroll_logs_index")
    val UI_SCROLL_LOGS_OFFSET = intPreferencesKey("ui_scroll_logs_offset")
}
