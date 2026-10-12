package com.example.data.sync

import com.example.data.attachments.BlobFetch
import com.example.data.attachments.DropboxBlobRemote
import com.example.data.sync.auth.AccountInterlock
import com.example.data.sync.auth.UnboundInterlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CR-KT-015: binary-safe blob upload (mode add, 409 conflict = already there) and download. */
class DropboxBlobTransportTest {

    private lateinit var originalClient: OkHttpClient
    private val stored = LinkedHashMap<String, ByteArray>()
    private val modes = mutableListOf<String>()
    private var nextStatus: Int? = null

    private val octet = "application/octet-stream".toMediaType()
    private fun resp(req: okhttp3.Request, code: Int, body: ByteArray, header: String? = null) = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .apply { if (header != null) header("dropbox-api-result", header) }
        .body(body.toResponseBody(octet)).build()

    private val interceptor = Interceptor { chain ->
        val req = chain.request()
        val arg = Json.parseToJsonElement(req.header("Dropbox-API-Arg")!!).jsonObject
        val path = arg["path"]!!.jsonPrimitive.content
        nextStatus?.let { s -> nextStatus = null; return@Interceptor resp(req, s, "{\"error_summary\":\"too_many_requests/\"}".toByteArray()) }
        if (req.url.encodedPath.endsWith("/upload")) {
            modes += arg["mode"]!!.jsonObject[".tag"]!!.jsonPrimitive.content
            if (path in stored) return@Interceptor resp(req, 409, "{\"error_summary\":\"path/conflict/file/..\"}".toByteArray())
            val b = Buffer(); req.body!!.writeTo(b); stored[path] = b.readByteArray()
            resp(req, 200, "{\"rev\":\"r1\"}".toByteArray())
        } else {
            val bytes = stored[path] ?: return@Interceptor resp(req, 409, "{\"error_summary\":\"path/not_found/..\"}".toByteArray())
            resp(req, 200, bytes, "{\"rev\":\"r1\"}")
        }
    }

    @Before fun setUp() {
        originalClient = DropboxService.client
        DropboxService.client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        DropboxService.bindAuth(AccountInterlock({ "dbid:x" }, { true }, { emptySet() }), null)
    }

    @After fun tearDown() {
        DropboxService.client = originalClient
        DropboxService.bindAuth(UnboundInterlock, null)
    }

    /** Every byte value, including ones that are not valid UTF-8. */
    private val jpegish = ByteArray(256) { it.toByte() }

    @Test fun upload_usesAdd_andConflictMeansAlreadyThere() = runBlocking {
        assertTrue(DropboxService.uploadBlob("t", "/attachments/s.jpg", jpegish) is UploadOutcome.Ok)
        assertEquals(UploadOutcome.Conflict, DropboxService.uploadBlob("t", "/attachments/s.jpg", jpegish))
        assertEquals(listOf("add", "add"), modes)
        assertTrue(DropboxBlobRemote.upload("t", "/attachments/s.jpg", jpegish))
        assertTrue(stored["/attachments/s.jpg"]!!.contentEquals(jpegish))
    }

    @Test fun upload_httpFailure_isFalse() = runBlocking {
        nextStatus = 500
        assertFalse(DropboxBlobRemote.upload("t", "/attachments/s.jpg", jpegish))
    }

    @Test fun download_isByteExact_andMissingIsNotFound() = runBlocking {
        stored["/attachments/s_t.jpg"] = jpegish
        val ok = DropboxService.downloadBlob("t", "/attachments/s_t.jpg") as BlobDownloadResult.Ok
        assertTrue(ok.bytes.contentEquals(jpegish))
        assertEquals(BlobDownloadResult.NotFound, DropboxService.downloadBlob("t", "/attachments/none.jpg"))
        assertTrue(DropboxBlobRemote.download("t", "/attachments/none.jpg") is BlobFetch.NotFound)
        nextStatus = 429
        assertTrue(DropboxBlobRemote.download("t", "/attachments/s_t.jpg") is BlobFetch.Failed)
    }
}
