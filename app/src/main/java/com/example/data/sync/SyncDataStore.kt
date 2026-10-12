package com.example.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*

val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "questa_sync_preferences")

class SyncDataStore(
    private val context: Context,
    private val internalDataStore: DataStore<Preferences>? = null
) {
    private val dataStore: DataStore<Preferences>
        get() = internalDataStore ?: context.syncDataStore

    companion object {
        val DROPBOX_ACCESS_TOKEN = stringPreferencesKey("dropbox_access_token")
        val DROPBOX_REFRESH_TOKEN = stringPreferencesKey("dropbox_refresh_token")
        val DROPBOX_EXPIRES_AT = longPreferencesKey("dropbox_expires_at")
        val DROPBOX_ENABLED = booleanPreferencesKey("dropbox_enabled")
        val DROPBOX_LAST_REV = stringPreferencesKey("dropbox_last_rev")
        val DROPBOX_BASE_SNAPSHOT = stringPreferencesKey("dropbox_base_snapshot")
        val LOCAL_DELETIONS = stringPreferencesKey("local_deletions")
        val OAUTH_VERIFIER = stringPreferencesKey("oauth_verifier")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val LOCAL_SAVED_AT = longPreferencesKey("local_saved_at")
        val HLC_LAST_ISSUED = longPreferencesKey("hlc_last_issued")

        // Setting for Save button position
        val SAVE_BUTTON_TOP = booleanPreferencesKey("save_button_top")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")

        // Stage 4 / T4.2-T4.3 (spec §3 addendum rule 26: PWA's `cfg.evtFileRevs` / cfg.evtLastUploadTs`
        // / `cfg.evtLastPullAt`, persisted via `syncCfgSave()`). Kotlin equivalent storage: same
        // stringPreferencesKey-holding-a-JSON-string pattern already used by LOCAL_DELETIONS for a
        // structured value, same longPreferencesKey pattern already used by LOCAL_SAVED_AT for a
        // plain scalar.
        val EVT_FILE_REVS = stringPreferencesKey("evt_file_revs")
        val EVT_LAST_UPLOAD_TS = longPreferencesKey("evt_last_upload_ts")
        val EVT_LAST_PULL_AT = longPreferencesKey("evt_last_pull_at")

        // WP-5.2 (spec §7 D9) -- auto-backup local state. `EXPORT_INTERVAL_DAYS`/`LAST_EXPORT_TS`
        // mirror PWA's `S.prefs.exportIntervalDays`/`S.prefs.lastExportTs`, but spec §3's
        // syncSubset() field table (line 243) excludes the FULL `prefs` object from the Dropbox
        // wire -- only `prefs.an.{views,metrics}` is synced (already handled by the top-level `an`
        // field elsewhere). So these two are modeled as LOCAL-ONLY Kotlin settings, never placed on
        // state.json's `state` body; see AutoBackupTest's leak-check for the enforced contract.
        // `MONTHLY_BACKUPS`, in contrast, mirrors `S.monthlyBackups` -- IS on the wire (spec §3 line
        // 240) and already merged by OptiSnapshotMerger.merge; this is the local cache of
        // the last-known-merged authoritative list (same JSON-string-holding-a-structured-value
        // pattern as EVT_FILE_REVS/LOCAL_DELETIONS, just a JSON array of strings here instead of an
        // object).
        val EXPORT_INTERVAL_DAYS = intPreferencesKey("export_interval_days")
        val LAST_EXPORT_TS = longPreferencesKey("last_export_ts")
        val MONTHLY_BACKUPS = stringPreferencesKey("monthly_backups")
    }

    val isSaveButtonTop: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SAVE_BUTTON_TOP] ?: false // WP-8.15 review 33: Opti freshState/migrate() saveBtnTop:false
    }

    suspend fun setSaveButtonTop(isTop: Boolean) {
        dataStore.edit { prefs ->
            prefs[SAVE_BUTTON_TOP] = isTop
        }
    }

    val hapticsEnabled: Flow<Boolean> = dataStore.data.map { it[HAPTICS_ENABLED] ?: true }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[HAPTICS_ENABLED] = enabled }
    }

    val localSavedAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[LOCAL_SAVED_AT] ?: 0L
    }

    suspend fun saveLocalSavedAt(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[LOCAL_SAVED_AT] = timestamp
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        return dataStore.data.map { it[DEVICE_ID] }.first() ?: run {
            val newId = "dev-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            saveLocalDeviceId(newId)
            newId
        }
    }

    suspend fun saveLocalDeviceId(deviceId: String) {
        dataStore.edit { prefs ->
            prefs[DEVICE_ID] = deviceId
        }
    }

    suspend fun getHlcLastIssued(): Long {
        return dataStore.data.map { it[HLC_LAST_ISSUED] ?: 0L }.first()
    }

    suspend fun saveHlcLastIssued(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[HLC_LAST_ISSUED] = timestamp
        }
    }
    val isDropboxEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DROPBOX_ENABLED] ?: false
    }

    val dropboxAccessToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[DROPBOX_ACCESS_TOKEN]
    }

    val dropboxRefreshToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[DROPBOX_REFRESH_TOKEN]
    }

    val dropboxExpiresAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[DROPBOX_EXPIRES_AT] ?: 0L
    }

    val dropboxLastRev: Flow<String?> = dataStore.data.map { prefs ->
        prefs[DROPBOX_LAST_REV]
    }

    val dropboxBaseSnapshot: Flow<String?> = dataStore.data.map { prefs ->
        prefs[DROPBOX_BASE_SNAPSHOT]
    }

    val oauthVerifier: Flow<String?> = dataStore.data.map { prefs ->
        prefs[OAUTH_VERIFIER]
    }

    suspend fun saveOAuthVerifier(verifier: String) {
        dataStore.edit { prefs ->
            prefs[OAUTH_VERIFIER] = verifier
        }
    }

    suspend fun getOAuthVerifier(): String? {
        return dataStore.data.map { it[OAUTH_VERIFIER] }.first()
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long, nowMs: Long = SyncClocks.transport()) {
        val expiresAt = nowMs + expiresIn * 1000L
        dataStore.edit { prefs ->
            prefs[DROPBOX_ACCESS_TOKEN] = accessToken
            prefs[DROPBOX_REFRESH_TOKEN] = refreshToken
            prefs[DROPBOX_EXPIRES_AT] = expiresAt
            prefs[DROPBOX_ENABLED] = true
        }
    }

    // WP-8.1 (PX-07): Opti `enabled`. Set false on invalid_grant without touching the tokens.
    suspend fun setDropboxEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DROPBOX_ENABLED] = enabled }
    }

    suspend fun clearDropbox() {
        dataStore.edit { prefs ->
            prefs.remove(DROPBOX_ACCESS_TOKEN)
            prefs.remove(DROPBOX_REFRESH_TOKEN)
            prefs.remove(DROPBOX_EXPIRES_AT)
            prefs.remove(DROPBOX_ENABLED)
            prefs.remove(DROPBOX_LAST_REV)
            prefs.remove(DROPBOX_BASE_SNAPSHOT)
        }
    }

    suspend fun saveSyncMetadata(rev: String, baseSnapshot: String) {
        dataStore.edit { prefs ->
            prefs[DROPBOX_LAST_REV] = rev
            prefs[DROPBOX_BASE_SNAPSHOT] = baseSnapshot
        }
    }

    // review r1 major 7: each array element is decoded independently, so ONE malformed entry
    // (a partial write, a future/older-build shape) no longer discards every other recorded
    // deletion -- previously a single bad element made the whole list read back empty, and the
    // caller (BaseStoreMigration) then deleted the key outright, so the deleted records "came
    // back" (their tombstone was never written). [parseLocalDeletionsOrNull] additionally
    // distinguishes "genuinely empty/absent" (returns an empty list) from "the raw string isn't
    // even a JSON array" (returns null) so BaseStoreMigration can refuse to clear the key on a
    // total parse failure instead of silently discarding whatever it could not read.
    /**
     * review r2 minor 3: distinguishes "every element recovered cleanly" from "at least one
     * element failed to parse" -- [BaseStoreMigration] must never clear LOCAL_DELETIONS while
     * [anyFailure] is true, so a deletion is never silently dropped. [leftoverJson] is the
     * still-unparsed remainder (verbatim) to write back under the SAME key when [anyFailure] is
     * true -- a total parse failure (the raw value is not even a JSON array) keeps the WHOLE raw
     * string as the leftover; a partial failure keeps only the bad element(s), since the good ones
     * were already recovered into [recovered].
     */
    data class LocalDeletionsRecovery(
        val recovered: List<Pair<String, Long>>,
        val anyFailure: Boolean,
        val leftoverJson: String?
    )

    private fun parseLocalDeletionsDetailed(raw: String?): LocalDeletionsRecovery {
        if (raw == null) return LocalDeletionsRecovery(emptyList(), anyFailure = false, leftoverJson = null)
        val arr = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (e: Exception) {
            null
        }
        if (arr == null) {
            // Not even a JSON array: nothing recoverable, keep the whole raw value as leftover.
            return LocalDeletionsRecovery(emptyList(), anyFailure = true, leftoverJson = raw)
        }
        val recovered = mutableListOf<Pair<String, Long>>()
        val bad = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for (element in arr) {
            val pair = try {
                val obj = element as? JsonObject
                val id = obj?.get("id")?.jsonPrimitive?.content
                val ts = obj?.get("ts")?.jsonPrimitive?.long
                if (id != null && ts != null) id to ts else null
            } catch (e: Exception) {
                null
            }
            if (pair != null) recovered.add(pair) else bad.add(element)
        }
        val leftoverJson = if (bad.isNotEmpty()) JsonArray(bad).toString() else null
        return LocalDeletionsRecovery(recovered, anyFailure = bad.isNotEmpty(), leftoverJson = leftoverJson)
    }

    val localDeletions: Flow<List<Pair<String, Long>>> = dataStore.data.map { prefs ->
        parseLocalDeletionsDetailed(prefs[LOCAL_DELETIONS]).recovered
    }

    /** [BaseStoreMigration]'s entry point: the full detailed recovery result (review r2 minor 3). */
    suspend fun localDeletionsRecovery(): LocalDeletionsRecovery {
        val raw = dataStore.data.map { it[LOCAL_DELETIONS] }.first()
        return parseLocalDeletionsDetailed(raw)
    }

    /** Back-compat convenience: `null` iff every element failed and nothing was recoverable at all. */
    suspend fun localDeletionsOrNull(): List<Pair<String, Long>>? {
        val recovery = localDeletionsRecovery()
        return if (recovery.anyFailure && recovery.recovered.isEmpty()) null else recovery.recovered
    }

    suspend fun recordDeletion(id: String, atMs: Long = SyncClocks.tombstone()) {
        val current = localDeletions.first().toMutableList()
        if (current.any { it.first == id }) return
        current.add(id to atMs)
        val json = JsonArray(current.map { (id, ts) ->
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("ts", JsonPrimitive(ts))
            }
        }).toString()
        dataStore.edit { prefs ->
            prefs[LOCAL_DELETIONS] = json
        }
    }

    // WP-8.2 (§4.3, BaseStoreMigration): removes the legacy keys BaseStoreMigration.runIfNeeded
    // moves into Room (sync_base + tombstones), so a second run is a correct no-op (the reads that
    // drive it come back null/empty). DROPBOX_ENABLED / DROPBOX_ACCESS_TOKEN / etc. are untouched --
    // only the base/rev/deletions keys owned by this WP. Split in two (review r1 major 7) so the
    // caller can clear the base/rev pair unconditionally while clearing LOCAL_DELETIONS only after
    // confirming it could actually read every recoverable entry (see localDeletionsOrNull).
    suspend fun clearBaseSnapshotKeys() {
        dataStore.edit { prefs ->
            prefs.remove(DROPBOX_LAST_REV)
            prefs.remove(DROPBOX_BASE_SNAPSHOT)
        }
    }

    suspend fun clearLocalDeletionsKey() {
        dataStore.edit { prefs -> prefs.remove(LOCAL_DELETIONS) }
    }

    // WP-8.2 test support: BaseStoreMigrationTest needs to set LOCAL_DELETIONS with precise,
    // caller-chosen timestamps (recordDeletion() always stamps PhysicalClock.nowMs()) to assert
    // the keep-max-`at` union rule deterministically. Not used by production code.
    suspend fun setLocalDeletionsRaw(json: String) {
        dataStore.edit { prefs -> prefs[LOCAL_DELETIONS] = json }
    }

    // WP-8.2 test support (review r1 major 7): the unparsed raw value, so a test can assert a
    // malformed LOCAL_DELETIONS value was left IN PLACE (not merely "still unparseable", which
    // would also be true of a cleared key) after a failed BaseStoreMigration recovery attempt.
    suspend fun rawLocalDeletions(): String? = dataStore.data.map { it[LOCAL_DELETIONS] }.first()

    suspend fun clearDeletionsUpTo(timestamp: Long) {
        val current = localDeletions.first()
        val remaining = current.filter { it.second > timestamp }
        val json = JsonArray(remaining.map { (id, ts) ->
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("ts", JsonPrimitive(ts))
            }
        }).toString()
        dataStore.edit { prefs ->
            prefs[LOCAL_DELETIONS] = json
        }
    }

    // Stage 4 / T4.2-T4.3 Flow/save accessors
    val evtLastUploadTs: Flow<Long> = dataStore.data.map { it[EVT_LAST_UPLOAD_TS] ?: 0L }
    suspend fun saveEvtLastUploadTs(ts: Long) {
        dataStore.edit { it[EVT_LAST_UPLOAD_TS] = ts }
    }

    /** WP-8.6 EventWatermark: raise EVT_LAST_UPLOAD_TS to [ts] (never lowers). One edit. */
    suspend fun advanceEvtLastUploadTs(ts: Long) {
        dataStore.edit { prefs -> if (ts > (prefs[EVT_LAST_UPLOAD_TS] ?: 0L)) prefs[EVT_LAST_UPLOAD_TS] = ts }
    }

    /** WP-8.6 EventWatermark: lower EVT_LAST_UPLOAD_TS to [ts] (never raises). One edit. */
    suspend fun lowerEvtLastUploadTs(ts: Long) {
        dataStore.edit { prefs -> if (ts < (prefs[EVT_LAST_UPLOAD_TS] ?: 0L)) prefs[EVT_LAST_UPLOAD_TS] = ts }
    }

    // ---- WP-8.6 (PX-49): persisted sync status (Opti cfg.lastSyncAt / lastError / lastBackupError) ----
    val lastSyncAt: Flow<Long> = dataStore.data.map { it[SyncKeys.SYNC_LAST_AT] ?: 0L }
    val lastSyncError: Flow<String?> = dataStore.data.map { it[SyncKeys.SYNC_LAST_ERROR] }
    val lastBackupError: Flow<String?> = dataStore.data.map { it[SyncKeys.SYNC_LAST_BACKUP_ERROR] }

    // ---- CR-KT-018: Opti `localEditAt` (app.js:335-342), device-only ----
    /** Wall-clock ms of the last user write on this device; null before the first one. */
    val localEditAt: Flow<Long?> = dataStore.data.map { it[SyncKeys.LOCAL_EDIT_AT] }

    /** Opti `_noteSaveCommitted`: `localEditAt = Date.now()`. Same clock as [lastSyncAt]. */
    suspend fun stampLocalEdit(nowMs: Long) {
        dataStore.edit { it[SyncKeys.LOCAL_EDIT_AT] = nowMs }
    }

    /** Opti `syncCfgSave({lastError})`: null clears it. [lastSyncAt] is written only when non-null. */
    suspend fun saveSyncStatus(lastError: String?, lastSyncAt: Long? = null) {
        dataStore.edit { prefs ->
            if (lastError == null) prefs.remove(SyncKeys.SYNC_LAST_ERROR) else prefs[SyncKeys.SYNC_LAST_ERROR] = lastError
            if (lastSyncAt != null) prefs[SyncKeys.SYNC_LAST_AT] = lastSyncAt
        }
    }

    suspend fun saveLastBackupError(msg: String?) {
        dataStore.edit { prefs ->
            if (msg == null) prefs.remove(SyncKeys.SYNC_LAST_BACKUP_ERROR) else prefs[SyncKeys.SYNC_LAST_BACKUP_ERROR] = msg
        }
    }

    // ---- WP-8.6: LocalTapQueue (PENDING_TAPS, a JSON list of TapRecord + attempts) ----
    suspend fun pendingTapsRaw(): String? = dataStore.data.map { it[SyncKeys.PENDING_TAPS] }.first()

    /** Read-modify-write in ONE DataStore edit, so two enqueues never lose a tap. */
    suspend fun updatePendingTaps(transform: (String?) -> String?) {
        dataStore.edit { prefs ->
            val next = transform(prefs[SyncKeys.PENDING_TAPS])
            if (next == null) prefs.remove(SyncKeys.PENDING_TAPS) else prefs[SyncKeys.PENDING_TAPS] = next
        }
    }

    val evtLastPullAt: Flow<Long> = dataStore.data.map { it[EVT_LAST_PULL_AT] ?: 0L }
    suspend fun saveEvtLastPullAt(ts: Long) {
        dataStore.edit { it[EVT_LAST_PULL_AT] = ts }
    }

    val evtFileRevs: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val json = prefs[EVT_FILE_REVS] ?: "{}"
        try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun saveEvtFileRevs(revs: Map<String, String>) {
        val json = buildJsonObject {
            revs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }.toString()
        dataStore.edit { it[EVT_FILE_REVS] = json }
    }

    // WP-5.2 Flow/save accessors
    val exportIntervalDays: Flow<Int> = dataStore.data.map { it[EXPORT_INTERVAL_DAYS] ?: 0 }
    suspend fun saveExportIntervalDays(days: Int) {
        dataStore.edit { it[EXPORT_INTERVAL_DAYS] = days }
    }

    val lastExportTs: Flow<Long> = dataStore.data.map { it[LAST_EXPORT_TS] ?: 0L }
    suspend fun saveLastExportTs(ts: Long) {
        dataStore.edit { it[LAST_EXPORT_TS] = ts }
    }

    val monthlyBackups: Flow<List<String>> = dataStore.data.map { prefs ->
        val json = prefs[MONTHLY_BACKUPS] ?: "[]"
        try {
            val arr = Json.parseToJsonElement(json) as JsonArray
            arr.map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveMonthlyBackups(list: List<String>) {
        val json = JsonArray(list.map { JsonPrimitive(it) }).toString()
        dataStore.edit { it[MONTHLY_BACKUPS] = json }
    }

    // ---- WP-8.1: linked account (D7 interlock) and AuthState (§4.3 new ACCOUNT_ID, AUTH_STATE) ----

    val accountId: Flow<String?> = dataStore.data.map { it[SyncKeys.ACCOUNT_ID] }

    suspend fun getAccountId(): String? = dataStore.data.map { it[SyncKeys.ACCOUNT_ID] }.first()

    suspend fun saveAccountId(id: String?) {
        dataStore.edit { prefs ->
            if (id.isNullOrEmpty()) prefs.remove(SyncKeys.ACCOUNT_ID) else prefs[SyncKeys.ACCOUNT_ID] = id
        }
    }

    /** Absent -> NOT_LINKED. An unknown stored name also reads as NOT_LINKED. */
    val authState: Flow<com.example.data.sync.auth.AuthState> = dataStore.data.map { prefs ->
        val raw = prefs[SyncKeys.AUTH_STATE]
        com.example.data.sync.auth.AuthState.values().firstOrNull { it.name == raw }
            ?: com.example.data.sync.auth.AuthState.NOT_LINKED
    }

    suspend fun saveAuthState(state: com.example.data.sync.auth.AuthState) {
        dataStore.edit { it[SyncKeys.AUTH_STATE] = state.name }
    }

    /**
     * Connect succeeded: one edit, so a reader never sees LINKED without the account id.
     * Clears the one-shot PKCE verifier (Opti removes PKCE_KEY after the exchange).
     */
    suspend fun markLinked(accountId: String) {
        dataStore.edit { prefs ->
            prefs[SyncKeys.ACCOUNT_ID] = accountId
            prefs[SyncKeys.AUTH_STATE] = com.example.data.sync.auth.AuthState.LINKED.name
            prefs[DROPBOX_ENABLED] = true
            prefs.remove(OAUTH_VERIFIER)
            // REWORK R-3: a fresh link starts with no stale round error.
            prefs.remove(SyncKeys.SYNC_LAST_ERROR)
        }
    }

    /** Opti `invalid_grant` -> `enabled:false, lastError:"reconnect required"`. */
    suspend fun markReconnectRequired() {
        dataStore.edit { prefs ->
            prefs[DROPBOX_ENABLED] = false
            prefs[SyncKeys.AUTH_STATE] = com.example.data.sync.auth.AuthState.RECONNECT_REQUIRED.name
        }
    }

    suspend fun clearOAuthVerifier() {
        dataStore.edit { it.remove(OAUTH_VERIFIER) }
    }

    /**
     * WP-8.1 -- PX-10, the disconnect key set. Port of Opti `syncDisconnect` (sync.js :758-780):
     * everything goes back to defaults EXCEPT deviceId, evtFileCounts and evtPushBlocked
     * (Opti's keep set; Kotlin has no lastSyncAt key). Kotlin additionally keeps the base
     * (sync_base, and the legacy DROPBOX_LAST_REV / DROPBOX_BASE_SNAPSHOT keys that
     * BaseStoreMigration reads) and APP_KEY_OF_DATA (D8, D16). User data and prefs are untouched.
     * The encrypted tokens are cleared by the caller ([SecureTokenStore.clear]).
     */
    suspend fun clearForDisconnect() {
        dataStore.edit { prefs ->
            prefs.remove(DROPBOX_ACCESS_TOKEN)
            prefs.remove(DROPBOX_REFRESH_TOKEN)
            prefs.remove(DROPBOX_EXPIRES_AT)
            prefs[DROPBOX_ENABLED] = false
            prefs.remove(OAUTH_VERIFIER)
            prefs.remove(SyncKeys.ACCOUNT_ID)
            prefs[SyncKeys.AUTH_STATE] = com.example.data.sync.auth.AuthState.NOT_LINKED.name
            // REWORK R-3: Opti resets lastError to its default (sync.js:757-770); lastSyncAt is kept.
            prefs.remove(SyncKeys.SYNC_LAST_ERROR)
            // Opti resets these event cursors to their defaults (0 / {}).
            prefs.remove(EVT_LAST_UPLOAD_TS)
            prefs.remove(EVT_FILE_REVS)
            prefs.remove(EVT_LAST_PULL_AT)
            prefs.remove(SyncKeys.EVT_FULL_SCAN_AT)
            prefs.remove(SyncKeys.EVT_BAD_REVS)
            prefs.remove(SyncKeys.EVT_FULL_PUSH_AT)
        }
    }

    // §4.3: "Moved to SecureTokenStore (Keystore AES-GCM); keys deleted". Used only by
    // TokenMigration.moveFromDataStore() (WP-8.21 step 2b) and clearForDisconnect().
    // DROPBOX_ENABLED is left alone (§4.3: "Kept; false on invalid_grant").
    suspend fun clearLegacyTokenKeysOnly() {
        dataStore.edit { prefs ->
            prefs.remove(DROPBOX_ACCESS_TOKEN)
            prefs.remove(DROPBOX_REFRESH_TOKEN)
            prefs.remove(DROPBOX_EXPIRES_AT)
        }
    }

    val uiSearchQuery: Flow<String> = dataStore.data.map { it[SyncKeys.UI_SEARCH_QUERY] ?: "" }
    suspend fun saveUiSearchQuery(value: String) {
        dataStore.edit { if (value.isEmpty()) it.remove(SyncKeys.UI_SEARCH_QUERY) else it[SyncKeys.UI_SEARCH_QUERY] = value }
    }

    val uiSelectedTag: Flow<String?> = dataStore.data.map { it[SyncKeys.UI_SELECTED_TAG] }
    suspend fun saveUiSelectedTag(value: String?) {
        dataStore.edit { if (value.isNullOrEmpty()) it.remove(SyncKeys.UI_SELECTED_TAG) else it[SyncKeys.UI_SELECTED_TAG] = value }
    }

    val uiDailyFilter: Flow<String> = dataStore.data.map { it[SyncKeys.UI_DAILY_FILTER] ?: "all" }
    suspend fun saveUiDailyFilter(value: String) {
        dataStore.edit { it[SyncKeys.UI_DAILY_FILTER] = value }
    }

    val uiHabitFilter: Flow<String> = dataStore.data.map { it[SyncKeys.UI_HABIT_FILTER] ?: "all" }
    suspend fun saveUiHabitFilter(value: String) {
        dataStore.edit { it[SyncKeys.UI_HABIT_FILTER] = value }
    }

    val uiTodoFilter: Flow<String> = dataStore.data.map { it[SyncKeys.UI_TODO_FILTER] ?: "active" }
    suspend fun saveUiTodoFilter(value: String) {
        dataStore.edit { it[SyncKeys.UI_TODO_FILTER] = value }
    }

    val uiHabitSort: Flow<String> = dataStore.data.map { it[SyncKeys.UI_HABIT_SORT] ?: "manual" }
    suspend fun saveUiHabitSort(value: String) {
        dataStore.edit { it[SyncKeys.UI_HABIT_SORT] = value }
    }

    val uiDailySort: Flow<String> = dataStore.data.map { it[SyncKeys.UI_DAILY_SORT] ?: "manual" }
    suspend fun saveUiDailySort(value: String) {
        dataStore.edit { it[SyncKeys.UI_DAILY_SORT] = value }
    }

    val uiTodoSort: Flow<String> = dataStore.data.map { it[SyncKeys.UI_TODO_SORT] ?: "manual" }
    suspend fun saveUiTodoSort(value: String) {
        dataStore.edit { it[SyncKeys.UI_TODO_SORT] = value }
    }

    val uiRewardSort: Flow<String> = dataStore.data.map { it[SyncKeys.UI_REWARD_SORT] ?: "manual" }
    suspend fun saveUiRewardSort(value: String) {
        dataStore.edit { it[SyncKeys.UI_REWARD_SORT] = value }
    }

    val uiSelectedTab: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SELECTED_TAB] ?: 0 }
    suspend fun saveUiSelectedTab(value: Int) {
        dataStore.edit { it[SyncKeys.UI_SELECTED_TAB] = value }
    }

    val uiHabitsScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_HABITS_INDEX] ?: 0 }
    val uiHabitsScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_HABITS_OFFSET] ?: 0 }
    suspend fun saveUiHabitsScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_HABITS_INDEX] = index; it[SyncKeys.UI_SCROLL_HABITS_OFFSET] = offset }
    }

    val uiDailiesScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_DAILIES_INDEX] ?: 0 }
    val uiDailiesScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_DAILIES_OFFSET] ?: 0 }
    suspend fun saveUiDailiesScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_DAILIES_INDEX] = index; it[SyncKeys.UI_SCROLL_DAILIES_OFFSET] = offset }
    }

    val uiTodosScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_TODOS_INDEX] ?: 0 }
    val uiTodosScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_TODOS_OFFSET] ?: 0 }
    suspend fun saveUiTodosScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_TODOS_INDEX] = index; it[SyncKeys.UI_SCROLL_TODOS_OFFSET] = offset }
    }

    val uiRewardsScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_REWARDS_INDEX] ?: 0 }
    val uiRewardsScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_REWARDS_OFFSET] ?: 0 }
    suspend fun saveUiRewardsScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_REWARDS_INDEX] = index; it[SyncKeys.UI_SCROLL_REWARDS_OFFSET] = offset }
    }

    val uiAnalyticsScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_ANALYTICS_INDEX] ?: 0 }
    val uiAnalyticsScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_ANALYTICS_OFFSET] ?: 0 }
    suspend fun saveUiAnalyticsScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_ANALYTICS_INDEX] = index; it[SyncKeys.UI_SCROLL_ANALYTICS_OFFSET] = offset }
    }

    val uiLogsScrollIndex: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_LOGS_INDEX] ?: 0 }
    val uiLogsScrollOffset: Flow<Int> = dataStore.data.map { it[SyncKeys.UI_SCROLL_LOGS_OFFSET] ?: 0 }
    suspend fun saveUiLogsScroll(index: Int, offset: Int) {
        dataStore.edit { it[SyncKeys.UI_SCROLL_LOGS_INDEX] = index; it[SyncKeys.UI_SCROLL_LOGS_OFFSET] = offset }
    }

    /** The Dropbox app key entered at runtime, or "" when none is stored. */
    suspend fun dropboxAppKey(): String = dataStore.data.first()[SyncKeys.DROPBOX_APP_KEY] ?: ""

    /** Stores [key] trimmed; an empty key removes the stored one. */
    suspend fun saveDropboxAppKey(key: String) {
        val k = key.trim()
        dataStore.edit { if (k.isEmpty()) it.remove(SyncKeys.DROPBOX_APP_KEY) else it[SyncKeys.DROPBOX_APP_KEY] = k }
    }

    /**
     * Writes [seed] as the app key once, only if no key is stored and no seed was ever applied.
     * After the first call the seed is never applied again, even if the user later clears the key.
     */
    suspend fun applyAppKeySeedOnce(seed: String) {
        if (seed.isEmpty()) return
        dataStore.edit {
            if (it[SyncKeys.DROPBOX_APP_KEY_SEEDED] == true) return@edit
            if (it[SyncKeys.DROPBOX_APP_KEY].isNullOrEmpty()) it[SyncKeys.DROPBOX_APP_KEY] = seed
            it[SyncKeys.DROPBOX_APP_KEY_SEEDED] = true
        }
    }
}
