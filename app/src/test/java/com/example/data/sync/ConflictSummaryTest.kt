package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * WP-8.1 -- PX-05 conflict table. Acceptance: "The conflict table passes on 4 synthetic cases
 * plus all of G10." The 4 synthetic cases are SY-03's: `path/conflict/file/..`,
 * `path/conflict/folder/..`, `path/insufficient_space/..`, `` -> Conflict/Conflict/Error/Conflict.
 * The G10 part replays every captured body under fixtures/opti/G10/ (skipped until captured).
 */
class ConflictSummaryTest {

    private fun body(summary: String) = "{\"error_summary\":\"$summary\",\"error\":{}}"

    @Test fun synthetic_fourCases() {
        assertEquals(UploadOutcome.Conflict, classifyUpload(409, body("path/conflict/file/.."), null, revChecked = true))
        assertEquals(UploadOutcome.Conflict, classifyUpload(409, body("path/conflict/folder/.."), null, revChecked = true))
        val space = classifyUpload(409, body("path/insufficient_space/.."), null, revChecked = true)
        assertTrue(space is UploadOutcome.Http && space.status == 409)
        assertEquals(UploadOutcome.Conflict, classifyUpload(409, body(""), null, revChecked = true))
    }

    @Test fun unreadable409Body_isConflict_likeOpti() {
        assertEquals(UploadOutcome.Conflict, classifyUpload(409, "not json", null, revChecked = true))
        assertEquals(UploadOutcome.Conflict, classifyUpload(409, null, null, revChecked = true))
    }

    @Test fun overwriteUpload_neverConflict() {
        val r = classifyUpload(409, body("path/conflict/file/.."), null, revChecked = false)
        assertTrue(r is UploadOutcome.Http)
    }

    @Test fun segmentRule() {
        assertTrue(isConflictSummary(null))
        assertTrue(isConflictSummary(""))
        assertTrue(isConflictSummary("conflict"))
        assertTrue(isConflictSummary("path/conflict"))
        assertTrue(isConflictSummary("update/conflict/.."))
        assertTrue(isConflictSummary("to/conflict/file/..."))
        assertFalse(isConflictSummary("restricted_content/.."))
        assertFalse(isConflictSummary("path/no_write_permission/.."))
        assertFalse(isConflictSummary("too_many_write_operations/.."))
        assertFalse(isConflictSummary("path/conflicting/.."))
        assertFalse(isConflictSummary("update_conflict"))
    }

    // ---- G10 replay ------------------------------------------------------------------------

    private fun g10Dir(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val g = File(dir, "fixtures/opti/G10")
            if (File(dir, "fixtures/opti/manifest.json").exists()) return g.takeIf { File(it, "index.json").exists() }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Each G10 capture file is `{name, kind, expect, status, headers, body}` (see
     * tools/capture_dropbox_bodies.mjs). `kind` picks the classifier, `expect` is the outcome
     * the capture step was designed to provoke.
     */
    @Test fun g10_everyCapturedBodyClassifiesAsExpected() {
        val dir = g10Dir()
        assumeTrue("G10 not captured yet (see plans/stage8/WP-8.1_G10_STEPS.md)", dir != null)
        val files = dir!!.listFiles { f -> f.name.endsWith(".json") && f.name != "index.json" }!!.sortedBy { it.name }
        assertTrue("G10 has no capture files", files.isNotEmpty())
        for (f in files) {
            val rec = Json.parseToJsonElement(f.readText()) as JsonObject
            val kind = rec["kind"]!!.jsonPrimitive.content
            val expect = rec["expect"]!!.jsonPrimitive.content
            val status = rec["status"]!!.jsonPrimitive.intOrNull!!
            val body = (rec["body"] as? JsonPrimitive)?.contentOrNull
            val headers = rec["headers"] as? JsonObject
            val apiResult = (headers?.get("dropbox-api-result") as? JsonPrimitive)?.contentOrNull
            val retryAfter = (headers?.get("retry-after") as? JsonPrimitive)?.contentOrNull
            val got = when (kind) {
                "upload" -> when (val r = classifyUpload(status, body, retryAfter, revChecked = true)) {
                    is UploadOutcome.Ok -> "Ok"; UploadOutcome.Conflict -> "Conflict"; is UploadOutcome.Http -> "Http"
                }
                "download_raw" -> when (classifyRawDownload(status, body, apiResult, retryAfter)) {
                    is RawDownloadResult.Ok -> "Ok"; RawDownloadResult.NotFound -> "NotFound"; is RawDownloadResult.Http -> "Http"
                }
                "move" -> when (classifyMove(status, body)) {
                    MoveResult.Ok -> "Ok"; MoveResult.NotFound -> "NotFound"; MoveResult.Conflict -> "Conflict"; is MoveResult.Http -> "Http"
                }
                "delete" -> when (classifyDelete(status, body, retryAfter)) {
                    DeleteResult.Ok -> "Ok"; is DeleteResult.Http -> "Http"
                }
                "list" -> if (status == 409 && isListFolderMissing(body)) "Missing" else if (status in 200..299) "Ok" else "Http"
                "status" -> status.toString()
                else -> error("unknown G10 kind $kind in ${f.name}")
            }
            assertEquals("G10 ${f.name}", expect, got)
        }
    }
}
