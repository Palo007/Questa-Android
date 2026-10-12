package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** WP-8.3 (PX-21, SY-14): deepEqual is key-order independent; 1,000 shuffles keep it true. */
class StableJsonPropertyTest {

    private fun fixture(rel: String): String {
        val f = File("../fixtures/opti/$rel").takeIf { it.isFile } ?: File("fixtures/opti/$rel")
        return f.readText(Charsets.UTF_8)
    }

    private fun shuffle(e: JsonElement, rnd: Random): JsonElement = when (e) {
        is JsonObject -> JsonObject(LinkedHashMap<String, JsonElement>().also { m ->
            e.entries.shuffled(rnd).forEach { (k, v) -> m[k] = shuffle(v, rnd) }
        })
        is JsonArray -> JsonArray(e.map { shuffle(it, rnd) }) // array order is data, never shuffled
        else -> e
    }

    @Test
    fun thousandKeyShufflesKeepDeepEqualTrue() {
        val original = Json.parseToJsonElement(fixture("G1/typical/state.json"))
        val stable = fixture("G1/typical/state.stable.json")
        val rnd = Random(1000)
        repeat(1_000) {
            val shuffled = shuffle(original, rnd)
            assertTrue(StableJson.deepEqual(original, shuffled))
            assertEquals(stable, StableJson.stableStringify(shuffled))
        }
    }

    @Test
    fun stableStringifyEqualsG1StableFixtures() {
        for (name in listOf("fresh", "typical", "pause_abs_cAbs_doneDay_cResetOn")) {
            val parsed = Json.parseToJsonElement(fixture("G1/$name/state.json"))
            assertEquals(name, fixture("G1/$name/state.stable.json"), StableJson.stableStringify(parsed))
        }
    }

    @Test
    fun valueAndArrayOrderDifferencesAreNotEqual() {
        assertFalse(StableJson.deepEqual(Json.parseToJsonElement("[1,2]"), Json.parseToJsonElement("[2,1]")))
        assertFalse(StableJson.deepEqual(Json.parseToJsonElement("""{"a":1}"""), Json.parseToJsonElement("""{"a":"1"}""")))
        assertTrue(StableJson.deepEqual(Json.parseToJsonElement("""{"a":1.0}"""), Json.parseToJsonElement("""{"a":1}""")))
        assertTrue(StableJson.deepEqual(null, null))
        assertFalse(StableJson.deepEqual(null, JsonNull))
    }
}
