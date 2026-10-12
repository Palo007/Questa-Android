package com.example.data.sync

import com.example.data.local.DataStorePruneWarnStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CR-KT-014: `pruneWarnedAt` is a device-only pref. It lives in its own DataStore file
 * ([DataStorePruneWarnStore.FILE_NAME]) that no sync, export or backup code reads, and the
 * Android auto-backup / device-transfer rules exclude that file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PruneWarnNotExportedTest : Wp89TestBase() {

    private fun file(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test fun backupText_hasNoPruneKey() = runBlocking {
        val d = device89()
        val text = d.live.backupText(d.live.exporter { 1_790_000_000_000L })
        assertFalse(text.contains("prune", ignoreCase = true))
        assertFalse(text.contains(DataStorePruneWarnStore.FILE_NAME))
    }

    @Test fun noSyncOrExportCode_readsTheStore() {
        val dir = file("src/main/java/com/example/data/sync")
        val hits = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("PruneWarnStore") || it.contains(DataStorePruneWarnStore.FILE_NAME) || it.contains("prune_warned_at") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun androidBackupRules_excludeTheStoreFile() {
        val path = "datastore/${DataStorePruneWarnStore.FILE_NAME}.preferences_pb"
        val full = file("src/main/res/xml/backup_rules.xml").readText()
        val extraction = file("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(full.contains("<exclude domain=\"file\" path=\"$path\" />"))
        assertEquals("cloud-backup and device-transfer", 2,
            Regex(Regex.escape("<exclude domain=\"file\" path=\"$path\" />")).findAll(extraction).count())
    }
}
