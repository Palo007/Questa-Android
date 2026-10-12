package com.example.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 3 / T3.5 (D12), ported in WP-8.5 -- plain-JVM tests against the merge core
 * ([com.example.data.sync.merge.OptiSnapshotMerger]) directly.
 *
 * See [com.example.StateSnapshotFieldAlignmentPersistenceTest] (Robolectric) for the companion
 * test exercising the REAL production wiring these merge-level tests assume is correct upstream
 * ([SyncManager.buildLocalSnapshot]'s field set/order, and the array-order round-trip via the
 * public `importSnapshotFromFile`/`exportSnapshotToFile` API).
 *
 * Ground truth: `plans/sync_format_spec.md` §3 "syncSubset() field inventory (extracted from
 * legacy_pwa/sync.js, Stage 1 T1.2)" -- 11 fields, in `sync.js:391-401` insertion order:
 * `char, tasks, rewards, tags, devices, lastCron, history, charHistory, an, monthlyBackups,
 * deletions`. `version`, full `prefs`, `__hlcLast`, `__seq`, `__savedAt`, `events` are all
 * explicitly EXCLUDED per that same table.
 */
class StateSnapshotFieldAlignmentTest {

    // WP-8.5: the merged snapshot is Opti merge()'s object literal (sync.js:2169-2322), which
    // is what JSON.stringify(merged) uploads: 12 keys (pause included), in THIS order. The old
    // expectation (syncSubset()'s 11-key order) described the legacy Kotlin merge, not Opti.
    private val expectedSyncSubsetKeyOrder = listOf(
        "tasks", "rewards", "tags", "devices", "an", "pause", "history", "charHistory",
        "monthlyBackups", "lastCron", "deletions", "char"
    )

    private fun mergeCore(base: JsonObject, local: JsonObject, remote: JsonObject): JsonObject {
        val ctx = com.example.data.sync.merge.MergeContext(
            localDev = "devA", remoteDev = "devB", futureCeil = 2000L + 120_000L, nowPhysical = 2000L,
            localToday = 20260924, zone = java.time.ZoneId.of("UTC"),
        )
        return com.example.data.sync.merge.OptiSnapshotMerger().merge(base, local, remote, ctx) { }
    }

    private fun minimalCharJson(updatedAt: Long): JsonObject = buildJsonObject {
        put("name", JsonPrimitive("Hero"))
        put("lvl", JsonPrimitive(1))
        put("xp", JsonPrimitive(0))
        put("hp", JsonPrimitive(50.0))
        put("maxHp", JsonPrimitive(50.0))
        put("mp", JsonPrimitive(0))
        put("gold", JsonPrimitive(0.0))
        put("updatedAt", JsonPrimitive(updatedAt))
    }

    /**
     * Deliberately includes `version` and `prefs` (and other PWA-only fields the real
     * `legacy_pwa/app.js freshState()`/`migrate()` puts in `S`) on the INPUT side, to prove the
     * exclusion happens because the merge doesn't copy them through -- not merely because
     * the test fixture never supplied them in the first place.
     */
    private fun inputSnapshotWithExtraFields(char: JsonObject): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("char", char)
        put("tasks", JsonArray(emptyList()))
        put("rewards", JsonArray(emptyList()))
        put("tags", JsonArray(emptyList()))
        put("devices", JsonArray(emptyList()))
        put("lastCron", SerializationEngine.lastCronToJson(""))
        put("history", JsonArray(emptyList()))
        put("charHistory", JsonArray(emptyList()))
        put("an", buildJsonObject {
            put("views", JsonArray(emptyList()))
            put("metrics", JsonArray(emptyList()))
        })
        put("monthlyBackups", JsonArray(emptyList()))
        put("deletions", JsonArray(emptyList()))
        put("prefs", buildJsonObject {
            put("haptics", JsonPrimitive(true))
            put("width", JsonPrimitive(42))
        })
        put("__hlcLast", JsonPrimitive(123456L))
        put("__seq", JsonPrimitive(7))
        put("__savedAt", JsonPrimitive(999L))
    }

    @Test
    fun merge_outputFieldSet_matchesSyncSubsetExactly_noMoreNoLess() {
        val base = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val local = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val remote = inputSnapshotWithExtraFields(minimalCharJson(1000L))

        val merged = mergeCore(base, local, remote)

        assertEquals(
            "merged snapshot must contain EXACTLY merge()'s 12 fields, no more/no less",
            expectedSyncSubsetKeyOrder.toSet(),
            merged.keys
        )
        assertFalse("`version` must be excluded per spec §3 (PWA-only, never synced)", merged.containsKey("version"))
        assertFalse("full `prefs` must be excluded per spec §3 (only an.{views,metrics} is synced)", merged.containsKey("prefs"))
        assertFalse(merged.containsKey("__hlcLast"))
        assertFalse(merged.containsKey("__seq"))
        assertFalse(merged.containsKey("__savedAt"))
        assertFalse(merged.containsKey("events"))
    }

    @Test
    fun merge_outputKeyOrder_matchesSyncJsInsertionOrderExactly() {
        val base = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val local = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val remote = inputSnapshotWithExtraFields(minimalCharJson(1000L))

        val merged = mergeCore(base, local, remote)

        // JsonObject is LinkedHashMap-backed (see CanonicalJson.kt's class doc) -- .keys.toList()
        // reflects buildJsonObject{}'s actual `put()` call order, which is exactly what
        // canonicalJsonString/state.json serialize in.
        assertEquals(expectedSyncSubsetKeyOrder, merged.keys.toList())
    }

    @Test
    fun merge_anStaysTopLevel_betweenDevicesAndPause() {
        // `an` is top-level in the merged snapshot, in merge()'s literal position. Asserted
        // explicitly since it's easy to silently drift if a future edit reorders the puts.
        val base = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val merged = mergeCore(base, base, base)
        val keys = merged.keys.toList()
        val anIdx = keys.indexOf("an")
        // merge() builds `an` right after devices and before pause (sync.js:2173-2178).
        assertEquals(keys.indexOf("devices") + 1, anIdx)
        assertEquals(keys.indexOf("pause") - 1, anIdx)
    }

    /**
     * Spec §4:370 "monthlyBackups: set union + sort" -- legacy_pwa/sync.js:946-950 does
     * `Array.from(new Set([...l, ...r])).sort()`, a lexicographic sort on "YYYY-MM" strings.
     * Out-of-order, overlapping inputs on both sides must come out deduped AND sorted.
     */
    @Test
    fun merge_monthlyBackups_unionIsDedupedAndLexicographicallySorted() {
        val base = inputSnapshotWithExtraFields(minimalCharJson(1000L))
        val local = inputSnapshotWithExtraFields(minimalCharJson(1000L)).let {
            JsonObject(it.toMutableMap().apply {
                put("monthlyBackups", JsonArray(listOf(JsonPrimitive("2026-07"), JsonPrimitive("2025-12"))))
            })
        }
        val remote = inputSnapshotWithExtraFields(minimalCharJson(1000L)).let {
            JsonObject(it.toMutableMap().apply {
                put("monthlyBackups", JsonArray(listOf(JsonPrimitive("2026-01"), JsonPrimitive("2025-12"))))
            })
        }

        val merged = mergeCore(base, local, remote)

        val monthlyBackups = merged["monthlyBackups"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("2025-12", "2026-01", "2026-07"), monthlyBackups)
    }
}
