package com.example.data.sync

import com.example.data.local.entities.SyncPrefsEntity
import com.example.data.local.entities.TombstoneEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import com.example.data.local.entities.CharAbsEntry
import com.example.data.local.entities.TaskAbsEntry
import org.junit.Test
import java.io.File

/**
 * WP-8.3 G1: `wrap(syncSubset())` (sync.js:806-824, :2326) must be BYTE-equal to the Opti
 * fixtures, both as `JSON.stringify` (state.json) and as `stableStringify` (state.stable.json).
 *
 * Each record goes through the real import path first (SerializationEngine.parse* -> Room entity),
 * then back out through [LocalWireState.fromEntities] and [SyncSubset.build], so this covers the
 * entity <-> wire mapping of every typed column, not only the subset assembly.
 */
class SyncSubsetGoldenTest {

    companion object {
        const val DEVICE = "fx-device-0001"
        const val SAVED_AT = 1790251200000L
        val CASES = listOf(
            "fresh", "typical", "pause_abs_cAbs_doneDay_cResetOn",
            "char_abs_map", "task_cabs_map", "devices_rewards_deletions"
        )

        fun fixture(rel: String): String {
            val f = File("../fixtures/opti/$rel").takeIf { it.isFile } ?: File("fixtures/opti/$rel")
            return f.readText(Charsets.UTF_8)
        }

        /** Rebuilds the fixture's state through Room entities, as a real device holds it. */
        fun wireFromFixture(name: String): LocalWireState {
            val st = Json.parseToJsonElement(fixture("G1/$name/state.json")).jsonObject.getValue("state").jsonObject
            fun arr(k: String) = st.getValue(k).jsonArray.map { it.jsonObject }
            val charObj = st.getValue("char").jsonObject
            val an = st.getValue("an").jsonObject
            val pause = st.getValue("pause").jsonObject
            val paused = pause.getValue("paused").jsonPrimitive.boolean
            return LocalWireState.fromEntities(
                character = if (charObj.isEmpty()) null else SerializationEngine.parseCharacterEntity(charObj),
                tasks = arr("tasks").map { SerializationEngine.parseTaskEntity(it) },
                rewards = arr("rewards").map { SerializationEngine.parseRewardEntity(it) },
                tags = arr("tags").map { SerializationEngine.parseTagEntity(it) },
                devices = arr("devices").map { SerializationEngine.parseDeviceEntity(it) },
                lastCron = st.getValue("lastCron").jsonPrimitive.int,
                history = st.getValue("history").jsonArray,
                charHistory = arr("charHistory").map { SerializationEngine.parseCharHistoryEntity(it) },
                views = an.getValue("views").jsonArray.map { SerializationEngine.parseViewEntity(it.jsonObject) },
                metrics = an.getValue("metrics").jsonArray,
                syncPrefs = if (!paused) null else SyncPrefsEntity(
                    paused = true,
                    pausedDays = pause.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int },
                    pausedAt = pause.getValue("at").jsonPrimitive.long,
                    extraJson = null
                ),
                monthlyBackups = st.getValue("monthlyBackups").jsonArray.map { it.jsonPrimitive.content },
                deletions = arr("deletions").map {
                    TombstoneEntity(it.getValue("id").jsonPrimitive.content, it.getValue("at").jsonPrimitive.long)
                }
            )
        }
    }

    private fun wrapped(name: String): JsonObject = wrap(SyncSubset.build(wireFromFixture(name)), DEVICE, SAVED_AT)

    @Test
    fun stateJsonIsByteEqual() {
        for (name in CASES) assertEquals(name, fixture("G1/$name/state.json"), CanonicalJson.stringify(wrapped(name)))
    }

    @Test
    fun stableJsonIsByteEqual() {
        for (name in CASES) assertEquals(name, fixture("G1/$name/state.stable.json"), StableJson.stableStringify(wrapped(name)))
    }

    @Test
    fun keyOrderIsSyncSubsetOrder() {
        val state = SyncSubset.build(wireFromFixture("typical"))
        assertEquals(SyncSubset.KEY_ORDER, state.keys.toList())
    }

    @Test
    fun cleanDevicesDedupesByIdKeepingFirstPosition() {
        val devices = Json.parseToJsonElement(
            """[{"id":"a","name":"","updatedAt":0},{"id":"b","name":"B","updatedAt":5},{"name":"no-id"},
               {"id":"a","name":"Phone","updatedAt":1},{"id":"b","name":"","updatedAt":4},{"id":""}]"""
        ).jsonArray
        assertEquals(
            """[{"id":"a","name":"Phone","updatedAt":1},{"id":"b","name":"B","updatedAt":5}]""",
            CanonicalJson.stringify(SyncSubset.cleanDevices(devices))
        )
    }

    @Test
    fun pauseDefaultsWhenNoPrefsRow() {
        assertEquals("""{"paused":false,"pausedDays":[],"at":0}""", CanonicalJson.stringify(LocalWireState.pauseWire(null)))
    }

    /** Opti `t.cAbs` = {deviceId: {ua, cUp, cDown}} (sync.js:1859-1872) decodes into the typed column. */
    @Test
    fun pauseFixtureCAbsDecodesAsDeviceMap() {
        val st = Json.parseToJsonElement(fixture("G1/pause_abs_cAbs_doneDay_cResetOn/state.json")).jsonObject
            .getValue("state").jsonObject
        val habit = SerializationEngine.parseTaskEntity(st.getValue("tasks").jsonArray[0].jsonObject)
        val cAbs = habit.cAbs
        assertNotNull("t.cAbs must decode to a device-keyed map", cAbs)
        assertEquals(listOf("fx-device-0001", "gx-dev-02"), cAbs!!.keys.toList())
        assertEquals(TaskAbsEntry(ua = SAVED_AT, cUp = 3, cDown = 0), cAbs.getValue("fx-device-0001"))
        assertEquals(TaskAbsEntry(ua = SAVED_AT - 60_000, cUp = 2, cDown = 0), cAbs.getValue("gx-dev-02"))
    }

    /** Opti `char.abs` = {deviceId: {ua, xp, gold, mp}} (sync.js:1699-1712) decodes into the typed column. */
    @Test
    fun charAbsDecodesAsDeviceMap() {
        val st = Json.parseToJsonElement(fixture("G1/char_abs_map/state.json")).jsonObject.getValue("state").jsonObject
        val abs = SerializationEngine.parseCharacterEntity(st.getValue("char").jsonObject).abs
        assertNotNull("char.abs must decode to a device-keyed map", abs)
        assertEquals(listOf("fx-device-0001", "gx-dev-02", "gx-dev-03"), abs!!.keys.toList())
        assertEquals(CharAbsEntry(ua = SAVED_AT - 3_600_000, xp = 395.5, gold = 0.5, mp = 0.0), abs.getValue("gx-dev-02"))
    }

    @Test
    fun taskCAbsDecodesWithThreeDevicesAndAbsentMap() {
        val st = Json.parseToJsonElement(fixture("G1/task_cabs_map/state.json")).jsonObject.getValue("state").jsonObject
        val tasks = st.getValue("tasks").jsonArray.map { SerializationEngine.parseTaskEntity(it.jsonObject) }
        assertEquals(listOf("gx-dev-03", "fx-device-0001", "gx-dev-02"), tasks[1].cAbs!!.keys.toList())
        assertEquals(TaskAbsEntry(ua = 1790000000000L, cUp = 0, cDown = 2), tasks[1].cAbs!!.getValue("gx-dev-03"))
        assertEquals(null, tasks[2].cAbs)
    }

    @Test
    fun typedDailyColumnsDecodeFromPauseFixture() {
        val st = Json.parseToJsonElement(fixture("G1/pause_abs_cAbs_doneDay_cResetOn/state.json")).jsonObject
            .getValue("state").jsonObject
        val daily = SerializationEngine.parseTaskEntity(st.getValue("tasks").jsonArray[1].jsonObject)
        assertEquals(20260920, daily.doneDay)
        assertEquals(20260920, daily.cResetOn)
        assertEquals(0, daily.streak)
        assertEquals(20260921, daily.missedOn)
    }

    @Test
    fun remindersKeepLastFiredKeyAndNoUpdatedAtBump() {
        val wire = wireFromFixture("typical")
        val daily = wire.tasks[1] as JsonObject
        val reminder = (daily.getValue("reminders") as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive(""), reminder["lastFiredKey"])
        assertEquals(JsonPrimitive(SAVED_AT), daily["updatedAt"])
    }
}
