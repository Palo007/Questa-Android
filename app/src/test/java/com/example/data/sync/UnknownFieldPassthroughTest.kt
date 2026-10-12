package com.example.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 3 / T3.1 -- unknown-field passthrough (spec `plans/sync_format_spec.md` §3, §7 D3).
 *
 * Plain-JVM tests (no Robolectric/Room), matching the existing convention in
 * `GoldenFixtureTest`/`SyncConflictResolutionTest`/`RealBackupImportSmokeTest`: everything here
 * exercises `SerializationEngine` directly (its `overlayJson` helper, the `import`/`export`
 * pipeline, and `mergeCollection`), since Room entities (`TaskEntity`, ...) are plain Kotlin data
 * classes that don't require an Android runtime to construct or pass through these functions.
 *
 * The test envelopes below use an empty `FM` (`{}`), which makes `detokenizeDeep` a no-op rename
 * (`rmap[k] ?: k` falls through to `k` for every key since the reverse map is empty) -- so `S` can
 * be written with real field names directly instead of hand-tokenizing it. `_backup` carries no
 * `hash` key, which `importBackup` treats as "hash absent -> import proceeds unchecked" (spec
 * §1.1), so these synthetic fixtures don't need a real SHA-256 computed up front (same technique
 * `RealBackupImportSmokeTest.fallbackHash_isAcceptedWithoutVerification` and
 * `SyncConflictResolutionTest.testCryptographicHashVerification`'s "no hash" implicit case rely
 * on).
 */
class UnknownFieldPassthroughTest {

    /**
     * A single habit task whose key order deliberately follows the PWA's empirically-observed
     * habit order (spec §1.2, "Empirical fixture order"): `id, type, title, notes, difficulty,
     * value, done, checklist, up, down, tags, createdAt, updatedAt, reminders`. This is NOT the
     * order `SerializationEngine.buildTaskJson`'s "fresh" builder would produce for a brand-new
     * Kotlin task (which has no `up`/`down`/`reminders` fields at all, and interleaves
     * `tags`/`createdAt`/`updatedAt` before ever emitting `streak`/`history`/`cUp`/`cDown`) -- so
     * a passing order-preservation assertion here is a genuine test, not a coincidence of two
     * builders agreeing.
     */
    private fun envelopeJson(): String = """
        {
          "_backup": { "schema": 2 },
          "K": [], "SRC": [], "TID": [], "TT": [],
          "FM": {},
          "S": {
            "version": 1,
            "char": { "name": "Hero", "face": "🧙", "cls": "Warrior", "lvl": 3, "xp": 20, "hp": 40.0, "maxHp": 50.0, "mp": 5, "gold": 12.5, "updatedAt": 5000 },
            "tasks": [
              {
                "id": "t1",
                "type": "habit",
                "title": "Drink Water",
                "notes": "stay hydrated",
                "difficulty": "easy",
                "value": 3.5,
                "done": false,
                "checklist": [],
                "up": true,
                "down": false,
                "tags": ["tag1"],
                "createdAt": 1000,
                "updatedAt": 2000,
                "reminders": [
                  { "id": "rem1", "enabled": true, "kind": "weekly", "time": "07:30", "lastFiredKey": "20260717", "days": [true, false, true, false, true, false, true] }
                ]
              }
            ],
            "rewards": [],
            "tags": [
              { "id": "tag1", "name": "Health", "color": "#4CAF50", "createdAt": 500, "updatedAt": 500 }
            ],
            "devices": [],
            "lastCron": 20260717,
            "history": [],
            "charHistory": [],
            "monthlyBackups": [],
            "deletions": [],
            "prefs": { "haptics": true, "width": 42, "an": { "views": [], "metrics": [] } },
            "__futureField": "keep-me-verbatim"
          },
          "E": []
        }
    """.trimIndent()

    // -----------------------------------------------------------------------------------------
    // overlayJson -- the core mechanism, tested in isolation
    // -----------------------------------------------------------------------------------------

