package com.example.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PX-76 / E5 / O5: Bubblewrap's ReminderDedupe is NOT ported. There is one notification path
 * (the fire key is the only guard), so main has no dedupe class and exactly one notify call.
 */
class NoDedupePathTest {

    private fun mainRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    @Test
    fun `no ReminderDedupe and a single notify call site`() {
        val files = mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val dedupe = files.filter { f -> f.readLines().any { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && it.contains("ReminderDedupe") } }
        assertTrue("dedupe code found: $dedupe", dedupe.isEmpty())
        // CR-KT-014: the prune warning (PruneWarnNotifier.kt, own "Data maintenance" channel) is not
        // a reminder path; it is the only other allowed notify site.
        val notifySites = files.filter { it.name != "PruneWarnNotifier.kt" }
            .sumOf { f -> f.readLines().count { it.contains(".notify(") && !it.trimStart().startsWith("//") } }
        assertEquals(1, notifySites)
        assertEquals(1, files.single { it.name == "PruneWarnNotifier.kt" }.readLines().count { it.contains(".notify(") })
    }
}
