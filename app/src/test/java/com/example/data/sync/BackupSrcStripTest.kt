package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.events.DiagRing
import com.example.data.sync.events.EventPuller
import com.example.data.sync.events.EvtCfg
import com.example.data.sync.events.FakeEventsRemote
import com.example.data.sync.events.InMemoryEvtCfgStore
import com.example.data.sync.events.RawEventFile
import com.example.data.sync.events.RoomEventStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WP-8.9-R1 (REWORK R-1, live scenario 6; Decision Pali 2026-09-25: B): Kotlin drops `src` from
 * events in BACKUP files only, before the hash. Sync `/events`, inbox and Room keep it; import
 * still accepts it (G-9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSrcStripTest : Wp89TestBase() {

    private val now = 1_790_000_000_000L
    private val tapTs = now - 86_400_000L

    private fun phoneTap() = EventLogEntity(
        timestamp = tapTs, kind = "habitTap", taskId = "h1", taskTitle = "Walk", direction = 1,
        uid = "u-tap-1", dev = "dev-src",
        payloadJson = "{\"src\":\"android-shortcut\",\"late\":false,\"scored\":true}",
    )

    @Test fun backup_dropsSrc_beforeTheHash_andTheHashVerifies() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(phoneTap())
        val file = src.live.exporter { now }.export(null)
        val text = CanonicalJson.stringifyPretty(file)
        assertFalse("no `src` anywhere in the backup events", file["E"]!!.jsonArray.single().jsonObject.containsKey("src"))
        assertFalse(text.contains("android-shortcut"))
        // decodeBackup recomputes the hash over the detokenized (stripped) events: it must match.
        val decoded = SerializationEngine.decodeBackup(file)
        assertEquals("u-tap-1", decoded.events.single()["uid"]!!.jsonPrimitive.content)
        assertEquals(1, SerializationEngine.importBackup(text).events.size)
    }

    @Test fun partialEventsBackup_alsoDropsSrc() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(phoneTap())
        val file = src.live.exporter { now }.export(setOf(Section.EVENTS))
        assertFalse(file["E"]!!.jsonArray.single().jsonObject.containsKey("src"))
        SerializationEngine.decodeBackup(file) // hash gate
        Unit
    }

    /** Restore a stripped backup, then pull the same uid WITH `src` from /events: still one row. */
    @Test fun restoredStrippedEvent_plusPulledEventWithSrc_isNotDuplicated() = runBlocking {
        val src = device89("dev-src")
        src.repository.insertLog(phoneTap())
        val backup = src.live.exporter { now }.export(null)
        val srcRow = src.repository.allLogs.first().single { it.kind == "habitTap" }

        val d = device89("dev-dst")
        val r = d.importer.importBackup(backup, Section.entries.toSet(), ImportMode.REPLACE)
        assertEquals(1, r.eventsAdded)
        val restored = d.repository.allLogs.first().single { it.kind == "habitTap" }
        assertEquals("u-tap-1", restored.uid)
        assertFalse(restored.payloadJson.orEmpty().contains("\"src\""))

        val pushed = EventSyncEngine.buildEventPushJson(srcRow)
        assertEquals("the /events copy carries src", JsonPrimitive("android-shortcut"), pushed["src"])
        val name = "dev-src-${EventSyncEngine.evtMonthKey(tapTs)}.json"
        val remote = FakeEventsRemote(
            entries = listOf(DropboxService.FileEntry(name, "rev1")),
            downloads = mutableMapOf(name to RawEventFile(JsonArray(listOf(pushed)).toString(), "rev1")),
        )
        val countBefore = d.repository.allLogs.first().size
        val diag = DiagRing({ now })
        EventPuller(InMemoryEvtCfgStore(EvtCfg()), RoomEventStore(d.db), { "dev-dst" }, { now }, diag)
            .pull(remote, force = true)
        assertEquals("the file was downloaded", 1, remote.downloadCalls)

        assertEquals("the pull inserted nothing", countBefore, d.repository.allLogs.first().size)
        val rows = d.repository.allLogs.first().filter { it.kind == "habitTap" }
        assertEquals("no duplicate after the pull: $rows", 1, rows.size)
        assertEquals("u-tap-1", rows.single().uid)
    }

    /**
     * `tools/verify_with_pwa.mjs --import`: the original PWA's real importData accepts the
     * Kotlin backup. Skipped unless Node and the PWA checkout are both present -- see
     * [PwaToolchain]; without them the script exits non-zero and this test would fail on a
     * clean clone for reasons unrelated to the code.
     */
    @Test fun optiImportData_acceptsAKotlinBackupWithAPhoneTap() = runBlocking {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "tools/verify_with_pwa.mjs").isFile }
        assumeTrue("tools/verify_with_pwa.mjs not found above ${File(".").absolutePath}", root != null)
        assumeTrue(PwaToolchain.skipReason(root), PwaToolchain.isOnPath("node"))
        assumeTrue(PwaToolchain.skipReason(root), PwaToolchain.pinnedRevOrNull(root) != null)
        val src = device89("dev-src")
        src.repository.insertLog(phoneTap())
        val text = src.live.backupText(src.live.exporter { now })
        val tmp = File.createTempFile("wp89r1_phone_tap_backup_", ".json")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            val p = ProcessBuilder("node", "tools/verify_with_pwa.mjs", "--import", tmp.absolutePath)
                .directory(root).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("node timed out: $out") }
            assertEquals("verify_with_pwa --import exit code; output:\n$out", 0, p.exitValue())
            assertTrue(out, out.contains("import OK"))
        } finally {
            tmp.delete()
        }
    }
}

/** Plain JVM (no Robolectric app boot): the sync `/events` push JSON keeps `src` (R-1 is backup-only). */
class BackupSrcStripPushTest {
    @Test fun syncEventPushJson_keepsSrc() {
        val e = EventLogEntity(timestamp = 1L, kind = "habitTap", taskId = "h1", direction = 1, uid = "u-tap-1", dev = "dev-src",
            payloadJson = "{\"src\":\"android-shortcut\",\"late\":false,\"scored\":true}")
        assertEquals(JsonPrimitive("android-shortcut"), EventSyncEngine.buildEventPushJson(e)["src"])
    }
}