    @Test
    fun overlayJson_preservesOriginalOrder_overwritesInPlace_appendsNewKeysAfter() {
        val original = buildJsonObject {
            put("b", JsonPrimitive("origB"))
            put("a", JsonPrimitive("origA"))
            put("c", JsonPrimitive("origC"))
        }
        val fresh = buildJsonObject {
            put("a", JsonPrimitive("newA"))
            put("d", JsonPrimitive("newD"))
        }

        val result = SerializationEngine.overlayJson(original, fresh)

        // Original key order wins for shared + unmodeled keys; fresh-only keys append after.
        assertEquals(listOf("b", "a", "c", "d"), result.keys.toList())
        // Fresh's CURRENT value wins for a key both sides have.
        assertEquals("newA", result["a"]?.jsonPrimitive?.content)
        // Unmodeled keys pass through verbatim, untouched.
        assertEquals("origB", result["b"]?.jsonPrimitive?.content)
        assertEquals("origC", result["c"]?.jsonPrimitive?.content)
        // Genuinely-new fresh-only key appended.
        assertEquals("newD", result["d"]?.jsonPrimitive?.content)
    }

    @Test
    fun overlayJson_returnsFreshUnchanged_whenNoOriginalKnown() {
        val fresh = buildJsonObject { put("x", JsonPrimitive(1)) }
        // No known original (fresh Kotlin-only record) -- must be exactly today's pre-T3.1
        // normative-order behavior, not merely equal but the SAME instance (no rebuild).
        assertSame(fresh, SerializationEngine.overlayJson(null, fresh))
        assertSame(fresh, SerializationEngine.overlayJson(JsonObject(emptyMap()), fresh))
    }

    // -----------------------------------------------------------------------------------------
    // Task-level decoy field + original-key-order re-emission
    // -----------------------------------------------------------------------------------------

