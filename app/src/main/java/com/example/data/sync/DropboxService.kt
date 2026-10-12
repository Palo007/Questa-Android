package com.example.data.sync

import com.example.data.sync.auth.DropboxInterlock
import com.example.data.sync.auth.UnboundInterlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WP-8.1 -- the Dropbox file transport (Opti sync.js at 7d5d4f3, section 2.8 plus
 * `dbxDownloadRaw`/`dbxListFolder`). Rules shared by every request method:
 * 1. D7: [interlock] `.assertAllowed()` runs BEFORE the first request. A refusal throws
 *    [com.example.data.sync.auth.InterlockRefused] and nothing is sent. The OAuth endpoints and
 *    revoke live in [com.example.data.sync.auth.DropboxOAuth] and are exempt.
 * 2. A 401 is retried once with the FRESH token returned by [tokenRefresher] (Opti round 3
 *    item 11: the retry carries the new token directly).
 * 3. `Dropbox-API-Arg` is built with [dbxArgHeader] (non-ASCII escaped, PX-12).
 * 4. Results are typed (see DropboxContracts.kt). A network/IO failure is status 0.
 * 5. Tokens are never logged; this object has no logging at all.
 */
object DropboxService {
    /** D7 gate. Refuses everything until [SyncManager] binds an AccountInterlock. */
    @Volatile internal var interlock: DropboxInterlock = UnboundInterlock

    /** Forces a token refresh and returns the new access token, or null (then the 401 stands). */
    @Volatile internal var tokenRefresher: (suspend () -> String?)? = null

    // Test seam (Stage 4 / T4.5): JVM tests swap in FakeDropboxServer's client and MUST restore it.
    internal var client: OkHttpClient = OkHttpClient()

    private val octet = "application/octet-stream".toMediaType()
    private val jsonType = "application/json".toMediaType()
    private val empty = ByteArray(0)

    private const val DOWNLOAD_URL = "https://content.dropboxapi.com/2/files/download"
    private const val UPLOAD_URL = "https://content.dropboxapi.com/2/files/upload"
    private const val LIST_URL = "https://api.dropboxapi.com/2/files/list_folder"
    private const val LIST_CONTINUE_URL = "https://api.dropboxapi.com/2/files/list_folder/continue"
    private const val MOVE_URL = "https://api.dropboxapi.com/2/files/move_v2"
    private const val DELETE_URL = "https://api.dropboxapi.com/2/files/delete_v2"

    /** One HTTP exchange, reduced to what the classifiers need. */
    internal data class Resp(
        val status: Int, val body: String?, val apiResult: String?, val retryAfter: String?,
        /** CR-KT-015: the raw 2xx body of a binary download ([body] is null then). */
        val raw: ByteArray? = null,
    )

