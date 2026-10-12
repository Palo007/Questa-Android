package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-8.3 G4: `buildBackupFile` / `computeHash` (app.js:7416-7475, :1589-1629) on the torture state
 * (12 values: a CJK + accented string, 0.1+0.2, 1e21, 5e-7, Number.MAX_VALUE, 2^53-1, a lone
 * surrogate, an explicit null, -1, -0, 0.30000000000000004, and an event field 2^53+1).
 *
 * The expected hash is the `_backup.hash` of fixtures/opti/G4/torture/backup.json, which
 * tools/gen_opti_fixtures.mjs produces by running Opti 7d5d4f3's own buildBackupFile +
 * computeHashWith (app.js:1589-1629, 7015-7497) in node. Nothing here is a pasted constant, so a
 * regenerate of the fixtures re-checks it. The event carries 2^53+1 in `storedSeq` (a known field
 * code) so Opti's importData accepts the file; see tools/verify_with_pwa.mjs --import.
 */
class HashPreimageGoldenTest {

    companion object {
        const val FIXED = 1790251200000L
        const val APP_VERSION = "v2026.09.24-1915"
        /** The torture `S`, exactly as the generator (gen_opti_fixtures.mjs genG4) builds it. */
        val TORTURE_S: JsonObject = Json.parseToJsonElement(
            """{"char":{"name":"菮 émigré line","gold":0.30000000000000004,"xp":1e21,"hp":5e-7,
               "maxHp":1.7976931348623157e308,"lvl":9007199254740991,"updatedAt":1790251200000},
               "tasks":[{"id":"123","type":"todo","title":"lone-surrogate:\ud800","notes":null,"value":-1,
                         "done":false,"checklist":[],"createdAt":1,"updatedAt":1790251200000},
                        {"id":"456","type":"habit","title":"int-like-key","value":0.30000000000000004,"cUp":-0,
                         "createdAt":1,"updatedAt":1790251200000}],
               "rewards":[],"tags":[],"devices":[],"lastCron":20260924,"history":[],"charHistory":[],
               "prefs":{"an":{"views":[],"metrics":[]}},"monthlyBackups":[],"deletions":[]}"""
        ).jsonObject

        fun tortureState(): FullLocalState {
            val s = TORTURE_S
            val an = s.getValue("prefs").jsonObject.getValue("an").jsonObject
            val wire = LocalWireState(
                char = s.getValue("char").jsonObject,
                tasks = s.getValue("tasks").jsonArray,
                rewards = s.getValue("rewards").jsonArray,
                tags = s.getValue("tags").jsonArray,
                devices = s.getValue("devices").jsonArray,
                lastCron = s.getValue("lastCron").jsonPrimitive.int,
                history = s.getValue("history").jsonArray,
                charHistory = s.getValue("charHistory").jsonArray,
                anViews = an.getValue("views").jsonArray,
                anMetrics = an.getValue("metrics").jsonArray,
                pause = LocalWireState.pauseWire(null),
                monthlyBackups = s.getValue("monthlyBackups").jsonArray,
                deletions = s.getValue("deletions").jsonArray,
                // The torture S has no pause row at all (real Opti, given this exact literal S,
                // never writes prefs.paused/pausedDays/pausedAt -- see BackupExporterPauseTest for
                // the case where a row DOES exist).
                pauseRowExists = false
            )
            // The torture S has no `version` and puts prefs before monthlyBackups; that key order
            // is what the last-known original S (snapshotExtras) supplies on a real device.
            return FullLocalState(wire = wire, prefs = JsonObject(emptyMap()), version = null, snapshotExtras = s)
        }

        val TORTURE_EVENTS = listOf(
            EventLogEntity(
                id = 1, timestamp = FIXED - 1000, uid = "fx-device-0001-abc-1", dev = "fx-device-0001",
                kind = "complete", taskId = "123", payloadJson = """{"storedSeq":9007199254740993}"""
            )
        )

        fun fixture(rel: String): String {
            val f = File("../fixtures/opti/$rel").takeIf { it.isFile } ?: File("fixtures/opti/$rel")
            return f.readText(Charsets.UTF_8)
        }

        /** `_backup.hash` as Opti's own computeHashWith wrote it into the G4 fixture. */
        fun fixtureHash(): String =
            Json.parseToJsonElement(fixture("G4/torture/backup.json")).jsonObject.getValue("_backup").jsonObject
                .getValue("hash").jsonPrimitive.content

        fun tortureEnvelope(): JsonObject =
            buildBackupFile(tortureState(), TORTURE_EVENTS, null, false, FIXED, "fx-device-0001", APP_VERSION)
    }

    private fun detokenizedEvents(env: JsonObject) = SerializationEngine.detokenizeEvents(env)

    /** The Kotlin preimage hashes to Opti's hash, so it is byte-equal to Opti's `JSON.stringify(backup)`. */
    @Test
    fun preimageHashesToOptiHash() {
        val env = tortureEnvelope()
        val s = SerializationEngine.detokenizeDeep(env.getValue("S"),
            env.getValue("FM").jsonObject.entries.associate { (k, v) -> v.jsonPrimitive.content to k }).jsonObject
        val preimage = backupHashPreimage(s, detokenizedEvents(env), env.getValue("_backup").jsonObject)
        assertEquals(fixtureHash(), SerializationEngine.computeSha256(preimage))
    }

