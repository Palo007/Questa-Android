package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * WP-8.1 transport contracts (§5 card "WP-8.1 -- Transport, auth and interlock").
 *
 * Every function here is pure (no network, no clock except where a `nowMs` is passed in) so the
 * classification rules are unit-testable against synthetic bodies AND against the real bodies
 * captured by G10 (`fixtures/opti/G10/`). [DropboxService] feeds each HTTP response through these.
 *
 * Opti reference: `sync.js` at 7d5d4f3 -- `dbxArgHeader` :326-328, `dbxDownload` :364-413,
 * `dbxUpload` :415-469, `dbxDelete` :471-495, `dbxMove` :501-521, `dbxDownloadRaw` :3218-3238,
 * `dbxListFolder` :3241-3269.
 */

/** Result of downloading `/state.json` (PX-06, PX-47). */
sealed interface DownloadResult {
    /** [json] is the whole wrapper `{schema, savedAt, deviceId, state}`; `state` is an object. */
    data class Ok(val rev: String, val body: String, val json: JsonObject) : DownloadResult
    /** The file does not exist yet. Only a 409 whose summary is empty or has `not_found`. */
    object NotFound : DownloadResult
    /** Any other HTTP failure. status 0 = network/IO failure (no HTTP status at all). */
    data class Http(val status: Int, val summary: String?, val retryAfterMs: Long?) : DownloadResult
    /** A 2xx whose body is not a JSON object, or whose `state` is missing, null or not an object. */
    data class Corrupt(val reason: String) : DownloadResult
}

/** Result of a raw file download (event month files). No wrapper validation (Opti `dbxDownloadRaw`). */
sealed interface RawDownloadResult {
    data class Ok(val rev: String, val body: String) : RawDownloadResult
    object NotFound : RawDownloadResult
    data class Http(val status: Int, val summary: String?, val retryAfterMs: Long?) : RawDownloadResult
}

/** Result of an upload (PX-05). [Conflict] is only produced for rev-checked (`add`/`update`) uploads. */
sealed interface UploadOutcome {
    data class Ok(val rev: String) : UploadOutcome
    object Conflict : UploadOutcome
    data class Http(val status: Int, val summary: String?, val retryAfterMs: Long?) : UploadOutcome
}

/** Result of `move_v2` (Opti `dbxMove`: `from_lookup/not_found` -> false). */
sealed interface MoveResult {
    object Ok : MoveResult
    object NotFound : MoveResult
    object Conflict : MoveResult
    data class Http(val status: Int, val summary: String?) : MoveResult
}

/** Result of `delete_v2` (Opti `dbxDelete`: an empty or `not_found` 409 means "already gone"). */
sealed interface DeleteResult {
    object Ok : DeleteResult
    data class Http(val status: Int, val summary: String?, val retryAfterMs: Long?) : DeleteResult
}

/**
 * Opti's upload conflict test (`sync.js` :449-451): `!summary || /(^|\/)conflict(\/|$)/.test(summary)`.
 * A missing or empty summary is a conflict. Matches a `conflict` PATH SEGMENT, so the real
 * `path/conflict/file/...` matches and `restricted_content` / `insufficient_space` do not (PX-05).
 */
fun isConflictSummary(summary: String?): Boolean {
    if (summary.isNullOrEmpty()) return true
    return CONFLICT_SEGMENT.containsMatchIn(summary)
}

private val CONFLICT_SEGMENT = Regex("(^|/)conflict(/|$)")

/**
 * Opti `dbxArgHeader` (`sync.js` :326-328): `JSON.stringify(obj)` with every UTF-16 code unit in
 * U+007F..U+FFFF replaced by a lowercase `\uXXXX` escape, so the header is pure ASCII (PX-12).
 * U+007F (DEL) is included, exactly like Opti's character class.
 */
fun dbxArgHeader(arg: JsonObject): String {
    val raw = arg.toString()
    val sb = StringBuilder(raw.length + 16)
    for (ch in raw) {
        if (ch.code >= 0x7F) sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        else sb.append(ch)
    }
    return sb.toString()
}

/**
 * Opti reads a 409 as `String(((await res.json()) || {}).error_summary || "")`: an unreadable
 * body, a non-object body or a missing field all give "".
 */
