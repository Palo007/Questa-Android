package com.example.data.sync.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * WP-8.5 -- every Opti merge() call in `fixtures/opti/G2_G3_core` (tools/gen_opti_fixtures.mjs,
 * set G2_G3_core: the G2/G3 matrix plus the core-only cases, run with a `syncDeviceId()` stub
 * so the cAbs watermarks and task-event device ids are real, in 4 time zones) is replayed
 * through [OptiSnapshotMerger]. The merged state AND the captured logEvent() payloads, in
 * order, must equal Opti's.
 */
class OptiMergeVectorsTest {

    data class Vector(val name: String, val zone: ZoneId, val now: Long, val input: JsonObject, val result: JsonObject, val events: JsonArray)

    companion object {
        fun coreDir(): File = File(MergeTestSupport.repoRoot(), "fixtures/opti/G2_G3_core")

        fun load(f: File): Vector {
            val j = MergeTestSupport.parse(f.readText(Charsets.UTF_8))
            return Vector(
                name = f.name,
                zone = ZoneId.of(j["tz"]!!.jsonPrimitive.content),
                now = j["now"]!!.jsonPrimitive.longOrNull!!,
                input = j["input"]!!.jsonObject,
                result = j["result"]!!.jsonObject,
                events = j["events"] as JsonArray,
            )
        }

        fun all(): List<Vector> = coreDir().listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.map(::load)

        fun ctxFor(v: Vector, throttle: ConflictThrottle = ConflictThrottle()): MergeContext {
            val i = v.input
            fun str(k: String): String? = (i[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val savedAt: Long? = (i["localSavedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
            return MergeContext(
                localDev = str("localDeviceId")!!,
                remoteDev = str("remoteDeviceId"),
                futureCeil = v.now + ClampPolicy.MAX_FUTURE_SKEW_MS,
                nowPhysical = v.now,
                localToday = OptiSnapshotMerger.dayStampOf(v.now, v.zone),
                localSavedAt = savedAt,
                zone = v.zone,
                throttle = throttle,
            )
        }

        fun run(v: Vector): Pair<JsonObject, List<ConflictEvent>> {
            val events = ArrayList<ConflictEvent>()
            val i = v.input
            val out = OptiSnapshotMerger().merge(
                i["base"] as? JsonObject, i["local"]!!.jsonObject, i["remote"]!!.jsonObject, ctxFor(v)
            ) { events += it }
            return out to events
        }

        fun stable(e: JsonElement?): String = Js.stableStringify(e)
    }

    @Test
    fun `every G2_G3_core vector matches Opti -- merged state and conflict events`() {
        val vectors = all()
        assertTrue("expected the G2_G3_core set (4 TZs), got ${vectors.size}", vectors.size >= 400)
        val bad = ArrayList<String>()
        for (v in vectors) {
            val (out, events) = try { run(v) } catch (e: Exception) { bad += "${v.name}: threw $e"; continue }
            if (stable(out) != stable(v.result)) bad += "${v.name}: state\n  kotlin=${stable(out)}\n  opti  =${stable(v.result)}"
            val ke = stable(JsonArray(events.map { it.toJson() }))
            if (ke != stable(v.events)) bad += "${v.name}: events\n  kotlin=$ke\n  opti  =${stable(v.events)}"
        }
        assertTrue("${bad.size}/${vectors.size} vectors differ:\n" + bad.take(15).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the cAbs watermark path is exercised with real Opti output`() {
        val v = load(File(coreDir(), "core_cabs_round0.UTC.json"))
        val task = (v.result["tasks"] as JsonArray)[0].jsonObject
        assertTrue("Opti wrote cAbs for both devices", task["cAbs"]!!.jsonObject.keys == setOf("devA", "devB"))
        val (out, _) = run(v)
        assertEquals(stable(task), stable((out["tasks"] as JsonArray)[0]))
    }

    @Test
    fun `merged key order is merge()'s object literal order (the upload body order)`() {
        val v = all().first { it.name.startsWith("g2_polarity_k1.") }
        val (out, _) = run(v)
        assertEquals(
            listOf("tasks", "rewards", "tags", "devices", "an", "pause", "history", "charHistory", "monthlyBackups", "lastCron", "deletions", "char"),
            out.keys.toList(),
        )
        assertEquals(listOf("views", "metrics"), out["an"]!!.jsonObject.keys.toList())
    }
}
