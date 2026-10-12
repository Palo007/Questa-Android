package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.objOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan §4.5 row 8.4: "A 4x retry is idempotent (cUp=8)" -- SY-08's base0 / L5 / R3 case.
 *
 * _pushWithConflictRetry re-merges with the ALREADY-MERGED state as `local` while `base` is
 * still the pre-round base (sync.js :1699-1703, :1851-1856). Without the cAbs / abs
 * watermarks cUp went 8, 11, 14, 17 (the comment at :1923-1925).
 */
class ConflictRetryIdempotenceTest {

    private val h = PrimitiveRoundHarness()
    private val base = PrimitiveRoundHarness.snapshot(lvl = 2, xp = 10.0, gold = 10.0, cUp = 0, charUa = 1000, taskUa = 1000)
    private val local = PrimitiveRoundHarness.earn(base, dXp = 50.0, dGold = 5.0, taps = 5, ua = 3000)
    private val remote = PrimitiveRoundHarness.earn(base, dXp = 30.0, dGold = 3.0, taps = 3, ua = 2000)

    @Test
    fun four_retries_keep_cUp_8_and_char_earnings_once() {
        var l = local
        repeat(5) { round ->
            val merged = h.merge(base, l, remote, "devA", "devB")
            val task = MergeTestSupport.tasksById(merged)["t1"]!!
            val char = merged.objOrNull("char")!!
            assertEquals("round $round cUp", 8.0, MergeTestSupport.num(task, "cUp"), 0.0)
            assertEquals("round $round total xp", 160.0 + 80.0, MergeTestSupport.totalXp(char), 0.0)
            assertEquals("round $round gold", 18.0, MergeTestSupport.num(char, "gold"), 0.0)
            // Round 1 re-stamps abs/cAbs[devA] with local's own (already merged) totals, as
            // Opti does; from round 2 on the document is a fixpoint.
            if (round > 1) assertEquals("round $round is a fixpoint", Js.stableStringify(l), Js.stableStringify(merged))
            l = merged
        }
    }

    @Test
    fun without_device_ids_the_legacy_path_grows_exactly_like_opti() {
        // Opti precondition 3 (sync.js :1976): no ids -> measured from the shared base on every
        // retry. This is the fixture g2_conflict_retry_idempotence shape (cDown 1,2,3,4).
        var l = local
        val seen = mutableListOf<Double>()
        repeat(4) {
            val b = MergeTestSupport.tasksById(base)["t1"]!!
            val lt = MergeTestSupport.tasksById(l)["t1"]!!
            val rt = MergeTestSupport.tasksById(remote)["t1"]!!
            val merged = CounterAccumulate.accumulate(b, lt, rt, lt, null, "devB", 20260924)
            seen += MergeTestSupport.num(merged, "cUp")
            l = kotlinx.serialization.json.JsonObject(l + mapOf("tasks" to kotlinx.serialization.json.JsonArray(listOf(merged))))
        }
        assertEquals(listOf(8.0, 11.0, 14.0, 17.0), seen)
    }
}
