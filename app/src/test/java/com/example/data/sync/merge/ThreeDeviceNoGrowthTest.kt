package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.objOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan §4.5 row 8.4: "3 devices x 10 rounds: no growth after round 1", plus the F1
 * (2026-09-19) three-device retry the sync.js comments measure (:1738-1739, :1883-1885).
 */
class ThreeDeviceNoGrowthTest {

    private val s0 = PrimitiveRoundHarness.snapshot(lvl = 2, xp = 10.0, gold = 10.0, cUp = 0, charUa = 1000, taskUa = 1000) // 160 total xp

    private data class Totals(val xp: Double, val gold: Double, val cUp: Double)

    private fun totalsOf(s: kotlinx.serialization.json.JsonObject): Totals {
        val c = s.objOrNull("char")!!
        return Totals(MergeTestSupport.totalXp(c), MergeTestSupport.num(c, "gold"), MergeTestSupport.num(MergeTestSupport.tasksById(s)["t1"]!!, "cUp"))
    }

    @Test
    fun three_devices_ten_rounds_converge_and_never_grow() {
        val h = PrimitiveRoundHarness()
        h.server = s0; h.writer = "devS"
        val a = PrimitiveRoundHarness.Device("devA", PrimitiveRoundHarness.earn(s0, 50.0, 5.0, 5, 2000), s0)
        val b = PrimitiveRoundHarness.Device("devB", PrimitiveRoundHarness.earn(s0, 30.0, 3.0, 3, 3000), s0)
        val c = PrimitiveRoundHarness.Device("devC", PrimitiveRoundHarness.earn(s0, 40.0, 4.0, 4, 4000), s0)
        val truth = Totals(160.0 + 120.0, 10.0 + 12.0, 12.0)
        val perRound = mutableListOf<Totals>()
        for (round in 1..10) {
            for (d in listOf(a, b, c)) h.sync(d)
            perRound += totalsOf(h.server!!)
            if (round >= 2) {
                for (d in listOf(a, b, c)) assertEquals("round $round ${d.id}", truth, totalsOf(d.local))
            }
        }
        assertEquals(truth, perRound[0])
        assertEquals("no growth after round 1", List(10) { truth }, perRound)
        assertEquals("uploads settle: 3 in round 1, none after round 2", 3, h.uploads)
    }

    @Test
    fun f1_three_device_retry_counts_each_contribution_once() {
        // A+50 / B+30 / C+40 with A's upload failing, then B+20; A's retry must land on
        // 160 + 140 = 300 total xp (sync.js :1738-1739), and cUp 3+4+5+2 = 14 (:1883-1885).
        val h = PrimitiveRoundHarness()
        h.server = s0; h.writer = "devS"
        val a = PrimitiveRoundHarness.Device("devA", PrimitiveRoundHarness.earn(s0, 50.0, 5.0, 5, 4000), s0)
        val b = PrimitiveRoundHarness.Device("devB", PrimitiveRoundHarness.earn(s0, 30.0, 3.0, 3, 2000), s0)
        val c = PrimitiveRoundHarness.Device("devC", PrimitiveRoundHarness.earn(s0, 40.0, 4.0, 4, 3000), s0)
        h.sync(b)
        h.sync(c)
        h.sync(a, uploadFails = true)
        assertEquals(Totals(280.0, 22.0, 12.0), totalsOf(a.local))
        b.local = PrimitiveRoundHarness.earn(b.local, 20.0, 2.0, 2, 5000)
        h.sync(b)
        val truth = Totals(300.0, 24.0, 14.0)
        repeat(4) { h.sync(a, uploadFails = true); assertEquals("retry $it", truth, totalsOf(a.local)) }
        for (round in 1..10) {
            for (d in listOf(a, b, c)) h.sync(d)
            assertEquals("round $round server", truth, totalsOf(h.server!!))
        }
        for (d in listOf(a, b, c)) assertEquals(d.id, truth, totalsOf(d.local))
    }
}
