package com.example.data.inbox

import com.example.data.sync.FakeDropboxServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * WP-8.8 test fake: a Dropbox file tree for the `/inbox*` paths, at the HTTP level (so the real
 * [DropboxServiceInbox] and DropboxService run). Every request whose path is not under `/inbox`
 * falls through to [next] (the shared [FakeDropboxServer], which owns `/state.json` and
 * `/events`). No real network.
 */
class InboxFakeDropbox(private val next: FakeDropboxServer? = null, var pageSize: Int = 2000) {
    /** path -> content (every stored path is a file). */
    val files: MutableMap<String, String> = linkedMapOf()

    data class Call(val endpoint: String, val path: String)
    val calls: MutableList<Call> = mutableListOf()

    /** "endpoint path" -> HTTP status to fail the next matching call with (one shot). */
    val failNext: MutableMap<String, Int> = mutableMapOf()

    /** Source paths whose next move finds the file already gone (another device won the claim). */
    val lostNext: MutableSet<String> = mutableSetOf()

    fun put(path: String, content: String) { files[path] = content }
    fun names(dir: String): List<String> = files.keys.filter { it.startsWith("$dir/") && it.indexOf('/', dir.length + 1) < 0 }
        .map { it.substring(dir.length + 1) }
    fun count(endpoint: String) = calls.count { it.endpoint == endpoint }

    private val json = "application/json".toMediaType()

    private fun resp(req: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody(json)).build()

    private fun err409(req: Request, summary: String) =
        resp(req, 409, buildJsonObject { put("error_summary", summary); put("error", buildJsonObject { }) }.toString())

    private fun bodyOf(req: Request): String = Buffer().also { req.body?.writeTo(it) }.readUtf8()

    private fun isInbox(p: String) = p == "/inbox" || p.startsWith("/inbox/") || p.startsWith("/inbox-")

    private fun list(req: Request, dir: String, offset: Int): Response {
        val all = names(dir)
        if (all.isEmpty() && files.keys.none { it.startsWith("$dir/") }) return err409(req, "path/not_found/..")
        val page = all.drop(offset).take(pageSize)
        val more = offset + page.size < all.size
        val body = buildJsonObject {
            put("entries", kotlinx.serialization.json.JsonArray(page.map { n ->
                buildJsonObject { put(".tag", "file"); put("name", n); put("rev", "r-$n") }
            }))
            put("cursor", "inbox|$dir|${offset + page.size}")
            put("has_more", more)
        }
        return resp(req, 200, body.toString())
    }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val argHeader = req.header("Dropbox-API-Arg")
        val body = bodyOf(req)
        val arg: JsonObject? = try {
            Json.parseToJsonElement(argHeader ?: body) as? JsonObject
        } catch (e: Exception) { null }
        val path = arg?.get("path")?.jsonPrimitive?.contentOrNull
            ?: arg?.get("from_path")?.jsonPrimitive?.contentOrNull
        val cursor = arg?.get("cursor")?.jsonPrimitive?.contentOrNull
        val endpoint = url.substringAfter("/2/")
        val mine = (path != null && isInbox(path)) || (cursor?.startsWith("inbox|") == true)
        if (!mine) {
            val n = next ?: return@Interceptor resp(req, 500, "no next")
            return@Interceptor n.interceptor.intercept(chain)
        }
        calls.add(Call(endpoint, path ?: cursor!!))
        failNext.remove("$endpoint ${path ?: cursor}")?.let { return@Interceptor resp(req, it, "{\"error_summary\":\"injected/\"}") }
        when (endpoint) {
            "files/list_folder" -> list(req, path!!, 0)
            "files/list_folder/continue" -> {
                val parts = cursor!!.split("|")
                list(req, parts[1], parts[2].toInt())
            }
            "files/download" -> {
                val c = files[path] ?: return@Interceptor err409(req, "path/not_found/.")
                resp(req, 200, c, mapOf("dropbox-api-result" to buildJsonObject { put("rev", "r1") }.toString()))
            }
            "files/upload" -> {
                files[path!!] = body
                resp(req, 200, buildJsonObject { put("rev", "r-up"); put("name", JsonPrimitive(path)) }.toString())
            }
            "files/move_v2" -> {
                val to = arg!!["to_path"]!!.jsonPrimitive.content
                val auto = arg["autorename"]?.jsonPrimitive?.contentOrNull == "true"
                if (lostNext.remove(path)) files.remove(path)
                val c = files.remove(path) ?: return@Interceptor err409(req, "from_lookup/not_found/..")
                var dest = to
                if (files.containsKey(dest)) {
                    if (!auto) { files[path!!] = c; return@Interceptor err409(req, "to/conflict/file/..") }
                    var i = 1
                    while (files.containsKey(dest)) { dest = to.removeSuffix(".json") + " ($i).json"; i++ }
                }
                files[dest] = c
                resp(req, 200, "{}")
            }
            "files/delete_v2" -> {
                if (files.remove(path) == null) return@Interceptor err409(req, "path_lookup/not_found/..")
                resp(req, 200, "{}")
            }
            else -> resp(req, 400, "unknown endpoint")
        }
    }

    fun asClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
}
