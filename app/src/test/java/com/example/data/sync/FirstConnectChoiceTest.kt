package com.example.data.sync

import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PX-111 / D8: the two first-connect choices end the NeedsFirstConnectChoice state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstConnectChoiceTest : Wp89TestBase() {

    @Test fun pull_remoteBecomesLocalAndBase_thenTheNextRoundMerges() = runBlocking {
        val d = device89()
        d.link()
        val rev = seedRemote(state(task("r1", "Remote", 5)))
        assertEquals(SyncResult.NeedsFirstConnectChoice, d.manager.sync())
        assertEquals(SyncResult.Ok, d.firstConnect.resolveFirstConnect(FirstConnectChoice.PullFromDropbox))
        assertEquals(listOf("r1"), d.repository.getAllTasksSync().map { it.id })
        assertEquals(rev, d.base()!!.rev)
        val next = d.manager.sync()
        assertTrue("no choice any more: $next", next == SyncResult.Ok || next == SyncResult.NothingToPush)
    }

    @Test fun push_thisDeviceBecomesRemoteAndBase() = runBlocking {
        val d = device89()
        d.link()
        seedRemote(state(task("r1", "Remote", 5)))
        d.repository.insertTask(TaskEntity(id = "l1", type = "habit", title = "Local", updatedAt = 3L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(SyncResult.Ok, d.firstConnect.resolveFirstConnect(FirstConnectChoice.PushThisDevice))
        val st = (Json.parseToJsonElement(fake.stateFile!!.content) as JsonObject)["state"] as JsonObject
        assertEquals(listOf("l1"), st["tasks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertNotNull(d.base())
        val next = d.manager.sync()
        assertTrue("no choice any more: $next", next == SyncResult.Ok || next == SyncResult.NothingToPush)
    }

    @Test fun preview_countsBothSides() = runBlocking {
        val d = device89()
        d.link()
        d.repository.insertTask(TaskEntity(id = "l1", type = "habit", title = "Local", updatedAt = 3L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        d.repository.insertReward(RewardEntity(id = "rw", title = "Treat", cost = 5.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        val remote = JsonObject(linkedMapOf(
            "char" to buildJsonObject { put("lvl", JsonPrimitive(7)); put("gold", JsonPrimitive(3)) },
            "tasks" to JsonArray(listOf(task("a", "A", 1), task("b", "B", 2))),
            "rewards" to JsonArray(emptyList()),
        ))
        seedRemote(remote)
        val p = d.firstConnect.firstConnectPreview()
        assertEquals(1, p.localTasks)
        assertEquals(1, p.localRewards)
        assertEquals(1, p.localLevel)
        assertFalse(p.localEmpty)
        assertEquals(2, p.remoteTasks)
        assertEquals(0, p.remoteRewards)
        assertEquals(7, p.remoteLevel)
        assertFalse(p.remoteEmpty)
        assertEquals(1L, p.remoteSavedAt)
        assertTrue(p.pushAllowed)
    }
}

/** §4.5 8.9: the 4-row table, local empty/non-empty x remote empty/non-empty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptyLocalPushGuardTest : Wp89TestBase() {

    private val realChar = buildJsonObject { put("lvl", JsonPrimitive(3)); put("xp", JsonPrimitive(10)) }

    private suspend fun row(localNonEmpty: Boolean, remote: JsonObject): Pair<SyncResult, Int> {
        val d = device89()
        d.link()
        if (localNonEmpty) d.repository.insertTask(TaskEntity(id = "l1", type = "habit", title = "Local", updatedAt = 3L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        seedRemote(remote)
        val before = fake.stateUploadAttempts
        val r = d.firstConnect.resolveFirstConnect(FirstConnectChoice.PushThisDevice)
        return r to (fake.stateUploadAttempts - before)
    }

    @Test fun emptyLocal_emptyRemote_pushAllowed() = runBlocking {
        val (r, uploads) = row(false, state())
        assertEquals(SyncResult.Ok, r)
        assertEquals(1, uploads)
    }

    @Test fun emptyLocal_nonEmptyRemote_byTasks_refusedWithZeroUploads() = runBlocking {
        val (r, uploads) = row(false, state(task("r", "R", 1)))
        assertTrue(FirstConnect.isEmptyLocalRefusal(r))
        assertEquals(0, uploads)
    }

    @Test fun emptyLocal_nonEmptyRemote_byRewards_refused() = runBlocking {
        val (r, uploads) = row(false, JsonObject(linkedMapOf("tasks" to JsonArray(emptyList()),
            "rewards" to JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("rw")) })))))
        assertTrue(FirstConnect.isEmptyLocalRefusal(r))
        assertEquals(0, uploads)
    }

    @Test fun emptyLocal_nonEmptyRemote_byARealChar_refused() = runBlocking {
        val (r, uploads) = row(false, JsonObject(linkedMapOf("tasks" to JsonArray(emptyList()), "char" to realChar)))
        assertTrue(FirstConnect.isEmptyLocalRefusal(r))
        assertEquals(0, uploads)
    }

    @Test fun nonEmptyLocal_emptyRemote_pushAllowed() = runBlocking {
        val (r, uploads) = row(true, state())
        assertEquals(SyncResult.Ok, r)
        assertEquals(1, uploads)
    }

    @Test fun nonEmptyLocal_nonEmptyRemote_pushAllowed() = runBlocking {
        val (r, uploads) = row(true, state(task("r", "R", 1)))
        assertEquals(SyncResult.Ok, r)
        assertEquals(1, uploads)
    }

    @Test fun oneRule_previewAndEngineAgree() {
        fun p(le: Boolean, re: Boolean) = FirstConnectPreview(0, 0, 1, le, 0, 0, 1, re, 0L).pushAllowed
        assertTrue(p(false, false)); assertTrue(p(false, true)); assertTrue(p(true, true)); assertFalse(p(true, false))
    }

    @Test fun untouched_isSyncJs2222() {
        fun c(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) })
        assertTrue(FirstConnect.untouched(null))
        assertTrue(FirstConnect.untouched(c("lvl" to 1, "xp" to 0, "gold" to 0, "maxHp" to 50)))
        assertTrue("maxHp 0 reads as 50", FirstConnect.untouched(c("maxHp" to 0)))
        assertFalse(FirstConnect.untouched(c("lvl" to 2)))
        assertFalse(FirstConnect.untouched(c("xp" to 1)))
        assertFalse(FirstConnect.untouched(c("gold" to 1)))
        assertFalse(FirstConnect.untouched(c("maxHp" to 51)))
        assertTrue("a string number is a number", FirstConnect.untouched(c("lvl" to "1")))
    }
}