    @Test
    fun sha256IsOptiSha256() {
        assertEquals(fixtureHash(), tortureEnvelope().getValue("_backup").jsonObject.getValue("hash").jsonPrimitive.content)
    }

    @Test
    fun fileBytesEqualG4() {
        assertEquals(fixture("G4/torture/backup.json"), CanonicalJson.stringifyPretty(tortureEnvelope()))
    }

    /**
     * Writes the Kotlin torture export (untracked, under app/build) for the node check that runs
     * Opti's real importData on it: `node tools/verify_with_pwa.mjs --import
     * app/build/kotlin_torture_export.json` must print "import OK".
     */
    @Test
    fun writesKotlinTortureExport() {
        val buildDir = if (File("../fixtures").isDirectory) File("build") else File("app/build")
        buildDir.mkdirs()
        val out = File(buildDir, "kotlin_torture_export.json")
        val text = CanonicalJson.stringifyPretty(tortureEnvelope())
        out.writeText(text, Charsets.UTF_8)
        assertEquals(text, out.readText(Charsets.UTF_8))
    }

    @Test
    fun hashAlgoIsWrittenBeforeHash() {
        val keys = tortureEnvelope().getValue("_backup").jsonObject.keys.toList()
        assertEquals(listOf("schema", "exportedAt", "appVersion", "eventCount", "items", "hashAlgo", "hash"), keys)
        assertEquals(listOf("_backup", "K", "SRC", "TID", "TT", "FM", "S", "E"), tortureEnvelope().keys.toList())
    }

    @Test
    fun partialExportIsSelfDescribingAndHashesTheSlice() {
        val env = buildBackupFile(tortureState(), TORTURE_EVENTS, setOf(Section.TODOS, Section.EVENTS), false, FIXED, "d", APP_VERSION)
        val meta = env.getValue("_backup").jsonObject
        assertEquals(JsonPrimitive(true), meta["partial"])
        assertEquals(JsonArray(listOf(JsonPrimitive("todos"), JsonPrimitive("events"))), meta["sections"])
        assertEquals(JsonPrimitive(1), meta.getValue("items").jsonObject["tasks"])
        val fm = env.getValue("FM").jsonObject.entries.associate { (k, v) -> v.jsonPrimitive.content to k }
        val s = SerializationEngine.detokenizeDeep(env.getValue("S"), fm).jsonObject
        assertEquals(listOf("tasks"), s.keys.toList())
        val pre = backupHashPreimage(s, detokenizedEvents(env), meta)
        assertEquals(meta.getValue("hash").jsonPrimitive.content, SerializationEngine.computeSha256(pre))
    }

    @Test
    fun eventsSectionOffGivesZeroEvents() {
        val env = buildBackupFile(tortureState(), TORTURE_EVENTS, setOf(Section.CHAR), false, FIXED, "d", APP_VERSION)
        assertEquals(JsonPrimitive(0), env.getValue("_backup").jsonObject["eventCount"])
        assertTrue(env.getValue("E").jsonArray.isEmpty())
    }

    /** app.js:7066 / :7133: a null boolean stays null and a null dictionary field is -1 -> null. */
    @Test
    fun nullEventFieldsSurviveTokenizationAndTheHashGate() {
        val ev = EventLogEntity(id = 2, timestamp = 5, uid = "u", dev = "d", kind = "complete",
            payloadJson = """{"done":null,"repCounted":true,"source":null}""")
        val env = buildBackupFile(tortureState(), listOf(ev), null, false, FIXED, "d", APP_VERSION)
        val e = env.getValue("E").jsonArray[0].jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, e["do"])
        assertEquals(JsonPrimitive(-1), e["o"])
        val back = SerializationEngine.detokenizeEvents(env)[0]
        assertEquals(kotlinx.serialization.json.JsonNull, back["done"])
        assertEquals(kotlinx.serialization.json.JsonNull, back["source"])
        assertEquals(JsonPrimitive(true), back["repCounted"])
    }

    @Test
    fun legacyExportBackupNowCarriesHashAlgoAndVerifies() {
        val text = SerializationEngine.exportBackup(
            nowPhysical = 1_790_251_200_000L, // WP-8.5: fixed export stamp (D18)
            character = SerializationEngine.parseCharacterEntity(buildJsonObject { put("name", JsonPrimitive("A")) }),
            tasks = emptyList(), rewards = emptyList(), events = emptyList(), deletions = emptyList(), lastCron = "20260924"
        )
        val env = Json.parseToJsonElement(text).jsonObject
        val meta = env.getValue("_backup").jsonObject
        assertEquals(JsonPrimitive("sha256"), meta["hashAlgo"])
        assertFalse(text.contains("\"gr\""))
        // Kotlin's own importer accepts it (its hash check covers hashAlgo).
        SerializationEngine.importBackup(text)
    }
}
