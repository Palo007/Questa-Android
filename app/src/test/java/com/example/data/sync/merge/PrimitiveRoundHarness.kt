package com.example.data.sync.merge

import com.example.data.sync.merge.MergeTestSupport.objOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A TEST-ONLY snapshot merger over {char, tasks: [one habit], pause} built purely from the
 * WP-8.4 primitives, plus a Dropbox-like single-document server, so the retry and multi-device
 * scenarios of plan §4.5 can run before the WP-8.5 merge core exists. Task arbitration is the
 * mergeCollection non-daily shape (one-sided -> that side; both changed -> clamped recency,
 * device-id tiebreak), which is all a single habit needs.
 */
internal class PrimitiveRoundHarness(private val ceil: Long = Long.MAX_VALUE / 2, private val today: Int = 20260924) {

    fun merge(base: JsonObject, local: JsonObject, remote: JsonObject, lDev: String, rDev: String): JsonObject {
        val char = MergeTestSupport.mergeChar(base, local, remote, lDev, rDev, ceil).char
        val b = MergeTestSupport.tasksById(base)["t1"]
        val l = MergeTestSupport.tasksById(local)["t1"]!!
        val r = MergeTestSupport.tasksById(remote)["t1"]!!
        val lc = !Js.deepEqual(l, b); val rc = !Js.deepEqual(r, b)
        val task = when {
            !lc && !rc -> b!!
            lc && !rc -> l
            !lc && rc -> r
            else -> {
                val lu = Js.num(l["updatedAt"]); val ru = Js.num(r["updatedAt"])
                val luc = if (lu > ceil) 0.0 else lu; val ruc = if (ru > ceil) 0.0 else ru
                val w = when {
                    luc == ruc && lDev != rDev -> if (rDev > lDev) r else l
                    else -> if (luc > ruc) l else r
                }
                CounterAccumulate.accumulate(b, l, r, w, lDev, rDev, today)
            }
        }
        val pause = PauseMerge.merge(base.objOrNull("pause"), local.objOrNull("pause"), remote.objOrNull("pause"), ceil)
        return buildJsonObject {
            put("char", char)
            put("tasks", JsonArray(listOf(task)))
            if (pause != null) put("pause", pause)
        }
    }

    class Device(val id: String, var local: JsonObject, var base: JsonObject)

    var server: JsonObject? = null
    var writer: String = ""
    var uploads = 0

    /** One sync round for [d]: download, merge, upload unless the result equals the remote. */
    fun sync(d: Device, uploadFails: Boolean = false) {
        val remote = server!!
        val merged = merge(d.base, d.local, remote, d.id, writer)
        d.local = merged
        if (Js.deepEqual(merged, remote)) { d.base = merged; return }
        if (uploadFails) return // base stays pristine: the retry shape of _pushWithConflictRetry
        server = merged; writer = d.id; uploads++
        d.base = merged
    }

    companion object {
        fun snapshot(lvl: Int, xp: Double, gold: Double, cUp: Int, charUa: Long, taskUa: Long): JsonObject = buildJsonObject {
            put("char", buildJsonObject {
                put("hp", 50); put("lvl", lvl); put("xp", Js.jsonNum(xp)); put("gold", Js.jsonNum(gold)); put("mp", 0)
                put("updatedAt", charUa)
            })
            put("tasks", JsonArray(listOf(buildJsonObject {
                put("id", "t1"); put("type", "habit"); put("value", 1); put("cUp", cUp); put("cDown", 0); put("updatedAt", taskUa)
            })))
            put("pause", buildJsonObject { put("paused", false); put("pausedDays", JsonArray(emptyList())); put("at", 0) })
        }

        /** Earn [dXp] total xp, [dGold] gold and [taps] habit taps on top of [s], stamped [ua]. */
        fun earn(s: JsonObject, dXp: Double, dGold: Double, taps: Int, ua: Long): JsonObject {
            val c = s.objOrNull("char")!!
            val (lvl, xp) = MergeTestSupport.levelFor(MergeTestSupport.totalXp(c) + dXp)
            val t = MergeTestSupport.tasksById(s)["t1"]!!
            val char = JsonObject(c + mapOf("lvl" to JsonPrimitive(lvl), "xp" to Js.jsonNum(xp),
                "gold" to Js.jsonNum(MergeTestSupport.num(c, "gold") + dGold), "updatedAt" to JsonPrimitive(ua)))
            val task = JsonObject(t + mapOf("cUp" to JsonPrimitive(MergeTestSupport.num(t, "cUp").toInt() + taps), "updatedAt" to JsonPrimitive(ua)))
            return JsonObject(s + mapOf("char" to char, "tasks" to JsonArray(listOf(task))))
        }
    }
}
