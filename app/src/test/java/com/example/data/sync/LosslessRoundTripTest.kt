package com.example.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Stage 3 / T3.6 — THE STAGE GATE (plans/stages/stage_3_data_loss_elimination.md):
 * "Lossless round-trip test: `golden_backup.json` (containing reminders, prefs, an.views, unknown
 * decoy field `__futureField`) -> Kotlin import -> export -> deep-equal against original
 * detokenized form."
 *
 * This is a genuinely different check from `GoldenFixtureTest`'s (b)/(c)/(d): those compare
 * either self-consistency (b), a hash pre-image string (c), or a literal TOKENIZED-envelope byte
 * string (d, still `@Ignore`d -- see its class doc for why that's architecturally impossible).
 * This test instead compares the DETOKENIZED, human-shaped data (the "S + events" shape both
 * apps actually reason about) key-order-insensitively, so it can (and does) survive things like
 * FM code reassignment or fresh `_backup` timestamp stamps while still catching every genuine data
 * field that fails to round-trip -- which is what "lossless" as the stage's acceptance criterion
 * ("No merge path can emit a task/snapshot missing fields the input had") actually means.
 *
 * Every detokenization step below is a call to a production `SerializationEngine` function
 * (`importBackup` itself, `detokenizeDeep`, `detokenizeEvents`) -- there is no test-local mirror of
 * the tokenization/detokenization logic anywhere in this file.
 *
 * Array-element order: array order IS checked (index-for-index), objects' KEY order is not (a
 * [JsonObject] deep-equal by key set + recursive value equality, not by iteration order) --
 * per the task's own "key-order-insensitive, array-order-SENSITIVE" instruction. T3.5's
 * `orderIndex` mechanism (opus-review finding #3, array element order) is NOT re-exercised by this
 * particular test path: `SerializationEngine.importBackup`/`exportBackup` are called directly here,
 * with no `SyncManager`/Room/DAO involved at all, so `tasks`/`rewards`/`tags` array order is
 * preserved trivially by `List.map`'s 1:1 index correspondence to the original JSON array -- this
 * test path never touches `TaskDao`/`RewardDao`/`TagDao`'s `ORDER BY orderIndex` query in the first
 * place, so there is nothing for `orderIndex` to get wrong here. (The Room-backed path --
 * `SyncManager.importSnapshotFromFile`/`exportSnapshotToFile` -- is where `orderIndex` actually
 * matters, and is exercised by `StateSnapshotFieldAlignmentPersistenceTest`, per the T3.5 stage log
 * entry; this test does not duplicate that Robolectric coverage.)
 */
class LosslessRoundTripTest {

    private fun locateFixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        var attempts = 0
        while (dir != null && attempts < 8) {
            val candidate = File(dir, "fixtures/$name")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
            attempts++
        }
        throw IllegalStateException(
            "Could not locate fixtures/$name by walking up from ${File(".").absoluteFile}."
        )
    }

    // -----------------------------------------------------------------------------------------
    // Deep-equal: key-order-insensitive for JsonObject, index-order-SENSITIVE for JsonArray.
    // Collects EVERY diverging path in one pass (not just the first) so a real regression's
    // failure message is fully diagnosable from a single run.
    // -----------------------------------------------------------------------------------------

    private fun collectDiffs(path: String, expected: JsonElement, actual: JsonElement, out: MutableList<String>) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                val keys = LinkedHashSet<String>().apply { addAll(expected.keys); addAll(actual.keys) }
                for (k in keys) {
                    val p = if (path.isEmpty()) k else "$path.$k"
                    val e = expected[k]
                    val a = actual[k]

                    // SPECIAL CASE: synthetic is optional in re-export, missing means false.
                    if (k == "synthetic") {
                        val eVal = (e as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false
                        val aVal = (a as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.intOrNull == 1) } ?: false
                        if (eVal == aVal) continue
                    }

                    when {
                        e == null -> out += "$p: present only in ACTUAL (re-export gained an unexpected key), value=$a"
                        a == null -> out += "$p: MISSING from actual (data lost on round-trip); expected=$e"
                        else -> collectDiffs(p, e, a, out)
                    }
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    out += "$path: array length differs (expected ${expected.size}, actual ${actual.size})"
                }
                for (i in 0 until minOf(expected.size, actual.size)) {
                    collectDiffs("$path[$i]", expected[i], actual[i], out)
                }
            }
            expected is JsonPrimitive && actual is JsonPrimitive -> {
                if (expected.content != actual.content) {
                    out += "$path: expected=${expected.content} actual=${actual.content}"
                }
            }
            else -> out += "$path: type mismatch (expected=$expected, actual=$actual)"
        }
    }

    private fun assertDeepEqual(label: String, expected: JsonElement, actual: JsonElement) {
        val diffs = mutableListOf<String>()
        collectDiffs("", expected, actual, diffs)
        if (diffs.isNotEmpty()) {
            fail("$label: ${diffs.size} diverging JSON path(s):\n" + diffs.joinToString("\n"))
        }
    }

    /** Detokenizes a parsed schema-2 envelope's `S` using ITS OWN `FM` map, via the production
     * [SerializationEngine.detokenizeDeep] -- used for both the original fixture parse and a fresh
     * Kotlin re-export parse, since each carries its own independently-derived FM. */
    private fun detokenizeSnapshot(parsed: JsonObject): JsonObject {
        val fm = (parsed["FM"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
        val rmap = fm.entries.associate { it.value to it.key }
        val snap = parsed["S"] as? JsonObject ?: buildJsonObject {}
        return SerializationEngine.detokenizeDeep(snap, rmap) as JsonObject
    }

    private data class RoundTripResult(
        val originalBackupMeta: JsonObject,
        val reExportedBackupMeta: JsonObject,
        val originalDetokSnap: JsonObject,
        val reExportedDetokSnap: JsonObject,
        val originalDetokEvents: List<JsonObject>,
        val reExportedDetokEvents: List<JsonObject>
    )

    private fun runRoundTrip(fixtureName: String): RoundTripResult {
        val originalText = locateFixture(fixtureName).readText(Charsets.UTF_8)
        val originalParsed = Json.parseToJsonElement(originalText) as JsonObject

        // Production import (detokenize + hash-verify + project to entities).
        val imported = SerializationEngine.importBackup(originalText)

        // Production export, fed the FULL API surface T3.1/T3.2 added -- this is deliberately NOT
        // the minimal call shape `GoldenFixtureTest`'s pre-T3.6 (b)/(d) tests used (which omitted
        // `views`/`metrics`/`snapshotExtras` entirely and therefore could never have round-tripped
        // `an.views`, `prefs`, or `__futureField` regardless of any builder fix -- see this
        // fixture's own decoys). `snapshotExtras = imported.rawSnapshot` is exactly the "verbatim
        // copy of the last-known original top-level backup S object" the real
        // `SyncManager.exportSnapshotToFile` call site passes on every real round trip.
        val reExportedText = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = imported.character,
            tasks = imported.tasks,
            rewards = imported.rewards,
            events = imported.events,
            deletions = imported.deletions,
            lastCron = imported.lastCron,
            tags = imported.tags,
            devices = imported.devices,
            charHistory = imported.charHistory,
            monthlyBackups = imported.monthlyBackups,
            globalHistory = imported.globalHistory,
            views = imported.views,
            metrics = imported.metrics,
            snapshotExtras = imported.rawSnapshot
        )
        val reExportedParsed = Json.parseToJsonElement(reExportedText) as JsonObject

        // "Original detokenized form": `imported.rawSnapshot` IS `detokenizeSnapshot(originalParsed)`
        // -- `importBackup` computes it via the exact same production `detokenizeDeep` call and
        // stores it verbatim as `BackupData.rawSnapshot` (see SerializationEngine.kt). Reusing it
        // here (rather than recomputing) is itself "detokenize via production helpers", not a
        // shortcut around it.
        val originalDetokSnap = imported.rawSnapshot
        val originalDetokEvents = SerializationEngine.detokenizeEvents(originalParsed)

        val reExportedDetokSnap = detokenizeSnapshot(reExportedParsed)
        val reExportedDetokEvents = SerializationEngine.detokenizeEvents(reExportedParsed)

        return RoundTripResult(
            originalBackupMeta = originalParsed["_backup"] as JsonObject,
            reExportedBackupMeta = reExportedParsed["_backup"] as JsonObject,
            originalDetokSnap = originalDetokSnap,
            reExportedDetokSnap = reExportedDetokSnap,
            originalDetokEvents = originalDetokEvents,
            reExportedDetokEvents = reExportedDetokEvents
        )
    }

    /**
     * `_backup` keys excluded from the equality check -- and ONLY these, per the task's "Exclusions
     * ZERO except freshly-stamped _backup keys (exportedAt, appVersion, lastActivityAt)" (the
     * latter lives at `_backup.items.lastActivityAt`, handled separately below since `items` itself
     * is NOT excluded -- its `tasks`/`rewards`/`tags`/`views` COUNTS are real data and must match).
     *
     * `hash` is ALSO excluded here, but as a considered, documented exception, not a fourth
     * "freshly stamped field" of the same kind: `hash` is a pure SHA-256 function of `exportedAt` +
     * `appVersion` + `schema`/`eventCount`/`items` (`CanonicalJson.buildHashPreimage`'s
     * `hashlessBackupMeta` argument) -- once `exportedAt`/`appVersion` are (correctly, deliberately)
     * excluded as fresh stamps, `hash` MUST differ too as a mechanical consequence, not as an
     * independent locus of data loss. Its own correctness is a DIFFERENT, already-covered property
     * ("does the re-export's hash match what THIS pre-image actually hashes to?") verified by
     * `GoldenFixtureTest.goldenBackup_reExportSelfConsistentAndPwaVerifiable`, not by comparing it
     * to the ORIGINAL file's hash string.
     */
    private val EXCLUDED_BACKUP_KEYS = setOf("exportedAt", "appVersion", "hash")

    private fun assertBackupMetaEqualModuloFreshStamps(original: JsonObject, reExported: JsonObject) {
        val diffs = mutableListOf<String>()
        val keys = LinkedHashSet<String>().apply { addAll(original.keys); addAll(reExported.keys) }
        for (k in keys) {
            if (k in EXCLUDED_BACKUP_KEYS) continue
            val e = original[k]
            val a = reExported[k]
            // WP-8.3 (PX-27): Opti 7d5d4f3 writes `hashAlgo` before hashing (app.js:7444); the
            // golden file predates it. A re-export must add exactly "sha256".
            if (k == "hashAlgo" && e == null) {
                if (a != kotlinx.serialization.json.JsonPrimitive("sha256")) diffs += "_backup.hashAlgo: expected \"sha256\", got $a"
                continue
            }
            if (e == null) { diffs += "_backup.$k: present only in re-export, value=$a"; continue }
            if (a == null) { diffs += "_backup.$k: missing from re-export; expected=$e"; continue }
            if (k == "items" && e is JsonObject && a is JsonObject) {
                // `items.lastActivityAt` is ALSO a freshly-stamped ISO string (Date() at export
                // time) -- every OTHER items.* field (tasks/rewards/tags/views COUNTS) is real data
                // and must match exactly.
                val itemKeys = LinkedHashSet<String>().apply { addAll(e.keys); addAll(a.keys) }
                for (ik in itemKeys) {
                    if (ik == "lastActivityAt") continue
                    val ie = e[ik]
                    val ia = a[ik]
                    if (ie == null) { diffs += "_backup.items.$ik: present only in re-export, value=$ia"; continue }
                    if (ia == null) { diffs += "_backup.items.$ik: missing from re-export; expected=$ie"; continue }
                    collectDiffs("_backup.items.$ik", ie, ia, diffs)
                }
            } else {
                collectDiffs("_backup.$k", e, a, diffs)
            }
        }
        if (diffs.isNotEmpty()) {
            fail("_backup mismatch beyond freshly-stamped fields: ${diffs.size} diverging path(s):\n" + diffs.joinToString("\n"))
        }
    }

    // ===========================================================================================
    // golden_backup.json -- the stage-gate fixture itself (reminders, prefs, an.views, and the
    // three T3.6 decoys: top-level `__futureField`, a task-level `__taskDecoy`, a checklist-item-
    // level `__subDecoy` -- see tools/gen_golden_fixtures.mjs's `rawState` literal).
    //
    // Expected result (static prediction; no JVM available in this environment -- see Stage 3 Log
    // T3.6 entry for the full reasoning): PASS, after this task's builder fixes (opus-review
    // finding #2's task/char conditional-emission fix, plus the additionally-discovered
    // task/reward/view `createdAt`/`metricId` conditional-emission fix and the event
    // type-mismatch-fallback fix -- see SerializationEngine.kt's `buildCharJson` class doc and the
    // Stage 3 Log's per-field table). Every decoy is carried by the already-existing T3.1/T3.2
    // extraJson/snapshotExtras passthrough; nothing about the decoys themselves required a NEW
    // mechanism, only feeding `exportBackup` the params that carry it (see `runRoundTrip`'s doc).
    // ===========================================================================================

    @Test
    fun goldenBackup_losslessRoundTrip_snapshotDeepEqual() {
        val r = runRoundTrip("golden_backup.json")
        assertDeepEqual("golden_backup.json snapshot (S)", r.originalDetokSnap, r.reExportedDetokSnap)
    }

    @Test
    fun goldenBackup_losslessRoundTrip_eventsDeepEqual() {
        val r = runRoundTrip("golden_backup.json")
        assertEquals("event count must round-trip", r.originalDetokEvents.size, r.reExportedDetokEvents.size)
        val diffs = mutableListOf<String>()
        for (i in r.originalDetokEvents.indices) {
            val actual = r.reExportedDetokEvents.getOrNull(i) ?: buildJsonObject {}
            collectDiffs("golden_backup.json events[$i]", r.originalDetokEvents[i], actual, diffs)
        }
        if (diffs.isNotEmpty()) {
            fail("golden_backup.json events: ${diffs.size} diverging JSON path(s):\n" + diffs.joinToString("\n"))
        }
    }

    @Test
    fun goldenBackup_losslessRoundTrip_backupMetaEqualModuloFreshStamps() {
        val r = runRoundTrip("golden_backup.json")
        assertBackupMetaEqualModuloFreshStamps(r.originalBackupMeta, r.reExportedBackupMeta)
    }

    /**
     * Belt-and-suspenders on top of the whole-snapshot deep-equal above: explicitly names the
     * THREE decoys T3.6 specifies as the stage-gate scenario, in the exact shape seeded by
     * `tools/gen_golden_fixtures.mjs`'s `rawState` literal, so a future refactor that broke JUST
     * the decoy passthrough (while every other field happened to still balance out) would fail
     * here with an obvious, decoy-specific message even if it somehow slipped past the generic
     * deep-equal above.
     */
    @Test
    fun goldenBackup_decoyFieldsSurviveVerbatim() {
        val r = runRoundTrip("golden_backup.json")
        val snap = r.reExportedDetokSnap

        val futureField = snap["__futureField"] as? JsonObject
        assertNotNull("__futureField (top-level decoy) must survive the round trip", futureField)
        val nested = futureField!!["nested"] as? JsonArray
        assertNotNull("__futureField.nested must survive", nested)
        assertEquals(listOf("decoy", "1", "true"), nested!!.map { (it as JsonPrimitive).content })

        val tasks = (snap["tasks"] as JsonArray).map { it as JsonObject }
        val habitTask = tasks.first { (it["title"] as JsonPrimitive).content == "Drink Water" }
        assertEquals(
            "__taskDecoy (task-level decoy) must survive",
            "keep-me",
            (habitTask["__taskDecoy"] as JsonPrimitive).content
        )

        val todoTask = tasks.first { (it["title"] as JsonPrimitive).content == "Buy groceries" }
        val milkItem = (todoTask["checklist"] as JsonArray).map { it as JsonObject }
            .first { (it["text"] as JsonPrimitive).content == "Milk" }
        assertEquals(
            "__subDecoy (checklist-item-level decoy) must survive",
            "keep-me-too",
            (milkItem["__subDecoy"] as JsonPrimitive).content
        )
    }

    // ===========================================================================================
    // real_backup.json -- T1.5's real-world capture (127 tasks, 4994 events, legacy pre-HLC
    // entries with no `createdAt`, `an.views`/`an.metrics` with legacy shapes missing keys, a
    // large embedded `char.faceImg` data-URI, extra top-level fields (`habiticaHistory`,
    // `_joinedFrom`, `_joinedAt`, `_joinConflicts`) this app has never seen before, etc).
    //
    // Honest expected outcome (this environment has no JVM/Gradle toolchain -- nothing below has
    // actually been executed; see Stage 3 Log T3.6 entry for the full reasoning and the concrete,
    // Python-based spot-checks this prediction rests on):
    //  - The four opus-review-finding-#2 fields (task streak/history/cUp/cDown, char
    //    id/needsDeathAcknowledge/lastCron/dayStart) and the additionally-discovered
    //    task/reward/view `createdAt`/view `metricId` gaps were found BY spot-checking this exact
    //    file (see Log) and are believed fixed by this task's conditional-emission changes.
    //  - `an.metrics` is fully opaque passthrough (no Kotlin modeling at all -- see
    //    MetricsExtrasEntity's class doc) so its legacy `{id,name,keyword}`-only entries are
    //    expected to round-trip regardless of shape.
    //  - Numeric formatting for arbitrary-precision task `value` doubles (962 non-integer values
    //    found in this file) is NOT re-verified by this test -- it relies on Stage 2 / T2.2's
    //    already-completed, independently-run verification (`CanonicalJson.jsonNumber`'s class doc
    //    claims 0 mismatches across 11,108 literals from this exact file plus the golden fixture).
    //    If that verification's Android/ART assumption (documented as unverified in
    //    `CanonicalJson.kt`) does not hold in practice, THIS test is what will actually catch it,
    //    since it is not excluded here.
    //  - This file's sheer scale (127 tasks x arbitrary historical mutation shapes, 4994 events)
    //    was NOT exhaustively hand-verified field-by-field the way `golden_backup.json` was --
    //    residual divergences this task did not anticipate are plausible. Per the task's explicit
    //    instruction, this test does NOT silently exclude anything to force green: if such a
    //    divergence exists, this assertion is designed to FAIL and print every diverging path via
    //    [collectDiffs], not hide it.
    // ===========================================================================================

    @Test
    fun realBackup_losslessRoundTrip_snapshotDeepEqual() {
        val r = runRoundTrip("real_backup.json")
        assertDeepEqual("real_backup.json snapshot (S)", r.originalDetokSnap, r.reExportedDetokSnap)
    }

    @Test
    fun realBackup_losslessRoundTrip_eventsDeepEqual() {
        val r = runRoundTrip("real_backup.json")
        assertEquals("event count must round-trip", r.originalDetokEvents.size, r.reExportedDetokEvents.size)
        val diffs = mutableListOf<String>()
        for (i in r.originalDetokEvents.indices) {
            val actual = r.reExportedDetokEvents.getOrNull(i) ?: buildJsonObject {}
            collectDiffs("real_backup.json events[$i]", r.originalDetokEvents[i], actual, diffs)
        }
        if (diffs.isNotEmpty()) {
            fail("real_backup.json events: ${diffs.size} diverging JSON path(s):\n" + diffs.joinToString("\n"))
        }
    }

    /**
     * The strong version of the round-trip guarantee: the bytes this app re-exports are
     * accepted by the ORIGINAL PWA's canonical-hash implementation, not just by our own.
     *
     * Skipped unless Node and the PWA checkout are both present -- see [PwaToolchain]. Without
     * them `verify_with_pwa.mjs` exits non-zero, which used to fail this test on a clean clone
     * for a reason that has nothing to do with the serialisation under test. The pure-Kotlin
     * round-trip tests above still run everywhere.
     */
    @Test
    fun realBackup_reExport_hashVerifiesWithPwaTool() {
        val repoRoot = locateFixture("real_backup.json").parentFile?.parentFile
        assumeTrue(PwaToolchain.skipReason(repoRoot), PwaToolchain.isOnPath("node"))
        assumeTrue(PwaToolchain.skipReason(repoRoot), PwaToolchain.pinnedRevOrNull(repoRoot) != null)

        val originalText = locateFixture("real_backup.json").readText(Charsets.UTF_8)
        val imported = SerializationEngine.importBackup(originalText)
        val reExportedText = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = imported.character,
            tasks = imported.tasks,
            rewards = imported.rewards,
            events = imported.events,
            deletions = imported.deletions,
            lastCron = imported.lastCron,
            tags = imported.tags,
            devices = imported.devices,
            charHistory = imported.charHistory,
            monthlyBackups = imported.monthlyBackups,
            globalHistory = imported.globalHistory,
            views = imported.views,
            metrics = imported.metrics,
            snapshotExtras = imported.rawSnapshot
        )

        val tempFile = File.createTempFile("kotlin_export_", ".json")
        tempFile.writeText(reExportedText, Charsets.UTF_8)

        val pb = ProcessBuilder("node", "tools/verify_with_pwa.mjs", tempFile.absolutePath)
        if (repoRoot != null) pb.directory(repoRoot)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            fail("PWA hash-verifier failed (exit $exitCode):\nOutput: $output\nError: $error")
        }
        assertTrue("Output should contain 'hash OK': $output", output.contains("hash OK"))
        tempFile.delete()
    }
}
