package com.example.data.sync.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncKeys
import com.example.data.sync.SyncSubset
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * WP-8.15 (PX-88..91, §4.3): the local UI prefs that ride in a backup's `S.prefs` (the backup
 * prefs overlay) and never on the sync wire. Opti names and defaults (`migrate()`, app.js:220;
 * `showStreaks !== false`, app.js:6818): width 480, notesLines 3, haptics true, cardThick 0
 * (clamped 0..60, `setCardThick`, app.js:6692), notificationsEnabled false, saveBtnTop false,
 * hideSyncDiag true, hideConflictDecisions false, showStreaks true.
 *
 * [read] exports the live values; [write] applies an imported `S.prefs` as Opti's `S = migrate(data)`
 * does: an absent key takes the `migrate()` default, a present key its JS truthiness.
 */
class UiPrefsOverlay(private val ds: DataStore<Preferences>) {

    suspend fun read(): JsonObject {
        val p = ds.data.first()
        return buildJsonObject {
            put("width", p[SyncKeys.WIDTH_PX] ?: DEFAULT_WIDTH)
            put("notesLines", p[SyncKeys.NOTES_LINES] ?: DEFAULT_NOTES_LINES)
            put("haptics", p[SyncKeys.HAPTICS_ENABLED] ?: true)
            put("cardThick", (p[SyncKeys.CARD_THICK] ?: 0).coerceIn(0, MAX_CARD_THICK))
            put("notificationsEnabled", p[SyncKeys.NOTIFICATIONS_ENABLED] ?: false)
            put("saveBtnTop", p[SyncKeys.SAVE_BUTTON_TOP] ?: false)
            put("hideSyncDiag", p[SyncKeys.HIDE_SYNC_DIAG] ?: true)
            put("hideConflictDecisions", p[SyncKeys.HIDE_CONFLICTS] ?: false)
            put("showStreaks", p[SyncKeys.SHOW_STREAKS] ?: true)
        }
    }

    suspend fun write(prefs: JsonObject) {
        ds.edit { p ->
            // `S.prefs.width || 480`
            p[SyncKeys.WIDTH_PX] = num(prefs["width"])?.takeIf { it != 0 } ?: DEFAULT_WIDTH
            // `S.prefs.notesLines == null ? 3 : S.prefs.notesLines`
            p[SyncKeys.NOTES_LINES] = num(prefs["notesLines"]) ?: DEFAULT_NOTES_LINES
            p[SyncKeys.HAPTICS_ENABLED] = !isFalse(prefs["haptics"])
            p[SyncKeys.CARD_THICK] = cardThickOf(prefs).coerceIn(0, MAX_CARD_THICK)
            p[SyncKeys.NOTIFICATIONS_ENABLED] = truthy(prefs["notificationsEnabled"], default = false)
            p[SyncKeys.SAVE_BUTTON_TOP] = truthy(prefs["saveBtnTop"], default = false)
            p[SyncKeys.HIDE_SYNC_DIAG] = truthy(prefs["hideSyncDiag"], default = true)
            p[SyncKeys.HIDE_CONFLICTS] = truthy(prefs["hideConflictDecisions"], default = false)
            p[SyncKeys.SHOW_STREAKS] = !isFalse(prefs["showStreaks"])
        }
    }

    // ---- The Settings rows (Opti setWidth / setNotesLines / setCardThick / setShowStreaks /
    // setHideSyncDiag / setHideConflictDecisions, app.js:6686-6692) ----

    suspend fun setWidth(px: Int) { ds.edit { it[SyncKeys.WIDTH_PX] = px } }
    suspend fun setNotesLines(n: Int) { ds.edit { it[SyncKeys.NOTES_LINES] = n } }
    suspend fun setCardThick(px: Int) { ds.edit { it[SyncKeys.CARD_THICK] = px.coerceIn(0, MAX_CARD_THICK) } }
    suspend fun setShowStreaks(on: Boolean) { ds.edit { it[SyncKeys.SHOW_STREAKS] = on } }
    suspend fun setHideSyncDiag(on: Boolean) { ds.edit { it[SyncKeys.HIDE_SYNC_DIAG] = on } }
    suspend fun setHideConflictDecisions(on: Boolean) { ds.edit { it[SyncKeys.HIDE_CONFLICTS] = on } }

    companion object {
        const val DEFAULT_WIDTH = 480
        const val DEFAULT_NOTES_LINES = 3
        const val MAX_CARD_THICK = 60

        /** The `S.prefs` keys this overlay owns (none of them is on the sync wire). */
        val KEYS = listOf(
            "width", "notesLines", "haptics", "cardThick", "notificationsEnabled",
            "saveBtnTop", "hideSyncDiag", "hideConflictDecisions", "showStreaks",
        )

        private fun num(e: JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.doubleOrNull?.takeIf { it.isFinite() }?.toInt()
        }

        /**
         * Opti migrate() (app.js:221-227): a legacy `cardPad` (any value but undefined) that
         * parseInt reads wins as `max(0, cardPad - 5)`; otherwise `cardThick` is kept.
         */
        private fun cardThickOf(prefs: JsonObject): Int {
            if (prefs.containsKey("cardPad")) {
                AutoBackupEnabled.parseIntJs(prefs["cardPad"])?.let { return maxOf(0, it - 5) }
            }
            return num(prefs["cardThick"]) ?: 0
        }

        private fun isFalse(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true

        private fun truthy(e: JsonElement?, default: Boolean): Boolean = if (e == null) default else SyncSubset.jsTruthy(e)
    }
}
