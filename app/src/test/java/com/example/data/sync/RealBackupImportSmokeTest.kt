package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 1 / T1.5 — import-smoke test for the real, live PWA export
 * (`fixtures/real_backup.json`, byte-identical copy of
 * `questa-backup-20260717-2243.json` at the project root, provided by Pali).
 *
 * This is the primary golden fixture for Stage 3's lossless round-trip test.
 * It contains Pali's live data — do not commit it to any public remote.
 *
 * What this test documents (truthfully, per current code as of Stage 1):
 *  - The fixture parses as a schema-2 envelope.
 *  - Detokenization (field-map + event-array reverse mapping) succeeds and
 *    reproduces the expected item counts recorded in the file's own
 *    `_backup.items` metadata.
 *  - Hash verification currently PASSES for this real file: the file's
 *    `_backup.hash` is a genuine 64-hex-char SHA-256 (not `fallback-`
 *    prefixed), and Kotlin's `importBackup` reconstructs the detokenized
 *    backup (S-keys in original parse order + events + hashless _backup)
 *    and recomputes a matching SHA-256.
 *
 *    This is NOT a contradiction of the D1/D2 conformance deltas tracked in
 *    `plans/sync_format_spec.md` §7: D1 (key-order) and D2 (number
 *    formatting) describe divergence in Kotlin's OWN *export* path (building
 *    a fresh snapshot from Room entities via `buildJsonObject`, which uses
 *    Kotlin's own field iteration order and native `Double` formatting).
 *    The *import* hash-check exercised here never re-derives values: it
 *    echoes back the exact `JsonElement`s produced by parsing this file
 *    (verbatim key order via `detokenizeDeep`'s in-place renaming, verbatim
 *    numeric literals via kotlinx.serialization's `JsonPrimitive.content`),
 *    so D1/D2 do not manifest on this codepath. If a future change alters
 *    `detokenizeDeep`/`detokenizeEvents` to rebuild rather than rename, or
 *    changes the `Json` instance used at the hash-check call site, this test
 *    should be revisited.
 *
 * Second test method documents the fallback-hash policy (WORK_TRACKER.md
 * Resolved decisions, 2026-07-17): hashes matching `^fallback-` must be
 * accepted WITHOUT verification. The real fixture does not itself contain a
 * fallback hash (see above), so that path is exercised with a small
 * synthetic envelope whose hash could not possibly be a valid SHA-256 match,
 * proving the fallback branch — not the SHA-256 branch — is what accepts it.
 *
 * WP-5.4 (Stage 4->5 carry-over, WORK_TRACKER.md line 55a) added a THIRD test method,
 * [realBackup_importSnapshotFromFile_persistsAllEventsToEventLogs], plus the Robolectric/in-memory
 * Room scaffolding it needs (per 00_README.md's §4 conventions -- SyncConflictRetryTest's
 * @Before/@After pattern). The two original tests above are untouched and still exercise only
 * SerializationEngine.importBackup directly (no Room, no Robolectric dependency of their own) --
 * adding @RunWith(RobolectricTestRunner::class)/@Config to the class is a superset capability
 * (enables Android framework classes) and does not change their behavior or assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealBackupImportSmokeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RpgRepository
    private lateinit var syncDataStore: SyncDataStore
    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncDataStore = SyncDataStore(context)
        repository = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(),
            db.tagDao(), db.deviceDao(), db.charHistoryDao(), db.tombstoneDao(),
            db.customViewDao(), db.snapshotExtrasDao(), db.metricsExtrasDao(),
            syncDataStore
        )
        syncManager = SyncManager(context, repository, syncDataStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Locates `fixtures/<name>` by walking up from the current working
     * directory. Gradle's `Test` task working directory defaults to the
     * module directory (e.g. `app/`), while the fixture lives at the
     * repository root (`fixtures/`), so a plain relative path is not
     * reliable across invocation contexts.
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
            "Could not locate fixtures/$name by walking up from ${File(".").absoluteFile}. " +
                "Expected it at the repository root (copied from questa-backup-20260717-2243.json)."
        )
    }

    @Test
    fun realBackup_parsesDetokenizesAndHashVerifies() {
        val fixture = locateFixture("real_backup.json")
        val jsonString = fixture.readText(Charsets.UTF_8)

        // Sanity on the raw envelope before we hand it to importBackup, so a
        // failure inside importBackup can be attributed precisely.
        val rawParsed = Json.parseToJsonElement(jsonString) as JsonObject
        val backupMeta = rawParsed["_backup"] as JsonObject
        assertEquals(2, backupMeta["schema"]?.jsonPrimitive?.intOrNull)
        val expectedHash = backupMeta["hash"]?.jsonPrimitive?.content
        assertNotNull("fixture must carry a _backup.hash", expectedHash)
        assertFalse(
            "this real fixture is expected to carry a genuine SHA-256 hash, not a " +
                "fallback- hash (that path is covered separately below)",
            expectedHash!!.startsWith("fallback-")
        )
        assertEquals("hash must be a 64-hex-char SHA-256 digest", 64, expectedHash.length)

        val expectedTasks = backupMeta["items"]?.jsonObject?.get("tasks")?.jsonPrimitive?.intOrNull
        val expectedRewards = backupMeta["items"]?.jsonObject?.get("rewards")?.jsonPrimitive?.intOrNull
        val expectedTags = backupMeta["items"]?.jsonObject?.get("tags")?.jsonPrimitive?.intOrNull
        val expectedEventCount = backupMeta["eventCount"]?.jsonPrimitive?.intOrNull

        // This is the actual subject under test: parse + detokenize + hash
        // verification per the fallback policy. Per current code, this real
        // fixture's SHA-256 hash verifies, so importBackup should complete
        // without throwing. If a regression reintroduces a hash mismatch for
        // this file, this call throws IllegalArgumentException and the test
        // fails loudly (the truthful, non-faked outcome), rather than the
        // failure being silently swallowed.
        val result = SerializationEngine.importBackup(jsonString)

        assertNotNull(result.character)
        assertEquals("task count must match _backup.items.tasks", expectedTasks, result.tasks.size)
        assertEquals("reward count must match _backup.items.rewards", expectedRewards, result.rewards.size)
        assertEquals("tag count must match _backup.items.tags", expectedTags, result.tags.size)
        assertEquals("event count must match _backup.eventCount", expectedEventCount, result.events.size)
        assertTrue("expected at least one device entry", result.devices.isNotEmpty())
    }

    @Test
    fun fallbackHash_isVerifiedAsFallback32_likeOpti() {
        // WP-8.9 (Q-04): Opti 7d5d4f3 no longer skips a `fallback-` hash; it verifies it with
        // `_hashFallback32` (app.js:8214-8215). A wrong fallback hash is refused, the right one
        // imports. This replaces the pre-Stage-8 "accepted without verification" test.
        fun envelope(hash: String) = buildJsonObject {
            put("_backup", buildJsonObject {
                put("schema", JsonPrimitive(2))
                put("hash", JsonPrimitive(hash))
            })
            put("FM", buildJsonObject {})
            put("S", buildJsonObject {})
            put("E", JsonArray(emptyList()))
        }
        val preimage = CanonicalJson.buildHashPreimage(
            buildJsonObject {}, emptyList(), buildJsonObject { put("schema", JsonPrimitive(2)) }
        )
        val good = SerializationEngine.hashFallback32(preimage)
        SerializationEngine.importBackup(Json.encodeToString(envelope(good)))
        try {
            SerializationEngine.importBackup(Json.encodeToString(envelope("fallback-00000000")))
            fail("a wrong fallback- hash must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("hash mismatch"))
        }
    }

    /**
     * WP-5.4 (Stage 4->5 carry-over, WORK_TRACKER.md line 55a) -- extends this file's existing
     * parse-level assertion (`realBackup_parsesDetokenizesAndHashVerifies`'s
     * `result.events.size == expectedEventCount`) one layer further: through the REAL production
     * entry point ([SyncManager.importSnapshotFromFile]) against an in-memory Room DB, asserting
     * the resulting `event_logs` ROW COUNT also matches the fixture's `_backup.eventCount`. Before
     * this WP, `backup.events` was parsed but never written to Room at all, so this assertion
     * would have failed with 0 rows regardless of how many events parsed correctly.
     */
    @Test
    fun realBackup_importSnapshotFromFile_persistsAllEventsToEventLogs() = runBlocking {
        val fixture = locateFixture("real_backup.json")
        val jsonString = fixture.readText(Charsets.UTF_8)

        val rawParsed = Json.parseToJsonElement(jsonString) as JsonObject
        val backupMeta = rawParsed["_backup"] as JsonObject
        val expectedEventCount = backupMeta["eventCount"]?.jsonPrimitive?.intOrNull
        assertNotNull("fixture must carry _backup.eventCount", expectedEventCount)

        val result = syncManager.importSnapshotFromFile(jsonString)
        assertTrue(
            "real-fixture import through the production entry point must succeed: " +
                (result.exceptionOrNull()?.message ?: "ok"),
            result.isSuccess
        )

        val persistedCount = repository.getLogsInRangeSync(0L, Long.MAX_VALUE).size
        assertEquals(
            "event_logs row count after import must match the fixture's own _backup.eventCount " +
                "(WP-5.4: backup.events must actually be persisted, not just parsed)",
            expectedEventCount,
            persistedCount
        )
    }
}
