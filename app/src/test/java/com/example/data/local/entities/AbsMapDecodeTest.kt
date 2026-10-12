package com.example.data.local.entities

import com.example.data.local.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.2 (Wave 1) -- review r2 major 2: `Converters.toCharAbsMap`/`toTaskAbsMap` must decode each
 * device entry independently (ported from Opti's `_absEntry`/`_cAbsEntry`, sync.js:1699-1893,
 * commit 7d5d4f3, which reads every numeric field through `_num` -- `Number(v) || 0`, sync.js:1672
 * -- so a `null`, a string number, or a fractional value never throws, and a non-object per-device
 * entry is simply skipped, never nulling the whole map).
 */
class AbsMapDecodeTest {

    private val converters = Converters()

    @Test
    fun `one bad device entry does not lose every other device's watermark`() {
        val json = """{"good":{"ua":1,"xp":10,"gold":5.5,"mp":2},"bad":"not-an-object"}"""
        val result = converters.toCharAbsMap(json)
        assertEquals(1, result?.size)
        assertEquals(10.0, result?.get("good")?.xp)
        assertNull(result?.get("bad"))
    }

    @Test
    fun `a null field value is treated as 0, matching Opti's _num`() {
        val json = """{"devA":{"ua":null,"xp":null,"gold":null,"mp":null}}"""
        val result = converters.toCharAbsMap(json)
        assertEquals(0L, result?.get("devA")?.ua)
        assertEquals(0.0, result?.get("devA")?.xp)
        assertEquals(0.0, result?.get("devA")?.gold)
        assertEquals(0.0, result?.get("devA")?.mp)
    }

    @Test
    fun `a string number is coerced, matching Opti's Number(v)`() {
        val json = """{"devA":{"ua":"5","xp":"10.5","gold":"2.25","mp":"3"}}"""
        val result = converters.toCharAbsMap(json)
        assertEquals(5L, result?.get("devA")?.ua)
        assertEquals(10.5, result?.get("devA")?.xp)
        assertEquals(2.25, result?.get("devA")?.gold)
        assertEquals(3.0, result?.get("devA")?.mp)
    }

    @Test
    fun `a non-numeric string coerces to 0, matching Number(v) being NaN then falling back`() {
        val json = """{"devA":{"ua":"oops","xp":"oops","gold":1,"mp":0}}"""
        val result = converters.toCharAbsMap(json)
        assertEquals(0L, result?.get("devA")?.ua)
        assertEquals(0.0, result?.get("devA")?.xp)
    }

    @Test
    fun `a fractional xp is preserved, not truncated`() {
        val json = """{"devA":{"ua":1,"xp":12.75,"gold":0,"mp":0}}"""
        val result = converters.toCharAbsMap(json)
        assertEquals(12.75, result?.get("devA")?.xp)
    }

    @Test
    fun `completely malformed json returns null, not a crash`() {
        assertNull(converters.toCharAbsMap("not json at all {{{"))
    }

    @Test
    fun `toTaskAbsMap has the same per-entry resilience`() {
        val json = """{"good":{"ua":1,"cUp":2,"cDown":1},"bad":42}"""
        val result = converters.toTaskAbsMap(json)
        assertEquals(1, result?.size)
        assertEquals(2, result?.get("good")?.cUp)
        assertNull(result?.get("bad"))
    }

    @Test
    fun `toTaskAbsMap coerces string numbers and nulls like Opti's _num`() {
        val json = """{"devA":{"ua":"7","cUp":null,"cDown":"3"}}"""
        val result = converters.toTaskAbsMap(json)
        assertEquals(7L, result?.get("devA")?.ua)
        assertEquals(0, result?.get("devA")?.cUp)
        assertEquals(3, result?.get("devA")?.cDown)
    }
}
