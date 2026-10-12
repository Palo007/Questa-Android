package com.example.data.sync

import com.example.data.local.entities.CustomViewEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 3 / T3.2 -- `an.views` / `an.metrics` first-class support (spec `plans/sync_format_spec.md`
 * §3 "an.views / an.metrics shape", §7 D3).
 *
 * Plain-JVM tests (no Robolectric/Room), same convention as `UnknownFieldPassthroughTest`: `an`
 * lives nested under `S.prefs.an` in the BACKUP shape (spec §1.2's `prefs` note: "not synced
 * except an.{views, metrics}"), matching `SerializationEngine.importBackup`/`exportBackup`'s
 * handling. The Dropbox `state.json` (`syncSubset()`) shape lifts `an` to the TOP level instead
 * (spec §3's field table, sync.js:389/399) -- exercised here via `mergeSnapshots` directly, which
 * is the function that actually operates on that shape.
 *
 * See `AnViewsMetricsPersistenceTest` (Robolectric, `com.example` package) for the Room-backed
 * "merged views persist and re-emit" acceptance test that this plain-JVM file cannot cover
 * (requires `SyncManager`/`RpgRepository`/an Android `Context`).
 */
class AnViewsMetricsTest {

    /**
     * One fully-populated PWA view (spec §3 T3.2a order: id, name, source, group, chart, tags,
     * types, metricId, createdAt, updatedAt) plus TWO metrics: a modern one with the full shape,
     * and a LEGACY one (app.js:2082-2084) with ONLY `{id, name, keyword}` -- missing `exact`,
     * `habits`, `createdAt`, `updatedAt` entirely, per spec §3 T3.2a's "Key observations".
     */
    private fun envelopeJson(): String = """
        {
          "_backup": { "schema": 2 },
          "K": [], "SRC": [], "TID": [], "TT": [],
          "FM": {},
          "S": {
            "version": 1,
            "char": { "name": "Hero", "face": "🧙", "cls": "Warrior", "lvl": 3, "xp": 20, "hp": 40.0, "maxHp": 50.0, "mp": 5, "gold": 12.5, "updatedAt": 5000 },
            "tasks": [], "rewards": [], "tags": [], "devices": [],
            "lastCron": 20260717,
            "history": [], "charHistory": [], "monthlyBackups": [], "deletions": [],
            "prefs": {
              "haptics": true,
              "an": {
                "views": [
                  { "id": "v1", "name": "Weekly completions", "source": "completed", "group": "week", "chart": "bar", "tags": ["tag1"], "types": ["habit", "daily"], "metricId": null, "createdAt": 1000, "updatedAt": 2000 }
                ],
                "metrics": [
                  { "id": "m-legacy", "name": "Kliky", "keyword": "kliky" },
                  { "id": "m-modern", "name": "Zdvihy", "keyword": "zdvih", "exact": true, "habits": [{ "id": "h1", "reps": 10 }], "createdAt": 500, "updatedAt": 1500 }
                ]
              }
            }
          },
          "E": []
        }
    """.trimIndent()

    // -----------------------------------------------------------------------------------------
    // Import: nested `prefs.an` parsing
    // -----------------------------------------------------------------------------------------

    @Test
    fun importBackup_parsesViewsFromNestedPrefsAn() {
        val imported = SerializationEngine.importBackup(envelopeJson())

        assertEquals(1, imported.views.size)
        val v = imported.views.single()
        assertEquals("v1", v.id)
        assertEquals("Weekly completions", v.name)
        assertEquals("completed", v.source)
        assertEquals("week", v.group)
        assertEquals("bar", v.chart)
        assertEquals(listOf("tag1"), v.tags)
        assertEquals(listOf("habit", "daily"), v.types)
        assertNull(v.metricId)
        assertEquals(1000L, v.createdAt)
        assertEquals(2000L, v.updatedAt)
    }

    @Test
    fun importBackup_legacyMetric_doesNotSynthesizeMissingKeys() {
        val imported = SerializationEngine.importBackup(envelopeJson())
        assertEquals(2, imported.metrics.size)

        val legacy = imported.metrics.single { it["id"]?.jsonPrimitive?.content == "m-legacy" }
        // Per spec §3 T3.2a: legacy metrics (app.js:2082-2084) have ONLY {id, name, keyword} --
        // exact/habits/createdAt/updatedAt must NOT be synthesized/defaulted onto them.
        assertEquals(setOf("id", "name", "keyword"), legacy.keys)
        assertEquals("Kliky", legacy["name"]?.jsonPrimitive?.content)
        assertEquals("kliky", legacy["keyword"]?.jsonPrimitive?.content)

        val modern = imported.metrics.single { it["id"]?.jsonPrimitive?.content == "m-modern" }
        assertEquals(setOf("id", "name", "keyword", "exact", "habits", "createdAt", "updatedAt"), modern.keys)
        assertTrue(modern["exact"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(1, modern["habits"]?.jsonArray?.size)
    }

    // -----------------------------------------------------------------------------------------
    // Round-trip: import -> export -> reimport, deep-equal (incl. the legacy metric)
    // -----------------------------------------------------------------------------------------

    @Test
    fun exportBackup_viewsAndMetrics_roundTripDeepEqual() {
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
            views = imported.views,
            metrics = imported.metrics,
            snapshotExtras = imported.rawSnapshot
        )
        val reImported = SerializationEngine.importBackup(reExported)

        // View: full field-for-field deep equal.
        assertEquals(1, reImported.views.size)
        val v = reImported.views.single()
        assertEquals("v1", v.id)
        assertEquals("Weekly completions", v.name)
        assertEquals("completed", v.source)
        assertEquals("week", v.group)
        assertEquals("bar", v.chart)
        assertEquals(listOf("tag1"), v.tags)
        assertEquals(listOf("habit", "daily"), v.types)
        assertNull(v.metricId)
        assertEquals(1000L, v.createdAt)
        assertEquals(2000L, v.updatedAt)

        // Metrics: byte/deep-equal, INCLUDING the legacy metric's missing-key-set staying missing
        // (not synthesized) after a full round trip through Room-shaped entities/opaque storage.
        assertEquals(2, reImported.metrics.size)
        val legacy = reImported.metrics.single { it["id"]?.jsonPrimitive?.content == "m-legacy" }
        assertEquals(setOf("id", "name", "keyword"), legacy.keys)
        assertEquals("Kliky", legacy["name"]?.jsonPrimitive?.content)
        assertEquals("kliky", legacy["keyword"]?.jsonPrimitive?.content)

        val modern = reImported.metrics.single { it["id"]?.jsonPrimitive?.content == "m-modern" }
        assertEquals(setOf("id", "name", "keyword", "exact", "habits", "createdAt", "updatedAt"), modern.keys)
        assertEquals(500L, modern["createdAt"]?.jsonPrimitive?.longOrNull)
        assertEquals(1500L, modern["updatedAt"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun exportBackup_itemsViewsCount_isRealCount_notHardcodedZero() {
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
            views = imported.views,
            metrics = imported.metrics,
            snapshotExtras = imported.rawSnapshot
        )
        // `_backup` itself is never FM-tokenized (only `S` is), so this is readable directly.
        val envelope = Json.parseToJsonElement(reExported) as JsonObject
        val itemsViews = envelope["_backup"]?.jsonObject?.get("items")?.jsonObject?.get("views")?.jsonPrimitive?.intOrNull
        assertEquals("_backup.items.views must be the real an.views count (spec: app.js:5263), not the old hardcoded 0", 1, itemsViews)

        // The hash-preimage's items block must be built from the SAME count (both blocks in
        // exportBackup use `viewsJson.size`) -- a real backup importer verifies the hash, so a
        // reimport succeeding at all (no exception) is itself evidence the two counts agree.
        val reImported = SerializationEngine.importBackup(reExported)
        assertEquals(1, reImported.views.size)
    }

    @Test
    fun exportBackup_excludesNativeQuickView_fromAnViewsAndItemsCount() {
        // A pre-existing Kotlin-native "quick view" row (title/chartType only, source == null --
        // see CustomViewEntity's class doc) must never leak onto the an.views wire, and must not
        // inflate `_backup.items.views`.
        val nativeQuickView = CustomViewEntity(id = "native-1", title = "Kliky 30D", chartType = "klik|30", createdAt = com.example.data.clock.PhysicalClock.nowMs())
        val pwaView = CustomViewEntity(
            id = "v1", title = "unused", chartType = "unused",
            name = "Weekly completions", source = "completed", group = "week", chart = "bar",
            createdAt = 1000L, updatedAt = 2000L
        )

        val exported = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = com.example.data.local.entities.CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            tasks = emptyList(), rewards = emptyList(), events = emptyList(),
            deletions = emptyList(), lastCron = null,
            views = listOf(nativeQuickView, pwaView)
        )
        val envelope = Json.parseToJsonElement(exported) as JsonObject
        val itemsViews = envelope["_backup"]?.jsonObject?.get("items")?.jsonObject?.get("views")?.jsonPrimitive?.intOrNull
        assertEquals(1, itemsViews)

        val reImported = SerializationEngine.importBackup(exported)
        assertEquals(1, reImported.views.size)
        assertEquals("v1", reImported.views.single().id)
    }

    // -----------------------------------------------------------------------------------------
    // Merge semantics (spec §3 T3.2a "Merge semantics" table): id-keyed mergeCollection(), same
    // "strictly-greater-updatedAt-wins, tie->remote" rule as tasks/rewards/tags -- exercised here
    // via mergeSnapshots (the actual Dropbox-wire entry point), where `an` is TOP-LEVEL (spec §3's
    // syncSubset() table), not nested under `prefs`.
    // -----------------------------------------------------------------------------------------

    private fun parseJsonObj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private fun minimalSnapshotWithView(viewName: String, viewUpdatedAt: Long): JsonObject = parseJsonObj(
        """
        {
          "lastCron": "20260716",
          "deletions": [],
          "char": { "name": "Hero", "lvl": 2, "xp": 10, "gold": 5.0, "updatedAt": 1000 },
          "tasks": [], "rewards": [],
          "an": {
            "views": [
              { "id": "v1", "name": "$viewName", "source": "overview", "group": "day", "chart": "list", "tags": [], "types": [], "metricId": null, "createdAt": 500, "updatedAt": $viewUpdatedAt }
            ],
            "metrics": [
              { "id": "m1", "name": "$viewName-metric", "keyword": "k", "createdAt": 500, "updatedAt": $viewUpdatedAt }
            ]
          }
        }
        """.trimIndent()
    )

    // WP-8.5: ported from the legacy SerializationEngine.mergeSnapshots to the merge core.
    private fun mergeAn(local: JsonObject, remote: JsonObject, remoteDev: String?): JsonObject {
        val now = 1_790_251_200_000L
        val ctx = com.example.data.sync.merge.MergeContext(
            localDev = "devA", remoteDev = remoteDev, futureCeil = now + 120_000L, nowPhysical = now,
            localToday = 20260924, zone = java.time.ZoneId.of("UTC"),
        )
        return com.example.data.sync.merge.OptiSnapshotMerger().merge(null, local, remote, ctx) { }
    }

    @Test
    fun merge_anViews_remoteNewerWins() {
        val merged = mergeAn(minimalSnapshotWithView("LocalName", 1000L), minimalSnapshotWithView("RemoteName", 2000L), "devB")

        val mergedViews = merged["an"]?.jsonObject?.get("views")?.jsonArray!!
        assertEquals(1, mergedViews.size)
        assertEquals("RemoteName", (mergedViews[0] as JsonObject)["name"]?.jsonPrimitive?.content)

        val mergedMetrics = merged["an"]?.jsonObject?.get("metrics")?.jsonArray!!
        assertEquals("RemoteName-metric", (mergedMetrics[0] as JsonObject)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun merge_anViews_localNewerWins() {
        val merged = mergeAn(minimalSnapshotWithView("LocalName", 2000L), minimalSnapshotWithView("RemoteName", 1000L), "devB")

        val mergedViews = merged["an"]?.jsonObject?.get("views")?.jsonArray!!
        assertEquals(1, mergedViews.size)
        assertEquals("LocalName", (mergedViews[0] as JsonObject)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun merge_anViews_tie_withoutRemoteId_goesToRemote_withIds_higherDeviceIdWins() {
        // sync.js:1462-1467: an exact clamped tie uses the device-id tiebreak when the remote
        // id is known, else `luc > ruc ? l : r` (remote).
        val l = minimalSnapshotWithView("LocalName", 2000L)
        val r = minimalSnapshotWithView("RemoteName", 2000L)
        fun name(m: JsonObject) = (m["an"]!!.jsonObject["views"]!!.jsonArray[0] as JsonObject)["name"]!!.jsonPrimitive.content
        assertEquals("RemoteName", name(mergeAn(l, r, null)))
        assertEquals("RemoteName", name(mergeAn(l, r, "devB"))) // "devB" > "devA"
        assertEquals("LocalName", name(mergeAn(l, r, "dev0")))  // "dev0" < "devA"
    }
}
