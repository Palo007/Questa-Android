package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.FUTURE_CEIL
import com.example.data.sync.merge.MergeTestSupport.TODAY
import com.example.data.sync.merge.MergeTestSupport.objOrNull
import com.example.data.sync.merge.MergeTestSupport.stable
import com.example.data.sync.merge.MergeTestSupport.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.4 "G2 subsets" (plan §4.5: 100% of the G2 subsets pass).
 *
 * Every G2/G3 fixture is a full Opti merge() at 7d5d4f3. The sub-results that the WP-8.4
 * primitives alone determine are checked stableStringify-equal against it:
 *  - `pause`  -- PauseMerge (every fixture);
 *  - `char`   -- CharTiebreak + CharAccumulate (every fixture), plus the char conflictResolved
 *               event's presence and winner/loser/dev words where the fixture records events;
 *  - tasks carrying cUp/cDown that reached mergeCollection's both-changed arm --
 *               CounterAccumulate, with the arbitration winner taken from the fixture's own
 *               conflictResolved event (G2) or its surviving updatedAt (G3/retry rounds).
 *
 * Note: gen_opti_fixtures.mjs defines no `syncDeviceId` in the merge sandbox, so Opti's
 * mergeCollection runs with `_localDev = null` (the events record winnerDev/loserDev null).
 * The counter checks therefore pass localDev = null, exactly as the fixtures were produced.
 */
class OptiG2PrimitivesFixtureTest {

    private data class Case(
        val fixture: String,
        val label: String,
        val base: JsonObject,
        val local: JsonObject,
        val remote: JsonObject,
        val lDev: String,
        val rDev: String?,
        val expected: JsonObject,
        val events: JsonArray?,
    )

    private fun cases(): List<Case> {
        val inputs = MergeTestSupport.inputs()
        val files = MergeTestSupport.fixtureDir().listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertEquals("fixture count", 89, files.size)
        val out = mutableListOf<Case>()
        for (f in files) {
            val name = f.name.removeSuffix(".json")
            val fx = MergeTestSupport.parse(f.readText())
            val calls = inputs[name]!!.jsonArray.map { it.jsonObject }
            fun mk(i: Int, label: String, local: JsonObject, expected: JsonObject, events: JsonArray?): Case {
                val c = calls[i]
                return Case(name, label, c["base"]!!.jsonObject, local, c["remote"]!!.jsonObject,
                    c.str("lDev")!!, c.str("rDev"), expected, events)
            }
            when {
                fx.containsKey("result") -> out += mk(0, name, calls[0]["local"]!!.jsonObject, fx["result"]!!.jsonObject, fx["events"]!!.jsonArray)
                fx.containsKey("fromA") -> {
                    out += mk(0, "$name/A", calls[0]["local"]!!.jsonObject, MergeTestSupport.parse(fx["fromA"]!!.jsonPrimitive.content), null)
                    out += mk(1, "$name/B", calls[1]["local"]!!.jsonObject, MergeTestSupport.parse(fx["fromB"]!!.jsonPrimitive.content), null)
                }
                fx.containsKey("rounds") -> {
                    val rounds = fx["rounds"]!!.jsonArray.map { MergeTestSupport.parse(it.jsonPrimitive.content) }
                    var local = calls[0]["local"]!!.jsonObject
                    rounds.forEachIndexed { i, r ->
                        out += mk(0, "$name/round$i", local, r, null)
                        local = r
                    }
                }
                else -> error("unknown fixture shape $name")
            }
        }
        return out
    }

