package com.example.data.sync

import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.TaskAbsEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WP-8.3: `an` (views from custom_views, metrics from metrics_extras) round-trips to the G1 `an`;
 * the other typed task columns keep Opti's wire shapes.
 */
class AnRoundTripTest {

    @Test
    fun anEqualsG1ForEveryFixture() {
        for (name in SyncSubsetGoldenTest.CASES) {
            val expected = Json.parseToJsonElement(SyncSubsetGoldenTest.fixture("G1/$name/state.json")).jsonObject
                .getValue("state").jsonObject.getValue("an")
            val an = SyncSubset.build(SyncSubsetGoldenTest.wireFromFixture(name)).getValue("an")
            assertEquals(name, CanonicalJson.stringify(expected), CanonicalJson.stringify(an))
        }
    }

    @Test
    fun nativeQuickViewsStayOffTheWire() {
        val native = CustomViewEntity(id = "q1", title = "Quick", chartType = "bar", createdAt = 1)
        val wire = LocalWireState.fromEntities(
            null, emptyList(), emptyList(), emptyList(), emptyList(), 0, JsonArray(emptyList()), emptyList(),
            listOf(native), JsonArray(emptyList()), null, emptyList(), emptyList()
        )
        assertEquals(0, wire.anViews.size)
    }

    @Test
    fun quickLogWireValuesAreFalseTrueDownBoth() {
        assertEquals(JsonPrimitive(false), SerializationEngine.quickLogWire(QuickLogMode.OFF))
        assertEquals(JsonPrimitive(true), SerializationEngine.quickLogWire(QuickLogMode.UP))
        assertEquals(JsonPrimitive("down"), SerializationEngine.quickLogWire(QuickLogMode.DOWN))
        assertEquals(JsonPrimitive("both"), SerializationEngine.quickLogWire(QuickLogMode.BOTH))
    }

    @Test
    fun typedColumnsRoundTripAndEditsAreWritten() {
        val src = Json.parseToJsonElement(
            """{"id":"h","type":"habit","title":"H","value":0,"done":false,"up":true,"down":false,"quickLog":"both",
               "cAbs":{"dev-a":{"ua":5,"cUp":2,"cDown":1}},"zz":{"keep":1},"tags":[],"createdAt":1,"updatedAt":2}"""
        ).jsonObject
        val t = SerializationEngine.parseTaskEntity(src)
        assertEquals(QuickLogMode.BOTH, t.quickLog)
        assertEquals(TaskAbsEntry(ua = 5, cUp = 2, cDown = 1), t.cAbs?.get("dev-a"))
        assertEquals(CanonicalJson.stringify(src), CanonicalJson.stringify(SerializationEngine.buildTaskJson(t)))

        val edited = SerializationEngine.buildTaskJson(t.copy(quickLog = QuickLogMode.UP, down = null, cAbs = mapOf("dev-b" to TaskAbsEntry(ua = 9, cUp = 1))))
        assertEquals(JsonPrimitive(true), edited["quickLog"])
        assertFalse("a cleared typed column removes the key", edited.containsKey("down"))
        assertEquals("""{"dev-b":{"ua":9,"cUp":1,"cDown":0}}""", CanonicalJson.stringify(edited.getValue("cAbs")))
        assertEquals("unknown keys pass through", """{"keep":1}""", CanonicalJson.stringify(edited.getValue("zz")))
    }

    @Test
    fun reminderLastFiredKeyEditIsWrittenWithoutTouchingUpdatedAt() {
        val src = Json.parseToJsonElement(SyncSubsetGoldenTest.fixture("G1/typical/state.json")).jsonObject
            .getValue("state").jsonObject.getValue("tasks").jsonArray[1].jsonObject
        val t = SerializationEngine.parseTaskEntity(src)
        val fired = t.copy(reminders = t.reminders!!.map { it.copy(lastFiredKey = "2026-09-24") })
        val out = SerializationEngine.buildTaskJson(fired)
        val r = out.getValue("reminders").jsonArray[0].jsonObject
        assertEquals(JsonPrimitive("2026-09-24"), r["lastFiredKey"])
        assertEquals(src["updatedAt"], out["updatedAt"])
        assertEquals(listOf("id", "enabled", "kind", "time", "lastFiredKey", "days"), r.keys.toList())
    }
}
