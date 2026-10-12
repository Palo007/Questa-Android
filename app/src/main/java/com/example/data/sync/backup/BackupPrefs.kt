package com.example.data.sync.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** `S.prefs.autoBackupEnabled` (app.js:120): one flag per tier. */
data class AutoBackupEnabled(
    val fourHour: Boolean = false,
    val daily: Boolean = false,
    val weekly: Boolean = false,
    val monthly: Boolean = false,
) {
    fun isOn(t: BkTier): Boolean = when (t) {
        BkTier.FOUR_HOUR -> fourHour
        BkTier.DAILY -> daily
        BkTier.WEEKLY -> weekly
        BkTier.MONTHLY -> monthly
    }

    fun with(t: BkTier, on: Boolean): AutoBackupEnabled = when (t) {
        BkTier.FOUR_HOUR -> copy(fourHour = on)
        BkTier.DAILY -> copy(daily = on)
        BkTier.WEEKLY -> copy(weekly = on)
        BkTier.MONTHLY -> copy(monthly = on)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("fourHour", fourHour); put("daily", daily); put("weekly", weekly); put("monthly", monthly)
    }

    companion object {
        /**
         * The `migrate()` backfill (app.js:249-256): `exportIntervalDays` 1|3 -> daily, 7 -> weekly,
         * 14|30 -> monthly, anything else -> all off. [days] null = absent (`parseInt` NaN).
         */
        fun fromExportIntervalDays(days: Int?): AutoBackupEnabled = when (days) {
            1, 3 -> AutoBackupEnabled(daily = true)
            7 -> AutoBackupEnabled(weekly = true)
            14, 30 -> AutoBackupEnabled(monthly = true)
            else -> AutoBackupEnabled()
        }

        /** JS truthiness of each tier flag of a stored / imported `autoBackupEnabled` object. */
        fun fromJson(o: JsonObject): AutoBackupEnabled {
            fun on(k: String): Boolean {
                val e = o[k] ?: return false
                if (e is kotlinx.serialization.json.JsonNull) return false
                val p = e as? JsonPrimitive ?: return true // an object or array is truthy
                p.booleanOrNull?.let { return it }
                if (p.isString) return p.content.isNotEmpty()
                val d = p.doubleOrNull ?: return false
                return d != 0.0 && !d.isNaN()
            }
            return AutoBackupEnabled(on("fourHour"), on("daily"), on("weekly"), on("monthly"))
        }

        /** `parseInt(x, 10)` of a JSON value (number or string); null = NaN. */
        fun parseIntJs(e: JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            if (p is kotlinx.serialization.json.JsonNull) return null
            val s = p.content.trimStart()
            val m = Regex("^[+-]?\\d+").find(s) ?: return null
            return m.value.toBigDecimal().toInt()
        }
    }
}

/** One tier's bookkeeping (Opti `questa.autobackup.local`, sync.js:2761-2792). */
data class TierState(val bkVersion: Int = 0, val lastTs: Long = 0L, val idx: Int = 0)

/**
 * WP-8.9 -- the backup prefs that live in DataStore:
 *  - [SyncKeys.AUTO_BACKUP_TIERS]: `S.prefs.autoBackupEnabled` (backup prefs overlay, §4.3).
 *    On first read, when absent, it is migrated from the legacy `EXPORT_INTERVAL_DAYS` with
 *    Opti's `migrate()` rule and that key is deleted (§4.3 row :58).
 *  - [TIER_STATE]: the per-tier bookkeeping, off the sync and merge path, like Opti's localStorage.
 *  - [MIGRATED_TOAST]: `S.prefs.autoBackupMigratedToast` (the 14-day upgrade notice was owed).
 */
class BackupPrefs(private val dataStore: DataStore<Preferences>) {

    companion object {
        /** Opti `questa.autobackup.local` (declared here: WP-8.9 guess, SyncKeys is 8.2's file). */
        val TIER_STATE = stringPreferencesKey("auto_backup_local")
        /** Opti `S.prefs.autoBackupMigratedToast`. */
        val MIGRATED_TOAST = booleanPreferencesKey("auto_backup_migrated_toast")
        /** Opti `questa.sync.pendingForcePush` (the "Connect & Force Push" intent, sync.js:3787). */
        val PENDING_FORCE_PUSH = booleanPreferencesKey("pending_force_push")
    }

