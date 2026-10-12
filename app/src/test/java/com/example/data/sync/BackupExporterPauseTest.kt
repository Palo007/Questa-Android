package com.example.data.sync

import com.example.data.local.entities.SyncPrefsEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WP-8.3 review finding 1 (MAJOR): `FullLocalState.toSnapshot()` must fold `wire.pause`
 * (sync.js:808 shape `{paused, pausedDays, at}`) into `S.prefs.paused` / `pausedDays` / `pausedAt`
 * (app.js `setPause`, line 6696: `S.prefs.paused=!!n; S.prefs.pausedAt=now(); ...pausedDays`),
 * the same three keys Opti's live backup exports. A Kotlin backup that skips this leaves those
 * keys stale/missing (they only ever come from the last *imported* S, SerializationEngine.kt
 * :1316-1317).
 */
class BackupExporterPauseTest {

    private fun wireWithPause(
        paused: Boolean,
        pausedDays: List<Int>,
        pausedAt: Long?,
        pauseRowExists: Boolean = true
    ): LocalWireState =
        LocalWireState(
            char = JsonObject(emptyMap()),
            tasks = JsonArray(emptyList()),
            rewards = JsonArray(emptyList()),
            tags = JsonArray(emptyList()),
            devices = JsonArray(emptyList()),
            lastCron = 20260924,
            history = JsonArray(emptyList()),
            charHistory = JsonArray(emptyList()),
            anViews = JsonArray(emptyList()),
            anMetrics = JsonArray(emptyList()),
            pause = LocalWireState.pauseWire(
                SyncPrefsEntity(paused = paused, pausedDays = pausedDays, pausedAt = pausedAt, extraJson = null)
            ),
            monthlyBackups = JsonArray(emptyList()),
            deletions = JsonArray(emptyList()),
            pauseRowExists = pauseRowExists
        )

    @Test
    fun toSnapshotWritesPauseFieldsFromWire() {
        val wire = wireWithPause(paused = true, pausedDays = listOf(20260920, 20260921), pausedAt = 1727000000000L)
        val snapshot = FullLocalState(wire).toSnapshot()
        val prefs = snapshot.getValue("prefs") as JsonObject

        assertEquals(true, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(
            listOf(20260920, 20260921),
            prefs.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int }
        )
        assertEquals(1727000000000L, prefs.getValue("pausedAt").jsonPrimitive.long)
    }

    @Test
    fun toSnapshotWritesUnpausedRowWithZeroAtAsNoPausedAtKey() {
        val wire = wireWithPause(paused = false, pausedDays = emptyList(), pausedAt = 0L)
        val prefs = FullLocalState(wire).toSnapshot().getValue("prefs") as JsonObject

        assertEquals(false, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(emptyList<Int>(), prefs.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int })
        // Opti sets S.prefs.pausedAt only in setPause (app.js:6696) or when subset.pause.at != 0
        // (sync.js:887): at == 0 must not fabricate the key.
        assertFalse(prefs.containsKey("pausedAt"))
    }

    @Test
    fun toSnapshotPauseOverridesStalePrefsOverlay() {
        // `prefs` simulates a stale overlay (e.g. carried from a prior import) with old pause
        // values; wire.pause (the live SyncPrefsEntity row) must win.
        val stalePrefs = JsonObject(
            mapOf(
                "paused" to JsonPrimitive(true),
                "pausedDays" to JsonArray(listOf(JsonPrimitive(20200101))),
                "pausedAt" to JsonPrimitive(1L)
            )
        )
        val wire = wireWithPause(paused = false, pausedDays = emptyList(), pausedAt = 0L)
        val prefs = FullLocalState(wire, prefs = stalePrefs).toSnapshot().getValue("prefs") as JsonObject

        assertEquals(false, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(emptyList<Int>(), prefs.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int })
        // at == 0 -> no live pausedAt; the imported overlay value is kept (sync.js:887 only
        // overwrites pausedAt when subset.pause.at is non-zero).
        assertEquals(1L, prefs.getValue("pausedAt").jsonPrimitive.long)
    }

    @Test
    fun toSnapshotWithoutPauseRowKeepsImportedPrefsOverlay() {
        val imported = JsonObject(
            mapOf(
                "paused" to JsonPrimitive(true),
                "pausedDays" to JsonArray(listOf(JsonPrimitive(20200101))),
                "pausedAt" to JsonPrimitive(5L)
            )
        )
        val wire = wireWithPause(paused = false, pausedDays = emptyList(), pausedAt = null, pauseRowExists = false)
        val prefs = FullLocalState(wire, prefs = imported).toSnapshot().getValue("prefs") as JsonObject

        assertEquals(true, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(listOf(20200101), prefs.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(5L, prefs.getValue("pausedAt").jsonPrimitive.long)
    }

    @Test
    fun toSnapshotRowWithNullPausedAtOmitsPausedAtKey() {
        val wire = wireWithPause(paused = false, pausedDays = emptyList(), pausedAt = null)
        val prefs = FullLocalState(wire).toSnapshot().getValue("prefs") as JsonObject

        assertEquals(false, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(emptyList<Int>(), prefs.getValue("pausedDays").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(prefs.containsKey("pausedAt"))
    }

    @Test
    fun toSnapshotRowWithNullPausedAtKeepsOverlayPausedAt() {
        val imported = JsonObject(mapOf("pausedAt" to JsonPrimitive(7L)))
        val wire = wireWithPause(paused = false, pausedDays = emptyList(), pausedAt = null)
        val prefs = FullLocalState(wire, prefs = imported).toSnapshot().getValue("prefs") as JsonObject

        assertEquals(false, prefs.getValue("paused").jsonPrimitive.boolean)
        assertEquals(7L, prefs.getValue("pausedAt").jsonPrimitive.long)
    }
}
