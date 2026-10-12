package com.example.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.sync.auth.AccountInterlock
import com.example.data.sync.auth.AndroidKeystoreTokenStore
import com.example.data.sync.auth.DropboxOAuth
import com.example.data.sync.auth.RefreshResult
import com.example.data.sync.auth.TokenSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * WP-8.1 G10 live capture (D11, D30). NOT part of any automatic run: it does nothing unless the
 * instrumentation argument `g10=yes` is passed. See plans/stage8/WP-8.1_G10_STEPS.md.
 *
 * It runs inside the installed debug app (com.example.dev), so it uses the tokens the user
 * created with "Connect Dropbox" straight from the encrypted SecureTokenStore. Tokens are never
 * printed, logged or written: the recorder stores only the response status, three response
 * headers and the response body, and scrubs anything token-shaped from them.
 *
 * Every request goes through DropboxService, so the D7 interlock applies: the linked account id
 * must be in scratchAccountIds (local.properties, D30). All writes stay inside the probe folder
 * [PROBE] of the app folder (Apps/Questa-temp); /state.json, /events and /inbox are never touched.
 * Output: <app files>/G10/<name>.json, pulled by tools/capture_dropbox_bodies.mjs.
 */
@RunWith(AndroidJUnit4::class)
class G10CaptureTest {

    companion object {
        const val PROBE = "/g10-probe"
        private val TOKEN_SHAPES = listOf(
            Regex("sl\\.[A-Za-z0-9_\\-]{10,}"),
            Regex("dbid:[A-Za-z0-9_\\-]+"),
            Regex("(?i)bearer\\s+\\S+")
        )
    }

    private data class Captured(val status: Int, val headers: Map<String, String?>, val body: String)

    private var last: Captured? = null
    private val secrets = mutableListOf<String>()

    private fun scrub(text: String): String {
        var out = text
        for (s in secrets) if (s.isNotEmpty()) out = out.replace(s, "<redacted>")
        for (r in TOKEN_SHAPES) out = r.replace(out, "<redacted>")
        return out
    }

