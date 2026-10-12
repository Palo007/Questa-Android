package com.example.data.sync.events

import com.example.data.sync.DropboxService
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.merge.Js
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.7 -- replays `events/evt_vectors.json`, produced by `tools/gen_evt_vectors.mjs` from Opti's
 * REAL syncEventsPush / syncEventsPull / syncEventsDeleteDevice and evt helpers at 7d5d4f3
 * (guard vectors, `evtpush-shrink-guard`, `evtpull-selfhealing`, `uid-collision` shapes).
 * Every scenario compares: uploads (path + records), the saved evt cfg and watermark, the
 * inserted records, the diag ring, the Dropbox call counts, and whether the op threw.
 */
class EvtOptiVectorsTest {

    private val root: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("events/evt_vectors.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }
    private val now get() = root["now"]!!.jsonPrimitive.long
    private val myDev get() = root["myDev"]!!.jsonPrimitive.content
    private val ageLimit get() = root["ageLimitMs"]!!.jsonPrimitive.long

    @Test
    fun vectorsComeFromThePinnedOptiCommit() {
        assertEquals("7d5d4f3", root["opti"]!!.jsonPrimitive.content)
        assertEquals(EvtUploadPolicy.EVENT_AGE_LIMIT_MS, ageLimit)
        assertTrue(root["scenarios"]!!.jsonArray.size >= 40)
    }

    @Test
    fun incomingSig_matchesOpti() {
        for (v in helper("sig")) {
            assertEquals(v.toString(), v["sig"]!!.jsonPrimitive.content, EvtDedup.incomingSig(v["r"]!!.jsonObject))
        }
    }

    @Test
    fun uidDisambiguate_matchesOpti() {
        for (v in helper("disambiguate")) {
            assertEquals(v["out"]!!.jsonPrimitive.content,
                EvtDedup.disambiguate(v["uid"]!!.jsonPrimitive.content, v["sig"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun uidHash_matchesOptiSha256() {
        for (v in helper("uidHash")) {
            val uids = v["uids"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
            assertEquals(v.toString(), v["hash"]!!.jsonPrimitive.content, EvtPushGuard.uidHash(uids))
        }
    }

    @Test
    fun uidsAreSuperset_matchesOpti() {
        for (v in helper("superset")) {
            val local = v["local"]!!.jsonArray.map { it.jsonPrimitive.content }
            val lh = (v["localHash"] as? JsonPrimitive)?.contentOrNull
            val kh = v["knownHash"]?.takeIf { it !is JsonNull }
            val ku = v["knownUids"]?.takeIf { it !is JsonNull }
            assertEquals(v.toString(), v["out"]!!.jsonPrimitive.booleanOrNull, EvtPushGuard.uidsAreSuperset(local, kh, ku, lh))
        }
    }

    @Test
    fun incomingFilter_legacySetForm_matchesOpti() {
        for (v in helper("filterSet")) {
            val recs = v["records"]!!.jsonArray.toList()
            val existing = v["existing"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val out = EvtDedup.incomingFilter(recs, existing, null, myDev, now, ageLimit)
            assertEquals(v["out"]!!.jsonArray.toList(), out)
        }
    }

    @Test
    fun monthOlderThan_matchesOpti() {
        for (v in helper("monthOlder")) {
            assertEquals(v["out"]!!.jsonPrimitive.booleanOrNull,
                EventSyncEngine.evtMonthOlderThan(v["month"]!!.jsonPrimitive.content, v["now"]!!.jsonPrimitive.long, ageLimit))
        }
    }

    @Test
    fun everyScenario_matchesOpti() = runBlocking {
        val failures = mutableListOf<String>()
        for (s in root["scenarios"]!!.jsonArray.map { it.jsonObject }) {
            try {
                replay(s)
            } catch (e: AssertionError) {
                failures.add(s["name"]!!.jsonPrimitive.content + ": " + e.message)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun helper(name: String) = root["helpers"]!!.jsonObject[name]!!.jsonArray.map { it.jsonObject }

    private fun long(o: JsonObject, k: String): Long = (o[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

    private suspend fun replay(s: JsonObject) {
        val name = s["name"]!!.jsonPrimitive.content
        val cfgIn = (s["cfg"] as? JsonObject) ?: JsonObject(emptyMap())
        val w = FakeWatermark(long(cfgIn, "evtLastUploadTs"))
        val cfg = InMemoryEvtCfgStore(EvtCfg(
            fullPushAt = long(cfgIn, "evtFullPushAt"),
            fileCounts = EvtTestData.map(cfgIn["evtFileCounts"]),
            pushBlocked = EvtTestData.map(cfgIn["evtPushBlocked"]),
            fileRevs = EvtTestData.map(cfgIn["evtFileRevs"]),
            badRevs = EvtTestData.map(cfgIn["evtBadRevs"]),
            lastPullAt = long(cfgIn, "evtLastPullAt"),
            fullScanAt = long(cfgIn, "evtFullScanAt"),
        ))
        val store = FakeEventStore((s["records"] as? JsonArray)?.map { EvtTestData.entityOf(it.jsonObject) } ?: emptyList())
        store.strictFails = s["strictFails"]?.jsonPrimitive?.booleanOrNull == true
        store.insertFails = (s["idb"] as? JsonPrimitive)?.contentOrNull == "abort"
        val remote = FakeEventsRemote(
            entries = (s["entries"] as? JsonArray)?.map {
                val o = it.jsonObject
                DropboxService.FileEntry(o["name"]!!.jsonPrimitive.content, (o["rev"] as? JsonPrimitive)?.contentOrNull)
            } ?: emptyList(),
        )
        (s["downloads"] as? JsonObject)?.forEach { (k, v) ->
            val o = v.jsonObject
            remote.downloads[k] = RawEventFile(o["text"]!!.jsonPrimitive.content, (o["rev"] as? JsonPrimitive)?.contentOrNull)
        }
        remote.listFails = s["listFails"]?.jsonPrimitive?.booleanOrNull == true
        remote.uploadFails = s["uploadFails"]?.jsonPrimitive?.booleanOrNull == true
        (s["deleteFails"] as? JsonArray)?.forEach { remote.deleteFails.add(it.jsonPrimitive.content) }
        val diag = DiagRing({ now })
        val opts = (s["opts"] as? JsonObject) ?: JsonObject(emptyMap())
        var threw: String? = null
        try {
            when (s["op"]!!.jsonPrimitive.content) {
                "push" -> EventPusher(w, cfg, store, { myDev }, { now }, diag).push(remote,
                    PushOpts(force = Js.truthy(opts["force"]), forceFullPush = Js.truthy(opts["forceFullPush"])))
                "pull" -> EventPuller(cfg, store, { myDev }, { now }, diag).pull(remote, force = Js.truthy(opts["force"]))
                "purge" -> purgeOwnEventFiles(remote, s["devId"]!!.jsonPrimitive.content)
            }
        } catch (e: EventsHttpException) {
            threw = e.message
        }
        val x = s["expect"]!!.jsonObject
        assertEquals("$name threw", (x["threw"] as? JsonPrimitive)?.contentOrNull, threw)

        val expUploads = x["uploads"]!!.jsonArray.map { it.jsonObject }
        assertEquals("$name upload paths", expUploads.map { it["path"]!!.jsonPrimitive.content }, remote.uploads.map { it.first })
        expUploads.zip(remote.uploads).forEach { (e, a) ->
            assertEquals("$name upload body ${a.first}", e["records"]!!.jsonArray.toList(), Json.parseToJsonElement(a.second).jsonArray.toList())
        }
        assertEquals("$name deletes", x["deletes"]!!.jsonArray.map { it.jsonPrimitive.content }, remote.deletes)
        assertEquals("$name inserted", x["inserted"]!!.jsonArray.toList(), store.inserted.map { EvtTestData.jsonOf(it) })
        assertEquals("$name diag", x["diag"]!!.jsonArray.toList(), diag.snapshot())

        val calls = x["calls"]!!.jsonObject
        assertEquals("$name list calls", calls["list"]!!.jsonPrimitive.long.toInt(), remote.listCalls)
        assertEquals("$name download calls", calls["download"]!!.jsonPrimitive.long.toInt(), remote.downloadCalls)
        assertEquals("$name upload calls", calls["upload"]!!.jsonPrimitive.long.toInt(), remote.uploadCalls)

        val c = x["cfg"]!!.jsonObject
        assertEquals("$name evtLastUploadTs", long(c, "evtLastUploadTs"), w.value)
        assertEquals("$name evtFullPushAt", long(c, "evtFullPushAt"), cfg.cfg.fullPushAt)
        assertEquals("$name evtLastPullAt", long(c, "evtLastPullAt"), cfg.cfg.lastPullAt)
        assertEquals("$name evtFullScanAt", long(c, "evtFullScanAt"), cfg.cfg.fullScanAt)
        assertMap("$name evtFileCounts", c["evtFileCounts"], cfg.cfg.fileCounts)
        assertMap("$name evtPushBlocked", c["evtPushBlocked"], cfg.cfg.pushBlocked)
        assertMap("$name evtFileRevs", c["evtFileRevs"], cfg.cfg.fileRevs)
        assertMap("$name evtBadRevs", c["evtBadRevs"], cfg.cfg.badRevs)
    }

    private fun assertMap(msg: String, expected: JsonElement?, actual: Map<String, JsonElement>) {
        assertNotNull(msg, expected)
        val e = EvtTestData.map(expected)
        assertEquals("$msg keys", e.keys.toList(), actual.keys.toList())
        assertEquals(msg, e, actual)
    }
}
