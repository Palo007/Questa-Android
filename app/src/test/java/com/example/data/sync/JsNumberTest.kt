package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * WP-8.3 (PX-22, SY-40, R9): JsNumber.format and CanonicalJson.stringify against node 24
 * (`n.toString()` / `JSON.stringify`) outputs, recorded by hand from
 * `node -e` for Opti's JSON engine (V8).
 */
class JsNumberTest {

    private val nodeCases: List<Pair<Double, String>> = listOf(
        0.1 + 0.2 to "0.30000000000000004",
        1e21 to "1e+21",
        9.999e20 to "999900000000000000000",
        5e-7 to "5e-7",
        1e-6 to "0.000001",
        1e-7 to "1e-7",
        1.7976931348623157e308 to "1.7976931348623157e+308",
        4.9e-324 to "5e-324",
        2.220446049250313e-16 to "2.220446049250313e-16",
        123456789012345680000.0 to "123456789012345680000",
        1152921504606846976.0 to "1152921504606847000",
        9007199254740994.0 to "9007199254740994",
        -1.1584629606842927 to "-1.1584629606842927",
        87.5 to "87.5",
        100.0 to "100",
        1e300 to "1e+300",
        4.35 to "4.35",
        0.000123 to "0.000123",
        1.5e-10 to "1.5e-10",
        9007199254740993.0 to "9007199254740992",
        1.5e-323 to "1.5e-323",
        1.0 / 3 to "0.3333333333333333",
        2.0 / 3 to "0.6666666666666666",
        1e23 to "1e+23",
        8.41e21 to "8.41e+21",
        5e-310 to "5e-310",
        1.2345678901234567e-300 to "1.2345678901234568e-300",
        -0.0 to "0",
        0.0 to "0",
        -1.0 to "-1",
        9007199254740991.0 to "9007199254740991",
    )

    @Test
    fun formatMatchesNode() {
        for ((d, expected) in nodeCases) assertEquals("format($d)", expected, JsNumber.format(d))
    }

    /** The found digits must round-trip, and never be longer than the JVM's own shortest digits. */
    @Test
    fun randomDoublesRoundTripAndAreShortest() {
        val rnd = Random(8_3)
        repeat(20_000) {
            val d = Double.fromBits(rnd.nextLong())
            if (d.isNaN() || d.isInfinite()) return@repeat
            val s = JsNumber.format(d)
            assertEquals(s, d, s.toDouble(), 0.0)
            // Desktop JDK 17+ Double.toString gives a round-tripping digit string; ours is never longer.
            val jvmDigits = java.math.BigDecimal(Math.abs(d).toString()).stripTrailingZeros().unscaledValue().toString().length
            val ourDigits = java.math.BigDecimal(s.removePrefix("-")).stripTrailingZeros().unscaledValue().toString().length
            assert(ourDigits <= jvmDigits) { "$d: $s longer than JVM ${Math.abs(d)}" }
        }
    }

    @Test
    fun stringifyNormalizesParsedNumberLiterals() {
        val parsed = Json.parseToJsonElement("""{"a":1.0,"b":1E21,"c":9007199254740993,"d":-0,"e":0.50,"f":12,"g":-7}""")
        assertEquals(
            """{"a":1,"b":1e+21,"c":9007199254740992,"d":0,"e":0.5,"f":12,"g":-7}""",
            CanonicalJson.stringify(parsed)
        )
        val nan = buildJsonObject { put("x", JsonPrimitive(Double.NaN)); put("y", JsonUnquotedLiteral("Infinity")) }
        assertEquals("""{"x":null,"y":null}""", CanonicalJson.stringify(nan))
    }

    @Test
    fun arrayIndexKeysComeFirstAscending() {
        val obj = Json.parseToJsonElement(
            """{"b":1,"10":2,"a":3,"2":4,"-1":5,"01":6,"4294967294":7,"4294967295":8,"":9,"Z":10,"é":11}"""
        )
        assertEquals(
            """{"2":4,"10":2,"4294967294":7,"b":1,"a":3,"-1":5,"01":6,"4294967295":8,"":9,"Z":10,"é":11}""",
            CanonicalJson.stringify(obj)
        )
        assertEquals(
            """{"2":4,"10":2,"4294967294":7,"":9,"-1":5,"01":6,"4294967295":8,"Z":10,"a":3,"b":1,"é":11}""",
            StableJson.stableStringify(obj)
        )
    }

    @Test
    fun loneSurrogatesAreEscapedPairsAreRaw() {
        val obj = buildJsonObject { put("s", JsonPrimitive("a\uD800b\uDC00c😀d \u0007\u007f/")) }
        assertEquals("{\"s\":\"a\\ud800b\\udc00c😀d \\u0007\u007f/\"}", CanonicalJson.stringify(obj))
    }

    @Test
    fun prettyMatchesJsonStringifyIndent2() {
        val obj = Json.parseToJsonElement("""{"a":[],"b":{},"c":[1,{"d":"x"}],"2":true}""")
        assertEquals(
            "{\n  \"2\": true,\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    1,\n    {\n      \"d\": \"x\"\n    }\n  ]\n}",
            CanonicalJson.stringifyPretty(obj)
        )
    }
}
