package com.example.data.sync

import com.example.data.local.entities.GrantedReward
import com.example.data.local.entities.TaskEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WP-8.3 (PX-24): the task key is `_gr` (app.js:2308), never `gr`. */
class GrKeyTest {

    private val gr = GrantedReward(xp = 10, gold = 1.5, mp = 2, delta = 0.25)

    @Test
    fun freshTaskWritesUnderscoreGr() {
        val json = SerializationEngine.buildTaskJson(TaskEntity(id = "t", type = "todo", title = "T", gr = gr, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals("""{"xp":10,"gold":1.5,"mp":2,"delta":0.25}""", CanonicalJson.stringify(json.getValue("_gr")))
        assertFalse(json.containsKey("gr"))
    }

    @Test
    fun optiUnderscoreGrRoundTripsVerbatim() {
        val src = Json.parseToJsonElement(
            """{"id":"t","type":"todo","title":"T","value":0,"done":true,"_gr":{"xp":10,"gold":1.5,"mp":2,"delta":0.25},"createdAt":1,"updatedAt":2}"""
        ).jsonObject
        val t = SerializationEngine.parseTaskEntity(src)
        assertEquals(10, t.gr?.xp)
        assertEquals(CanonicalJson.stringify(src), CanonicalJson.stringify(SerializationEngine.buildTaskJson(t)))
    }

    @Test
    fun uncompletedTaskKeepsExplicitNull() {
        val src = Json.parseToJsonElement(
            """{"id":"t","type":"todo","title":"T","value":0,"done":true,"_gr":{"xp":1,"gold":1,"mp":0,"delta":0},"createdAt":1,"updatedAt":2}"""
        ).jsonObject
        val undone = SerializationEngine.parseTaskEntity(src).copy(done = false, gr = null)
        val out = SerializationEngine.buildTaskJson(undone)
        assertEquals(JsonNull, out["_gr"]) // app.js:2334 `t._gr = null`
        val nullSrc = Json.parseToJsonElement("""{"id":"t","type":"todo","title":"T","value":0,"done":false,"_gr":null,"createdAt":1,"updatedAt":2}""").jsonObject
        assertEquals(CanonicalJson.stringify(nullSrc), CanonicalJson.stringify(SerializationEngine.buildTaskJson(SerializationEngine.parseTaskEntity(nullSrc))))
    }

    @Test
    fun legacyKotlinGrIsReadAndRewrittenAsUnderscoreGr() {
        val legacy = Json.parseToJsonElement(
            """{"id":"t","type":"todo","title":"T","value":0,"done":true,"gr":{"xp":3,"gold":0,"mp":0,"delta":0},"createdAt":1,"updatedAt":2}"""
        ).jsonObject
        val t = SerializationEngine.parseTaskEntity(legacy)
        assertEquals(3, t.gr?.xp)
        val out = SerializationEngine.buildTaskJson(t)
        assertFalse(out.containsKey("gr"))
        assertTrue(out.containsKey("_gr"))
    }

    /** "Write paths have 0 gr": no main source puts a task-level "gr" key. */
    @Test
    fun noMainSourceWritesGrKey() {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.contains("put(\"gr\"")) "${f.name}:${i + 1}" else null }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
