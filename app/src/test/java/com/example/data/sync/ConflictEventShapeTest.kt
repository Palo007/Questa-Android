package com.example.data.sync

import com.example.data.sync.merge.ClampPolicy
import com.example.data.sync.merge.ConflictEvent
import com.example.data.sync.merge.ConflictThrottle
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * WP-8.5 (PX-41, SY-53) -- the conflict events the merge core emits: their exact key sets
 * (sync.js :1007, :1011, :1523, :2277, :2301, :2316), the once-per-round throttle
 * (`_conflictLogThrottle`, cleared once per syncNow(), :2368), and the mapping to the Room row
 * and the pushed wire event. Opti's own payloads are compared byte-for-byte in
 * merge/OptiMergeVectorsTest; this pins the shape rules independently.
 */
class ConflictEventShapeTest {

    private val now = 1_790_251_200_000L
    private val zone = ZoneId.of("UTC")
    private fun ctx(remoteDev: String? = "devB", throttle: ConflictThrottle = ConflictThrottle()) = MergeContext(
        localDev = "devA", remoteDev = remoteDev, futureCeil = now + ClampPolicy.MAX_FUTURE_SKEW_MS,
        nowPhysical = now, localToday = 20260924, zone = zone, throttle = throttle,
    )

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun snap(tasks: String = "[]", char: String = """{"hp":40,"gold":10,"xp":0,"updatedAt":1}""", extra: String = "") =
        obj("""{"char":$char,"tasks":$tasks,"rewards":[],"tags":[],"devices":[],"lastCron":20260924$extra}""")

    private val base = snap(tasks = """[{"id":"t1","type":"todo","title":"B","updatedAt":1}]""")
    private val local = snap(tasks = """[{"id":"t1","type":"todo","title":"L","updatedAt":$now}]""")
    private val remote = snap(tasks = """[{"id":"t1","type":"todo","title":"R","updatedAt":${now - 5}}]""")

    private fun events(b: JsonObject?, l: JsonObject, r: JsonObject, c: MergeContext): List<ConflictEvent> {
        val out = ArrayList<ConflictEvent>()
        OptiSnapshotMerger().merge(b, l, r, c) { out += it }
        return out
    }

