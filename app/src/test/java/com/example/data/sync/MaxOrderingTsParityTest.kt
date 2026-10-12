package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3' / KT-51 -- [RoundMath.maxOrderingTs] reads every stamp the way Opti `_maxOrderingTs`
 * does: `Number(x)||0` (Opti 7d5d4f3:sync.js:2405-2426).
 *
 * The vectors in `fixtures/opti/KT51/max_ordering_ts.json` come from running Opti's real
 * function (`node tools/gen_kt51_fixture.mjs`). `opti` is JS `String(mx)`.
 *
 * Kotlin returns a Long HLC, so a fractional result is truncated and +Infinity becomes
 * Long.MAX_VALUE. That Long narrowing is [DEFERRED_OPEN_QUESTION] (Long HLC type), and
 * [expectedLong] applies it to the Opti value; only the input coercion is under test here.
 */
class MaxOrderingTsParityTest {

    private fun locateFixture(rel: String): File {
        var dir: File? = File(".").absoluteFile
        var attempts = 0
        while (dir != null && attempts < 8) {
            val candidate = File(dir, rel)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
            attempts++
        }
        throw IllegalStateException("Could not locate $rel by walking up from ${File(".").absoluteFile}.")
    }

    /** [DEFERRED_OPEN_QUESTION] Long HLC: Opti's Double `mx` narrowed the way RoundMath does. */
    private fun expectedLong(opti: String): Long {
        if (opti == "Infinity") return Long.MAX_VALUE
        val d = opti.toDouble()
        return if (d >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else d.toLong()
    }

    @Test
    fun numCoercion_matchesOptiNumber() {
        val root = Json.parseToJsonElement(
            locateFixture("fixtures/opti/KT51/max_ordering_ts.json").readText(Charsets.UTF_8)
        ).jsonObject
        val vectors = root.getValue("vectors").jsonArray
        assertTrue("fixture must hold vectors", vectors.size >= 30)

        val mismatches = mutableListOf<String>()
        for (v in vectors) {
            val o = v.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val state = o.getValue("state") as JsonObject
            val opti = o.getValue("opti").jsonPrimitive.content
            val want = expectedLong(opti)
            val got = RoundMath.maxOrderingTs(state)
            if (got != want) mismatches += "$name: state=$state opti=$opti want=$want got=$got"
        }
        assertTrue(
            "RoundMath.maxOrderingTs differs from Opti _maxOrderingTs on ${mismatches.size} vector(s):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty()
        )
    }
}
