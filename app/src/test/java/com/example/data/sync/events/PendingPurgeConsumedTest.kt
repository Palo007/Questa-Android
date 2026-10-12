package com.example.data.sync.events

import com.example.data.sync.DropboxService.FileEntry
import com.example.data.sync.RoundContext
import com.example.data.sync.StepResult
import com.example.data.sync.merge.MergeContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.7 (PX-56) -- a PENDING_PURGE_DEV left by a reset while disconnected (8.9) is consumed on
 * the next linked round: read and removed, then that device's files are purged, errors ignored
 * (Opti sync.js:272-279).
 */
class PendingPurgeConsumedTest {
    private val now = 1790251200000L

    private fun round() = RoundContext(
        remote = null, local = JsonObject(emptyMap()), merged = null,
        ctx = MergeContext.forRound(localDev = "dev-me", remoteDev = null, nowPhysical = now, localSavedAt = null),
    ).also { it.accessToken = "tok" }

    @Test
    fun consumedOnTheNextLinkedRound_thenNeverAgain() = runBlocking {
        val e = EventsStepTest.Env(now)
        e.cfg.pendingPurgeDev = "dev-old"
        e.remote.entries = listOf(FileEntry("dev-old-202609.json", "a"), FileEntry("dev-other-202609.json", "b"))
        assertEquals(StepResult.Continue, e.step.run(round()))
        assertNull("the key is removed", e.cfg.pendingPurgeDev)
        assertEquals(listOf("/events/dev-old-202609.json"), e.remote.deletes)
        // Purge first (Opti purges before its syncNow()), then the push and the pull.
        assertEquals(listOf("list", "delete /events/dev-old-202609.json", "list"), e.remote.order.take(3))
        e.remote.deletes.clear()
        e.step.run(round())
        assertTrue(e.remote.deletes.isEmpty())
    }

    @Test
    fun purgeFailure_isIgnored_andTheKeyStaysRemoved() = runBlocking {
        val e = EventsStepTest.Env(now)
        e.cfg.pendingPurgeDev = "dev-old"
        e.remote.listFails = true
        e.step.run(round())
        assertNull(e.cfg.pendingPurgeDev)
        // Only the pull's list failure is an event-sync error; the purge's own failure is silent.
        assertEquals(listOf("event sync failed: list failed: 500"), e.errors)
    }

    @Test
    fun noPendingPurge_noDelete() = runBlocking {
        val e = EventsStepTest.Env(now)
        e.remote.entries = listOf(FileEntry("dev-me-202609.json", "a"))
        e.step.run(round())
        assertTrue(e.remote.deletes.isEmpty())
    }

    @Test
    fun emptyPendingValue_isRemovedWithoutAPurge() = runBlocking {
        val e = EventsStepTest.Env(now)
        e.cfg.pendingPurgeDev = ""
        e.remote.entries = listOf(FileEntry("-202609.json", "a"))
        e.step.run(round())
        assertNull(e.cfg.pendingPurgeDev)
        assertTrue(e.remote.deletes.isEmpty())
    }
}
