package com.example.data.sync

import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TombstoneEntity
import com.example.data.sync.backup.AutoBackupEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun obj(vararg kv: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf(*kv.map { (k, v) -> k to el(v) }.toTypedArray()))
private fun el(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { el(it) })
    else -> JsonPrimitive(v.toString())
}
private fun t(id: String, type: String, title: String = id, ua: Long? = null, ca: Long? = null) =
    obj(*listOfNotNull("id" to id, "type" to type, "title" to title, ua?.let { "updatedAt" to it }, ca?.let { "createdAt" to it }).toTypedArray())
private fun ids(s: JsonObject, k: String = "tasks") = s[k]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
private fun titles(s: JsonObject) = s["tasks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }

/** granular-export-import.test.js G1-G8 on the pure registry. */
class GranularExportImportTest {
    private val local = obj(
        "version" to 1,
        "char" to obj("name" to "Me", "lvl" to 5),
        "tasks" to listOf(t("h1", "habit"), t("h2", "habit"), t("d1", "daily"), t("o1", "todo", "Taxes", ua = 10), t("o2", "todo", "Call bank", ua = 50), t("o3", "todo")),
        "rewards" to listOf(obj("id" to "r1")),
        "tags" to listOf(obj("id" to "g1")),
        "history" to listOf<Any>(), "charHistory" to listOf(obj("date" to 1)), "monthlyBackups" to listOf("2026-06", "2026-07"),
        "devices" to listOf(obj("id" to "dev-a")), "deletions" to listOf(obj("id" to "x", "at" to 1)),
        "prefs" to obj("width" to 480),
    )

    @Test fun g1_counts() {
        assertEquals(2, ExportSections.count(Section.HABITS, local))
        assertEquals(1, ExportSections.count(Section.DAILIES, local))
        assertEquals(3, ExportSections.count(Section.TODOS, local))
        assertEquals(1, ExportSections.count(Section.REWARDS, local))
        assertEquals(3, ExportSections.count(Section.HISTORY, local))
        assertEquals(2, ExportSections.count(Section.DEVICES, local))
    }

    @Test fun g2_fullSelection() {
        assertTrue(ExportSections.isFull(Section.entries))
        assertFalse(ExportSections.isFull(Section.entries.drop(1)))
        assertFalse(ExportSections.isFull(emptyList()))
    }

    @Test fun g4_detect() {
        assertEquals(Section.entries.filter { it != Section.EVENTS }, ExportSections.detect(local))
        assertEquals("a tasks array with zero habits still offers habits",
            listOf(Section.HABITS, Section.DAILIES, Section.TODOS), ExportSections.detect(obj("tasks" to listOf<Any>())))
        assertEquals(listOf(Section.EVENTS), ExportSections.detect(obj("events" to listOf(obj("ts" to 1), obj("ts" to 2)))))
        assertEquals("the manifest is trusted", listOf(Section.TODOS, Section.REWARDS),
            ExportSections.detect(obj("_backup" to obj("sections" to listOf("todos", "rewards")), "char" to obj())))
        assertTrue(ExportSections.detect(obj("hello" to "world")).isEmpty())
        assertTrue(ExportSections.detect(null).isEmpty())
    }

    @Test fun g5_replaceTodos_replacesOnlyTodos() {
        val file = obj("tasks" to listOf(t("oNEW", "todo")), "char" to obj("name" to "Other"))
        val out = ExportSections.applySectionsToState(local, file, setOf(Section.TODOS), ImportMode.REPLACE)
        assertEquals(listOf("h1", "h2", "d1", "oNEW"), ids(out))
        for (k in listOf("char", "rewards", "tags", "prefs", "devices", "deletions")) assertEquals(k, local[k], out[k])
    }

    @Test fun g5b_anUntickedSectionOfTheFile_isIgnored() {
        val file = obj("char" to obj("name" to "Other"), "rewards" to listOf<Any>())
        val out = ExportSections.applySectionsToState(local, file, setOf(Section.REWARDS), ImportMode.REPLACE)
        assertEquals(local["char"], out["char"])
        assertEquals(JsonArray(emptyList()), out["rewards"])
    }

    @Test fun g6_mergeTodos_unionNewerWins_neverShrinks() {
        val file = obj("tasks" to listOf(t("o1", "todo", "Taxes NEWER", ua = 20), t("o2", "todo", "Call bank OLD", ua = 5), t("o9", "todo", "New")))
        val out = ExportSections.applySectionsToState(local, file, setOf(Section.TODOS), ImportMode.MERGE)
        assertEquals(listOf("h1", "h2", "d1", "o1", "o2", "o3", "o9"), ids(out))
        assertEquals(listOf("h1", "h2", "d1", "Taxes NEWER", "Call bank", "o3", "New"), titles(out))
    }

    @Test fun g7_mergeTies_keepLocal_createdAtIsTheFallback() {
        fun m(l: JsonObject, i: JsonObject) = ExportSections.mergeById(JsonArray(listOf(l)), JsonArray(listOf(i))).single().jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("L", m(t("a", "todo", "L"), t("a", "todo", "I")))
        assertEquals("L", m(t("a", "todo", "L", ua = 5), t("a", "todo", "I")))
        assertEquals("L", m(t("a", "todo", "L", ua = 5), t("a", "todo", "I", ua = 5)))
        assertEquals("I", m(t("a", "todo", "L", ca = 100), t("a", "todo", "I", ca = 200)))
    }

    @Test fun g8_charMerge_devicesReplace_deletionsUnion_eventsNoop_emptyFileNoop() {
        val merged = ExportSections.applySectionsToState(local, obj("char" to obj("name" to "Other", "gold" to 9)), setOf(Section.CHAR), ImportMode.MERGE)
        assertEquals(obj("gold" to 9, "name" to "Me", "lvl" to 5).toMap(), merged["char"]!!.jsonObject.toMap())
        val replaced = ExportSections.applySectionsToState(local, obj("char" to obj("name" to "Other")), setOf(Section.CHAR), ImportMode.REPLACE)
        assertEquals(obj("name" to "Other"), replaced["char"])
        val dev = ExportSections.applySectionsToState(local, obj("devices" to listOf(obj("id" to "dev-z")), "deletions" to listOf(obj("id" to "y", "at" to 2))),
            setOf(Section.DEVICES), ImportMode.REPLACE)
        assertEquals(listOf("dev-z"), ids(dev, "devices"))
        assertEquals("tombstones are never dropped", listOf("x", "y"), ids(dev, "deletions"))
        val ev = ExportSections.applySectionsToState(local, obj("events" to listOf<Any>()), setOf(Section.EVENTS), ImportMode.REPLACE)
        assertEquals(local, ev)
        val none = ExportSections.applySectionsToState(local, obj(), Section.entries, ImportMode.REPLACE)
        assertEquals(CanonicalJson.canonicalJsonString(local), CanonicalJson.canonicalJsonString(none))
    }

    @Test fun prefsReplace_takesLastCron_mergeKeepsLocal() {
        val file = obj("prefs" to obj("width" to 600, "haptics" to false), "lastCron" to 20260701)
        val r = ExportSections.applySectionsToState(local, file, setOf(Section.PREFS), ImportMode.REPLACE)
        assertEquals(obj("width" to 600, "haptics" to false), r["prefs"])
        assertEquals(JsonPrimitive(20260701), r["lastCron"])
        val m = ExportSections.applySectionsToState(local, file, setOf(Section.PREFS), ImportMode.MERGE)
        assertEquals(JsonPrimitive(480), m["prefs"]!!.jsonObject["width"])
        assertEquals(JsonPrimitive(false), m["prefs"]!!.jsonObject["haptics"])
        assertEquals(null, m["lastCron"])
    }
}

/** PX-62: an unknown short event code is refused; a long untokenized field passes. */
class UnknownTokenRefusalTest {
    private fun envelope(vararg event: Pair<String, JsonElement>) = buildJsonObject {
        put("_backup", buildJsonObject { put("schema", JsonPrimitive(2)) })
        put("K", JsonArray(listOf(JsonPrimitive("habit"))))
        put("SRC", JsonArray(emptyList())); put("TID", JsonArray(emptyList())); put("TT", JsonArray(emptyList()))
        put("FM", buildJsonObject {}); put("S", buildJsonObject { put("tasks", JsonArray(emptyList())) })
        put("E", JsonArray(listOf(JsonObject(linkedMapOf(*event)))))
    }

    @Test fun anUnknownShortCode_throws() {
        try {
            SerializationEngine.importBackup(envelope("t" to JsonPrimitive(1), "k" to JsonPrimitive(0), "zz" to JsonPrimitive(5)).toString())
            fail("an unknown code must be refused")
        } catch (e: UnknownFieldCodeException) {
            assertEquals("zz", e.fieldCode)
        }
    }

    @Test fun aLongUntokenizedField_isCarriedThrough() {
        val b = SerializationEngine.importBackup(envelope("t" to JsonPrimitive(1), "k" to JsonPrimitive(0), "winnerDev" to JsonPrimitive("x")).toString())
        assertEquals("habit", b.events.single().kind)
        assertTrue(b.events.single().payloadJson!!.contains("winnerDev"))
    }

    @Test fun src_isAcceptedOnImport_reviewFix2() {
        // [DEFERRED_OPEN_QUESTION] Opti refuses this file (node check, WP-8.9 notes); Kotlin accepts `src`.
        val d = SerializationEngine.decodeBackup(envelope("t" to JsonPrimitive(1), "k" to JsonPrimitive(0), "src" to JsonPrimitive("android-shortcut")))
        assertEquals(JsonPrimitive("android-shortcut"), d.events.single()["src"])
    }

    @Test fun otherUnknownShortCodes_areStillRefused() {
        for (code in listOf("zz", "abc", "q")) {
            try {
                SerializationEngine.decodeBackup(envelope("t" to JsonPrimitive(1), code to JsonPrimitive(1)))
                fail("$code must be refused")
            } catch (e: UnknownFieldCodeException) {
                assertEquals(code, e.fieldCode)
            }
        }
    }

    @Test fun everyKnownCode_isShortCodeShaped() {
        assertTrue(SerializationEngine.EXPORT_FIELD_MAP.values.all { Regex("^[a-z]{1,3}$").matches(it) })
    }
}

/** The import on Room: picked sections only, in one diff; PX-61 and the round trip (GR). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SectionImportRoomTest : Wp89TestBase() {

    private suspend fun seed(d: Wp89Device) {
        d.repository.insertTask(TaskEntity(id = "h1", type = "habit", title = "Habit", updatedAt = 1L, orderIndex = 0L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.repository.insertTask(TaskEntity(id = "d1", type = "daily", title = "Daily", updatedAt = 1L, orderIndex = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.repository.insertTask(TaskEntity(id = "o1", type = "todo", title = "Old todo", updatedAt = 1L, orderIndex = 2L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.repository.insertReward(RewardEntity(id = "r1", title = "Treat", cost = 5.0, createdAt = 1L, updatedAt = 1L))
        d.repository.insertTag(TagEntity(id = "g1", name = "Tag", color = "#fff", createdAt = 1L, updatedAt = 1L))
        d.repository.insertTombstone(TombstoneEntity(id = "gone", at = 3L))
        d.repository.getCharacterSync()
    }

    private suspend fun snapshotRows(d: Wp89Device) = listOf(
        d.repository.getAllTasksSync().filter { it.type != "todo" },
        d.repository.getAllRewardsSync(), d.repository.getAllTagsSync(), d.repository.getAllTombstonesSync(),
        d.repository.getAllDevicesSync(), d.repository.getAllCharHistorySync(), d.repository.getCharacterSync(),
        d.repository.readSyncPrefs(),
    )

    @Test fun aTodosOnlyImport_changesZeroOtherRows() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertTask(TaskEntity(id = "oNEW", type = "todo", title = "New todo", updatedAt = 9L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        src.repository.insertReward(RewardEntity(id = "rX", title = "Other", cost = 1.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val file = src.live.exporter { 1L }.export(setOf(Section.TODOS))

        val d = device89("dev-dst")
        seed(d)
        val before = snapshotRows(d)
        val r = d.importer.importBackup(file, setOf(Section.TODOS), ImportMode.REPLACE)
        assertTrue(r.changed)
        assertEquals(listOf("oNEW"), d.repository.getAllTasksSync().filter { it.type == "todo" }.map { it.id })
        assertEquals(before, snapshotRows(d))
    }

    @Test fun px61_aPartialFile_neverWipesAbsentSections_evenWithEveryBoxTicked() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertTask(TaskEntity(id = "oNEW", type = "todo", title = "New todo", updatedAt = 9L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val file = src.live.exporter { 1L }.export(setOf(Section.TODOS))
        val d = device89("dev-dst")
        seed(d)
        val r = d.importer.importBackup(file, Section.entries.toSet(), ImportMode.REPLACE)
        assertEquals(listOf(Section.TODOS), r.sections)
        assertFalse(r.full)
        assertEquals(listOf("h1", "d1", "oNEW"), d.repository.getAllTasksSync().sortedBy { it.orderIndex }.map { it.id })
        assertEquals(listOf("r1"), d.repository.getAllRewardsSync().map { it.id })
        assertEquals(listOf("gone"), d.repository.getAllTombstonesSync().map { it.id })
    }

    @Test fun gr_partialExport_roundTrips_intoADeviceMissingTodos() = runBlocking {
        val src = device89("dev-src")
        listOf("a", "b", "c").forEach { src.repository.insertTask(TaskEntity(id = "o$it", type = "todo", title = it, updatedAt = 2L, createdAt = com.example.data.clock.PhysicalClock.nowMs())) }
        src.repository.insertReward(RewardEntity(id = "r1", title = "Treat", cost = 5.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val file = src.live.exporter { 1L }.export(setOf(Section.TODOS, Section.REWARDS))
        val meta = file["_backup"]!!.jsonObject
        assertEquals(JsonPrimitive(true), meta["partial"])
        assertEquals(listOf("todos", "rewards"), meta["sections"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, meta["items"]!!.jsonObject["tasks"]!!.jsonPrimitive.content.toInt())

        val d = device89("dev-dst")
        d.repository.insertTask(TaskEntity(id = "h1", type = "habit", title = "Habit", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val lvlBefore = d.repository.getCharacterSync()
        val decoded = d.importer.decode(file)
        assertEquals(listOf(Section.TODOS, Section.REWARDS), ExportSections.detect(decoded))
        assertFalse(decoded.containsKey("char"))
        d.importer.importBackup(file, setOf(Section.TODOS, Section.REWARDS), ImportMode.MERGE)
        assertEquals(setOf("h1", "oa", "ob", "oc"), d.repository.getAllTasksSync().map { it.id }.toSet())
        assertEquals(lvlBefore.lvl, d.repository.getCharacterSync().lvl)
        assertEquals(lvlBefore.name, d.repository.getCharacterSync().name)
    }

    @Test fun aFullReplace_isTheWholeFile() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertTask(TaskEntity(id = "x1", type = "habit", title = "X", updatedAt = 2L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        val file = src.live.exporter { 1L }.export(null)
        val d = device89("dev-dst")
        seed(d)
        val r = d.importer.importBackup(file, Section.entries.toSet(), ImportMode.REPLACE)
        assertTrue(r.full)
        assertEquals(listOf("x1"), d.repository.getAllTasksSync().map { it.id })
        assertTrue(d.repository.getAllRewardsSync().isEmpty())
        assertTrue("a full replace takes the file's deletions", d.repository.getAllTombstonesSync().isEmpty())
    }

    @Test fun events_areUnionAdded_andIdempotent() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_000_000L, kind = "habit", taskTitle = "a"))
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_100_000L, kind = "habit", taskTitle = "b"))
        val file = src.live.exporter { 1L }.export(setOf(Section.EVENTS))
        val d = device89("dev-dst")
        d.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_600_000_000_000L, kind = "habit", taskTitle = "mine"))
        val r1 = d.importer.importBackup(file, setOf(Section.EVENTS), ImportMode.REPLACE)
        assertEquals(2, r1.eventsAdded)
        val r2 = d.importer.importBackup(file, setOf(Section.EVENTS), ImportMode.REPLACE)
        assertEquals(0, r2.eventsAdded)
        assertEquals(2, r2.eventsSkipped)
        val titles = d.repository.allLogs.first().map { it.taskTitle }
        assertTrue("mine" in titles && "a" in titles && "b" in titles)
    }

    /** CR-KT-009: a failed event write is counted, not hidden (Opti bulkAddEvents `failed`, app.js:8569-8580). */
    @Test fun aFailedEventWrite_isCountedAsFailed() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_000_000L, kind = "habitTap", taskTitle = "a"))
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_100_000L, kind = "habitTap", taskTitle = "b"))
        val file = src.live.exporter { 1L }.export(setOf(Section.EVENTS))
        val d = device89("dev-dst")
        val importer = SectionImporter(d.repository, d.syncDataStore, d.backupPrefs, insertEvent = {
            if (it.taskTitle == "b") throw IllegalStateException("disk full") else d.repository.insertImportedLog(it)
        })
        val r = importer.importBackup(file, setOf(Section.EVENTS), ImportMode.MERGE)
        assertEquals(1, r.eventsAdded)
        assertEquals(0, r.eventsSkipped)
        assertEquals(1, r.eventsFailed)
        assertEquals(listOf("a"), d.repository.allLogs.first().filter { it.kind == "habitTap" }.map { it.taskTitle })
    }

    /**
     * CR-KT-010: the import skips an event whose CONTENT is already stored under another uid, and a
     * second copy inside the same file (Opti eventMergeFilter, app.js:1406). Fixture made by running
     * the PWA functions in Node.
     */
    @Test fun importDedup_bySignature_matchesOpti() = runBlocking {
        val fx = Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("fixtures/CR-KT-010/import_dedup.json")!!.readText()).jsonObject
        val sigs = fx["sigs"]!!.jsonObject
        fx["incoming"]!!.jsonArray.forEach {
            val e = SerializationEngine.parseEventEntity(it.jsonObject)
            assertEquals(sigs[e.uid!!]!!.jsonPrimitive.content, com.example.data.sync.events.EvtDedup.entitySig(e))
        }
        val src = device89("dev-src")
        fx["incoming"]!!.jsonArray.forEach { src.repository.insertImportedLog(SerializationEngine.parseEventEntity(it.jsonObject)) }
        val file = src.live.exporter { 1L }.export(setOf(Section.EVENTS))
        val d = device89("dev-dst")
        fx["existing"]!!.jsonArray.forEach { d.repository.insertImportedLog(SerializationEngine.parseEventEntity(it.jsonObject)) }
        val r = d.importer.importBackup(file, setOf(Section.EVENTS), ImportMode.MERGE)
        val want = fx["expected_added_uids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(want.size, r.eventsAdded)
        assertEquals(fx["expected_skipped"]!!.jsonPrimitive.content.toInt(), r.eventsSkipped)
        val stored = d.repository.allLogs.first().filter { it.kind != "import" }.mapNotNull { it.uid }.toSet()
        assertEquals(setOf("u1", "u2") + want, stored)
    }

    /** CR-KT-009: the summary of ALL embedded events goes into ImportResult and the import note (app.js:8575). */
    @Test fun importNote_carriesTheEventSummary() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_000_000L, kind = "habitTap", taskTitle = "a"))
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(timestamp = 1_700_000_100_000L, kind = "lifecycle", taskTitle = "b"))
        val file = src.live.exporter { 1L }.export(setOf(Section.EVENTS))
        val d = device89("dev-dst")
        d.importer.importBackup(file, setOf(Section.EVENTS), ImportMode.MERGE)
        val r = d.importer.importBackup(file, setOf(Section.EVENTS), ImportMode.MERGE)
        assertEquals(ImportSummary(2, 1, 1, mapOf("habit" to 1, "daily" to 0, "todo" to 0, "system" to 0)), r.summary)
        val notes = d.repository.allLogs.first().filter { it.kind == "import" }.map {
            kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson!!).jsonObject["notes"]!!.jsonPrimitive.content
        }
        assertTrue(notes.toString(), "Restored 0 events (2 stored total · 1 visible in feed · 1 system/diagnostic hidden · " +
            "Habits 1, Dailies 0, To-dos 0, System 0; 2 already present, skipped) [events; merge]" in notes)
    }

    /**
     * Review fix 2 + WP-8.9-R1 (Decision Pali 2026-09-25: B): a Kotlin backup with a phone-tap
     * event imports, and the BACKUP file drops `src` (Opti's importData refuses it). The other
     * payload keys stay.
     */
    @Test fun aPhoneTapWithSrc_backupDropsSrc_andImports() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(com.example.data.local.entities.EventLogEntity(
            timestamp = 1_700_000_000_000L, kind = "habitTap", taskId = "h1", taskTitle = "Walk", direction = 1,
            payloadJson = "{\"src\":\"android-shortcut\",\"late\":true,\"scored\":false}"))
        val file = src.live.exporter { 1L }.export(null)
        val e = file["E"]!!.jsonArray.single().jsonObject
        assertFalse("R-1: `src` is not written to a backup", e.containsKey("src"))
        assertTrue("R-1: the source Room row keeps `src`",
            src.repository.allLogs.first().single().payloadJson!!.contains("\"src\":\"android-shortcut\""))

        val d = device89("dev-dst")
        val r = d.importer.importBackup(file, Section.entries.toSet(), ImportMode.REPLACE)
        assertEquals(1, r.eventsAdded)
        val got = d.repository.allLogs.first().single { it.kind == "habitTap" }
        assertFalse(got.payloadJson!!.contains("\"src\""))
        assertTrue(got.payloadJson!!.contains("\"late\":true"))
        // The legacy entry point too.
        assertEquals(1, SerializationEngine.importBackup(CanonicalJson.stringifyPretty(file)).events.size)
    }

    @Test fun prefsImport_restoresTheBackupTiers_withTheMigrateBackfill() = runBlocking {
        val d = device89("dev-dst")
        val file = obj("prefs" to obj("exportIntervalDays" to 7, "lastExportTs" to 1234))
        d.importer.importBackup(file, setOf(Section.PREFS), ImportMode.REPLACE)
        assertEquals(AutoBackupEnabled(weekly = true), d.backupPrefs.autoBackupEnabled())
        assertEquals(1234L, d.syncDataStore.lastExportTs.first())
    }

    @Test fun aNonQuestaFile_isRefused() = runBlocking {
        val d = device89("dev-dst")
        try {
            d.importer.importBackup(obj("hello" to "world"), Section.entries.toSet(), ImportMode.REPLACE)
            fail("must refuse")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not look like a valid Questa backup"))
        }
    }

    /** Review 22 / WP-8.3: the live pause row wins over a stale imported pause in a real backup. */
    @Test fun liveBackup_takesThePauseFromTheLiveRow() = runBlocking {
        val d = device89("dev-p")
        d.repository.saveSnapshotExtrasJson(CanonicalJson.canonicalJsonString(obj("prefs" to obj("paused" to true, "pausedDays" to listOf(20260101)))))
        d.repository.writeSyncPrefs(SyncPrefsEntity(id = 0, paused = false, pausedDays = emptyList(), pausedAt = null, extraJson = null))
        val snap = d.live.fullLocalState().toSnapshot()
        val prefs = snap["prefs"]!!.jsonObject
        assertEquals(JsonPrimitive(false), prefs["paused"])
        assertEquals(JsonArray(emptyList()), prefs["pausedDays"])
        assertNotEquals(null, prefs["autoBackupEnabled"])
        val text = d.live.backupText(d.live.exporter { 1L })
        assertTrue((Json.parseToJsonElement(text) as JsonObject).containsKey("_backup"))
    }
}