fun errorSummaryOf(body: String?): String {
    if (body.isNullOrEmpty()) return ""
    return try {
        val obj = Json.parseToJsonElement(body) as? JsonObject ?: return ""
        (obj["error_summary"] as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** `rev` from the `dropbox-api-result` header; "" when absent or unreadable (Opti `meta.rev || null`). */
fun revFromApiResult(header: String?): String {
    if (header.isNullOrEmpty()) return ""
    return try {
        ((Json.parseToJsonElement(header) as? JsonObject)?.get("rev") as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** Opti's error detail: the first 200 characters of the body text. */
private fun detailOf(body: String?): String? = body?.take(200)?.takeIf { it.isNotEmpty() }

/**
 * `/state.json` download classification (Opti `dbxDownload`, after the 401 retry):
 * - 409 with an empty summary or one containing `not_found` -> [DownloadResult.NotFound];
 *   any other 409 -> [DownloadResult.Http] (e.g. `restricted_content`).
 * - any other non-2xx (404 included: Dropbox never uses it here) -> [DownloadResult.Http].
 * - 2xx: body must be a JSON object whose `state` is a JSON object, else [DownloadResult.Corrupt].
 *   `state: null` is Corrupt (PX-47, WP-8.6 acceptance "state:null or {schema:1} -> 0 uploads").
 */
fun classifyDownload(status: Int, body: String?, apiResultHeader: String?, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()): DownloadResult { // clock-ok:transport-timer
    if (status == 409) {
        val summary = errorSummaryOf(body)
        if (summary.isEmpty() || summary.contains("not_found")) return DownloadResult.NotFound
        return DownloadResult.Http(409, summary.take(200), null)
    }
    if (status !in 200..299) {
        return DownloadResult.Http(status, detailOf(body), RetryPolicy.parseRetryAfterMs(retryAfterHeader, nowMs))
    }
    val text = body ?: return DownloadResult.Corrupt("remote corrupt: empty body")
    val json = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return DownloadResult.Corrupt("remote corrupt: not a JSON object")
    if (json["state"] !is JsonObject) return DownloadResult.Corrupt("remote corrupt: state is not an object")
    return DownloadResult.Ok(rev = revFromApiResult(apiResultHeader), body = text, json = json)
}

/** Raw download classification (Opti `dbxDownloadRaw`). */
fun classifyRawDownload(status: Int, body: String?, apiResultHeader: String?, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()): RawDownloadResult { // clock-ok:transport-timer
    if (status == 409) {
        val summary = errorSummaryOf(body)
        if (summary.isEmpty() || summary.contains("not_found")) return RawDownloadResult.NotFound
        return RawDownloadResult.Http(409, summary.take(200), null)
    }
    if (status !in 200..299) {
        return RawDownloadResult.Http(status, detailOf(body), RetryPolicy.parseRetryAfterMs(retryAfterHeader, nowMs))
    }
    return RawDownloadResult.Ok(rev = revFromApiResult(apiResultHeader), body = body ?: "")
}

/**
 * Upload classification (Opti `dbxUpload`). [revChecked] = the request used mode `add`/`update`.
 * An `overwrite` upload (Opti `dbxUploadText`) never yields [UploadOutcome.Conflict]: every
 * non-2xx is [UploadOutcome.Http].
 */
fun classifyUpload(status: Int, body: String?, retryAfterHeader: String?, revChecked: Boolean, nowMs: Long = System.currentTimeMillis()): UploadOutcome { // clock-ok:transport-timer
    if (status == 409 && revChecked) {
        val summary = errorSummaryOf(body)
        if (isConflictSummary(summary)) return UploadOutcome.Conflict
        return UploadOutcome.Http(409, summary.take(200), null)
    }
    if (status !in 200..299) {
        return UploadOutcome.Http(status, detailOf(body), RetryPolicy.parseRetryAfterMs(retryAfterHeader, nowMs))
    }
    val rev = try {
        ((Json.parseToJsonElement(body ?: "") as? JsonObject)?.get("rev") as? JsonPrimitive)?.contentOrNull
    } catch (e: Exception) {
        null
    }
    return UploadOutcome.Ok(rev ?: "")
}

/**
 * Move classification (Opti `dbxMove`): `from_lookup/not_found` -> [MoveResult.NotFound].
 * Kotlin adds [MoveResult.Conflict] (card shape) for a non-empty summary with a `conflict`
 * segment (`to/conflict/file/...`); Opti throws an HttpError for it, and callers treat
 * Conflict as a failure the same way. An empty summary is [MoveResult.Http], as in Opti.
 */
fun classifyMove(status: Int, body: String?): MoveResult {
    if (status == 409) {
        val summary = errorSummaryOf(body)
        if (summary.contains("from_lookup/not_found")) return MoveResult.NotFound
        if (summary.isNotEmpty() && isConflictSummary(summary)) return MoveResult.Conflict
        return MoveResult.Http(409, summary.take(200))
    }
    if (status !in 200..299) return MoveResult.Http(status, detailOf(body))
    return MoveResult.Ok
}

/** Delete classification (Opti `dbxDelete`). */
fun classifyDelete(status: Int, body: String?, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()): DeleteResult { // clock-ok:transport-timer
    if (status == 409) {
        val summary = errorSummaryOf(body)
        if (summary.isEmpty() || summary.contains("not_found")) return DeleteResult.Ok
        return DeleteResult.Http(409, summary.take(200), null)
    }
    if (status !in 200..299) {
        return DeleteResult.Http(status, detailOf(body), RetryPolicy.parseRetryAfterMs(retryAfterHeader, nowMs))
    }
    return DeleteResult.Ok
}

/** `list_folder` 409 rule (Opti `dbxListFolder`): empty or `not_found` summary = "no folder yet" = []. */
fun isListFolderMissing(body: String?): Boolean {
    val summary = errorSummaryOf(body)
    return summary.isEmpty() || summary.contains("not_found")
}

/** CR-KT-015: a binary attachment blob download. */
sealed interface BlobDownloadResult {
    class Ok(val bytes: ByteArray) : BlobDownloadResult
    object NotFound : BlobDownloadResult
    data class Http(val status: Int, val summary: String?) : BlobDownloadResult
}