    @Test
    fun `task conflictResolved has exactly Opti's keys, absolute device ids included`() {
        val e = events(base, local, remote, ctx()).single()
        val j = e.toJson()
        assertEquals(listOf("kind", "taskType", "taskId", "taskTitle", "winner", "loser", "winnerDev", "loserDev", "reason"), j.keys.toList())
        assertEquals("todo", j["taskType"]!!.jsonPrimitive.content)
        assertEquals("L", j["taskTitle"]!!.jsonPrimitive.content)
        assertEquals("local", j["winner"]!!.jsonPrimitive.content)
        assertEquals("devA", j["winnerDev"]!!.jsonPrimitive.content)
        assertEquals("devB", j["loserDev"]!!.jsonPrimitive.content)
        assertEquals("updatedAt recency", j["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `task event writes JSON null for a missing remote id, and 'task' and '' fallbacks`() {
        val b = snap(tasks = """[{"id":"t1","updatedAt":1}]""")
        val l = snap(tasks = """[{"id":"t1","updatedAt":${now - 5}}]""")
        val r = snap(tasks = """[{"id":"t1","updatedAt":$now}]""")
        val j = events(b, l, r, ctx(remoteDev = null)).single().toJson()
        assertEquals("task", j["taskType"]!!.jsonPrimitive.content)
        assertEquals("", j["taskTitle"]!!.jsonPrimitive.content)
        assertEquals("remote", j["winner"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, j["winnerDev"])
        assertEquals("devA", j["loserDev"]!!.jsonPrimitive.content)
    }

    @Test
    fun `char conflictResolved carries day = mergedLastCron and omits an absent charId`() {
        val l = snap(char = """{"hp":40,"gold":20,"xp":10,"lvl":3,"name":"Hero","updatedAt":$now}""")
        val r = snap(char = """{"hp":38,"gold":22,"xp":12,"lvl":3,"name":"Hero","updatedAt":$now}""")
        val e = events(snap(), l, r, ctx()).single()
        val j = e.toJson()
        assertEquals(listOf("kind", "taskType", "winner", "loser", "winnerDev", "loserDev", "reason", "charTitle", "day"), j.keys.toList())
        assertEquals("char", j["taskType"]!!.jsonPrimitive.content)
        assertEquals("deviceId tiebreak", j["reason"]!!.jsonPrimitive.content)
        assertEquals("devB", j["winnerDev"]!!.jsonPrimitive.content) // "devB" > "devA"
        assertEquals(20260924, j["day"]!!.jsonPrimitive.int)
    }

    @Test
    fun `char unresolved tie keeps local and names it`() {
        val l = snap(char = """{"hp":40,"gold":20,"xp":10,"lvl":3,"updatedAt":$now}""")
        val r = snap(char = """{"hp":38,"gold":22,"xp":12,"lvl":3,"updatedAt":$now}""")
        val j = events(snap(), l, r, ctx(remoteDev = null)).single().toJson()
        assertEquals("unresolved tie, local kept", j["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, j["loserDev"])
    }

    @Test
    fun `missReverted has taskType daily, the task id and title, and the event day -- and is not throttled`() {
        val b = snap(tasks = """[{"id":"t1","type":"daily","title":"Run","done":false,"updatedAt":1}]""")
        val l = snap(tasks = """[{"id":"t1","type":"daily","title":"Run","done":true,"doneDay":20260924,"doneAt":$now,"updatedAt":${now - 9}}]""")
        val r = snap(tasks = """[{"id":"t1","type":"daily","title":"Run","done":false,"missedOn":20260924,"updatedAt":$now}]""")
        val throttle = ConflictThrottle()
        val first = events(b, l, r, ctx(throttle = throttle))
        assertEquals(listOf("missReverted", "conflictResolved"), first.map { it.kind })
        assertEquals(listOf("kind", "taskType", "taskId", "taskTitle", "day"), first[0].toJson().keys.toList())
        assertEquals(20260924, first[0].toJson()["day"]!!.jsonPrimitive.int)
        // A conflict retry in the same round: missReverted again, conflictResolved throttled.
        val retry = events(b, l, r, ctx(throttle = throttle))
        assertEquals(listOf("missReverted"), retry.map { it.kind })
    }

    @Test
    fun `the throttle is per round -- a fresh round logs the conflict again`() {
        val throttle = ConflictThrottle()
        assertEquals(1, events(base, local, remote, ctx(throttle = throttle)).size)
        assertEquals(0, events(base, local, remote, ctx(throttle = throttle)).size)
        assertEquals(1, events(base, local, remote, ctx()).size)
    }

    // ---- ConflictEvent -> EventLogEntity -> wire push round-trip -------------------------

    private val taskEvent = ConflictEvent(
        kind = "conflictResolved", taskType = JsonPrimitive("todo"), taskId = JsonPrimitive("t1"),
        taskTitle = JsonPrimitive("Local Title"), winner = "local", loser = "remote",
        winnerDev = JsonPrimitive("devA"), loserDev = JsonNull, reason = "updatedAt recency",
    )

    @Test
    fun `toEventLogEntity maps columns and keeps every other Opti key in payloadJson`() {
        val entity = taskEvent.toEventLogEntity(at = com.example.data.clock.PhysicalClock.nowMs())
        assertEquals("conflictResolved", entity.kind)
        assertEquals("todo", entity.taskType)
        assertEquals("t1", entity.taskId)
        assertEquals("Local Title", entity.taskTitle)
        assertFalse(entity.synthetic)
        assertNull(entity.uid)
        val payload = Json.parseToJsonElement(entity.payloadJson!!).jsonObject
        assertEquals(listOf("winner", "loser", "winnerDev", "loserDev", "reason"), payload.keys.toList())
        assertEquals(JsonNull, payload["loserDev"])
    }

    @Test
    fun `a pushed task conflict spreads the payload keys at top level and survives re-parse`() {
        val entity = taskEvent.toEventLogEntity(at = com.example.data.clock.PhysicalClock.nowMs()).copy(uid = "dev-a-1-x1", dev = "dev-a")
        val push = EventSyncEngine.buildEventPushJson(entity)
        assertFalse(push.containsKey("payloadJson"))
        assertEquals("local", push["winner"]!!.jsonPrimitive.content)
        assertEquals("devA", push["winnerDev"]!!.jsonPrimitive.content)
        assertEquals("updatedAt recency", push["reason"]!!.jsonPrimitive.content)
        val back = EventSyncEngine.parseIncomingEventJson(push)
        assertEquals("t1", back.taskId)
        val payload = Json.parseToJsonElement(back.payloadJson!!).jsonObject
        assertEquals("remote", payload["loser"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pushed char conflict has charId, charTitle and day, and no task keys`() {
        val e = ConflictEvent(
            kind = "conflictResolved", taskType = JsonPrimitive("char"), winner = "remote", loser = "local",
            winnerDev = JsonPrimitive("devB"), loserDev = JsonPrimitive("devA"), reason = "updatedAt recency",
            charId = JsonPrimitive("1"), charTitle = JsonPrimitive("Hero"), day = JsonPrimitive(20260719),
        )
        val entity = e.toEventLogEntity(at = com.example.data.clock.PhysicalClock.nowMs()).copy(uid = "dev-a-1-x2", dev = "dev-a")
        assertNull(entity.taskId)
        assertNull(entity.taskTitle)
        val push = EventSyncEngine.buildEventPushJson(entity)
        assertEquals("char", push["taskType"]!!.jsonPrimitive.content)
        assertEquals("Hero", push["charTitle"]!!.jsonPrimitive.content)
        assertEquals(20260719, push["day"]!!.jsonPrimitive.int)
        assertFalse(push.containsKey("taskId"))
        assertTrue(push.containsKey("charId"))
    }

    @Test
    fun `merge output tasks array is a JsonArray even when every side is empty`() {
        val out = OptiSnapshotMerger().merge(null, obj("{}"), obj("{}"), ctx()) { }
        assertTrue(out["tasks"] is JsonArray)
        assertEquals(0, (out["lastCron"] as JsonPrimitive).int)
    }
}