    @Test
    fun g2_g3_primitive_subsets_match_opti() {
        val all = cases()
        val failures = mutableListOf<String>()
        var pauseChecks = 0; var charChecks = 0; var charEventChecks = 0; var counterChecks = 0
        val fixturesWithCounter = mutableSetOf<String>()

        for (c in all) {
            // ---- pause (sync.js :2178-2191)
            val pause = PauseMerge.merge(c.base.objOrNull("pause"), c.local.objOrNull("pause"), c.remote.objOrNull("pause"), FUTURE_CEIL)
            pauseChecks++
            if (stable(pause) != stable(c.expected["pause"])) failures += "${c.label} pause: got ${stable(pause)} want ${stable(c.expected["pause"])}"

            // ---- char (sync.js :2201-2321)
            val ch = MergeTestSupport.mergeChar(c.base, c.local, c.remote, c.lDev, c.rDev)
            charChecks++
            if (stable(ch.char) != stable(c.expected["char"])) failures += "${c.label} char: got ${stable(ch.char)} want ${stable(c.expected["char"])}"
            if (c.events != null) {
                val ev = c.events.map { it.jsonObject }.filter { it.str("taskType") == "char" }
                charEventChecks++
                if (ch.side != Side.BOTH_CHANGED) {
                    if (ev.isNotEmpty()) failures += "${c.label} char event: none expected for ${ch.side}, fixture has $ev"
                } else {
                    val wantWinner = if (ch.tiebreak == Side.REMOTE) "remote" else "local"
                    val winnerDev = if (ch.tiebreak == Side.REMOTE) c.rDev else c.lDev
                    val loserDev = if (ch.tiebreak == Side.REMOTE) c.lDev else c.rDev
                    val e = ev.singleOrNull()
                    if (e == null || e.str("winner") != wantWinner || e.str("winnerDev") != winnerDev || e.str("loserDev") != loserDev) {
                        failures += "${c.label} char event: want winner=$wantWinner/$winnerDev, fixture $ev"
                    }
                }
            }

            // ---- counters (sync.js :1510 -> _accumCounters :1959-2023)
            val bT = MergeTestSupport.tasksById(c.base)
            val lT = MergeTestSupport.tasksById(c.local)
            val rT = MergeTestSupport.tasksById(c.remote)
            val want = MergeTestSupport.tasksById(c.expected)
            for ((id, expectedTask) in want) {
                val l = lT[id] ?: continue
                val r = rT[id] ?: continue
                val b = bT[id]
                val bothChanged = !Js.deepEqual(l, b) && !Js.deepEqual(r, b)
                val hasCounters = listOf("cUp", "cDown").any { Js.isNumber(l[it]) || Js.isNumber(r[it]) }
                if (!bothChanged || !hasCounters) continue
                val winners: List<JsonObject> = c.events
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it.str("taskId") == id }
                    ?.let { listOf(if (it.str("winner") == "local") l else r) }
                    ?: listOf(l, r).filter { stable(it["updatedAt"]) == stable(expectedTask["updatedAt"]) }
                assertTrue("${c.label} $id: no winner candidate", winners.isNotEmpty())
                for (w in winners) {
                    val got = CounterAccumulate.accumulate(b, l, r, w, null, c.rDev, TODAY)
                    counterChecks++
                    fixturesWithCounter += c.fixture
                    if (stable(got) != stable(expectedTask)) failures += "${c.label} task $id counters: got ${stable(got)} want ${stable(expectedTask)}"
                }
            }
        }
        val fixtures = all.map { it.fixture }.toSet()
        println("WP-8.4 G2/G3: fixtures=${fixtures.size} cases=${all.size} pause=$pauseChecks char=$charChecks " +
            "charEvents=$charEventChecks counters=$counterChecks (fixtures with counter checks=${fixturesWithCounter.size}) failures=${failures.size}")
        assertTrue("counter subset must be exercised", fixturesWithCounter.size >= 20)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun retry_inputs_hold_round0_only_later_rounds_are_chained_from_the_fixture() {
        // Opti fed round i the output of round i-1 as `local`; the dump cannot know those
        // outputs, so it keeps round 0 only and the test chains rounds 1-3 from `rounds`.
        val retry = MergeTestSupport.inputs().filterKeys { it.startsWith("g2_conflict_retry_idempotence") }
        assertEquals(3, retry.size)
        for ((name, calls) in retry) assertEquals(name, 1, calls.jsonArray.size)
    }

    @Test
    fun inputs_cover_every_fixture() {
        val names = MergeTestSupport.fixtureDir().listFiles()!!.map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(names, MergeTestSupport.inputs().keys)
    }
}
