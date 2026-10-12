package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.objOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * WP-8.5 -- convergence of the real merge core.
 *
 * 1. G3: every pair Opti merged from BOTH device perspectives (device A merges (b, L, R),
 *    device B merges (b, R, L)) gives Opti's two results, and they agree with each other
 *    exactly when Opti's do.
 * 2. A Dropbox-like single-document simulation (3 devices x 10 rounds, and the F1 retry):
 *    every device ends on the server state, the totals are right, and nothing grows.
 */
class ConvergenceTest {

    @Test
    fun `G3 pairs merged from both perspectives equal Opti's, in every time zone`() {
        val byName = OptiMergeVectorsTest.all().associateBy { it.name }
        val pairs = byName.keys.filter { it.startsWith("g3_") && it.contains("__A.") }
        assertTrue("expected 9 G3 pairs x 4 TZs, got ${pairs.size}", pairs.size == 36)
        for (a in pairs) {
            val va = byName.getValue(a)
            val vb = byName.getValue(a.replace("__A.", "__B."))
            val ka = OptiMergeVectorsTest.stable(OptiMergeVectorsTest.run(va).first)
            val kb = OptiMergeVectorsTest.stable(OptiMergeVectorsTest.run(vb).first)
            assertEquals("$a (device A)", OptiMergeVectorsTest.stable(va.result), ka)
            assertEquals("$a (device B)", OptiMergeVectorsTest.stable(vb.result), kb)
            val optiAgree = OptiMergeVectorsTest.stable(va.result) == OptiMergeVectorsTest.stable(vb.result)
            assertEquals("$a: A/B agreement must match Opti's", optiAgree, ka == kb)
        }
    }

    // ---- simulation ----------------------------------------------------------------------

    private val now = 1_790_251_200_000L
    private val merger = OptiSnapshotMerger()

    private inner class World {
        var server: JsonObject? = null
        var writer: String? = null
        var uploads = 0

        fun sync(d: Dev, uploadFails: Boolean = false) {
            val remote = server!!
            val ctx = MergeContext(
                localDev = d.id, remoteDev = writer, futureCeil = now + ClampPolicy.MAX_FUTURE_SKEW_MS,
                nowPhysical = now, localToday = 20260924, zone = ZoneId.of("UTC"),
            )
            val merged = merger.merge(d.base, d.local, remote, ctx) { }
            d.local = merged
            if (Js.deepEqual(merged, remote)) { d.base = merged; return }
            if (uploadFails) return
            server = merged; writer = d.id; uploads++
            d.base = merged
        }
    }

    private class Dev(val id: String, var local: JsonObject, var base: JsonObject)

    private data class Totals(val xp: Double, val gold: Double, val cUp: Double)

    private fun totalsOf(s: JsonObject): Totals {
        val c = s.objOrNull("char")!!
        return Totals(MergeTestSupport.totalXp(c), MergeTestSupport.num(c, "gold"), MergeTestSupport.num(MergeTestSupport.tasksById(s)["t1"]!!, "cUp"))
    }

    private val s0 = PrimitiveRoundHarness.snapshot(lvl = 2, xp = 10.0, gold = 10.0, cUp = 0, charUa = now - 9000, taskUa = now - 9000)

    @Test
    fun `three devices ten rounds converge on the server state and never grow`() {
        val w = World().apply { server = s0; writer = "devS" }
        val a = Dev("devA", PrimitiveRoundHarness.earn(s0, 50.0, 5.0, 5, now - 3000), s0)
        val b = Dev("devB", PrimitiveRoundHarness.earn(s0, 30.0, 3.0, 3, now - 2000), s0)
        val c = Dev("devC", PrimitiveRoundHarness.earn(s0, 40.0, 4.0, 4, now - 1000), s0)
        val truth = Totals(160.0 + 120.0, 10.0 + 12.0, 12.0)
        for (round in 1..10) {
            for (d in listOf(a, b, c)) w.sync(d)
            assertEquals("round $round server totals", truth, totalsOf(w.server!!))
        }
        for (d in listOf(a, b, c)) assertEquals("${d.id} converged", Js.stableStringify(w.server), Js.stableStringify(d.local))
        val settled = w.uploads
        for (d in listOf(a, b, c)) w.sync(d)
        assertEquals("a settled world uploads nothing", settled, w.uploads)
    }

    @Test
    fun `F1 retry through the real merge counts each contribution once`() {
        val w = World().apply { server = s0; writer = "devS" }
        val a = Dev("devA", PrimitiveRoundHarness.earn(s0, 50.0, 5.0, 5, now - 1000), s0)
        val b = Dev("devB", PrimitiveRoundHarness.earn(s0, 30.0, 3.0, 3, now - 3000), s0)
        val c = Dev("devC", PrimitiveRoundHarness.earn(s0, 40.0, 4.0, 4, now - 2000), s0)
        w.sync(b); w.sync(c); w.sync(a, uploadFails = true)
        assertEquals(Totals(280.0, 22.0, 12.0), totalsOf(a.local))
        b.local = PrimitiveRoundHarness.earn(b.local, 20.0, 2.0, 2, now - 500)
        w.sync(b)
        val truth = Totals(300.0, 24.0, 14.0)
        repeat(4) { w.sync(a, uploadFails = true); assertEquals("retry $it", truth, totalsOf(a.local)) }
        for (round in 1..10) for (d in listOf(a, b, c)) w.sync(d)
        for (d in listOf(a, b, c)) assertEquals(d.id, truth, totalsOf(d.local))
        for (d in listOf(a, b, c)) assertEquals("${d.id} converged", Js.stableStringify(w.server), Js.stableStringify(d.local))
    }
}
