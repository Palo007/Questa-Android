package com.example.data.sync

import com.example.data.local.entities.TaskEntity
import com.example.data.sync.merge.OptiSnapshotMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CR-KT-018: `local_edit_at` (Opti "questa.localEditAt.v1") is stamped by a user write only (a
 * merge apply emits no localWrites, Opti's `applying` skip) and is device-only: it is in no
 * backup export, no Dropbox upload, and no sync/export code reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalEditAtTest : Wp89TestBase() {

    private val stampAt = 1_790_000_000_000L
    private val jobs = mutableListOf<Job>()

    private suspend fun startStamper(writes: kotlinx.coroutines.flow.Flow<Unit>, store: SyncDataStore) {
        jobs += LocalEditStamper(writes, store::stampLocalEdit) { stampAt }.start(CoroutineScope(Dispatchers.Default))
        delay(50) // the collector is subscribed before the first write (localWrites has no replay)
    }

    @org.junit.After fun stopStampers() = jobs.forEach { it.cancel() }

    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.exists() }

    @Test fun storeRoundTrip_nullUntilStamped() = runBlocking {
        val d = device89()
        assertNull(d.syncDataStore.localEditAt.first())
        d.syncDataStore.stampLocalEdit(42L)
        assertEquals(42L, d.syncDataStore.localEditAt.first())
    }

    @Test fun userWrite_stampsLocalEditAt() = runBlocking {
        val d = device()
        startStamper(d.repository.localWrites, d.syncDataStore)
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = 1L))
        val v = withTimeout(5_000) { while (d.syncDataStore.localEditAt.first() == null) delay(10); d.syncDataStore.localEditAt.first() }
        assertEquals(stampAt, v)
    }

    @Test fun mergeApply_doesNotStampLocalEditAt() = runBlocking {
        val d = device(merger = OptiSnapshotMerger())
        d.link()
        val local = d.manager.buildLocalSnapshot()
        val rev = seedRemote(JsonObject(local + ("tasks" to JsonArray(listOf(task("r1", "Remote", 5L))))))
        d.seedBase(rev, CanonicalJson.canonicalJsonString(local))
        startStamper(d.repository.localWrites, d.syncDataStore)
        d.manager.sync()
        delay(100)
        assertEquals("premise: the remote task was applied", listOf("r1"), d.repository.getAllTasksSync().map { it.id })
        assertNull("a merge apply is not a user edit", d.syncDataStore.localEditAt.first())
    }

    @Test fun backupExport_hasNoLocalEditAt() = runBlocking {
        val d = device89()
        d.syncDataStore.stampLocalEdit(stampAt)
        val text = d.live.backupText(d.live.exporter { 1_790_000_000_000L })
        assertFalse(text.contains("local_edit_at"))
        assertFalse(text.contains("localEditAt"))
        assertFalse(text.contains(stampAt.toString()))
    }

    @Test fun dropboxUpload_hasNoLocalEditAt() = runBlocking {
        val d = device()
        d.link()
        d.syncDataStore.stampLocalEdit(stampAt)
        d.repository.insertTask(TaskEntity(id = "t1", type = "habit", title = "Walk", updatedAt = 10L, createdAt = 1L))
        assertEquals(SyncResult.Ok, d.manager.sync())
        assertNotNull(fake.stateFile)
        val body = fake.stateFile!!.content
        assertFalse(body.contains("local_edit_at"))
        assertFalse(body.contains("localEditAt"))
        fake.eventFiles.values.forEach { assertFalse(it.content.contains("local_edit_at")) }
    }

    @Test fun onlyTheKeyStoreAndStamper_referenceTheKey() {
        val dir = file("src/main/java/com/example/data")
        val allowed = setOf("SyncKeys.kt", "SyncDataStore.kt", "LocalEditStamper.kt")
        val hits = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { f -> f.readText().let { it.contains("LOCAL_EDIT_AT") || it.contains("local_edit_at") || it.contains("localEditAt") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