    private val recorder = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.peekBody(1_000_000L).string()
        last = Captured(
            response.code,
            mapOf(
                "dropbox-api-result" to response.header("dropbox-api-result"),
                "content-type" to response.header("content-type"),
                "retry-after" to response.header("retry-after")
            ),
            body
        )
        response
    }

    private val written = mutableListOf<String>()

    private fun save(dir: File, name: String, kind: String, expect: String, request: JsonObject) {
        val c = last ?: error("no response recorded for $name")
        last = null
        val rec = buildJsonObject {
            put("name", name)
            put("kind", kind)
            put("expect", expect)
            put("request", request)
            put("status", c.status)
            put("headers", buildJsonObject {
                for ((k, v) in c.headers) put(k, if (v == null) JsonNull else JsonPrimitive(scrub(v)))
            })
            put("body", scrub(c.body))
        }
        val text = rec.toString()
        for (s in secrets) check(s.isEmpty() || !text.contains(s)) { "a token survived redaction in $name" }
        File(dir, "$name.json").writeText(text + "\n")
        written.add(name)
    }

    private fun req(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, v) } }

    @Test
    fun captureG10() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("G10 capture runs only with -e g10 yes", args.getString("g10") == "yes")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("run against the debug build (com.example.dev)", ctx.packageName.endsWith(".dev"))
        val ds = SyncDataStore(ctx)
        val store = AndroidKeystoreTokenStore(ctx)
        var tokens: TokenSet = store.read() ?: error("Not connected: tap Link Dropbox in the app first")
        secrets += tokens.accessToken
        secrets += tokens.refreshToken

        val interlock = AccountInterlock(ds)
        interlock.assertAllowed() // D7/D30: stops here unless the linked account is allowlisted

        if (System.currentTimeMillis() >= tokens.expiresAtMs) {
            val r = DropboxOAuth().refresh(tokens.refreshToken, tokens.appKey)
            check(r is RefreshResult.Ok) { "token refresh failed; reconnect in the app" }
            tokens = r.tokens.copy(accountId = tokens.accountId)
            store.write(tokens)
            secrets += tokens.accessToken
        }
        val at = tokens.accessToken

        val originalClient = DropboxService.client
        DropboxService.client = OkHttpClient.Builder().addInterceptor(recorder).build()
        DropboxService.bindAuth(interlock, null) // no silent 401 retry: the 401 probe must be seen raw
        val dir = File(ctx.filesDir, "G10").apply { deleteRecursively(); mkdirs() }
        try {
            DropboxService.delete(at, PROBE) // leftover from an earlier run, if any
            last = null

            DropboxService.downloadFile(at, "$PROBE/missing.json")
            save(dir, "download_missing", "download_raw", "NotFound", req("endpoint" to "download", "path" to "$PROBE/missing.json"))

            DropboxService.listFolder(at, "$PROBE-none")
            save(dir, "list_missing", "list", "Missing", req("endpoint" to "list_folder", "path" to "$PROBE-none"))

            val add = DropboxService.uploadChecked(at, "$PROBE/a.json", "{\"g10\":1}", null)
            save(dir, "upload_add", "upload", "Ok", req("endpoint" to "upload", "path" to "$PROBE/a.json", "mode" to "add"))
            val rev1 = (add as? UploadOutcome.Ok)?.rev ?: error("probe upload failed: $add")

            DropboxService.uploadChecked(at, "$PROBE/a.json", "{\"g10\":2}", null)
            save(dir, "upload_add_existing", "upload", "Conflict", req("endpoint" to "upload", "path" to "$PROBE/a.json", "mode" to "add"))

            DropboxService.uploadChecked(at, "$PROBE/a.json", "{\"g10\":3}", rev1)
            save(dir, "upload_update_current", "upload", "Ok", req("endpoint" to "upload", "path" to "$PROBE/a.json", "mode" to "update(current)"))

            DropboxService.uploadChecked(at, "$PROBE/a.json", "{\"g10\":4}", rev1)
            save(dir, "upload_update_stale", "upload", "Conflict", req("endpoint" to "upload", "path" to "$PROBE/a.json", "mode" to "update(stale)"))

            DropboxService.downloadFile(at, "$PROBE/a.json")
            save(dir, "download_present", "download_raw", "Ok", req("endpoint" to "download", "path" to "$PROBE/a.json"))

            DropboxService.listFolder(at, PROBE)
            save(dir, "list_present", "list", "Ok", req("endpoint" to "list_folder", "path" to PROBE))

            DropboxService.move(at, "$PROBE/missing.json", "$PROBE/x.json")
            save(dir, "move_from_missing", "move", "NotFound", req("endpoint" to "move_v2", "from" to "$PROBE/missing.json"))

            DropboxService.uploadChecked(at, "$PROBE/b.json", "{\"g10\":5}", null)
            last = null
            DropboxService.move(at, "$PROBE/b.json", "$PROBE/a.json")
            save(dir, "move_onto_existing", "move", "Conflict", req("endpoint" to "move_v2", "from" to "$PROBE/b.json", "to" to "$PROBE/a.json"))

            DropboxService.move(at, "$PROBE/b.json", "$PROBE/c.json")
            save(dir, "move_ok", "move", "Ok", req("endpoint" to "move_v2", "from" to "$PROBE/b.json", "to" to "$PROBE/c.json"))

            DropboxService.delete(at, "$PROBE/missing.json")
            save(dir, "delete_missing", "delete", "Ok", req("endpoint" to "delete_v2", "path" to "$PROBE/missing.json"))

            DropboxService.delete(at, "$PROBE/c.json")
            save(dir, "delete_ok", "delete", "Ok", req("endpoint" to "delete_v2", "path" to "$PROBE/c.json"))

            DropboxService.downloadFile("g10-deliberately-invalid-token", "$PROBE/a.json")
            save(dir, "unauthorized", "status", "401", req("endpoint" to "download", "token" to "invalid"))
        } finally {
            try { DropboxService.delete(at, PROBE) } catch (e: Exception) { }
            DropboxService.client = originalClient
        }
        File(dir, "index.json").writeText(
            buildJsonObject {
                put("capturedAtMs", System.currentTimeMillis())
                put("package", ctx.packageName)
                put("files", kotlinx.serialization.json.JsonArray(written.map { JsonPrimitive("$it.json") }))
            }.toString() + "\n"
        )
    }
}
