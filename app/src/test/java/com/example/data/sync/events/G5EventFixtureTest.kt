package com.example.data.sync.events

import com.example.data.sync.EventSyncEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * WP-8.7 -- 100% of G5 (fixtures/opti/G5, WP-8.0). The inputs are the four records
 * `tools/gen_opti_fixtures.mjs` genG5 builds (FIXED_NOW_MS, a 400-day age limit, the legacy Set
 * form `new Set([records[0].uid])`).
 */
class G5EventFixtureTest {
    private val fixedNow = 1790251200000L
    private val myDev = "fx-device-0001"

    private val records: List<JsonObject> = listOf(
        obj("uid" to "$myDev-a-1", "dev" to myDev, "ts" to 1788220800000L, "kind" to "habitTap"),
        obj("uid" to "$myDev-a-1", "dev" to myDev, "ts" to 1788220801000L, "kind" to "habitTap"),
        obj("uid" to "other-dev-b-2", "dev" to "other-dev", "ts" to 1788220799000L, "kind" to "rep-dedup", "source" to "rep-a1"),
        obj("uid" to "$myDev-c-3", "dev" to myDev, "ts" to 1790812799999L, "kind" to "complete", "synthetic" to true, "repCounted" to false, "inferred" to true),
    )

    private fun obj(vararg kv: Pair<String, Any>): JsonObject = buildJsonObject {
        kv.forEach { (k, v) ->
            put(k, when (v) { is String -> JsonPrimitive(v); is Long -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> error("x") })
        }
    }

    private fun g5(name: String): JsonElement {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "fixtures/opti/G5").isDirectory) dir = dir.parentFile
        return Json.parseToJsonElement(File(dir!!, "fixtures/opti/G5/$name").readText())
    }

    @Test
    fun monthKeysAndRanges() {
        val exp = g5("month_keys_and_ranges.json").jsonObject
        val keys = records.map { EventSyncEngine.evtMonthKey(it["ts"]!!.jsonPrimitive.long) }
        assertEquals(exp["monthKeys"]!!.jsonArray.map { it.jsonPrimitive.content }, keys)
        val ranges = keys.toSortedSet().map { k ->
            val (from, to) = EventSyncEngine.evtMonthRange(k)
            k to (from to to)
        }
        val expRanges = exp["monthRanges"]!!.jsonArray.map {
            val o = it.jsonObject
            val r = o["range"]!!.jsonObject
            o["key"]!!.jsonPrimitive.content to (r["from"]!!.jsonPrimitive.long to r["to"]!!.jsonPrimitive.long)
        }
        assertEquals(expRanges, ranges)
    }

    @Test
    fun ownMonthRecords() {
        val own = records.map { EvtTestData.entityOf(it) }.filter { EvtUploadPolicy.ownMonthRecord(it, myDev) }
        assertEquals(g5("own_month_records.json").jsonArray.toList(), own.map { EvtTestData.jsonOf(it) })
    }

    @Test
    fun incomingFilter_legacySet() {
        val out = EvtDedup.incomingFilter(records, setOf(records[0]["uid"]!!.jsonPrimitive.content), null, myDev, fixedNow, 400L * 24 * 3600000)
        assertEquals(g5("incoming_filter.json").jsonArray.toList(), out)
        // The pre-8.7 entry point returns the same.
        assertEquals(out, EventSyncEngine.evtIncomingFilter(records, setOf(records[0]["uid"]!!.jsonPrimitive.content), myDev, fixedNow, 400L * 24 * 3600000))
    }

    @Test
    fun incomingSig() {
        assertEquals(g5("incoming_sig.json").jsonArray.map { it.jsonPrimitive.content }, records.map { EvtDedup.incomingSig(it) })
    }
}
