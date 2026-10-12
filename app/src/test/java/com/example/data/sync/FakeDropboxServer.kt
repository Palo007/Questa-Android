package com.example.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Stage 4 / T4.5 -- in-memory fake of the Dropbox HTTP surface [DropboxService] actually calls
 * (`/2/files/download`, `/2/files/upload`, `/2/files/list_folder[/continue]`), implemented as a
 * single OkHttp [Interceptor] that answers every request itself and NEVER calls
 * `chain.proceed(...)` -- no real socket, DNS lookup, or network permission is ever touched, so
 * this is safe to run in a plain JVM unit test (no MockWebServer/robolectric-shadows-http
 * dependency needed; `okhttp3`/`okio` are already project dependencies via `DropboxService` itself
 * -- see its class doc).
 *
 * Usage (see `EventSyncPushPullTest`/`SyncConflictRetryTest`):
 * ```
 * val fake = FakeDropboxServer()
 * DropboxService.client = OkHttpClient.Builder().addInterceptor(fake.interceptor).build()
 * try {
 *     // ... seed fake.stateFile / fake.eventFiles, run syncManager.syncNow() ...
 * } finally {
 *     DropboxService.client = OkHttpClient() // MANDATORY: DropboxService.client is a JVM-wide
 *     // singleton field (see its doc) -- leaving a test's fake installed would leak into any
 *     // later test class sharing this JVM/Gradle test worker.
 * }
 * ```
 */
class FakeDropboxServer {

    companion object {
        /** WP-8.1: the allowlisted account id every FakeDropboxServer-based round test links. */
        const val SCRATCH_ACCOUNT_ID = "dbid:fake-scratch-account"

        /**
         * WP-8.1 (D7): SyncManager binds a fail-closed AccountInterlock, so a round test must link
         * an allowlisted account first. Pair with [restoreInterlockPolicy] in @After.
         */
        suspend fun linkScratchAccount(ds: SyncDataStore) {
            com.example.data.sync.auth.InterlockPolicy.unlocked = false
            com.example.data.sync.auth.InterlockPolicy.allowlist = setOf(SCRATCH_ACCOUNT_ID)
            ds.saveAccountId(SCRATCH_ACCOUNT_ID)
        }

        /**
         * WP-8.1 review fix: SyncManager no longer falls back to the legacy plaintext DataStore
         * tokens while ACCOUNT_ID is set, so a round test seeds a fresh scratch-account token in
         * the SecureTokenStore it passed to SyncManager (and sets DROPBOX_ENABLED, as saveTokens did).
         */
        suspend fun seedScratchTokens(
            ds: SyncDataStore,
            store: com.example.data.sync.auth.SecureTokenStore,
            accessToken: String = "fake-access-token",
            refreshToken: String = "fake-refresh-token"
        ) {
            store.write(com.example.data.sync.auth.TokenSet(accessToken, refreshToken, System.currentTimeMillis() + 3_600_000L, SCRATCH_ACCOUNT_ID))
            ds.setDropboxEnabled(true)
        }

        fun restoreInterlockPolicy() {
            com.example.data.sync.auth.InterlockPolicy.resetToBuildConfig()
        }
    }

    /** WP-8.1: one observed request (never a real token: tests only use fake strings). */
    data class Seen(val endpoint: String, val arg: JsonObject?, val bearer: String?, val body: String)

    /** Every request that reached this fake, in order. */
    val requests: MutableList<Seen> = mutableListOf()