    private suspend fun execute(request: Request, binary: Boolean = false): Resp = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r ->
                if (binary && r.code in 200..299) {
                    Resp(r.code, null, r.header("dropbox-api-result"), r.header("Retry-After"), r.body?.bytes() ?: ByteArray(0))
                } else {
                    Resp(r.code, r.body?.string(), r.header("dropbox-api-result"), r.header("Retry-After"))
                }
            }
        } catch (e: Exception) {
            Resp(0, "network error: ${e.javaClass.simpleName}", null, null)
        }
    }

    /**
     * The last 401 refresh: (dead token -> fresh token). A round passes one token string to many
     * calls; once a call has refreshed it, later calls in the same round use the fresh token
     * directly, as Opti does by re-reading the stored token in `syncToken()`.
     */
    @Volatile private var superseded: Pair<String, String>? = null

    /** Binds the D7 gate and the 401 refresher (SyncManager init); forgets any old token mapping. */
    internal fun bindAuth(gate: DropboxInterlock, refresher: (suspend () -> String?)?) {
        interlock = gate
        tokenRefresher = refresher
        superseded = null
    }

    /** Interlock, then the request, then at most one 401 retry with a fresh token. */
    private suspend fun call(accessToken: String, binary: Boolean = false, build: (token: String) -> Request): Resp {
        interlock.assertAllowed()
        val token = superseded?.takeIf { it.first == accessToken }?.second ?: accessToken
        val first = execute(build(token), binary)
        if (first.status != 401) return first
        val fresh = tokenRefresher?.invoke() ?: return first
        superseded = accessToken to fresh
        return execute(build(fresh), binary)
    }

    private fun contentRequest(url: String, token: String, arg: JsonObject, body: ByteArray, withOctet: Boolean): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", dbxArgHeader(arg))
            .apply { if (withOctet) header("Content-Type", "application/octet-stream") }
            .post(body.toRequestBody(if (withOctet) octet else null))
            .build()

    private fun rpcRequest(url: String, token: String, arg: JsonObject): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(arg.toString().toRequestBody(jsonType))
            .build()

    // ---- /state.json -----------------------------------------------------------------------

    /** Opti `dbxDownload(STATE_PATH)`. Only [DownloadResult.NotFound] means "no remote yet" (PX-06). */
    suspend fun downloadState(accessToken: String): DownloadResult {
        val arg = buildJsonObject { put("path", "/state.json") }
        val r = call(accessToken) { t -> contentRequest(DOWNLOAD_URL, t, arg, empty, withOctet = false) }
        return classifyDownload(r.status, r.body, r.apiResult, r.retryAfter)
    }

    /** Opti `dbxUpload(STATE_PATH, wrapper, rev)`: mode `add` without a rev, `update` with one. */
    suspend fun uploadState(accessToken: String, content: String, rev: String?): UploadOutcome =
        uploadChecked(accessToken, "/state.json", content, rev)

    /** Rev-checked upload to any path (Opti `dbxUpload`); used by [uploadState] and the G10 capture. */
    internal suspend fun uploadChecked(accessToken: String, path: String, content: String, rev: String?): UploadOutcome {
        val mode = if (rev.isNullOrEmpty()) buildJsonObject { put(".tag", "add") }
        else buildJsonObject { put(".tag", "update"); put("update", rev) }
        val arg = buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("autorename", false)
            put("mute", true)
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val r = call(accessToken) { t -> contentRequest(UPLOAD_URL, t, arg, bytes, withOctet = true) }
        return classifyUpload(r.status, r.body, r.retryAfter, revChecked = true)
    }

    // ---- other files -----------------------------------------------------------------------

    /** Opti `dbxDownloadRaw`. */
    suspend fun downloadFile(accessToken: String, path: String): RawDownloadResult {
        val arg = buildJsonObject { put("path", path) }
        val r = call(accessToken) { t -> contentRequest(DOWNLOAD_URL, t, arg, empty, withOctet = false) }
        return classifyRawDownload(r.status, r.body, r.apiResult, r.retryAfter)
    }

    /** Opti `dbxUploadText`: mode `overwrite`, no rev handshake, so never [UploadOutcome.Conflict]. */
    suspend fun uploadOverwrite(accessToken: String, path: String, content: String): UploadOutcome {
        val arg = buildJsonObject {
            put("path", path)
            put("mode", buildJsonObject { put(".tag", "overwrite") })
            put("autorename", false)
            put("mute", true)
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val r = call(accessToken) { t -> contentRequest(UPLOAD_URL, t, arg, bytes, withOctet = true) }
        return classifyUpload(r.status, r.body, r.retryAfter, revChecked = false)
    }

    // ---- CR-KT-015: content-addressed attachment blobs (/attachments/<sha>.jpg, <sha>_t.jpg) ----

    /**
     * Uploads image bytes with mode `add` (never overwrites). A `path/conflict` 409 means the
     * file is already there and is returned as [UploadOutcome.Conflict]; callers treat it as
     * success (the content is addressed by its sha, so it is the same file).
     */
    suspend fun uploadBlob(accessToken: String, path: String, bytes: ByteArray): UploadOutcome {
        val arg = buildJsonObject {
            put("path", path)
            put("mode", buildJsonObject { put(".tag", "add") })
            put("autorename", false)
            put("mute", true)
        }
        val r = call(accessToken) { t -> contentRequest(UPLOAD_URL, t, arg, bytes, withOctet = true) }
        return classifyUpload(r.status, r.body, r.retryAfter, revChecked = true)
    }

    /** Downloads image bytes. A missing path is [BlobDownloadResult.NotFound]. */
    suspend fun downloadBlob(accessToken: String, path: String): BlobDownloadResult {
        val arg = buildJsonObject { put("path", path) }
        val r = call(accessToken, binary = true) { t -> contentRequest(DOWNLOAD_URL, t, arg, empty, withOctet = false) }
        if (r.status in 200..299) return BlobDownloadResult.Ok(r.raw ?: ByteArray(0))
        return when (val c = classifyRawDownload(r.status, r.body, r.apiResult, r.retryAfter)) {
            RawDownloadResult.NotFound -> BlobDownloadResult.NotFound
            is RawDownloadResult.Http -> BlobDownloadResult.Http(c.status, c.summary)
            is RawDownloadResult.Ok -> BlobDownloadResult.Ok(ByteArray(0)) // unreachable: 2xx handled above
        }
    }

    /** Opti `dbxMove`. */
    suspend fun move(accessToken: String, from: String, to: String, autorename: Boolean = false): MoveResult {
        val arg = buildJsonObject {
            put("from_path", from)
            put("to_path", to)
            put("autorename", autorename)
        }
        val r = call(accessToken) { t -> rpcRequest(MOVE_URL, t, arg) }
        return classifyMove(r.status, r.body)
    }

    /** Opti `dbxDelete`: an already-missing path is [DeleteResult.Ok]. */
    suspend fun delete(accessToken: String, path: String): DeleteResult {
        val arg = buildJsonObject { put("path", path) }
        val r = call(accessToken) { t -> rpcRequest(DELETE_URL, t, arg) }
        return classifyDelete(r.status, r.body, r.retryAfter)
    }

    // ---- list_folder -----------------------------------------------------------------------

    data class FileEntry(val name: String, val rev: String?)
    data class ListFolderResult(val entries: List<FileEntry>, val cursor: String?, val hasMore: Boolean)

    private fun parseListFolderResponse(body: String): ListFolderResult? = try {
        val json = Json.parseToJsonElement(body) as JsonObject
        val entries = (json["entries"] as? JsonArray)?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            if (obj[".tag"]?.jsonPrimitive?.contentOrNull != "file") return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            FileEntry(name, obj["rev"]?.jsonPrimitive?.contentOrNull)
        } ?: emptyList()
        ListFolderResult(entries, json["cursor"]?.jsonPrimitive?.contentOrNull, json["has_more"]?.jsonPrimitive?.booleanOrNull ?: false)
    } catch (e: Exception) {
        null
    }

    private fun listResult(r: Resp): ListFolderResult? {
        if (r.status == 409) return if (isListFolderMissing(r.body)) ListFolderResult(emptyList(), null, false) else null
        if (r.status !in 200..299) return null
        return parseListFolderResponse(r.body ?: return null)
    }

    /**
     * Opti `dbxListFolder` first page. A missing folder is an empty, non-error page; any other
     * failure is null. (Pagination stays with the caller, as before; the round is WP-8.6's.)
     */
    suspend fun listFolder(accessToken: String, path: String): ListFolderResult? {
        val arg = buildJsonObject {
            put("path", path)
            put("recursive", false)
            put("limit", 2000)
        }
        return listResult(call(accessToken) { t -> rpcRequest(LIST_URL, t, arg) })
    }

    suspend fun listFolderContinue(accessToken: String, cursor: String): ListFolderResult? {
        val arg = buildJsonObject { put("cursor", cursor) }
        return listResult(call(accessToken) { t -> rpcRequest(LIST_CONTINUE_URL, t, arg) })
    }
}