    @Test
    fun importBackup_capturesTasksOriginalObject_includingUnmodeledFields_inOriginalOrder() {
        val imported = SerializationEngine.importBackup(envelopeJson())
        val task = imported.tasks.single()

        assertNotNull("task.extraJson must be populated from the parsed original object", task.extraJson)
        val orig = SerializationEngine.parseExtraJson(task.extraJson)!!

        assertEquals(
            listOf(
                "id", "type", "title", "notes", "difficulty", "value", "done", "checklist",
                "up", "down", "tags", "createdAt", "updatedAt", "reminders"
            ),
            orig.keys.toList()
        )
        assertTrue(orig["up"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(orig["down"]?.jsonPrimitive?.booleanOrNull == false)
        assertEquals(1, orig["reminders"]?.jsonArray?.size)
    }

    @Test
    fun exportBackup_reEmitsTaskDecoyFields_inOriginalRelativePosition() {
        val imported = SerializationEngine.importBackup(envelopeJson())

        val reExported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = imported.character,
            tasks = imported.tasks.map { it.copy(streak = 1) },
            rewards = imported.rewards,
            events = imported.events,
            deletions = imported.deletions,
            lastCron = imported.lastCron,
            tags = imported.tags,
            devices = imported.devices,
            charHistory = imported.charHistory,
            monthlyBackups = imported.monthlyBackups,
            globalHistory = imported.globalHistory,
            snapshotExtras = imported.rawSnapshot
        )

        // Re-import the fresh export (mirrors GoldenFixtureTest's re-export-then-reimport
        // pattern) so the assertions below inspect production detokenization output, not a
        // hand-rolled parse of the tokenized envelope.
        val reImported = SerializationEngine.importBackup(reExported)
        val reTask = reImported.tasks.single()
        val reOrig = SerializationEngine.parseExtraJson(reTask.extraJson)!!
        val keys = reOrig.keys.toList()
        fun idx(k: String) = keys.indexOf(k).also { assertTrue("missing key $k", it >= 0) }

        // Original relative order preserved for every key the input had.
        assertTrue(idx("id") < idx("type"))
        assertTrue(idx("up") < idx("down"))
        assertTrue(idx("down") < idx("tags"))
        assertTrue(idx("tags") < idx("createdAt"))
        assertTrue(idx("createdAt") < idx("updatedAt"))
        assertTrue(idx("updatedAt") < idx("reminders"))
        // Fields TaskEntity always populates but the ORIGINAL object never had (streak, cUp,
        // cDown, history) are genuinely new keys -- appended AFTER every original key, per
        // overlayJson's contract.
        assertTrue("streak/history/etc. must append after the original's own keys", idx("reminders") < idx("streak"))

        // The decoy/unmodeled fields themselves survived byte-for-byte.
        assertTrue(reOrig["up"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(reOrig["down"]?.jsonPrimitive?.booleanOrNull == false)
        assertEquals(1, reOrig["reminders"]?.jsonArray?.size)
        assertEquals(
            "rem1",
            reOrig["reminders"]?.jsonArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content
        )
    }

    // -----------------------------------------------------------------------------------------
    // Snapshot-level (top-level `S`) unknown field passthrough
    // -----------------------------------------------------------------------------------------

    @Test
    fun importBackup_capturesSnapshotLevelUnknownFields() {
        val imported = SerializationEngine.importBackup(envelopeJson())

        assertEquals("keep-me-verbatim", imported.rawSnapshot["__futureField"]?.jsonPrimitive?.content)
        assertEquals(42L, imported.rawSnapshot["prefs"]?.jsonObject?.get("width")?.jsonPrimitive?.longOrNull)
        assertNotNull(imported.rawSnapshot["prefs"]?.jsonObject?.get("an"))
    }

    @Test
    fun exportBackup_reEmitsSnapshotLevelDecoyField_whenSnapshotExtrasProvided() {
        val imported = SerializationEngine.importBackup(envelopeJson())

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
            snapshotExtras = imported.rawSnapshot
        )
        val reImported = SerializationEngine.importBackup(reExported)

        assertEquals(
            "the __futureField decoy (spec's own example of a genuinely-unknown top-level key) " +
                "must round-trip verbatim when snapshotExtras is supplied",
            "keep-me-verbatim",
            reImported.rawSnapshot["__futureField"]?.jsonPrimitive?.content
        )
        assertEquals(42L, reImported.rawSnapshot["prefs"]?.jsonObject?.get("width")?.jsonPrimitive?.longOrNull)
        assertNotNull(reImported.rawSnapshot["prefs"]?.jsonObject?.get("an"))
    }

    @Test
    fun exportBackup_omitsSnapshotLevelExtras_whenSnapshotExtrasNotProvided() {
        // Contrast case: proves the previous test's passthrough is actually caused by the
        // snapshotExtras parameter, not some other mechanism -- omitting it reproduces the
        // pre-T3.1 behavior (only the hardcoded {"haptics": true} default `prefs`, no
        // __futureField at all).
        val imported = SerializationEngine.importBackup(envelopeJson())

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
            globalHistory = imported.globalHistory
            // snapshotExtras intentionally omitted (defaults to null)
        )
        val reImported = SerializationEngine.importBackup(reExported)

        assertNull(reImported.rawSnapshot["__futureField"])
        assertNull(reImported.rawSnapshot["prefs"]?.jsonObject?.get("width"))
    }

    // -----------------------------------------------------------------------------------------
    // Merge keeps the record winner's extras (spec: "opaque extras travel with the record
    // winner")
    // -----------------------------------------------------------------------------------------

    @Test
    fun mergeCollection_keepsWinnersExtras() {
        val remoteTask = buildJsonObject {
            put("id", JsonPrimitive("t1"))
            put("type", JsonPrimitive("todo"))
            put("title", JsonPrimitive("Remote Title"))
            put("done", JsonPrimitive(false))
            put("updatedAt", JsonPrimitive(2000L))
            put("reminders", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("r1")) })))
        }
        val localTask = buildJsonObject {
            put("id", JsonPrimitive("t1"))
            put("type", JsonPrimitive("todo"))
            put("title", JsonPrimitive("Local Title"))
            put("done", JsonPrimitive(false))
            put("updatedAt", JsonPrimitive(1000L))
        }

        // WP-8.5: through the merge core (OptiSnapshotMerger), with an empty base.
        val ctx = com.example.data.sync.merge.MergeContext(
            localDev = "devA", remoteDev = "devB", futureCeil = 10_000L, nowPhysical = 2000L,
            localToday = 20260924, zone = java.time.ZoneId.of("UTC"),
        )
        val merged = com.example.data.sync.merge.OptiSnapshotMerger().merge(
            buildJsonObject { put("tasks", JsonArray(emptyList())) },
            buildJsonObject { put("tasks", JsonArray(listOf(localTask))) },
            buildJsonObject { put("tasks", JsonArray(listOf(remoteTask))) },
            ctx,
        ) { }["tasks"]!!.jsonArray

        val winner = merged.single().jsonObject
        // Sanity: remote is the genuine winner (strictly newer updatedAt), not a tie/fallback.
        assertEquals("Remote Title", winner["title"]?.jsonPrimitive?.content)
        assertTrue(
            "the merge winner (remote) must carry its own reminders[] extras forward, not drop them",
            winner.containsKey("reminders")
        )
    }

    // -----------------------------------------------------------------------------------------
    // T3.1 opus-review FIX 4 -- regression tests for FIX 1 (monthlyBackups/globalHistory
    // stripped on the real exportSnapshotToFile call shape) and the deleted-in-Kotlin boundary.
    // -----------------------------------------------------------------------------------------

    /**
     * A minimal envelope carrying non-empty `monthlyBackups`/`history` (the two top-level `S`
     * collections Kotlin has no Room-backed live source for at all -- see
     * `SerializationEngine.exportBackup`'s `freshSSnap` doc). `history` is annotated by spec
     * §1.2 as "always empty" in real PWA output, but Kotlin must not assume that invariant on
     * behalf of whatever produced the file -- the passthrough must hold regardless of content.
     */
    private fun envelopeJsonWithMonthlyBackupsAndHistory(): String = """
        {
          "_backup": { "schema": 2 },
          "K": [], "SRC": [], "TID": [], "TT": [],
          "FM": {},
          "S": {
            "version": 1,
            "char": { "name": "Hero", "face": "🧙", "cls": "Warrior", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
            "tasks": [],
            "rewards": [],
            "tags": [],
            "devices": [],
            "lastCron": 0,
            "history": [ { "legacyNote": "pre-migration-global-history-entry" } ],
            "charHistory": [],
            "monthlyBackups": ["2026-05", "2026-06"],
            "deletions": [],
            "prefs": { "haptics": true }
          },
          "E": []
        }
    """.trimIndent()

    /**
     * FIX 1 regression test. `SyncManager.exportSnapshotToFile` (the REAL production call site)
     * never passes `monthlyBackups`/`globalHistory` to `exportBackup` at all -- both silently
     * default to `emptyList()`, since neither collection has a Room table backing it; the only
     * place a real value for either one survives is a prior import's persisted
     * `SnapshotExtrasEntity` (`imported.rawSnapshot`, threaded through as `snapshotExtras` here,
     * exactly like `exportSnapshotToFile` does via `repository.getSnapshotExtrasJson()`).
     *
     * BEFORE the fix: `freshSSnap` put an empty `JsonArray` at both keys unconditionally, so the
     * key existed on both `snapshotExtras` and `freshSSnap`, and `overlayJson`'s "fresh wins the
     * value" rule (correct for keys Kotlin genuinely can populate) silently clobbered the
     * captured originals with that empty array -- this assertion would have FAILED (both
     * collections would re-import as empty).
     * AFTER the fix: `freshSSnap` only emits the empty default when there is no captured
     * original to preserve instead, so `overlayJson` passes `snapshotExtras`' original values
     * through untouched -- this assertion PASSES.
     */
    @Test
    fun exportBackup_preservesMonthlyBackupsAndGlobalHistory_onTheRealExportSnapshotToFileCallShape() {
        val imported = SerializationEngine.importBackup(envelopeJsonWithMonthlyBackupsAndHistory())
        assertEquals(listOf("2026-05", "2026-06"), imported.monthlyBackups.map { it.jsonPrimitive.content })
        assertEquals(1, imported.globalHistory.size)

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
            // monthlyBackups/globalHistory intentionally OMITTED -- this is the exact shape of
            // SyncManager.exportSnapshotToFile's real call (see SyncManager.kt), which never
            // sources either collection at all. Both silently default to emptyList().
            snapshotExtras = imported.rawSnapshot
        )
        val reImported = SerializationEngine.importBackup(reExported)

        assertEquals(
            "monthlyBackups must survive via snapshotExtras even though exportBackup's own " +
                "monthlyBackups param was never populated by the real call site -- FIX 1",
            listOf("2026-05", "2026-06"),
            reImported.monthlyBackups.map { it.jsonPrimitive.content }
        )
        assertEquals(
            "global history[] must survive via snapshotExtras the same way -- FIX 1",
            1,
            reImported.globalHistory.size
        )
        assertEquals(
            "pre-migration-global-history-entry",
            (reImported.globalHistory.single() as JsonObject)["legacyNote"]?.jsonPrimitive?.content
        )
    }

    /**
     * "Deleted-in-Kotlin-does-not-resurrect" boundary test. A checklist item removed from a
     * `TaskEntity`'s live `checklist` list (a genuine user edit, made through the Kotlin app --
     * NOT a re-import) must NOT be resurrected from that task's own captured `extraJson`
     * original on the next export, even though the original object (still sitting in
     * `extraJson`, untouched by the edit) contains both items. This holds today because
     * `overlayJson` operates per-KEY, not per-array-element: `buildTaskJson`'s `fresh` object
     * always supplies a freshly-built `checklist` array from the entity's CURRENT (edited) list,
     * so `overlayJson` substitutes that whole array as the value at the `checklist` key rather
     * than reconciling it item-by-item against the original array. Added per the T3.1 opus
     * review as an explicit regression guard for this boundary (distinct from FIX 1, which is
     * about top-level collections Kotlin can never populate at all).
     */
    @Test
    fun exportBackup_deletedChecklistItem_doesNotResurrectFromTasksOwnExtraJson() {
        val fixture = """
            {
              "_backup": { "schema": 2 },
              "K": [], "SRC": [], "TID": [], "TT": [],
              "FM": {},
              "S": {
                "version": 1,
                "char": { "name": "Hero", "face": "🧙", "cls": "Warrior", "lvl": 1, "xp": 0, "hp": 50.0, "maxHp": 50.0, "mp": 0, "gold": 0.0, "updatedAt": 1000 },
                "tasks": [
                  {
                    "id": "t1",
                    "type": "todo",
                    "title": "Groceries",
                    "checklist": [
                      { "id": "c1", "text": "Milk", "done": false },
                      { "id": "c2", "text": "Eggs", "done": false }
                    ],
                    "createdAt": 1000,
                    "updatedAt": 1000
                  }
                ],
                "rewards": [], "tags": [], "devices": [], "lastCron": 0, "history": [],
                "charHistory": [], "monthlyBackups": [], "deletions": [], "prefs": {}
              },
              "E": []
            }
        """.trimIndent()

        val imported = SerializationEngine.importBackup(fixture)
        val task = imported.tasks.single()
        assertEquals(2, task.checklist.size)

        // Simulate the user deleting "Eggs" (c2) in the Kotlin app: a live entity edit, not a
        // re-import. `task.extraJson` (the captured original, still containing BOTH items) is
        // untouched by this edit -- exactly like a real UI edit would leave it.
        val editedTask = task.copy(checklist = task.checklist.filter { it.id != "c2" })

        val reExported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = imported.character,
            tasks = listOf(editedTask),
            rewards = imported.rewards,
            events = imported.events,
            deletions = imported.deletions,
            lastCron = imported.lastCron,
            tags = imported.tags,
            devices = imported.devices,
            charHistory = imported.charHistory,
            monthlyBackups = imported.monthlyBackups,
            globalHistory = imported.globalHistory,
            snapshotExtras = imported.rawSnapshot
        )
        val reImported = SerializationEngine.importBackup(reExported)
        val reTask = reImported.tasks.single()

        assertEquals(1, reTask.checklist.size)
        assertEquals("c1", reTask.checklist.single().id)
        assertNull(
            "a checklist item deleted in the Kotlin app must not resurrect from the task's own " +
                "extraJson original on export",
            reTask.checklist.find { it.id == "c2" }
        )
    }
}
