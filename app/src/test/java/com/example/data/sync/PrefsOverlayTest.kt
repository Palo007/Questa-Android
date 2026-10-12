package com.example.data.sync

import androidx.datastore.preferences.core.edit
import com.example.data.sync.backup.UiPrefsOverlay
import com.example.ui.CardPrefs
import com.example.ui.settings.FeedFilters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.15 (§4.3): the Appearance / Activity Feed / Interaction prefs ride in the backup prefs
 * overlay (export and import) and never in the sync state (`state.json` = the sync subset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsOverlayTest : Wp89TestBase() {

    private fun keysOf(e: JsonElement): Set<String> = when (e) {
        is JsonObject -> e.keys + e.values.flatMap { keysOf(it) }
        is JsonArray -> e.flatMap { keysOf(it) }.toSet()
        else -> emptySet()
    }

    /** The keys WP-8.15 adds (haptics was already exported by 8.9). */
    private val addedKeys = UiPrefsOverlay.KEYS - "haptics"

    private suspend fun customise(o: UiPrefsOverlay, d: Wp89Device) {
        o.setWidth(720)
        o.setNotesLines(5)
        o.setCardThick(17)
        o.setShowStreaks(false)
        o.setHideSyncDiag(false)
        o.setHideConflictDecisions(true)
        d.syncDataStore.setHapticsEnabled(false)
        d.syncDataStore.setSaveButtonTop(false)
        d.prefsStore.edit { it[SyncKeys.NOTIFICATIONS_ENABLED] = true }
    }

    @Test fun each_row_writes_its_key() = runBlocking {
        val d = device89("dev-a")
        val o = UiPrefsOverlay(d.prefsStore)
        customise(o, d)
        val p = d.prefsStore.data.first()
        assertEquals(720, p[SyncKeys.WIDTH_PX])
        assertEquals(5, p[SyncKeys.NOTES_LINES])
        assertEquals(17, p[SyncKeys.CARD_THICK])
        assertEquals(false, p[SyncKeys.SHOW_STREAKS])
        assertEquals(false, p[SyncKeys.HIDE_SYNC_DIAG])
        assertEquals(true, p[SyncKeys.HIDE_CONFLICTS])
        assertEquals(CardPrefs(notesLines = 5, cardThick = 17, showStreaks = false, widthPx = 720), CardPrefs.flow(d.prefsStore.data).first())
        assertEquals(FeedFilters(hideSyncDiag = false, hideConflictDecisions = true), FeedFilters.flow(d.prefsStore.data).first())
        o.setCardThick(80)
        assertEquals(60, d.prefsStore.data.first()[SyncKeys.CARD_THICK])
    }

    @Test fun the_overlay_has_the_keys_and_state_json_has_none() = runBlocking {
        val d = device89("dev-a")
        val o = UiPrefsOverlay(d.prefsStore)
        customise(o, d)
        val live = LiveBackupSource(d.repository, d.syncDataStore, d.backupPrefs, o)

        val prefs = live.fullLocalState().toSnapshot()["prefs"]!!.jsonObject
        assertEquals(JsonPrimitive(720), prefs["width"])
        assertEquals(JsonPrimitive(5), prefs["notesLines"])
        assertEquals(JsonPrimitive(17), prefs["cardThick"])
        assertEquals(JsonPrimitive(false), prefs["showStreaks"])
        assertEquals(JsonPrimitive(false), prefs["hideSyncDiag"])
        assertEquals(JsonPrimitive(true), prefs["hideConflictDecisions"])
        assertEquals(JsonPrimitive(false), prefs["haptics"])
        assertEquals(JsonPrimitive(false), prefs["saveBtnTop"])
        assertEquals(JsonPrimitive(true), prefs["notificationsEnabled"])

        // The backup file carries them too.
        val file = live.exporter { 1L }.export(null)
        assertTrue(file.toString().contains("\"hideConflictDecisions\""))

        // state.json: the sync subset of the round and of the backup's S.
        val wire = keysOf(d.manager.buildLocalSnapshot()) + keysOf(ExportSections.subsetOf(live.fullLocalState().toSnapshot()))
        assertEquals(9, UiPrefsOverlay.KEYS.size)
        UiPrefsOverlay.KEYS.forEach { assertFalse("state.json has $it", it in wire) }
    }

    @Test fun without_the_overlay_only_haptics_is_exported_as_before() = runBlocking {
        val d = device89("dev-a")
        val prefs = d.live.fullLocalState().toSnapshot()["prefs"]!!.jsonObject
        assertTrue("haptics" in prefs)
        addedKeys.forEach { assertFalse(it in prefs) }
    }

    @Test fun a_prefs_import_restores_them() = runBlocking {
        val src = device89("dev-src")
        val so = UiPrefsOverlay(src.prefsStore)
        customise(so, src)
        val file = LiveBackupSource(src.repository, src.syncDataStore, src.backupPrefs, so).exporter { 1L }.export(setOf(Section.PREFS))

        val dst = device89("dev-dst")
        val dOverlay = UiPrefsOverlay(dst.prefsStore)
        SectionImporter(dst.repository, dst.syncDataStore, dst.backupPrefs, dOverlay).importBackup(file, setOf(Section.PREFS), ImportMode.REPLACE)
        assertEquals(so.read(), dOverlay.read())
        assertFalse(dst.syncDataStore.hapticsEnabled.first())
    }

    @Test fun absent_keys_take_Optis_migrate_defaults_and_values_their_truthiness() = runBlocking {
        val d = device89("dev-a")
        val o = UiPrefsOverlay(d.prefsStore)
        customise(o, d)
        o.write(JsonObject(emptyMap()))
        assertEquals(
            buildJsonObject {
                put("width", 480); put("notesLines", 3); put("haptics", true); put("cardThick", 0)
                put("notificationsEnabled", false); put("saveBtnTop", false); put("hideSyncDiag", true)
                put("hideConflictDecisions", false); put("showStreaks", true)
            },
            o.read()
        )
        o.write(buildJsonObject {
            put("width", 0); put("cardThick", 99); put("notesLines", 1.0); put("haptics", 0)
            put("hideSyncDiag", 0); put("showStreaks", 0); put("hideConflictDecisions", "x")
        })
        val r = o.read()
        assertEquals(JsonPrimitive(480), r["width"]) // S.prefs.width || 480
        assertEquals(JsonPrimitive(60), r["cardThick"])
        assertEquals(JsonPrimitive(1), r["notesLines"])
        assertEquals(JsonPrimitive(true), r["haptics"]) // !== false
        assertEquals(JsonPrimitive(true), r["showStreaks"]) // !== false
        assertEquals(JsonPrimitive(false), r["hideSyncDiag"])
        assertEquals(JsonPrimitive(true), r["hideConflictDecisions"])
    }

    /** Opti migrate() (app.js:221-227): a legacy `cardPad` becomes `cardThick = max(0, parseInt(cardPad) - 5)`. */
    @Test fun legacy_cardPad_converts_as_Optis_migrate() = runBlocking {
        val d = device89("dev-a")
        val o = UiPrefsOverlay(d.prefsStore)
        suspend fun thick(prefs: JsonObject): JsonElement? { o.write(prefs); return o.read()["cardThick"] }
        assertEquals(JsonPrimitive(10), thick(buildJsonObject { put("cardPad", 15) }))
        assertEquals(JsonPrimitive(0), thick(buildJsonObject { put("cardPad", 3) }))
        assertEquals(JsonPrimitive(7), thick(buildJsonObject { put("cardPad", "12px") }))
        assertEquals(JsonPrimitive(10), thick(buildJsonObject { put("cardThick", 40); put("cardPad", 15) }))
        // parseInt fails -> cardThick is kept.
        assertEquals(JsonPrimitive(40), thick(buildJsonObject { put("cardThick", 40); put("cardPad", "x") }))
        assertEquals(JsonPrimitive(40), thick(buildJsonObject { put("cardThick", 40); put("cardPad", kotlinx.serialization.json.JsonNull) }))
    }

    /** Opti freshState / migrate() saveBtnTop:false (app.js:120, :220): a missing key reads false, as the overlay restores it. */
    @Test fun saveBtnTop_missing_reads_false_in_store_and_overlay() = runBlocking {
        val d = device89("dev-a")
        assertFalse(d.syncDataStore.isSaveButtonTop.first())
        val o = UiPrefsOverlay(d.prefsStore)
        assertEquals(JsonPrimitive(false), o.read()["saveBtnTop"])
        d.syncDataStore.setSaveButtonTop(true)
        o.write(JsonObject(emptyMap()))
        assertFalse(d.syncDataStore.isSaveButtonTop.first())
    }
}
