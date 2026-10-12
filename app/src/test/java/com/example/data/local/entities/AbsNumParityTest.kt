package com.example.data.local.entities

import com.example.data.local.Converters
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP-8.22-E (WP-8.2 follow-up): every numeric field of `char.abs[dev]` / `task.cAbs[dev]` is read
 * through Opti's `_num` (sync.js:1672, commit 7d5d4f3: `Number(v) || 0`) inside `_absEntry`
 * (sync.js:1705-1712) and `_cAbsEntry` (sync.js:1865-1872). These vectors are the JS results of
 * `Number(v) || 0` (then `Math.max(0, ...)` for xp/gold/mp/cUp/cDown).
 */
class AbsNumParityTest {

    private val converters = Converters()

    private fun xpOf(raw: String): Double =
        converters.toCharAbsMap("""{"d":{"ua":1,"xp":$raw}}""")!!["d"]!!.xp

    @Test
    fun `string xp values follow JS Number(v) or 0`() {
        val vectors = listOf(
            "\"NaN\"" to 0.0,
            "\"\"" to 0.0,
            "\"   \"" to 0.0,
            "\"abc\"" to 0.0,
            "\"Infinity\"" to Double.POSITIVE_INFINITY,
            "\"+Infinity\"" to Double.POSITIVE_INFINITY,
            "\"-Infinity\"" to 0.0, // Math.max(0, -Infinity)
            "\"infinity\"" to 0.0,
            "\" 5 \"" to 5.0,
            "\"\\t7\\n\"" to 7.0,
            "\"0x10\"" to 16.0,
            "\"0b101\"" to 5.0,
            "\"0o17\"" to 15.0,
            "\"1e3\"" to 1000.0,
            "\".5\"" to 0.5,
            "\"5.\"" to 5.0,
            "\"+2.5\"" to 2.5,
            "\"-3\"" to 0.0, // Math.max(0, -3)
            "\"5f\"" to 0.0, // Java parseDouble accepts "5f"; JS does not
            "\"5d\"" to 0.0,
            "\"1_000\"" to 0.0,
            "\"12px\"" to 0.0,
            "\"true\"" to 0.0, // the STRING "true" is NaN in JS, not 1
            "\"null\"" to 0.0,
        )
        for ((raw, expected) in vectors) {
            assertEquals("xp=$raw", expected, xpOf(raw), 0.0)
        }
    }

    @Test
    fun `non-string xp values follow JS Number(v) or 0`() {
        val vectors = listOf(
            "true" to 1.0,
            "false" to 0.0,
            "null" to 0.0,
            "[]" to 0.0,
            "[\"4\"]" to 4.0,
            "[[6]]" to 6.0,
            "[1,2]" to 0.0,
            "[null]" to 0.0,
            "{}" to 0.0,
            "2.25" to 2.25,
        )
        for ((raw, expected) in vectors) {
            assertEquals("xp=$raw", expected, xpOf(raw), 0.0)
        }
    }

    @Test
    fun `string NaN in ua and in cAbs counters becomes 0`() {
        val c = converters.toCharAbsMap("""{"d":{"ua":"NaN","xp":1,"gold":"NaN","mp":" 3 "}}""")!!["d"]!!
        assertEquals(0L, c.ua)
        assertEquals(0.0, c.gold, 0.0)
        assertEquals(3.0, c.mp, 0.0)

        val t = converters.toTaskAbsMap("""{"d":{"ua":" 9 ","cUp":"NaN","cDown":"abc"}}""")!!["d"]!!
        assertEquals(9L, t.ua)
        assertEquals(0, t.cUp)
        assertEquals(0, t.cDown)
    }

    @Test
    fun `an array device entry is an object to JS, so it decodes as all zeros`() {
        // `typeof [] === "object"`, so `_absEntry` / `_cAbsEntry` do not return null for it.
        assertEquals(CharAbsEntry(0L, 0.0, 0.0, 0.0), converters.toCharAbsMap("""{"d":[1,2]}""")!!["d"])
        assertEquals(TaskAbsEntry(0L, 0, 0), converters.toTaskAbsMap("""{"d":[]}""")!!["d"])
    }
}
