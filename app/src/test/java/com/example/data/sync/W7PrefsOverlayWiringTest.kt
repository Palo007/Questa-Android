package com.example.data.sync

import com.example.QuestaApplication
import com.example.data.local.AppDatabase
import com.example.data.sync.backup.UiPrefsOverlay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-8.I (W7 close, 8.15 patches 1-2, REQUIRED): the auto-backup source (AppSyncWiring) and the
 * app's SectionImporter both carry the [UiPrefsOverlay], so the tier backups export all 9 local UI
 * prefs and a restore brings them back instead of the frozen `S.prefs` of an older import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class W7PrefsOverlayWiringTest : Wp89TestBase() {

    @Test fun appWiring_autoBackupSource_exportsAllNineUiPrefs() = runBlocking {
        val app = context.applicationContext as QuestaApplication
        val wiring = AppSyncWiring(app, AppDatabase.getDatabase(app), app.repository, app.syncDataStore)
        val prefs = wiring.liveBackup.fullLocalState().toSnapshot()["prefs"]!!.jsonObject
        assertEquals(9, UiPrefsOverlay.KEYS.size)
        UiPrefsOverlay.KEYS.forEach { assertTrue("auto-backup prefs miss $it", it in prefs) }
    }

    @Test fun appSectionImporter_hasTheOverlay() {
        val app = context.applicationContext as QuestaApplication
        val f = SectionImporter::class.java.getDeclaredField("uiPrefs").apply { isAccessible = true }
        assertNotNull("QuestaApplication.sectionImporter has no UiPrefsOverlay", f.get(app.sectionImporter))
    }

    /** 8.15 scenario: import tasks only -> set width 720 -> restore the next auto-backup -> still 720. */
    @Test fun importTasksOnly_thenWidth720_restoreNextAutoBackup_keeps720() = runBlocking {
        val src = device89("dev-src")
        val srcFile: JsonObject = LiveBackupSource(src.repository, src.syncDataStore, src.backupPrefs, UiPrefsOverlay(src.prefsStore))
            .exporter { 1L }.export(null)

        // The device as the app wires it (AppSyncWiring.liveBackup / QuestaApplication.sectionImporter).
        val d = device89("dev-a")
        val overlay = UiPrefsOverlay(d.prefsStore)
        val autoBackup = LiveBackupSource(d.repository, d.syncDataStore, d.backupPrefs, overlay)
        val importer = SectionImporter(d.repository, d.syncDataStore, d.backupPrefs, overlay)

        assertEquals(JsonPrimitive(480), overlay.read()["width"])
        importer.importBackup(srcFile, setOf(Section.HABITS, Section.DAILIES, Section.TODOS), ImportMode.REPLACE)
        overlay.setWidth(720)

        val next = autoBackup.backupText()
        importer.importBackupText(next, Section.entries.toSet(), ImportMode.REPLACE)
        assertEquals(JsonPrimitive(720), overlay.read()["width"])
    }

    /** Control: the pre-patch wiring (auto-backup source with no overlay) reverts the width to 480. */
    @Test fun control_autoBackupWithoutOverlay_revertsTheWidth() = runBlocking {
        val src = device89("dev-src")
        val srcFile: JsonObject = LiveBackupSource(src.repository, src.syncDataStore, src.backupPrefs, UiPrefsOverlay(src.prefsStore))
            .exporter { 1L }.export(null)
        val d = device89("dev-a")
        val overlay = UiPrefsOverlay(d.prefsStore)
        val importer = SectionImporter(d.repository, d.syncDataStore, d.backupPrefs, overlay)
        importer.importBackup(srcFile, setOf(Section.HABITS, Section.DAILIES, Section.TODOS), ImportMode.REPLACE)
        overlay.setWidth(720)
        importer.importBackupText(d.live.backupText(), Section.entries.toSet(), ImportMode.REPLACE)
        assertEquals(JsonPrimitive(480), overlay.read()["width"])
    }
}
