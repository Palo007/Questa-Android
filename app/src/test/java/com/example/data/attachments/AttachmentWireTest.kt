package com.example.data.attachments

import com.example.data.local.Converters
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.CanonicalJson
import com.example.data.sync.SerializationEngine
import com.example.data.sync.Wp89TestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CR-KT-015: the task key `attachments` on the wire, in Room and in the JSON backup. */
private val SA = "a".repeat(64)
private val SB = "b".repeat(64)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentWireTest : Wp89TestBase() {

    private val a1 = Attachment("i1", SA, w = 1600, h = 1200, bytes = 10, addedAt = 100)
    private val a2 = Attachment("i2", SB, w = 1200, h = 1600, bytes = 20, addedAt = 200)
    private fun task(vararg a: Attachment, extraJson: String? = null) =
        TaskEntity(id = "t1", type = "todo", title = "x", createdAt = 1, updatedAt = 2, attachments = a.toList(), extraJson = extraJson)

    @Test
    fun emptyList_omitsKey_freshAndOverlay() {
        assertFalse(SerializationEngine.buildTaskJson(task()).containsKey("attachments"))
        // A stale key in the original JSON is removed when the live list is empty.
        val stale = """{"id":"t1","type":"todo","title":"x","attachments":[{"id":"i1","sha":"$SA","mime":"image/jpeg","w":1600,"h":1200,"bytes":10,"addedAt":100}],"zz":1}"""
        val out = SerializationEngine.buildTaskJson(task(extraJson = stale))
        assertFalse(out.containsKey("attachments"))
        assertTrue("unknown keys survive", out.containsKey("zz"))
    }

    @Test
    fun nonEmpty_writesItemsInKeyOrder() {
        val out = SerializationEngine.buildTaskJson(task(a1, a2))
        assertEquals(
            """[{"id":"i1","sha":"$SA","mime":"image/jpeg","w":1600,"h":1200,"bytes":10,"addedAt":100},""" +
                """{"id":"i2","sha":"$SB","mime":"image/jpeg","w":1200,"h":1600,"bytes":20,"addedAt":200}]""",
            out["attachments"].toString()
        )
    }

    @Test
    fun originalKeptVerbatim_whenUnchanged_andReplacedWhenChanged() {
        // Foreign extra key inside an item survives while the list is unchanged.
        val orig = """{"id":"t1","type":"todo","title":"x","attachments":[{"id":"i1","sha":"$SA","mime":"image/jpeg","w":1600,"h":1200,"bytes":10,"addedAt":100,"x":true}]}"""
        val parsed = SerializationEngine.parseTaskEntity(Json.parseToJsonElement(orig).jsonObject)
        assertEquals(listOf(a1), parsed.attachments)
        val same = SerializationEngine.buildTaskJson(parsed)
        assertTrue(same["attachments"]!!.jsonArray[0].jsonObject.containsKey("x"))
        val added = SerializationEngine.buildTaskJson(parsed.copy(attachments = listOf(a1, a2)))
        assertEquals(listOf("i1", "i2"), added["attachments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun parse_missingOrEmptyOrJunk_isEmpty() {
        fun p(s: String) = SerializationEngine.parseTaskEntity(Json.parseToJsonElement(s).jsonObject).attachments
        assertEquals(emptyList<Attachment>(), p("""{"id":"t1"}"""))
        assertEquals(emptyList<Attachment>(), p("""{"id":"t1","attachments":[]}"""))
        assertEquals(emptyList<Attachment>(), p("""{"id":"t1","attachments":null}"""))
        assertEquals(emptyList<Attachment>(), p("""{"id":"t1","attachments":[{"sha":"x"},5]}"""))
    }

    @Test
    fun roomConverter_roundTrips() {
        val c = Converters()
        assertEquals(listOf(a1, a2), c.toAttachments(c.fromAttachments(listOf(a1, a2))))
        assertEquals(emptyList<Attachment>(), c.toAttachments("not json"))
        assertEquals("[]", c.fromAttachments(emptyList()))
    }

    @Test
    fun backupExport_carriesMetadataOnly_schemaStays2_andImports() = runBlocking {
        val d = device89("dev-a")
        d.repository.insertTask(task(a1, a2))
        val file = d.live.exporter { 1_790_000_000_000L }.export(null)
        assertEquals(2, file["_backup"]!!.jsonObject["schema"]!!.jsonPrimitive.int)
        val decoded = SerializationEngine.decodeBackup(file)
        val t = (decoded.snapshot["tasks"] as JsonArray).single { it.jsonObject["id"]!!.jsonPrimitive.content == "t1" }.jsonObject
        assertEquals(AttachmentJson.listToJson(listOf(a1, a2)), t["attachments"])
        val text = CanonicalJson.stringifyPretty(file)
        assertFalse("no image bytes in the backup", text.contains("base64"))
        val imported = SerializationEngine.importBackup(text).tasks.single { it.id == "t1" }
        assertEquals(listOf(a1, a2), imported.attachments)
    }

    // ---- MAJOR 1: sha must be ^[0-9a-f]{64}$ (sync.js syncUploadPendingAttachments) -----------

    @Test
    fun parse_dropsItemsWhoseShaIsNotLowerHex64() {
        fun item(sha: String) = """{"id":"x","sha":"$sha","w":1,"h":1,"bytes":1,"addedAt":1}"""
        for (bad in listOf("../../evil", "A".repeat(64), "a".repeat(63), "a".repeat(65), "g".repeat(64), "a".repeat(63) + "/")) {
            assertEquals(bad, null, AttachmentJson.fromJson(Json.parseToJsonElement(item(bad))))
        }
        assertEquals(SA, AttachmentJson.fromJson(Json.parseToJsonElement(item(SA)))!!.sha)
    }

    private val foreign = """{"id":"i9","sha":"../../evil","mime":"image/jpeg","w":1,"h":1,"bytes":1,"addedAt":150}"""
    private val origWithForeign =
        """{"id":"t1","type":"todo","title":"x","attachments":[{"id":"i1","sha":"$SA","mime":"image/jpeg","w":1600,"h":1200,"bytes":10,"addedAt":100},$foreign]}"""

    @Test
    fun invalidWireItem_isNotTyped_butSurvivesRoundTripVerbatim() {
        val orig = Json.parseToJsonElement(origWithForeign).jsonObject
        val parsed = SerializationEngine.parseTaskEntity(orig)
        assertEquals(listOf(a1), parsed.attachments)
        assertEquals(orig["attachments"], SerializationEngine.buildTaskJson(parsed)["attachments"])
    }

    @Test
    fun invalidWireItem_survivesLocalAddAndRemove() {
        val parsed = SerializationEngine.parseTaskEntity(Json.parseToJsonElement(origWithForeign).jsonObject)
        val added = SerializationEngine.buildTaskJson(parsed.copy(attachments = listOf(a1, a2)))["attachments"]!!.jsonArray
        assertEquals(listOf("i1", "i9", "i2"), added.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(Json.parseToJsonElement(foreign), added[1])
        val removedAll = SerializationEngine.buildTaskJson(parsed.copy(attachments = emptyList()))["attachments"]!!.jsonArray
        assertEquals(listOf(Json.parseToJsonElement(foreign)), removedAll.toList())
    }
}
