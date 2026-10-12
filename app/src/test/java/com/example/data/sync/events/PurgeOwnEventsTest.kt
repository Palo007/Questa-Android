package com.example.data.sync.events

import com.example.data.sync.DropboxService.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-8.7 (PX-56) -- `purgeOwnEventFiles` deletes exactly the device's own files (sync.js:3621-3630). */
class PurgeOwnEventsTest {

    private val names = listOf(
        "dev-old-202609.json",          // own
        "dev-old-202608.json",          // own
        "dev-old-2-202609.json",        // device "dev-old-2": foreign, although it starts with dev-old
        "dev-other-202609.json",        // foreign
        "dev-old-202609 (1).json",      // Dropbox conflict copy: unparsed, never touched
        "x-dev-old-202609.json",        // device "x-dev-old": foreign
        "notes.txt",
    )

    @Test
    fun deletesOnlyOwnFiles_zeroForeign() = runBlocking {
        val remote = FakeEventsRemote(entries = names.map { FileEntry(it, "r") })
        purgeOwnEventFiles(remote, "dev-old")
        assertEquals(listOf("/events/dev-old-202609.json", "/events/dev-old-202608.json"), remote.deletes)
        val foreign = remote.deletes.filterNot { it.removePrefix("/events/").matches(Regex("^dev-old-\\d{6}\\.json$")) }
        assertEquals(0, foreign.size)
    }

    @Test
    fun oneFailedDelete_doesNotStopTheRest() = runBlocking {
        val remote = FakeEventsRemote(entries = names.map { FileEntry(it, "r") })
        remote.deleteFails.add("/events/dev-old-202609.json")
        purgeOwnEventFiles(remote, "dev-old")
        assertEquals(2, remote.deletes.size)
    }

    @Test
    fun emptyDeviceId_isANoOp() = runBlocking {
        val remote = FakeEventsRemote(entries = names.map { FileEntry(it, "r") })
        purgeOwnEventFiles(remote, "")
        assertEquals(0, remote.listCalls)
        assertTrue(remote.deletes.isEmpty())
    }

    @Test(expected = EventsHttpException::class)
    fun listFailure_throws() = runBlocking {
        val remote = FakeEventsRemote().apply { listFails = true }
        purgeOwnEventFiles(remote, "dev-old")
    }
}