    /** WP-8.1: a canned answer consumed (FIFO) before normal handling, for [endpoint] suffixes. */
    data class Scripted(val endpoint: String, val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

    val script: ArrayDeque<Scripted> = ArrayDeque()

    fun scriptNext(endpoint: String, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        script.addLast(Scripted(endpoint, code, body, headers))
    }

    /** `/state.json` uploads observed, with the `mode` they used (`add` has rev=null). */
    fun stateUploads(): List<Seen> = requests.filter { it.endpoint == "/2/files/upload" && it.arg?.get("path")?.jsonPrimitive?.content == "/state.json" }

    fun stateUploadsWithNullRev(): Int = stateUploads().count { it.arg?.get("mode")?.jsonObject?.get(".tag")?.jsonPrimitive?.content == "add" }

    /** One stored Dropbox file: raw content bytes (as a JVM `String`) + its current Dropbox rev. */
    data class StoredFile(var content: String, var rev: String)

    /** `/state.json`, or `null` if never uploaded (mirrors a fresh Dropbox account). */
    var stateFile: StoredFile? = null

    /** `/events/<name>.json` files, keyed by bare filename (no `/events/` prefix). */
    val eventFiles: MutableMap<String, StoredFile> = mutableMapOf()

    /**
     * When `> 0`, the NEXT `/state.json` upload attempt (regardless of whether its supplied rev
     * actually matches [stateFile]'s current rev) is answered with a 409 conflict, and this
     * counter is decremented. Lets a test deterministically force N consecutive conflicts to
     * exercise [SyncManager.pushStateWithConflictRetry]'s retry loop without needing to simulate a
     * real second writer's timing.
     */
    var forceStateConflicts: Int = 0

    /** Total `/state.json` upload attempts observed (conflicted + successful) -- retry-count proof. */
    var stateUploadAttempts: Int = 0

    /** Per-path `/events/<name>.json` download call counts -- "unchanged rev skipped" proof. */
    val downloadFileCallCounts: MutableMap<String, Int> = mutableMapOf()

    var listFolderCallCount: Int = 0

    /**
     * WP-5.2 (`AutoBackupTest`'s "failed upload leaves state unchanged for retry" case) -- when
     * true, ANY non-`/state.json` upload (`/events/` month-files, and the two
     * WP-5.2 backup blob paths, `/export-backup.json`/`/questa-backup-*.json`) is answered with a
     * generic 500 instead of succeeding, so a test can exercise an upload failure without needing
     * per-path granularity. Default false — existing tests rely on these paths always succeeding.
     */
    var forceGenericUploadFailure: Boolean = false

    private var revCounter = 0
    private fun nextRev(): String = "rev-${++revCounter}"

    /** Seeds `/state.json` with [content], returning the rev it was assigned. */
    fun seedState(content: String): String {
        val rev = nextRev()
        stateFile = StoredFile(content, rev)
        return rev
    }

    /** Seeds (or overwrites, with a FRESH rev -- simulates another device's re-push) an events file. */
    fun seedEventFile(name: String, content: String): String {
        val rev = nextRev()
        eventFiles[name] = StoredFile(content, rev)
        return rev
    }

    private val jsonMediaType = "application/json".toMediaType()

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun dropboxApiArg(request: Request): JsonObject =
        (Json.parseToJsonElement(request.header("Dropbox-API-Arg") ?: "{}")) as JsonObject

    private fun plainResponse(request: Request, code: Int, message: String, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody(jsonMediaType))
            .build()

    // WP-8.1 (PX-05): the REAL Dropbox stale-rev summary (a `conflict` path segment), not the
    // invented "update/conflict" string the old matcher expected.
    private fun conflictResponse(request: Request): Response =
        plainResponse(
            request, 409, "Conflict",
            buildJsonObject { put("error_summary", JsonPrimitive("path/conflict/file/..")) }.toString()
        )

    private fun notFoundResponse(request: Request): Response =
        plainResponse(request, 409, "NotFound", buildJsonObject { put("error_summary", JsonPrimitive("path/not_found/...")) }.toString())

    private fun handleDownload(request: Request): Response {
        val path = dropboxApiArg(request)["path"]?.jsonPrimitive?.content ?: ""
        val stored: StoredFile? = if (path == "/state.json") {
            stateFile
        } else {
            downloadFileCallCounts[path] = (downloadFileCallCounts[path] ?: 0) + 1
            eventFiles[path.removePrefix("/events/")]
        }
        if (stored == null) return notFoundResponse(request)

        val apiResult = buildJsonObject { put("rev", JsonPrimitive(stored.rev)) }.toString()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("dropbox-api-result", apiResult)
            .body(stored.content.toResponseBody(jsonMediaType))
            .build()
    }

    private fun handleUpload(request: Request): Response {
        val arg = dropboxApiArg(request)
        val path = arg["path"]?.jsonPrimitive?.content ?: ""
        val content = bodyOf(request)

        if (path == "/state.json") {
            stateUploadAttempts++
            if (forceStateConflicts > 0) {
                forceStateConflicts--
                return conflictResponse(request)
            }
            val mode = arg["mode"]?.jsonObject
            val tag = mode?.get(".tag")?.jsonPrimitive?.content
            if (tag == "add") {
                if (stateFile != null) return conflictResponse(request)
            } else {
                val suppliedRev = mode?.get("update")?.jsonPrimitive?.content
                val currentRev = stateFile?.rev
                if (currentRev == null || suppliedRev != currentRev) return conflictResponse(request)
            }
            val newRev = nextRev()
            stateFile = StoredFile(content, newRev)
            return plainResponse(request, 200, "OK", buildJsonObject { put("rev", JsonPrimitive(newRev)) }.toString())
        }

        // /events/<name>.json (or a WP-5.2 backup blob path), always `overwrite` mode -- no rev
        // handshake (rule 23 / DropboxService.uploadOverwrite's doc).
        if (forceGenericUploadFailure) {
            return plainResponse(request, 500, "Internal Server Error", "{}")
        }
        val name = path.removePrefix("/events/")
        eventFiles[name] = StoredFile(content, nextRev())
        return plainResponse(request, 200, "OK", "{}")
    }

    private fun handleListFolder(request: Request): Response {
        listFolderCallCount++
        val entries = eventFiles.entries.map { (name, file) ->
            buildJsonObject {
                put(".tag", JsonPrimitive("file"))
                put("name", JsonPrimitive(name))
                put("rev", JsonPrimitive(file.rev))
            }
        }
        val body = buildJsonObject {
            put("entries", JsonArray(entries))
            put("cursor", JsonPrimitive("cursor-1"))
            put("has_more", JsonPrimitive(false))
        }.toString()
        return plainResponse(request, 200, "OK", body)
    }

    private fun handleListFolderContinue(request: Request): Response {
        // No test in this suite needs real pagination; always answer "no more pages".
        val body = buildJsonObject {
            put("entries", JsonArray(emptyList()))
            put("cursor", JsonPrimitive("cursor-done"))
            put("has_more", JsonPrimitive(false))
        }.toString()
        return plainResponse(request, 200, "OK", body)
    }

    private val endpoints = listOf(
        "/2/files/download", "/2/files/upload", "/2/files/list_folder/continue", "/2/files/list_folder",
        "/2/files/move_v2", "/2/files/delete_v2"
    )

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        val endpoint = endpoints.firstOrNull { url.endsWith(it) } ?: request.url.encodedPath
        val argHeader = request.header("Dropbox-API-Arg")
        val body = bodyOf(request)
        val arg = try {
            (Json.parseToJsonElement(argHeader ?: body) as? JsonObject)
        } catch (e: Exception) {
            null
        }
        requests.add(Seen(endpoint, arg, request.header("Authorization")?.removePrefix("Bearer "), body))
        val scripted = script.firstOrNull { it.endpoint == endpoint }
        if (scripted != null) {
            script.remove(scripted)
            return@Interceptor Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(scripted.code)
                .message("scripted")
                .apply { scripted.headers.forEach { (k, v) -> header(k, v) } }
                .body(scripted.body.toResponseBody(jsonMediaType))
                .build()
        }
        when {
            url.endsWith("/2/files/download") -> handleDownload(request)
            url.endsWith("/2/files/upload") -> handleUpload(request)
            url.endsWith("/2/files/list_folder") -> handleListFolder(request)
            url.endsWith("/2/files/list_folder/continue") -> handleListFolderContinue(request)
            url.endsWith("/2/files/move_v2") -> plainResponse(request, 200, "OK", "{}")
            url.endsWith("/2/files/delete_v2") -> plainResponse(request, 200, "OK", "{}")
            else -> plainResponse(request, 501, "Not Implemented", "{}")
        }
    }

    fun asClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
} 
