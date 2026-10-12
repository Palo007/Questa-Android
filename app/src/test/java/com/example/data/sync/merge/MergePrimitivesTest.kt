package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.obj
import com.example.data.sync.merge.MergeTestSupport.stable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Targeted WP-8.4 vectors taken from the sync.js comments at 7d5d4f3. */
class MergePrimitivesTest {

    private val ctx = MergeContext("devA", "devB", futureCeil = 10_000L, nowPhysical = 9_880L, localToday = 20260924)

    // ---- ClampPolicy (sync.js :1225-1245; SY-25)

    @Test fun uaRaw_falls_through_updatedAt_zero_to_createdAt() {
        assertEquals(500L, ClampPolicy.uaRaw(obj("""{"updatedAt":0,"createdAt":500}""")))
        assertEquals(700L, ClampPolicy.uaRaw(obj("""{"updatedAt":"700","createdAt":500}""")))
        assertEquals(0L, ClampPolicy.uaRaw(obj("""{"updatedAt":"x"}""")))
        assertEquals(0L, ClampPolicy.uaRaw(obj("{}")))
    }

    @Test fun ua_and_ca_clamp_future_to_zero_but_uaRaw_does_not() {
        val fut = obj("""{"updatedAt":10001,"createdAt":10001}""")
        assertEquals(10001L, ClampPolicy.uaRaw(fut))
        assertEquals(0L, ClampPolicy.ua(fut, ctx))
        assertEquals(0L, ClampPolicy.ca(fut, ctx))
        assertEquals(10000L, ClampPolicy.ua(obj("""{"updatedAt":10000}"""), ctx))
        assertEquals(0L, ClampPolicy.clampFuture(10001L, ctx))
        assertEquals(10000L, ClampPolicy.clampFuture(10000L, ctx))
        assertEquals(0L, ClampPolicy.ca(obj("""{"updatedAt":5}"""), ctx))
    }

    // ---- PauseMerge (sync.js :2178-2191)

    @Test fun pause_tie_goes_to_local_and_at_is_raw_max() {
        val l = obj("""{"paused":true,"pausedDays":[3],"at":100}""")
        val r = obj("""{"paused":false,"pausedDays":[1,3,"x",2],"at":100}""")
        assertEquals("""{"at":100,"paused":true,"pausedDays":[1,2,3]}""", stable(PauseMerge.merge(null, l, r, 10_000L)))
    }

    @Test fun pause_future_skewed_at_loses_the_choice_but_is_kept_raw() {
        val l = obj("""{"paused":true,"pausedDays":[],"at":20000}""")
        val r = obj("""{"paused":false,"pausedDays":[],"at":50}""")
        assertEquals("""{"at":20000,"paused":false,"pausedDays":[]}""", stable(PauseMerge.merge(obj("{}"), l, r, 10_000L)))
    }

    @Test fun pause_absent_everywhere_is_the_default_object() {
        assertEquals("""{"at":0,"paused":false,"pausedDays":[]}""", stable(PauseMerge.merge(null, null, null, 10_000L)))
    }

    @Test fun pause_union_includes_base_and_keeps_last_7() {
        val b = obj("""{"pausedDays":[1,2]}""")
        val l = obj("""{"pausedDays":[3,4,5],"at":2}""")
        val r = obj("""{"pausedDays":[6,7,8,8],"at":1,"paused":1}""")
        assertEquals("""{"at":2,"paused":false,"pausedDays":[2,3,4,5,6,7,8]}""", stable(PauseMerge.merge(b, l, r, 10_000L)))
    }

    // ---- CharTiebreak (sync.js :2222-2240, :2266-2320)

    private val base = obj("""{"hp":40,"gold":10,"xp":0,"updatedAt":100}""")

    @Test fun untouched_default_yields_to_a_real_character_either_side() {
        val fresh = obj("""{"lvl":1,"xp":0,"gold":0,"hp":50,"maxHp":50,"updatedAt":999}""")
        val real = obj("""{"lvl":3,"xp":5,"gold":1,"updatedAt":1}""")
        assertEquals(Side.REMOTE, CharTiebreak.oneSided(base, fresh, real))
        assertEquals(Side.LOCAL, CharTiebreak.oneSided(base, real, fresh))
    }

    @Test fun one_sided_guard_uses_raw_updatedAt() {
        assertEquals(Side.BASE, CharTiebreak.oneSided(base, base, base))
        val newer = obj("""{"hp":30,"gold":10,"xp":0,"updatedAt":99999999}""")
        assertEquals(Side.REMOTE, CharTiebreak.oneSided(base, base, newer)) // never clamped (site 4 of 4)
        val older = obj("""{"hp":30,"gold":10,"xp":0,"updatedAt":50}""")
        assertEquals(Side.LOCAL, CharTiebreak.oneSided(base, base, older))
        assertEquals(Side.LOCAL, CharTiebreak.oneSided(base, older, base))
        assertEquals(Side.BOTH_CHANGED, CharTiebreak.oneSided(base, older, newer))
    }

