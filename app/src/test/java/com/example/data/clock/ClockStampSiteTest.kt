package com.example.data.clock

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.2 (Wave 1) -- PARITY_FIX_PLAN.html §4.4 "Clock stamp-site inventory (verified by grep)".
 *
 * Enforces the §4.4 matching rule against the live `app/src/main` tree, against the baseline in
 * `app/src/test/resources/clock_baseline.txt`:
 *  - scope: every `.kt` file under `app/src/main`;
 *  - a MATCHED line is one containing `System.currentTimeMillis()`, `Instant.now()`, a bare
 *    `Date()`, `SystemClock.` or `Clock.System`, counted once per line even with 2+ matches;
 *  - a line whose first non-blank characters are `//`, `*` or a block-comment opener is skipped;
 *  - for a file listed in the baseline: its live count must be <= its baseline entry;
 *  - for a file NOT listed in the baseline: every matched line must carry an inline
 *    `// clock-ok:<category>` tag whose category is one of the §4.4 permitted categories, or the
 *    file has 0 matches.
 *
 * review r1 major 9: a call to `PhysicalClock.nowMs()` is ALSO a clock-stamp site (it is still a
 * read of wall-clock time, just relocated behind one named wrapper) -- counting only the 5 raw
 * platform APIs let the 7 entity files and the 3 DAO files read as "0 matches" purely because they
 * now call `PhysicalClock.nowMs()` instead of `System.currentTimeMillis()` directly, which hid
 * real sites rather than removing them. The pattern below also matches `PhysicalClock\.`.
 * [PhysicalClock] itself (`app/src/main/java/com/example/data/clock/HlcClock.kt`) is a
 * non-baseline file; every one of its own `PhysicalClock.`-matching lines carries the
 * `// clock-ok:hlc-physical` tag.
 *
 * WP-8.23 (Pali, N-5): the baseline is 0. The last 13 lines (10 default-value stamps in 7 entity
 * files, 1 `deleteWithTombstone(at = ...)` default in each of 3 DAOs) were removed: the entities
 * and DAOs have no clock default, and every caller passes the stamp explicitly -- `updatedAt` /
 * the character's `updatedAt` and tombstone `at` from the shared HLC (review 49, PX-20), `createdAt`
 * and event `timestamp` from the physical clock seam (`RpgRepository.physicalNow()` = `BootGate.clock()`). A baseline
 * file may never gain a site again (live count <= 0), and every entry must stay 0.
 */
class ClockStampSiteTest {

    private val pattern = Regex(
        """System\.currentTimeMillis\(\)|Instant\.now\(\)|\bDate\(\)|SystemClock\.|Clock\.System|PhysicalClock\."""
    )

    private val permittedCategories = setOf(
        "hlc-physical",
        "transport-timer",
        "alarm-trigger",
        "scheduler-debounce",
        "backup-local-stamp",
        "merge-now-physical",
        "wrapper-savedAt",
        "tombstone-gc",
        "tap-physical",
        "boot-gate-timer",
        "event-prune" // CR-KT-013: EventPruner age cutoff (skipped when a debug offset is set)
    )

    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    private fun clockOkCategory(line: String): String? {
        val idx = line.indexOf("// clock-ok:")
        if (idx < 0) return null
        val rest = line.substring(idx + "// clock-ok:".length)
        return rest.takeWhile { !it.isWhitespace() }
    }

