package com.example.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Stage 1 / T1.4 — Kotlin fixture test scaffold against the synthetic,
 * hand-generated fixture set (`fixtures/golden_backup.json`,
 * `fixtures/golden_state.json`, `fixtures/golden_hash_preimage.txt`)
 * produced under T1.3. These are separate from `fixtures/real_backup.json`
 * (T1.5's live-data import smoke fixture, see `RealBackupImportSmokeTest`).
 *
 * ---------------------------------------------------------------------------
 * Stage 2 / T2.4 RE-SCOPE (binding architect decision, opus review — see
 * `plans/stages/stage_2_canonical_serialization.md` Log, entry "T2.4 opus
 * re-scope", 2026-07-18):
 *
 * T2.4's original brief was "un-`@Ignore` (b)(c); make green" where (b) was a
 * byte-compare of `exportBackup(importBackup(golden_backup.json))` against
 * the golden fixture text. The opus review of that plan established this is
 * architecturally impossible to make green in Stage 2: `importBackup`
 * destructures the tokenized envelope down to Room entities
 * (`CharacterEntity`, `TaskEntity`, ...), which is a genuinely lossy
 * projection — per-event numeric `id` has no modeled field, per-task-type
 * key order is not preserved (entities re-serialize in a fixed field order,
 * not the source mutation-history-dependent order), FM (field map) codes are
 * re-derived by frequency sort rather than preserved, and `exportedAt`/
 * `items.lastActivityAt` are freshly stamped, not replayed from the input.
 * None of that is fixable without a lossless entity round-trip, which is
 * explicitly Stage 3 scope (Room schema work), not Stage 2 (canonical
 * *serialization* — i.e. same logical state -> same bytes, not same bytes in
 * -> same bytes out through a lossy intermediate representation).
 *
 * Resulting re-scope, reflected in the four assertions below:
 *  - (a) unchanged: import golden_backup.json -> hash verifies.
 *  - (b) REDEFINED: import -> exportBackup -> the re-export's OWN hash must
 *    verify (self-consistent SHA-256 over its own canonical pre-image), AND
 *    the re-export is written to `fixtures/kotlin_export.json` so a
 *    deferred-toolchain session can run it through the PWA import verifier
 *    (`tools/verify_with_pwa.mjs`) for "hash OK" acceptance. Un-ignored.
 *  - (c) un-ignored, now calls the production `CanonicalJson.buildHashPreimage`
 *    API directly (instead of a test-local mirror) and byte-compares to
 *    `fixtures/golden_hash_preimage.txt`.
 *  - (d) NEW: the original byte-compare-vs-golden assertion, preserved
 *    verbatim so the original T1.4/T2.4 goal isn't lost, kept
 *    `@Ignore("Stage 3 — requires lossless entity round-trip")`.
 * ---------------------------------------------------------------------------
 *
 * Per T1.4's original spec: 3 assertions, (a) active and truthfully
 * documenting current pass/fail, (b) and (c) present but `@Ignore("Stage 2")`
 * so the build stays green while Stage 2's lossless round-trip / D1-D2
 * conformance work is pending (see `plans/sync_format_spec.md` §7). That
 * `@Ignore("Stage 2")` reason string is now obsolete per the re-scope above;
 * (b) and (c) are un-ignored and (d) carries the new Stage-3-deferred reason.
 */
class GoldenFixtureTest {

    /**
     * Locates `fixtures/<name>` by walking up from the current working
     * directory, matching the convention established in
     * `RealBackupImportSmokeTest` (Gradle's `Test` task cwd defaults to the
     * module directory, e.g. `app/`, while fixtures live at the repo root).
     */
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

    /**
     * Locates the `fixtures/` directory itself (as opposed to [locateFixture],
     * which requires the target file to already exist) by walking up until a
     * directory containing `fixtures/golden_backup.json` is found. Used by
     * assertion (b) to know where to write `fixtures/kotlin_export.json`,
     * which does not exist yet on a fresh checkout.
     */
    private fun fixturesDir(): File {
        var dir: File? = File(".").absoluteFile
        var attempts = 0
        while (dir != null && attempts < 8) {
            val candidate = File(dir, "fixtures")
            if (File(candidate, "golden_backup.json").exists()) return candidate
            dir = dir.parentFile
            attempts++
        }
        throw IllegalStateException(
            "Could not locate the fixtures/ directory (looked for fixtures/golden_backup.json) " +
                "by walking up from ${File(".").absoluteFile}."
        )
    }

    /**
     * (a) Import `golden_backup.json` -> hash verifies.
     *
     * Calls `SerializationEngine.importBackup()` — the same fallback-hash-
     * aware SHA-256 verification path exercised by
     * `RealBackupImportSmokeTest.realBackup_parsesDetokenizesAndHashVerifies`.
     * `golden_backup.json`'s `_backup.hash`
     * (`97c7f766e2d8ff6f41c695303d9eea6b99a403f1788999144e20e8acdcc42ec7`) is a
     * genuine 64-hex SHA-256 digest, not a `fallback-` sentinel, and was
     * confirmed outside the JVM (Python `hashlib.sha256`) while authoring
     * this test to equal `sha256(fixtures/golden_hash_preimage.txt bytes)`.
     *
     * Expected result: PASS. Per the reasoning already documented in
     * `RealBackupImportSmokeTest`'s class doc, `importBackup`'s hash-check
     * never re-derives values — it echoes the parsed `JsonElement`s back
     * verbatim (`detokenizeDeep` renames keys in place, preserving the
     * original parse-order `LinkedHashMap` iteration; `JsonPrimitive.content`
     * preserves the original numeric literal text), now routed through
     * `CanonicalJson.buildHashPreimage` (Stage 2 / T2.2) rather than
     * `Json.encodeToString`, which is what makes this genuinely verbatim
     * instead of silently reformatting numeric literals (see
     * `CanonicalJson.kt` class doc).
     *
     * IMPORTANT: this has not actually been executed under a JVM/Gradle run
     * — this sandbox has no `gradlew`/`gradle`/`kotlinc` and no network
     * access. This docblock states a static prediction based on reading the
     * source and diffing the fixture text by hand/script; it is not a
     * substitute for a real `./gradlew test --tests '*GoldenFixtureTest*'`
     * run, which the architect or a machine with the Android/Kotlin
     * toolchain should perform to get a real green/red signal.
     */
    @Test
    fun goldenBackup_importHashVerifies() {
        val fixture = locateFixture("golden_backup.json")
        val jsonString = fixture.readText(Charsets.UTF_8)

        val rawParsed = Json.parseToJsonElement(jsonString) as JsonObject
        val backupMeta = rawParsed["_backup"] as JsonObject
        assertEquals(2, backupMeta["schema"]?.jsonPrimitive?.intOrNull)
        val expectedHash = backupMeta["hash"]?.jsonPrimitive?.content
        assertNotNull("fixture must carry a _backup.hash", expectedHash)
        assertFalse(
            "golden_backup.json is expected to carry a genuine SHA-256 hash, not a fallback- hash",
            expectedHash!!.startsWith("fallback-")
        )
        assertEquals("hash must be a 64-hex-char SHA-256 digest", 64, expectedHash.length)

        val expectedTasks = backupMeta["items"]?.jsonObject?.get("tasks")?.jsonPrimitive?.intOrNull
        val expectedRewards = backupMeta["items"]?.jsonObject?.get("rewards")?.jsonPrimitive?.intOrNull
        val expectedTags = backupMeta["items"]?.jsonObject?.get("tags")?.jsonPrimitive?.intOrNull
        val expectedEventCount = backupMeta["eventCount"]?.jsonPrimitive?.intOrNull

        // Subject under test: throws IllegalArgumentException("...hash mismatch")
        // on a failed verification, so a bare successful return is the pass
        // condition for "hash verifies".
        val result = SerializationEngine.importBackup(jsonString)

        assertNotNull(result.character)
        assertEquals("Aria Stormblade", result.character.name)
        assertEquals("task count must match _backup.items.tasks", expectedTasks, result.tasks.size)
        assertEquals("reward count must match _backup.items.rewards", expectedRewards, result.rewards.size)
        assertEquals("tag count must match _backup.items.tags", expectedTags, result.tags.size)
        assertEquals("event count must match _backup.eventCount", expectedEventCount, result.events.size)
    }

    /**
     * (b) REDEFINED for Stage 2 (see class doc's "T2.4 RE-SCOPE" section for
     * the full opus-review rationale) — self-consistent re-export hash check,
     * plus writes `fixtures/kotlin_export.json` for the deferred PWA-verifier
     * leg.
     *
     * What this asserts, precisely:
     *  1. Import `golden_backup.json` (production `importBackup`).
     *  2. Feed the resulting `BackupData` into `exportBackup()` (production).
     *  3. The re-export is written to a temp file and must equal the committed
     *     `fixtures/kotlin_export.json` byte for byte (WP-8.0 follow-up: the test
     *     no longer rewrites the tracked file; the historic note below is kept).
     *     This is deliberate, not a side effect to clean up: T2.4's original
     *     brief required a "Kotlin export of the seeded state" for the
     *     reverse-direction PWA-verifier check, and this is the only
     *     equivalent this sandbox (no JVM) can produce — see point 5 below
     *     for why the actual verifier run is deferred rather than attempted
     *     here.
     *  4. `SerializationEngine.importBackup(reExported)` is called again, on
     *     the FRESH re-export text. `importBackup`'s hash-check (Stage 2 /
     *     T2.2: `CanonicalJson.buildHashPreimage` + `computeSha256`,
     *     verified against the file's own embedded `_backup.hash`) is
     *     production code, not a test-local mirror. A bare successful return
     *     (no `IllegalArgumentException("...hash mismatch")`) is the pass
     *     condition: it proves `exportBackup`'s own hash field is internally
     *     consistent with the canonical pre-image `exportBackup` itself
     *     builds from the very same data — i.e. Kotlin's export path is
     *     self-consistent, independent of whether it byte-matches the PWA's
     *     original golden output (which it does NOT, and is not expected to
     *     — see (d)).
     *  5. The PWA-verifier leg (`node tools/verify_with_pwa.mjs
     *     fixtures/kotlin_export.json` -> expect "hash OK" on stdout, exit
     *     0) is NOT run by this test. This sandbox has no JVM/Gradle/Kotlin
     *     toolchain, so this docblock's predictions (including this whole
     *     test) have never actually been executed here — there is no
     *     `fixtures/kotlin_export.json` to feed the verifier with in the
     *     first place until a real local run produces one. Per the Stage 2
     *     task instructions, this leg is explicitly deferred to that local
     *     toolchain session: run `./gradlew test --tests
     *     '*GoldenFixtureTest*'` locally (which will write
     *     `fixtures/kotlin_export.json` via step 3 above as a side effect of
     *     this very test), then separately run `node
     *     tools/verify_with_pwa.mjs fixtures/kotlin_export.json` and confirm
     *     it prints "hash OK" and exits 0. `tools/verify_with_pwa.mjs` was
     *     sanity-checked in this sandbox against `fixtures/golden_backup.json`
     *     and `fixtures/real_backup.json` (both print "hash OK", exit 0) and
     *     against a deliberately-tampered copy of `golden_backup.json`
     *     (correctly reports a hash mismatch with a diff summary and exits
     *     1) — see the Stage 2 log for those verbatim runs — but it has
     *     never been run against actual Kotlin-produced output, since none
     *     exists until step 3 runs under a real JVM.
     *
     * Expected local-run outcome: PASS for steps 1-4 (self-consistency is a
     * property of `exportBackup`+`importBackup` agreeing with each other,
     * which doesn't depend on JS/JVM `Double` formatting parity the way a
     * byte-compare against a PWA-produced fixture would) — this is a
     * genuine, currently-untested prediction, not a certainty, since no JVM
     * run has occurred. Step 5 (the actual "hash OK" from the Node verifier)
     * requires that same local run and is not automatically checked by this
     * JUnit test itself.
     */
    @Test
    fun goldenBackup_reExportSelfConsistentAndPwaVerifiable() {
        val fixture = locateFixture("golden_backup.json")
        val goldenText = fixture.readText(Charsets.UTF_8)

        val imported = SerializationEngine.importBackup(goldenText)

        // Stage 3 / T3.6: feed the FULL exportBackup API surface T3.1/T3.2 added
        // (views/metrics/snapshotExtras) -- omitting them (as this call did pre-T3.6) silently
        // drops `an.views`, `prefs`, and any top-level decoy (`__futureField`) from the re-export
        // regardless of any builder fix, which would make `fixtures/kotlin_export.json` a
        // misleading artifact for the deferred PWA-verifier leg this test documents in point 3/5
        // below. `snapshotExtras = imported.rawSnapshot` matches the real
        // `SyncManager.exportSnapshotToFile` call shape.
        val reExported = SerializationEngine.exportBackup(
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

        // WP-8.0 follow-up: the re-export goes to a temp file and is compared with the COMMITTED
        // fixtures/kotlin_export.json (the file `node tools/verify_with_pwa.mjs
        // fixtures/kotlin_export.json` reads), so a full test run never rewrites a tracked file.
        // To refresh it on purpose, copy the temp file named in the failure message over it.
        val tempExport = File.createTempFile("kotlin_export_", ".json")
        tempExport.writeText(reExported, Charsets.UTF_8)
        val committed = File(fixturesDir(), "kotlin_export.json")
        assertEquals(
            "re-export differs from committed ${committed.path}; new bytes are in ${tempExport.path}",
            committed.readText(Charsets.UTF_8),
            tempExport.readText(Charsets.UTF_8)
        )
        tempExport.delete()

        // Self-consistency: re-importing the fresh re-export must pass its
        // own hash check (production `importBackup`, not a mirror -- see
        // docblock point 4). A thrown IllegalArgumentException means fail.
        val reImported = SerializationEngine.importBackup(reExported)

        // Not a vacuous check: confirms the round-trip actually carried real
        // data through (rather than e.g. both sides being empty).
        assertEquals(
            "re-imported character name should match the originally-imported name",
            imported.character.name,
            reImported.character.name
        )
        assertEquals(imported.tasks.size, reImported.tasks.size)
        assertEquals(imported.rewards.size, reImported.rewards.size)
        assertEquals(imported.events.size, reImported.events.size)
    }

    /**
     * (c) Hash pre-image string equality — now calling production code
     * directly.
     *
     * Stage 1's version of this assertion had to mirror `importBackup`'s
     * private pre-image-reconstitution step inline (there was no standalone,
     * directly-callable API for it). Stage 2 / T2.2 closed that gap:
     * `CanonicalJson.buildHashPreimage(snapshotWithoutEvents, events,
     * hashlessBackupMeta)` is now a public, production-used function (both
     * `importBackup` and `exportBackup` call it — see `SerializationEngine.kt`
     * lines around the "Routed through CanonicalJson" comments). This
     * assertion now calls that directly instead of duplicating its logic,
     * closing the "test partially exercises its own mirrored reconstruction"
     * limitation the Stage 1 docblock flagged. `detokenizeEvents` and
     * `detokenizeDeep` (used to build this assertion's inputs) are likewise
     * production `SerializationEngine` functions, not test-local copies.
     *
     * Expected result: PASS, by the same reasoning as (a) above — since
     * `golden_hash_preimage.txt` is confirmed (via Python `hashlib.sha256`
     * during authoring) to be exactly the string that hashes to
     * `golden_backup.json`'s declared `_backup.hash`, and
     * `CanonicalJson.buildHashPreimage` is the same key-order-preserving /
     * literal-preserving construction `importBackup()` uses internally, the
     * two should match byte-for-byte. As with (a), this has NOT been
     * executed under a real JVM/Gradle run in this sandbox (no toolchain, no
     * network); it is a static prediction. Un-ignored per the T2.4 re-scope
     * (see class doc) now that (1) `CanonicalJson.buildHashPreimage` exists
     * as the production API this docblock previously asked for, and (2) the
     * old `Json.encodeToString`-based test body (which the original Stage 1
     * docblock assumed would "pass without needing any source changes") is
     * gone -- that assumption was independently found false (opus review
     * finding #6, see `CanonicalJson.kt` class doc point 2): the old test
     * body would actually have FAILED had it ever been run, since
     * `Json.encodeToString` re-parses and re-renders numeric literals
     * through Kotlin's non-JS-compatible `Double` formatting rather than
     * echoing them verbatim.
     */
    @Test
    fun goldenBackup_hashPreimageStringMatchesGolden() {
        val goldenBackupText = locateFixture("golden_backup.json").readText(Charsets.UTF_8)
        val expectedPreimage = locateFixture("golden_hash_preimage.txt").readText(Charsets.UTF_8)

        val parsed = Json.parseToJsonElement(goldenBackupText) as JsonObject
        val backupMeta = parsed["_backup"] as JsonObject

        // Production detokenization (SerializationEngine), not a test mirror.
        val detokEvents = SerializationEngine.detokenizeEvents(parsed)
        val fm = (parsed["FM"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        val rmap = fm.entries.associate { it.value to it.key }
        val snap = parsed["S"] as JsonObject
        val detokSnap = SerializationEngine.detokenizeDeep(snap, rmap) as JsonObject

        val hashlessBackupMeta = buildJsonObject {
            backupMeta.forEach { (k, v) ->
                if (k != "hash") put(k, v)
            }
        }

        // Production canonical pre-image builder (CanonicalJson, Stage 2 / T2.2),
        // not Json.encodeToString and not a test-local reconstruction.
        val actualPreimage = CanonicalJson.buildHashPreimage(detokSnap, detokEvents, hashlessBackupMeta)

        assertEquals(expectedPreimage, actualPreimage)

        // Belt-and-suspenders: also confirm the *stored* hash matches what
        // hashing this reconstituted pre-image produces, tying (a) and (c)
        // together.
        val expectedHash = backupMeta["hash"]?.jsonPrimitive?.content
        assertEquals(expectedHash, SerializationEngine.computeSha256(actualPreimage))
    }

    /**
     * (d) NEW (Stage 2 / T2.4 re-scope — see class doc) — the ORIGINAL
     * byte-compare-of-reimported-PWA-data assertion, preserved verbatim from
     * Stage 1's (b) so the original goal isn't lost, just deferred.
     *
     * Re-export -> byte-compare against golden.
     *
     * There is no single "re-export exactly what was imported" call; the
     * closest available API is `SerializationEngine.exportBackup()`, which
     * takes the live Room-entity shape (`CharacterEntity`,
     * `List<TaskEntity>`, ...) — the same shape `importBackup()` just handed
     * back as `BackupData`. So this test round-trips: import
     * `golden_backup.json`, feed the resulting `BackupData` straight into
     * `exportBackup()`, and byte-compares the result against the original
     * fixture text.
     *
     * Expected result: FAIL — and per the opus review that drove the T2.4
     * re-scope (see class doc), this is now understood to be an
     * ARCHITECTURAL property of Stage 2, not a bug Stage 2 can fix. Known
     * causes, per `plans/sync_format_spec.md` §7 plus the opus review:
     *  - D1 (key order): `exportBackup`'s field map (`FM`) is built by
     *    descending key-frequency (`buildFieldMap` + `sortedByDescending`),
     *    not the fixture's original PWA field-assignment order, so tokenized
     *    key codes will differ even for identical field sets.
     *  - D2 (number formatting): Stage 2 / T2.2's `CanonicalJson.jsonNumber`
     *    fixes the *hash pre-image*'s number formatting (see (b)/(c)), but
     *    that doesn't make the full tokenized envelope byte-identical to the
     *    golden fixture, since D1 and the lossy-projection issues below are
     *    independent of number formatting.
     *  - Lossy Room-entity projection (the opus review's core finding): the
     *    fixture's per-event numeric `"id"` field (e.g. `"id":1`) is not
     *    modeled by `EventLogEntity` at all and is silently dropped on
     *    import, so it cannot round-trip back out; per-task-type key order
     *    is mutation-history-dependent in the PWA and not replayable from a
     *    fixed Room entity field order; `exportedAt`/`items.lastActivityAt`
     *    are stamped with the real current time (`Date()`), not the
     *    fixture's fixed 2026-07-17 timestamps; `appVersion` defaults to
     *    `"v1.0-android"` rather than the fixture's `"v2026.07.16-1611"`.
     *    None of these are fixable without Stage 3's lossless entity
     *    round-trip work (modeling event `id`, preserving source key order
     *    through the Room layer, fixed-clock injection for tests, etc).
     *
     * ---------------------------------------------------------------------------
     * Stage 3 / T3.6 RE-VISIT (this task, 2026-07-18): the "lossy Room-entity
     * projection" causes listed above are now BELIEVED FIXED (event `id` and
     * every other unmodeled field round-trip via T3.1's per-record `extraJson`
     * overlay; per-task-type key order replays the captured original via the
     * same overlay when one is known; `views`/`metrics`/`snapshotExtras` are
     * now actually passed into `exportBackup` below, unlike the pre-T3.6
     * version of this exact call). `LosslessRoundTripTest` (new this task) is
     * the test that actually exercises and confirms this at the DETOKENIZED
     * (key-order-insensitive) level — see that file for the full per-field
     * accounting. This assertion, however, is a literal TOKENIZED-envelope
     * BYTE string compare, which remains genuinely, architecturally blocked by
     * two SEPARATE things no amount of entity-round-trip-fidelity work can
     * close:
     *  1. `exportedAt`/`appVersion`/`_backup.items.lastActivityAt` (and, as a
     *     mechanical consequence, `_backup.hash`, which is a function of
     *     those) are freshly stamped with the real current wall-clock time on
     *     every `exportBackup` call — there is no fixed-clock injection point
     *     in production code, only in the golden-fixture GENERATOR (a
     *     deliberately test-only `FixedDate` shim, see
     *     `tools/gen_golden_fixtures.mjs`). A byte compare against a fixture
     *     frozen at 2026-07-17T12:00:00Z can never match live-clock output.
     *  2. `FM`'s key→code assignment is by descending FREQUENCY with ties
     *     broken by first-encounter order in the recursive walk (spec §2:
     *     "ties: insertion order, engine-dependent — harmless, FM travels
     *     with file"). Even with identical field CONTENT (which the
     *     round-trip fixes above should now achieve), Kotlin's
     *     `buildFieldMap`'s walk order over its OWN freshly-`buildJsonObject`-
     *     constructed tree is not guaranteed to visit keys in the same
     *     relative order JS's original `for...in` walk over the PWA's own
     *     live object did, whenever two keys tie on frequency — nothing in
     *     this task's scope (or T3.1–T3.5's) makes that engine-dependent tie
     *     order match another independent implementation's tie order.
     * Both are orthogonal to "does the DATA round-trip losslessly" (the
     * stage's actual acceptance bar) — they are wire-encoding/bookkeeping
     * artifacts, not data loss. Per this task's explicit instruction ("if
     * byte-parity still blocked ... KEEP ignored but change reason string to
     * name the exact remaining blockers"), this assertion STAYS
     * `@Ignore`d, with the reason string below now naming these two blockers
     * specifically instead of the now-largely-superseded pre-T3.6 reasoning.
     * Stage 5 acceptance should use `LosslessRoundTripTest`'s deep-equal
     * assertions (detokenized, key-order-insensitive, array-order-sensitive)
     * plus `tools/verify_with_pwa.mjs`'s hash-OK check as the actual
     * round-trip acceptance criteria — NOT this byte-literal comparison,
     * which cannot pass for the two structural reasons above regardless of
     * how complete the entity-level round-trip becomes.
     * ---------------------------------------------------------------------------
     * P3' / KT-52 (2026-09-25): the @Ignore reason above was stale -- the export clock is
     * injected (`nowPhysical`, WP-8.5 D18) and the FM tie-break was ported with JS key order
     * (WP-8.3). The test now feeds the golden's own `_backup.exportedAt` and `_backup.appVersion`
     * and, on a mismatch, names every differing JSON path ([jsonDiffPaths]).
     * Runner result at f53935a (runs/P3.md): 7 differing paths, listed in the @Ignore reason.
     * None is a DEC-4 / KT-36 stamp. Opti refs: 7d5d4f3:app.js:7449 (hashAlgo written),
     * :2480 (habitTap literal order reps, value), :7053-7070 (_tokenizeEvents keeps record
     * key order; sy written only when the record has `synthetic`).
     */
    @Test
    @Ignore(
        "KT-52: byte re-export differs from fixtures/golden_backup.json at 7 JSON paths. " +
            "(1) _backup.hashAlgo extra \"sha256\" = STALE GOLDEN (Opti app.js:7449 now writes hashAlgo; golden predates it). " +
            "(2) _backup.hash = follows from (1) and (6) (hashAlgo is inside the hashed bytes), not a bug of its own. " +
            "(3) E[0] key order, id moved to end = STALE GOLDEN (golden harness put id first; real Opti IndexedDB injects the autoIncrement id key after the record fields). " +
            "(4) E[1] key order, id moved to end = STALE GOLDEN (same as 3). " +
            "(5) E[2] key order, id moved to end = STALE GOLDEN (same as 3). " +
            "(6) E[2].sy missing in re-export = KOTLIN-SIDE BUG (synthetic:false is dropped on import/export; Opti keeps the key, app.js:7069). " +
            "(7) E[3] key order, id moved to end + rs/v swapped = STALE GOLDEN (Opti habitTap writes reps before value, app.js:2480). " +
            "No path is a KT-36 stamp [DEFERRED_OPEN_QUESTION]. Un-ignore after regenerating the golden and fixing (6)."
    )
    fun goldenBackup_reExportByteMatchesGolden() {
        val fixture = locateFixture("golden_backup.json")
        val goldenText = fixture.readText(Charsets.UTF_8)

        val imported = SerializationEngine.importBackup(goldenText)
        val goldenMeta = Json.parseToJsonElement(goldenText).jsonObject.getValue("_backup").jsonObject

        val reExported = SerializationEngine.exportBackup(
            // KT-52: the golden's own stamps (fixed clock, WP-8.5 D18).
            nowPhysical = java.time.Instant.parse(goldenMeta.getValue("exportedAt").jsonPrimitive.content).toEpochMilli(),
            appVersion = goldenMeta.getValue("appVersion").jsonPrimitive.content,
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

        if (reExported != goldenText) {
            val paths = jsonDiffPaths(
                Json.parseToJsonElement(goldenText),
                Json.parseToJsonElement(reExported),
                "\$"
            )
            fail(
                "re-export differs from golden_backup.json at ${paths.size} JSON path(s)" +
                    (if (paths.isEmpty()) " (trees equal: formatting/whitespace only)" else "") +
                    ":" + NL + paths.joinToString(NL)
            )
        }
    }

    private val NL = Char(10).toString()

    /** KT-52 diagnostics: every JSON path where [a] (golden) and [b] (re-export) differ, key order included. */
    private fun jsonDiffPaths(a: JsonElement, b: JsonElement, path: String): List<String> {
        val out = mutableListOf<String>()
        when {
            a is JsonObject && b is JsonObject -> {
                for (k in a.keys) if (k !in b) out += "$path.$k: missing in re-export"
                for (k in b.keys) if (k !in a) out += "$path.$k: extra in re-export = ${b.getValue(k)}"
                val common = a.keys.filter { it in b }
                if (common != b.keys.filter { it in a }) out += "$path: key order golden=$common re-export=${b.keys.filter { it in a }}"
                for (k in common) out += jsonDiffPaths(a.getValue(k), b.getValue(k), "$path.$k")
            }
            a is JsonArray && b is JsonArray -> {
                if (a.size != b.size) out += "$path: array size golden=${a.size} re-export=${b.size}"
                for (i in 0 until minOf(a.size, b.size)) out += jsonDiffPaths(a[i], b[i], "$path[$i]")
            }
            a != b -> out += "$path: golden=$a re-export=$b"
        }
        return out
    }
}
