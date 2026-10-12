package com.example.data.sync.events

import com.example.data.sync.DropboxService
import com.example.data.sync.FakeDropboxServer
import com.example.data.sync.auth.DropboxInterlock
import com.example.data.sync.auth.InterlockRefused
import com.example.data.sync.auth.UnboundInterlock
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * WP-8.7 -- the production [EventsRemote] over [DropboxService] (in-memory FakeDropboxServer, no
 * network): every call goes through the D7 interlock; failures throw Opti-shaped messages.
 */
class DropboxEventsRemoteTest {
    private val fake = FakeDropboxServer()

    @Before
    fun setUp() {
        DropboxService.client = fake.asClient()
        DropboxService.bindAuth(DropboxInterlock { }, null)
    }

    @After
    fun tearDown() {
        DropboxService.client = OkHttpClient()
        DropboxService.bindAuth(UnboundInterlock, null)
    }

    @Test
    fun listDownloadUploadDelete() = runBlocking {
        val rev = fake.seedEventFile("dev-o-202609.json", "[]")
        val r = DropboxEventsRemote("tok")
        assertEquals(listOf(DropboxService.FileEntry("dev-o-202609.json", rev)), r.listEvents())
        assertEquals(RawEventFile("[]", rev), r.downloadRaw("/events/dev-o-202609.json"))
        assertNull(r.downloadRaw("/events/missing-202609.json"))
        r.uploadText("/events/dev-me-202609.json", "[{\"ts\":1}]")
        assertEquals("[{\"ts\":1}]", fake.eventFiles["dev-me-202609.json"]!!.content)
        r.delete("/events/dev-me-202609.json")
        assertEquals("/2/files/delete_v2", fake.requests.last().endpoint)
        assertEquals("tok", fake.requests.last().bearer)
    }

    @Test
    fun missingFolder_isEmpty() = runBlocking {
        fake.scriptNext("/2/files/list_folder", 409, """{"error_summary":"path/not_found/.."}""")
        assertEquals(emptyList<DropboxService.FileEntry>(), DropboxEventsRemote("tok").listEvents())
    }

    @Test
    fun failures_throw() = runBlocking {
        val r = DropboxEventsRemote("tok")
        fake.forceGenericUploadFailure = true
        // Opti: status + the first 200 chars of the body.
        expectHttp("upload failed: 500 {}") { r.uploadText("/events/x-202609.json", "[]") }
        fake.scriptNext("/2/files/list_folder", 500, "boom")
        expectHttp("list failed") { r.listEvents() }
        fake.scriptNext("/2/files/download", 500, "boom")
        expectHttp("download failed: 500 boom") { r.downloadRaw("/events/x-202609.json") }
        fake.scriptNext("/2/files/delete_v2", 500, "boom")
        expectHttp("delete failed: 500 boom") { r.delete("/events/x-202609.json") }
    }

    @Test
    fun interlockRefusal_makesNoRequest() = runBlocking {
        DropboxService.bindAuth(UnboundInterlock, null)
        try {
            DropboxEventsRemote("tok").listEvents()
            fail("expected InterlockRefused")
        } catch (e: InterlockRefused) {
        }
        assertEquals(0, fake.requests.size)
    }

    private suspend fun expectHttp(msg: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $msg")
        } catch (e: EventsHttpException) {
            assertEquals(msg, e.message)
        }
    }
}