    /**
     * The tier selection, migrating `EXPORT_INTERVAL_DAYS` on the first read (one edit: the new
     * key written, the old key deleted). Opti sets `autoBackupMigratedToast` for 14 days only.
     */
    suspend fun autoBackupEnabled(): AutoBackupEnabled {
        val raw = dataStore.data.first()[SyncKeys.AUTO_BACKUP_TIERS]
        if (raw != null) return parse(raw)
        var out = AutoBackupEnabled()
        dataStore.edit { prefs ->
            val stored = prefs[SyncKeys.AUTO_BACKUP_TIERS]
            if (stored != null) { out = parse(stored); return@edit }
            val days = prefs[SyncKeys.EXPORT_INTERVAL_DAYS]
            out = AutoBackupEnabled.fromExportIntervalDays(days)
            prefs[SyncKeys.AUTO_BACKUP_TIERS] = out.toJson().toString()
            prefs.remove(SyncKeys.EXPORT_INTERVAL_DAYS)
            if (days == 14 && prefs[MIGRATED_TOAST] != true) prefs[MIGRATED_TOAST] = true
        }
        return out
    }

    suspend fun setAutoBackupEnabled(v: AutoBackupEnabled) {
        dataStore.edit { prefs ->
            prefs[SyncKeys.AUTO_BACKUP_TIERS] = v.toJson().toString()
            prefs.remove(SyncKeys.EXPORT_INTERVAL_DAYS)
        }
    }

    suspend fun migratedToastOwed(): Boolean = dataStore.data.first()[MIGRATED_TOAST] == true

    private fun parse(raw: String): AutoBackupEnabled = try {
        (Json.parseToJsonElement(raw) as? JsonObject)?.let { AutoBackupEnabled.fromJson(it) } ?: AutoBackupEnabled()
    } catch (e: Exception) { AutoBackupEnabled() }

    /** `bkLocalLoad()`: defaults `{bkVersion:0,lastTs:0,idx:0}` per tier; non-numbers -> 0. */
    suspend fun tierStates(): Map<BkTier, TierState> {
        val raw = dataStore.data.first()[TIER_STATE]
        val out = BkTier.ORDER.associateWith { TierState() }.toMutableMap()
        val obj = try { raw?.let { Json.parseToJsonElement(it) as? JsonObject } } catch (e: Exception) { null } ?: return out
        for (t in BkTier.ORDER) {
            val o = obj[t.key] as? JsonObject ?: continue
            fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            out[t] = TierState(
                bkVersion = num("bkVersion")?.toInt() ?: 0,
                lastTs = num("lastTs")?.toLong() ?: 0L,
                idx = num("idx")?.toInt() ?: 0,
            )
        }
        return out
    }

    /** `bkLocalSave(data)`. */
    suspend fun saveTierStates(states: Map<BkTier, TierState>) {
        val json = buildJsonObject {
            for (t in BkTier.ORDER) {
                val s = states[t] ?: TierState()
                put(t.key, buildJsonObject { put("bkVersion", s.bkVersion); put("lastTs", s.lastTs); put("idx", s.idx) })
            }
        }
        dataStore.edit { it[TIER_STATE] = json.toString() }
    }

    suspend fun pendingForcePush(): Boolean = dataStore.data.first()[PENDING_FORCE_PUSH] == true

    suspend fun setPendingForcePush(on: Boolean) {
        dataStore.edit { if (on) it[PENDING_FORCE_PUSH] = true else it.remove(PENDING_FORCE_PUSH) }
    }

    /** Reset: `autoBackupEnabled` back to freshState (all off); LAST_EXPORT_TS removed. */
    suspend fun resetToFresh() {
        dataStore.edit { prefs ->
            prefs[SyncKeys.AUTO_BACKUP_TIERS] = AutoBackupEnabled().toJson().toString()
            prefs.remove(SyncKeys.EXPORT_INTERVAL_DAYS)
            prefs.remove(SyncKeys.LAST_EXPORT_TS)
            prefs.remove(MIGRATED_TOAST)
        }
    }
}