    private fun countMatchedLines(file: File): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, String>>()
        file.readLines().forEachIndexed { index, line ->
            if (isCommentLine(line)) return@forEachIndexed
            if (pattern.containsMatchIn(line)) {
                hits.add((index + 1) to line)
            }
        }
        return hits
    }

    private fun findMainSrcRoot(): File {
        // Robolectric/Gradle test working directory is the `app` module root.
        var dir = File(".").absoluteFile
        var candidate = File(dir, "src/main")
        if (candidate.isDirectory) return candidate
        // Fall back: walk up looking for app/src/main (e.g. when invoked from the repo root).
        candidate = File(dir, "app/src/main")
        if (candidate.isDirectory) return candidate
        throw IllegalStateException("Could not locate app/src/main from ${dir.absolutePath}")
    }

    /** Baseline keys are always `app/src/main/<relative path>`, regardless of the Gradle CWD. */
    private fun relativeKey(root: File, file: File): String {
        val relToRoot = file.absoluteFile.path.replace('\\', '/')
            .substringAfter(root.absoluteFile.path.replace('\\', '/') + "/")
        return "app/src/main/$relToRoot"
    }

    @Test
    fun `every kt file in app src main satisfies the §4_4 clock-stamp-site rule`() {
        val root = findMainSrcRoot()
        val baselineFile = File("src/test/resources/clock_baseline.txt").let {
            if (it.isFile) it else File("app/src/test/resources/clock_baseline.txt")
        }
        val baseline: Map<String, Int> = baselineFile.readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (path, count) = line.split("=", limit = 2)
                path.trim() to count.trim().toInt()
            }

        val violations = mutableListOf<String>()
        var totalBaselineFiles = 0

        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val key = relativeKey(root, file)
            val hits = countMatchedLines(file)
            if (key in baseline) {
                totalBaselineFiles++
                val limit = baseline.getValue(key)
                if (hits.size > limit) {
                    violations.add("$key: ${hits.size} matches > baseline $limit")
                }
            } else {
                val untagged = hits.filterNot { (_, line) ->
                    clockOkCategory(line) in permittedCategories
                }
                if (untagged.isNotEmpty()) {
                    violations.add(
                        "$key: ${untagged.size} untagged/mis-tagged clock-stamp line(s) " +
                            "(not in baseline, no valid clock-ok tag): " +
                            untagged.joinToString { (n, l) -> "L$n" }
                    )
                }
            }
        }

        assertTrue(
            "clock_baseline.txt did not match any files under $root -- path resolution is broken",
            totalBaselineFiles > 0
        )
        assertTrue("Clock-stamp-site violations:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * Review 49 (PX-20): `updatedAt` and tombstone `at` are HLC stamps (Opti `now()`); a line that
     * writes one of them must not read the physical seam (`physicalNow()` / `BootGate.clock()`).
     */
    @Test
    fun `updatedAt and tombstone at sites never use the physical clock seam`() {
        val root = findMainSrcRoot()
        val stampSite = Regex("""\bupdatedAt\s*=|deleteWithTombstone\(|TombstoneEntity\(|insertTombstone\(""")
        val physical = Regex("""physicalNow\(\)|BootGate\.clock\(\)""")
        val bad = mutableListOf<String>()
        var hlcTombstones = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (isCommentLine(line) || !stampSite.containsMatchIn(line)) return@forEachIndexed
                if (physical.containsMatchIn(line)) bad.add("${relativeKey(root, file)}:L${i + 1}")
                if (line.contains("deleteWithTombstone(") && line.contains("hlcClock.now()")) hlcTombstones++
            }
        }
        assertTrue("updatedAt / tombstone `at` stamped from the physical clock:\n" + bad.joinToString("\n"), bad.isEmpty())
        assertEquals("expected the 6 repository deletes to stamp the tombstone from the HLC (bulk-select-v1 added bulkDelete)", 6, hlcTombstones)
    }

    @Test
    fun `baseline lists the 19 §4_4 files and every entry is 0`() {
        val baselineFile = File("src/test/resources/clock_baseline.txt").let {
            if (it.isFile) it else File("app/src/test/resources/clock_baseline.txt")
        }
        val entries = baselineFile.readLines().filter { it.isNotBlank() }.associate {
            val (path, count) = it.split("=", limit = 2)
            path.trim() to count.trim().toInt()
        }
        // WP-8.14: +1 file, AnalyticsScreen.kt (its 1 site moved verbatim out of DashboardScreen.kt).
        assertTrue("expected 19 files in clock_baseline.txt, got ${entries.size}", entries.size == 19)

        // review r1 major 9 / minor 10: the DAOs went 2 each -> 1 each (the consolidated
        // deleteWithTombstone default); WP-8.23: 1 -> 0 each (no default; the repository passes
        // `at`). The 7 entity files went from their original 10 lines to 0 (no stamp defaults).
        val daoEntries = entries.filterKeys {
            it.endsWith("/dao/TaskDao.kt") || it.endsWith("/dao/RewardDao.kt") || it.endsWith("/dao/TagDao.kt")
        }
        assertTrue("expected 3 DAO entries, got ${daoEntries.size}", daoEntries.size == 3)
        assertTrue("expected every DAO entry to be 0 after WP-8.23, got $daoEntries", daoEntries.values.all { it == 0 })

        val entityEntries = entries.filterKeys { it.contains("/entities/") }
        assertTrue("expected 7 entity entries, got ${entityEntries.size}", entityEntries.size == 7)
        assertTrue("expected every entity entry to be 0 after WP-8.23, got $entityEntries", entityEntries.values.all { it == 0 })

        // WP-8.5 (D18): SerializationEngine.kt 18 -> 0, so the ratcheted sum is 64 - 18 = 46.
        assertEquals("expected SerializationEngine.kt at 0 after WP-8.5", 0, entries.getValue("app/src/main/java/com/example/data/sync/SerializationEngine.kt"))
        // WP-8.6: SyncManager.kt 5 -> 0, RpgRepository.kt 1 -> 0, SyncDataStore.kt 2 -> 0 (8 lines): 46 - 8 = 38.
        for (f in listOf("sync/SyncManager.kt", "RpgRepository.kt", "sync/SyncDataStore.kt")) {
            assertEquals("expected $f at 0 after WP-8.6", 0, entries.getValue("app/src/main/java/com/example/data/$f"))
        }
        // WP-8.7: EventSyncEngine.kt 1 -> 0; WP-8.10: RpgEngine.kt 16 -> 0, MainViewModel.kt 2 -> 0: 38 - 1 - 18 = 19.
        assertEquals("expected EventSyncEngine.kt at 0 after WP-8.7", 0, entries.getValue("app/src/main/java/com/example/data/sync/EventSyncEngine.kt"))
        assertEquals(0, entries.getValue("app/src/main/java/com/example/data/RpgEngine.kt"))
        assertEquals(0, entries.getValue("app/src/main/java/com/example/ui/MainViewModel.kt"))
        // WP-8.14: DashboardScreen.kt 6 -> 4 (1 moved to AnalyticsScreen.kt, 1 left with the old
        // export dialog; the Settings host names files from the transport clock), AnalyticsScreen.kt 1: 19 - 2 + 1 = 18.
        // WP-8.16: DashboardScreen.kt 4 -> 0 (the editor save and the 3 in-card subtask writes now
        // stamp from the shared HLC in MainViewModel): 18 - 4 = 14.
        // WP-8.20 (tightened by 8.I at the W7 close): AnalyticsScreen.kt 1 -> 0 (the VM reads
        // BootGate.clock()): 14 - 1 = 13.
        assertEquals(0, entries.getValue("app/src/main/java/com/example/ui/DashboardScreen.kt"))
        assertEquals(0, entries.getValue("app/src/main/java/com/example/ui/analytics/AnalyticsScreen.kt"))
        // WP-8.23: the 7 entity files (10) and the 3 DAOs (3) -> 0: 13 - 13 = 0. The baseline is
        // closed: any entry above 0 fails here, and any new site in a baseline file fails the rule test.
        val nonZero = entries.filterValues { it != 0 }
        assertTrue("expected every baseline entry to be 0 after WP-8.23, got $nonZero", nonZero.isEmpty())
        assertEquals("expected the 19-file baseline to sum to 0", 0, entries.values.sum())
    }
}
