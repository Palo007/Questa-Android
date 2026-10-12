package com.example.data.sync

import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-8.1 -- PX-06 / PX-47: Opti `dbxDownload` classification (sync.js :364-413). */
class DownloadResultTest {

    private fun sum(s: String) = "{\"error_summary\":\"$s\"}"
    private val wrapper = """{"schema":1,"savedAt":5,"deviceId":"d","state":{"tasks":[]}}"""

    @Test fun conflict409_notFound_isNotFound() {
        assertEquals(DownloadResult.NotFound, classifyDownload(409, sum("path/not_found/.."), null, null))
    }

    @Test fun conflict409_emptyOrUnreadableSummary_isNotFound_likeOpti() {
        assertEquals(DownloadResult.NotFound, classifyDownload(409, sum(""), null, null))
        assertEquals(DownloadResult.NotFound, classifyDownload(409, "<html>", null, null))
        assertEquals(DownloadResult.NotFound, classifyDownload(409, null, null, null))
    }

    @Test fun conflict409_restricted_isHttp() {
        val r = classifyDownload(409, sum("path/restricted_content/.."), null, null)
        assertTrue(r is DownloadResult.Http)
        r as DownloadResult.Http
        assertEquals(409, r.status)
        assertEquals("path/restricted_content/..", r.summary)
    }

    @Test fun status404_isHttp_notEmptyRemote() {
        // Dropbox never answers 404 for a missing file; Opti treats any non-409 non-OK as HttpError.
        assertTrue(classifyDownload(404, "", null, null) is DownloadResult.Http)
    }

    @Test fun status500_carriesDetailAndRetryAfter() {
        val r = classifyDownload(500, "internal", null, "5") as DownloadResult.Http
        assertEquals(500, r.status)
        assertEquals("internal", r.summary)
        assertEquals(5_000L, r.retryAfterMs)
    }

    @Test fun status429_detailIsFirst200Chars() {
        val r = classifyDownload(429, "x".repeat(500), null, null) as DownloadResult.Http
        assertEquals(200, r.summary!!.length)
        assertEquals(null, r.retryAfterMs)
    }

    @Test fun networkError_isHttpStatus0() {
        assertTrue((classifyDownload(0, "network error: IOException", null, null) as DownloadResult.Http).status == 0)
    }

    @Test fun ok_nonJson_isCorrupt() {
        assertTrue(classifyDownload(200, "not json", null, null) is DownloadResult.Corrupt)
        assertTrue(classifyDownload(200, "", null, null) is DownloadResult.Corrupt)
        assertTrue(classifyDownload(200, "[1,2]", null, null) is DownloadResult.Corrupt)
    }

    @Test fun ok_missingOrNullOrNonObjectState_isCorrupt() {
        assertTrue(classifyDownload(200, """{"schema":1}""", null, null) is DownloadResult.Corrupt)
        assertTrue(classifyDownload(200, """{"schema":1,"state":null}""", null, null) is DownloadResult.Corrupt)
        assertTrue(classifyDownload(200, """{"schema":1,"state":"x"}""", null, null) is DownloadResult.Corrupt)
    }

    @Test fun ok_validWrapper_carriesRevBodyJson() {
        val r = classifyDownload(200, wrapper, """{"rev":"015f","name":"state.json"}""", null) as DownloadResult.Ok
        assertEquals("015f", r.rev)
        assertEquals(wrapper, r.body)
        assertEquals(5L, r.json["savedAt"]!!.jsonPrimitive.long)
    }

    @Test fun ok_missingApiResult_revIsEmpty() {
        assertEquals("", (classifyDownload(200, wrapper, null, null) as DownloadResult.Ok).rev)
        assertEquals("", (classifyDownload(200, wrapper, "garbage", null) as DownloadResult.Ok).rev)
    }

    @Test fun raw_rules() {
        assertEquals(RawDownloadResult.NotFound, classifyRawDownload(409, sum("path/not_found/"), null, null))
        assertTrue(classifyRawDownload(409, sum("path/restricted_content/"), null, null) is RawDownloadResult.Http)
        assertEquals(RawDownloadResult.Ok("r1", "[]"), classifyRawDownload(200, "[]", """{"rev":"r1"}""", null))
    }

    @Test fun move_delete_list_rules() {
        assertEquals(MoveResult.NotFound, classifyMove(409, sum("from_lookup/not_found/..")))
        assertEquals(MoveResult.Conflict, classifyMove(409, sum("to/conflict/file/..")))
        assertTrue(classifyMove(409, sum("")) is MoveResult.Http)
        assertEquals(MoveResult.Ok, classifyMove(200, "{}"))
        assertEquals(DeleteResult.Ok, classifyDelete(409, sum("path_lookup/not_found/.."), null))
        assertEquals(DeleteResult.Ok, classifyDelete(409, "", null))
        assertTrue(classifyDelete(409, sum("path_lookup/restricted_content/.."), null) is DeleteResult.Http)
        assertTrue(isListFolderMissing(sum("path/not_found/..")))
        assertTrue(isListFolderMissing(""))
        assertTrue(!isListFolderMissing(sum("path/restricted_content/..")))
    }
}