    @Test fun k4_tiebreak_only_on_exact_clamped_tie() {
        val l = obj("""{"updatedAt":500}"""); val r = obj("""{"updatedAt":400}""")
        assertEquals(Side.LOCAL, CharTiebreak.bothChanged(l, r, "devA", "devZ", 10_000L))
        assertEquals(Side.REMOTE, CharTiebreak.bothChanged(r, l, "devZ", "devA", 10_000L))
        val t = obj("""{"updatedAt":500}""")
        assertEquals(Side.REMOTE, CharTiebreak.bothChanged(t, t, "devA", "devB", 10_000L))
        assertEquals(Side.LOCAL, CharTiebreak.bothChanged(t, t, "devB", "devA", 10_000L))
        assertEquals(Side.LOCAL, CharTiebreak.bothChanged(t, t, "devA", null, 10_000L))
        assertEquals(Side.LOCAL, CharTiebreak.bothChanged(t, t, "devA", "devA", 10_000L))
        // a future-skewed remote clamps to 0 and cannot win
        assertEquals(Side.LOCAL, CharTiebreak.bothChanged(l, obj("""{"updatedAt":20000}"""), "devA", "devZ", 10_000L))
    }

    // ---- CharAccumulate (sync.js :1783-1834)

    @Test fun accumulate_preconditions_return_null() {
        val l = obj("""{"gold":15,"xp":0}"""); val r = obj("""{"gold":12,"xp":0}""")
        assertNull(CharAccumulate.accumulate(null, l, r, "a", "b"))
        assertNull(CharAccumulate.accumulate(JsonObject(emptyMap()), l, r, "a", "b"))
        assertNull(CharAccumulate.accumulate(base, obj("""{"lvl":1,"xp":150}"""), r, "a", "b")) // non-canonical
        assertNull(CharAccumulate.accumulate(base, l, r, "a", "a"))
        assertNull(CharAccumulate.accumulate(base, l, r, "a", null))
        assertNull(CharAccumulate.accumulate(base, base, base, "a", "b")) // no numeric delta
    }

    @Test fun accumulate_sums_totals_relevels_and_writes_abs() {
        val l = obj("""{"lvl":1,"xp":100,"gold":10.123,"updatedAt":7}""")
        val r = obj("""{"lvl":1,"xp":80,"gold":12,"mp":2,"updatedAt":"8"}""")
        val acc = CharAccumulate.accumulate(base, l, r, "devA", "devB")!!
        assertEquals(2, acc.lvl); assertEquals(30.0, acc.xp, 0.0)
        assertEquals(12.12, acc.gold, 0.0) // +gold.toFixed(2): node gives 12.12
        assertEquals(2.0, acc.mp, 0.0)
        assertEquals("""{"devA":{"gold":10.123,"mp":0,"ua":7,"xp":100},"devB":{"gold":12,"mp":2,"ua":8,"xp":80}}""", stable(acc.abs))
        val w = CharAccumulate.overlay(obj("""{"name":"n","lvl":1,"xp":80,"hp":3}"""), acc)
        assertEquals(listOf("name", "lvl", "xp", "hp", "gold", "mp", "abs"), w.keys.toList())
    }

    @Test fun gold_can_fall_but_earnings_floor_holds() {
        val b = obj("""{"lvl":1,"xp":10,"gold":10}""")
        val acc = CharAccumulate.accumulate(b, obj("""{"lvl":1,"xp":10,"gold":2}"""), obj("""{"lvl":1,"xp":20,"gold":10}"""), "a", "b")!!
        assertEquals(2.0, acc.gold, 0.0)
        assertEquals(20.0, acc.xp, 0.0)
    }

    @Test fun abs_map_enumerates_integer_keys_first_like_js() {
        val l = obj("""{"xp":5,"gold":0,"abs":{"zz":{"ua":1,"xp":1},"10":{"ua":1},"2":{"ua":1}}}""")
        val acc = CharAccumulate.accumulate(base, l, obj("""{"xp":6,"gold":0}"""), "devA", "7")!!
        assertEquals(listOf("2", "7", "10", "zz", "devA"), acc.abs.keys.toList())
    }

    // ---- CounterAccumulate (sync.js :1847-2023)

    private fun t(json: String) = obj(json)

    @Test fun counters_absent_returns_winner_untouched() {
        val w = t("""{"id":"t1","type":"todo"}""")
        assertSame(w, CounterAccumulate.accumulate(null, w, t("""{"id":"t1"}"""), w, "a", "b", 20260924))
    }

    @Test fun legacy_both_below_base_means_both_reset() {
        val got = CounterAccumulate.accumulate(t("""{"cUp":10}"""), t("""{"cUp":6}"""), t("""{"cUp":3}"""), t("""{"cUp":6}"""), null, "b", 20260924)
        assertEquals(9.0, MergeTestSupport.num(got, "cUp"), 0.0)
        assertTrue(!got.containsKey("cAbs") && !got.containsKey("cDown"))
    }

