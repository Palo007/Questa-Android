package com.example.data.attachments

import com.example.data.sync.merge.ConflictSink
import com.example.data.sync.merge.MergeContext
import com.example.data.sync.merge.MergeTestSupport
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * CR-KT-015: the attachments splice against the golden fixture (fixtures/cr_kt_015/, copied
 * unchanged from shared\fixtures\CR-KT-015\), plus both splice sites in mergeCollection.
 */
class AttachmentMergeFixtureTest {

    private val merger = OptiSnapshotMerger()
    private val fixture: JsonObject by lazy {
        val f = File(MergeTestSupport.repoRoot(), "fixtures/cr_kt_015/task_attachments.json")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun ids(a: JsonArray): List<String> = a.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun arr(e: kotlinx.serialization.json.JsonElement?): JsonArray? = if (e == null || e is JsonNull) null else e.jsonArray

    @Test
    fun allFourFixtureCases() {
        val cases = fixture["merge_cases"]!!.jsonArray
        assertEquals(4, cases.size)
        for (c in cases.map { it.jsonObject }) {
            val name = c["name"]!!.jsonPrimitive.content
            val out = merger.mergeAttachments(arr(c["base"]), c["local"]!!.jsonArray, c["remote"]!!.jsonArray)
            val expected = c["expected_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(name, expected, ids(out))
            // Items are immutable: each output item equals the input item with that id.
            val all = (c["local"]!!.jsonArray + c["remote"]!!.jsonArray).associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
            for (item in out) assertEquals(name, all[item.jsonObject["id"]!!.jsonPrimitive.content], item)
            // Idempotent: merging the result with itself (and with the base) changes nothing.
            assertEquals(name, out, merger.mergeAttachments(arr(c["base"]), out, out))
            assertEquals(name, out, merger.mergeAttachments(out, out, out))
        }
    }

    @Test
    fun symmetric_localAndRemoteSwapped() {
        for (c in fixture["merge_cases"]!!.jsonArray.map { it.jsonObject }) {
            val a = merger.mergeAttachments(arr(c["base"]), c["local"]!!.jsonArray, c["remote"]!!.jsonArray)
            val b = merger.mergeAttachments(arr(c["base"]), c["remote"]!!.jsonArray, c["local"]!!.jsonArray)
            assertEquals(ids(a), ids(b))
        }
    }

    @Test
    fun fixtureTaskShape_parsesAndRoundTripsByteStable() {
        val task = fixture["taskWithAttachments"]!!.jsonObject
        val entity = com.example.data.sync.SerializationEngine.parseTaskEntity(task)
        assertEquals(2, entity.attachments.size)
        val a = entity.attachments[0]
        assertEquals("mgk1a9z0aaaa1", a.id)
        assertEquals("06ada57c26aa5cf429e9f2c0a99e3e4a42daecd45fc4c955d7c1399ab4227ae8", a.sha)
        assertEquals(1600, a.w); assertEquals(1200, a.h); assertEquals(284113L, a.bytes); assertEquals(1791460100000L, a.addedAt)
        val out = com.example.data.sync.SerializationEngine.buildTaskJson(entity)
        assertEquals(task["attachments"], out["attachments"])
        // Fresh (no extraJson) render uses the same key order as the fixture.
        val fresh = AttachmentJson.listToJson(entity.attachments)
        assertEquals(task["attachments"].toString(), fresh.toString())
        // blobPaths
        val paths = fixture["blobPaths"]!!.jsonObject
        assertEquals(paths["full"]!!.jsonPrimitive.content, Attachment.fullPath(a.sha))
        assertEquals(paths["thumb"]!!.jsonPrimitive.content, Attachment.thumbPath(a.sha))
    }

    // ---- both splice sites inside mergeCollection --------------------------------------------

    private fun ctx() = MergeContext.forRound("devL", "devR", 1791460900000L, null, ZoneId.of("UTC"))
    private fun item(id: String, at: Long) = """{"id":"$id","sha":"s$id","mime":"image/jpeg","w":1,"h":1,"bytes":1,"addedAt":$at}"""
    private fun task(ua: Long, vararg items: String, extra: String = ""): JsonObject = Json.parseToJsonElement(
        """{"id":"t1","type":"todo","title":"x","done":false,"createdAt":1,"updatedAt":$ua$extra,"attachments":[${items.joinToString(",")}]}"""
    ).jsonObject
    private fun one(o: JsonObject) = JsonArray(listOf(o))
    private fun run(b: JsonObject?, l: JsonObject, r: JsonObject): JsonObject =
        merger.mergeCollection(b?.let { one(it) }, one(l), one(r), null, ctx(), ConflictSink { })[0].jsonObject

    @Test
    fun normalPath_bothChanged_unionWithBase() {
        val base = task(100, item("A", 10))
        val local = task(300, extra = ",\"title2\":1")               // removed A, newer
        val remote = task(200, item("A", 10), item("C", 20))      // added C
        val out = run(base, local, remote)
        assertEquals(listOf("C"), ids(out["attachments"]!!.jsonArray))
    }

    @Test
    fun guard1_baseIsNull_pureUnion_removeComesBack() {
        // local untouched (== base) and NEWER than remote: GUARD 1 keeps local but splices with base = null.
        val base = task(500, item("A", 10))
        val remote = task(400, item("C", 20))                      // remote dropped A, added C, older clock
        val out = run(base, base, remote)
        assertEquals(500.0, out["updatedAt"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(listOf("A", "C"), ids(out["attachments"]!!.jsonArray))
    }

    @Test
    fun noSideHasTheKey_keyStaysAbsent() {
        val b = Json.parseToJsonElement("""{"id":"t1","type":"todo","title":"x","updatedAt":100}""").jsonObject
        val l = Json.parseToJsonElement("""{"id":"t1","type":"todo","title":"l","updatedAt":300}""").jsonObject
        val r = Json.parseToJsonElement("""{"id":"t1","type":"todo","title":"r","updatedAt":200}""").jsonObject
        assertFalse(run(b, l, r).containsKey("attachments"))
    }

    @Test
    fun emptyResult_onWinnerWithoutKey_doesNotAddEmptyKey() {
        val base = task(100, item("A", 10))
        val local = Json.parseToJsonElement("""{"id":"t1","type":"todo","title":"x","done":false,"createdAt":1,"updatedAt":300}""").jsonObject
        val remote = task(200, item("A", 10), extra = ",\"notes\":\"n\"")
        val out = run(base, local, remote)
        assertFalse(out.containsKey("attachments"))
    }

    /** sync.js `_withAttachments`: an empty merge result DELETES the key, even on a winner that had it. */
    @Test
    fun emptyResult_onWinnerWithKey_removesKey_likePwa() {
        val base = task(100, item("A", 10))
        val remote = task(300, item("A", 10), extra = ",\"notes\":\"n\"")   // remote wins, still holds A
        val localNoKey = Json.parseToJsonElement("""{"id":"t1","type":"todo","title":"x","done":false,"createdAt":1,"updatedAt":200}""").jsonObject
        assertFalse(run(base, localNoKey, remote).containsKey("attachments"))
        val localEmpty = task(200)                                         // local removed A, wrote []
        assertFalse(run(base, localEmpty, remote).containsKey("attachments"))
    }

    @Test
    fun emptyResult_guard1Path_removesKey() {
        // GUARD 1 (local == base, newer): union with base = null; both sides [] -> key removed.
        val base = task(500)
        val remote = task(400)
        assertFalse(run(base, base, remote).containsKey("attachments"))
    }
}
