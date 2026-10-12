package com.example.data.sync.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * WP-8.5 -- sync.js `dayStampOf` / `doneDayOf` against Opti's own output in 4 time zones
 * (fixtures/opti/G9, WP-8.0), plus the frozen-frame rules the merge relies on.
 */
class DayStampTzTest {

    private val merger = OptiSnapshotMerger()

    @Test
    fun `G9 dayStampOf and doneDayOf match Opti in all four zones`() {
        val dir = File(MergeTestSupport.repoRoot(), "fixtures/opti/G9")
        val files = dir.listFiles { f -> f.name.startsWith("daystamps.") }!!
        assertEquals(4, files.size)
        var rows = 0
        for (f in files) {
            for (row in MergeTestSupport.json.parseToJsonElement(f.readText()) as JsonArray) {
                val o = row.jsonObject
                val zone = ZoneId.of(o["tz"]!!.jsonPrimitive.content)
                val ms = o["ms"]!!.jsonPrimitive.long
                assertEquals("${f.name} $ms", o["dayStampOf"]!!.jsonPrimitive.int, OptiSnapshotMerger.dayStampOf(ms, zone))
                val t = kotlinx.serialization.json.JsonObject(mapOf("doneAt" to JsonPrimitive(ms)))
                assertEquals("${f.name} $ms doneDayOf", o["doneDayOf"]!!.jsonPrimitive.int, merger.doneDayOf(t, zone))
                rows++
            }
        }
        assertEquals(16, rows)
    }

    @Test
    fun `dayStampOf of a falsy ms is 0`() {
        assertEquals(0, OptiSnapshotMerger.dayStampOf(0L, ZoneId.of("UTC")))
    }

    @Test
    fun `doneDayOf prefers the frozen doneDay over the merging zone's reading of doneAt`() {
        // 2026-09-24T12:00Z is 2026-09-25 in Auckland; a frozen doneDay wins over that.
        val t = kotlinx.serialization.json.JsonObject(mapOf("doneAt" to JsonPrimitive(1_790_251_200_000L), "doneDay" to JsonPrimitive(20260924)))
        assertEquals(20260924, merger.doneDayOf(t, ZoneId.of("Pacific/Auckland")))
        val legacy = kotlin.collections.mapOf("doneAt" to JsonPrimitive(1_790_251_200_000L))
        assertEquals(20260925, merger.doneDayOf(kotlinx.serialization.json.JsonObject(legacy), ZoneId.of("Pacific/Auckland")))
    }

    @Test
    fun `mergedLastCron ignores a remote day in this device's future`() {
        assertEquals(20260924, merger.mergedLastCron(20260924, 20270101, 20260924))
        assertEquals(20260924, merger.mergedLastCron(20260924, 20260101, 20260924))
        assertEquals(20270101, merger.mergedLastCron(20270101, 20260924, 20260924))
        assertEquals(20260924, merger.mergedLastCron(20260923, 20260924, 20260924))
    }

    @Test
    fun `the core-vector set covers every zone`() {
        val zones = OptiMergeVectorsTest.all().map { it.zone.id }.toSet()
        assertTrue(zones.containsAll(listOf("UTC", "Europe/Bratislava", "Pacific/Auckland", "America/Los_Angeles")))
    }
}