    @Test fun markers_make_deltas_signed_so_a_lowered_tally_propagates() {
        val b = t("""{"cUp":10,"cResetOn":20260901}""")
        val one = CounterAccumulate.accumulate(b, t("""{"cUp":8,"cResetOn":20260901}"""), t("""{"cUp":10,"cResetOn":20260901}"""), b, "a", "b", 20260924)
        assertEquals(8.0, MergeTestSupport.num(one, "cUp"), 0.0)
        val both = CounterAccumulate.accumulate(b, t("""{"cUp":8,"cResetOn":20260901}"""), t("""{"cUp":7,"cResetOn":20260901}"""), b, "a", "b", 20260924)
        assertEquals(5.0, MergeTestSupport.num(both, "cUp"), 0.0)
        // local reset since base: its whole value is new taps, base no longer carried
        val reset = CounterAccumulate.accumulate(b, t("""{"cUp":2,"cResetOn":20260924}"""), t("""{"cUp":12,"cResetOn":20260901}"""), b, "a", "b", 20260924)
        assertEquals(4.0, MergeTestSupport.num(reset, "cUp"), 0.0)
        assertEquals(20260924.0, MergeTestSupport.num(reset, "cResetOn"), 0.0)
    }

    @Test fun future_cResetOn_is_dropped_before_the_max() {
        val b = t("""{"cUp":0,"cResetOn":20260901}""")
        val got = CounterAccumulate.accumulate(b, t("""{"cUp":3,"cResetOn":20260920}"""), t("""{"cUp":2,"cResetOn":20270101}"""), b, "a", "b", 20260924)
        assertEquals(20260920.0, MergeTestSupport.num(got, "cResetOn"), 0.0)
        val none = CounterAccumulate.accumulate(b, t("""{"cUp":3}"""), t("""{"cUp":2,"cResetOn":20270101}"""), b, "a", "b", 20260924)
        assertEquals(20260901.0, MergeTestSupport.num(none, "cResetOn"), 0.0)
    }

    @Test fun cAbs_is_written_per_device_with_raw_ua() {
        val got = CounterAccumulate.accumulate(t("""{"cUp":0}"""), t("""{"cUp":5,"updatedAt":99999999999}"""), t("""{"cUp":3,"cDown":1,"createdAt":4}"""),
            t("""{"id":"x"}"""), "devA", "devB", 20260924)
        assertEquals("""{"cAbs":{"devA":{"cDown":0,"cUp":5,"ua":99999999999},"devB":{"cDown":1,"cUp":3,"ua":4}},"cDown":1,"cUp":8,"id":"x"}""", stable(got))
        assertEquals(listOf("id", "cUp", "cDown", "cAbs"), got.keys.toList())
    }

    // ---- device ids: raw for accumulate (:2248), resolved _ld/_rd for the tiebreak (:2283-2286)

    @Test fun merge_context_remote_dev_is_nullable() {
        // production: remoteDeviceId = remote.deviceId || null (sync.js :2453, :2537)
        val c = MergeContext("devA", null, futureCeil = 1L, nowPhysical = 1L, localToday = 20260924)
        assertNull(c.remoteDev)
    }

    @Test fun char_null_remote_id_skips_accumulate_but_tiebreak_uses_devices_fallback() {
        val b = obj("""{"char":{"hp":40,"gold":10,"xp":0,"updatedAt":100}}""")
        val l = obj("""{"char":{"hp":40,"gold":20,"xp":10,"lvl":3,"updatedAt":500}}""")
        val r = obj("""{"char":{"hp":38,"gold":22,"xp":12,"lvl":3,"updatedAt":500},"devices":[{"id":"devA"},{"name":"x"},{"id":"devZ"}]}""")
        val out = MergeTestSupport.mergeChar(b, l, r, "devA", null)
        assertEquals("_rd = first remote.devices id != _ld", Side.REMOTE, out.tiebreak)
        assertEquals("raw remoteDeviceId null -> no accumulation, no abs", stable(r["char"]), stable(out.char))
        val onlySelf = obj("""{"char":{"hp":38,"gold":22,"xp":12,"lvl":3,"updatedAt":500},"devices":[{"id":"devA"}]}""")
        val out2 = MergeTestSupport.mergeChar(b, l, onlySelf, "devA", null)
        assertEquals("no usable _rd -> F2 local bias", Side.LOCAL, out2.tiebreak)
        assertEquals(stable(l["char"]), stable(out2.char))
    }

    // ---- FakeStableMerger

    @Test fun fake_stable_merger_returns_remote_only_when_stable_equal() {
        val m = FakeStableMerger()
        val l = obj("""{"a":1,"b":[1,2]}"""); val r = obj("""{"b":[1,2],"a":1.0}""")
        val sink = ConflictSink { error("no events") }
        assertSame(r, m.merge(null, l, r, ctx, sink))
        val r2 = obj("""{"a":2}""")
        assertSame(l, m.merge(null, l, r2, ctx, sink))
    }
}
