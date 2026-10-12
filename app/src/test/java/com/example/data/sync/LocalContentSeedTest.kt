package com.example.data.sync

import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.seedFirstLaunch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review fix 1: the empty-device guard on the REAL seeded state (`seedFirstLaunch`, the function
 * MainActivity runs on a fresh install; R-4: never on an upgrade, a restore or after Reset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalContentSeedTest : Wp89TestBase() {

    private suspend fun seeded(id: String = "dev-seed"): Wp89Device {
        val d = device89(id)
        seedFirstLaunch(d.repository, OPEN_GATE, awaitBarrier = {})
        assertEquals("the seed ran", 4, d.repository.getAllTasksSync().size)
        return d
    }

    /** The fresh-install gate, held open: this test checks the seeded CONTENT (R-4 owns the gate). */
    private object OPEN_GATE : com.example.startup.SeedGate {
        override fun shouldSeed() = true
        override fun markSeeded() {}
    }

    private fun content(d: Wp89Device) = LocalContent(d.repository)

    @Test fun freshSeed_isEmpty() = runBlocking {
        assertTrue(content(seeded()).isEffectivelyEmpty())
    }

    @Test fun oneTap_isNotEmpty() = runBlocking {
        val d = seeded()
        val habit = d.repository.getAllTasksSync().first { it.type == "habit" }
        assertTrue(d.repository.scoreHabitUp(habit.id))
        assertFalse(content(d).isEffectivelyEmpty())
    }

    @Test fun oneEditedTitle_isNotEmpty() = runBlocking {
        val d = seeded()
        val t = d.repository.getAllTasksSync().first()
        d.repository.updateTask(t.copy(title = t.title + "!", updatedAt = t.updatedAt + 1))
        assertFalse(content(d).isEffectivelyEmpty())
    }

    @Test fun aCompletedSeedTodo_isNotEmpty() = runBlocking {
        val d = seeded()
        val todo = d.repository.getAllTasksSync().first { it.type == "todo" }
        d.repository.completeTask(todo.id)
        assertFalse(content(d).isEffectivelyEmpty())
    }

    @Test fun anExtraTask_rewardOrTag_isNotEmpty() = runBlocking {
        val a = seeded("dev-a")
        a.repository.insertTask(TaskEntity(id = "mine", type = "todo", title = "Mine", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertFalse(content(a).isEffectivelyEmpty())
        val b = seeded("dev-b")
        b.repository.insertReward(RewardEntity(id = "rw", title = "Mine", cost = 1.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertFalse(content(b).isEffectivelyEmpty())
        val c = seeded("dev-c")
        c.repository.insertTag(TagEntity(id = "g", name = "Tag", color = "#fff", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertFalse(content(c).isEffectivelyEmpty())
    }

    @Test fun aUserEvent_isNotEmpty_aCronEventIsNot() = runBlocking {
        val d = seeded()
        d.repository.insertLog(EventLogEntity(kind = "cron", taskTitle = "cron", timestamp = com.example.data.clock.PhysicalClock.nowMs()))
        assertTrue(content(d).isEffectivelyEmpty())
        d.repository.insertLog(EventLogEntity(kind = "complete", taskTitle = "x", timestamp = com.example.data.clock.PhysicalClock.nowMs()))
        assertFalse(content(d).isEffectivelyEmpty())
    }

    @Test fun aRealCharacter_isNotEmpty() = runBlocking {
        val d = seeded()
        d.repository.insertCharacter(d.repository.getCharacterSync().copy(xp = 5))
        assertFalse(content(d).isEffectivelyEmpty())
    }

    @Test fun aDeletedSeed_isStillEmpty_andNoTasksAtAllIsEmpty() = runBlocking {
        val d = seeded()
        d.repository.deleteTaskById(d.repository.getAllTasksSync().first().id)
        assertTrue(content(d).isEffectivelyEmpty())
        assertTrue(content(device89("dev-blank")).isEffectivelyEmpty())
    }

    /** The scenario of the review: reset + reconnect + "Push this device" must NOT wipe Dropbox. */
    @Test fun seededDevice_pushOverARealRemote_isRefused_withZeroUploads() = runBlocking {
        val d = seeded()
        d.link()
        seedRemote(state(task("real-1", "Real", 5)))
        val fc = FirstConnect(d.manager, d.force, { d.manager.buildLocalSnapshot() }, LocalContent(d.repository)::isEffectivelyEmpty)
        val p = fc.firstConnectPreview()
        assertTrue(p.localEmpty)
        assertFalse(p.pushAllowed)
        val r = fc.resolveFirstConnect(FirstConnectChoice.PushThisDevice)
        assertTrue(FirstConnect.isEmptyLocalRefusal(r))
        assertEquals(0, fake.stateUploadAttempts)
    }

    /** Review fix 1 (timing gap): the guard is re-checked on the rev that is uploaded against. */
    @Test fun aRemoteThatFillsUpBetweenCheckAndUpload_isReChecked_andRefused() = runBlocking {
        val d = seeded()
        d.link()
        seedRemote(state()) // empty remote: the first check allows the push
        var downloads = 0
        val spy = object : SyncEngineInternals by d.manager {
            override suspend fun download(): DownloadResult {
                val r = d.manager.download()
                // Right after the first guard read, another device writes real data.
                if (++downloads == 1) seedRemote(state(task("real-1", "Real", 5)))
                return r
            }
        }
        val force = ForceOpsEngine(spy, d.syncDataStore, { d.manager.buildLocalSnapshot() }, { true })
        val fc = FirstConnect(spy, force, { d.manager.buildLocalSnapshot() }, LocalContent(d.repository)::isEffectivelyEmpty)
        val r = fc.resolveFirstConnect(FirstConnectChoice.PushThisDevice)
        assertTrue("refused on the re-check: $r", FirstConnect.isEmptyLocalRefusal(r))
        assertEquals("one upload attempt, a rev conflict, nothing written", 1, fake.stateUploadAttempts)
        assertEquals(listOf("real-1"), (kotlinx.serialization.json.Json.parseToJsonElement(fake.stateFile!!.content) as kotlinx.serialization.json.JsonObject)
            .let { (it["state"] as kotlinx.serialization.json.JsonObject)["tasks"] as kotlinx.serialization.json.JsonArray }
            .map { (it as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"') })
    }
}
