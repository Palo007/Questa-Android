package com.example.data.inbox

import com.example.data.TapRecord
import com.example.data.TapResult
import com.example.data.applyHabitTap
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.DataStoreEventWatermark
import com.example.data.sync.SyncResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PX-58: the consumer maps each TapResult exactly as Opti's consumer maps applyInboxLog (sync.js:575-650). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxConsumerTest : InboxTestBase() {

    private fun consumerWith(apply: suspend (TapRecord, Long) -> TapResult) = InboxConsumer(
        dev.repository, DataStoreEventWatermark(dev.syncDataStore), prefs, gateBlocks = { false }, tapNow = { tapNow },
        throttleNow = { throttleNow }, zone = { ZONE }, diag = diag, apply = apply,
    )

    @Test fun applied_isClaimed_andHandedToTheFinisher_notDeleted() = runBlocking {
        drop(id(1))
        val r = round()
        consumer.run(r)
        assertEquals(1, h1().cUp)
        assertEquals(listOf(id(1) + ".json"), inbox.names(claimed))
        assertTrue(inbox.names(InboxPaths.INBOX_DIR).isEmpty())
        assertEquals(listOf("$claimed/${id(1)}.json"), consumer.takeDone(r))
        assertEquals("handed out once", emptyList<String>(), consumer.takeDone(r))
        assertEquals(0, inbox.count("files/delete_v2"))
        assertTrue(diags.any { it.first == "inboxApplied" && it.second["result"] == "applied" })
    }

    @Test fun unknownHabit_notInRemote_isRejected() = runBlocking {
        drop(id(2), habit = "ghost")
        val r = round(remoteWith(habitJson("h1")))
        consumer.run(r)
        assertEquals(listOf(id(2) + ".json"), inbox.names(InboxPaths.INBOX_REJECTED_DIR))
        assertTrue(inbox.names(claimed).isEmpty())
        assertTrue(consumer.takeDone(r).isEmpty())
        assertTrue(diags.any { it.first == "inboxRejected" && it.second["reason"] == "unknownHabit" })
    }

    @Test fun habitOnlyInRemote_isDeferred_keptClaimed() = runBlocking {
        drop(id(3), habit = "h9")
        val r = round(remoteWith(habitJson("h9")))
        consumer.run(r)
        assertEquals(listOf(id(3) + ".json"), inbox.names(claimed))
        assertTrue(consumer.takeDone(r).isEmpty())
        assertTrue(diags.any { it.first == "inboxDefer" && it.second["reason"] == "habitNotMergedYet" })
    }

    @Test fun localNonHabit_isRejected_evenWhenRemoteSaysHabit() = runBlocking {
        dev.repository.insertTask(TaskEntity(id = "d1", type = "daily", title = "Read", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        drop(id(4), habit = "d1")
        consumer.run(round(remoteWith(habitJson("d1"))))
        assertEquals(listOf(id(4) + ".json"), inbox.names(InboxPaths.INBOX_REJECTED_DIR))
    }

    @Test fun remoteNonHabit_doesNotDefer() = runBlocking {
        drop(id(5), habit = "x1")
        consumer.run(round(remoteWith(habitJson("x1", type = "daily"))))
        assertEquals(listOf(id(5) + ".json"), inbox.names(InboxPaths.INBOX_REJECTED_DIR))
    }

    @Test fun badRecord_isRejected_withAutorename() = runBlocking {
        inbox.put("${InboxPaths.INBOX_REJECTED_DIR}/${id(6)}.json", "old")
        inbox.put("${InboxPaths.INBOX_DIR}/${id(6)}.json", "not-json{{{")
        inbox.put("${InboxPaths.INBOX_DIR}/${id(7)}.json", bubblewrapJson(id(8), "h1", 1, at(25, 9))) // name != id
        consumer.run(round())
        assertEquals(setOf("${id(6)}.json", "${id(6)} (1).json", "${id(7)}.json"), inbox.names(InboxPaths.INBOX_REJECTED_DIR).toSet())
        assertEquals(0, h1().cUp)
        assertEquals(2, diags.count { it.first == "inboxRejected" && it.second["reason"] == "badRecord" })
    }

    @Test fun gatedAndError_areKeptForTheNextRound_thenApplied() = runBlocking {
        drop(id(9)); drop(id(10))
        var pass = 0
        val c = consumerWith { rec, now ->
            if (pass == 0) (if (rec.id == id(9)) TapResult.GATED else TapResult.ERROR) else dev.repository.applyHabitTap(rec, now, ZONE) { false }
        }
        val r1 = round()
        c.run(r1)
        assertTrue(c.takeDone(r1).isEmpty())
        assertEquals(setOf("${id(9)}.json", "${id(10)}.json"), inbox.names(claimed).toSet())
        assertEquals(setOf("gated", "error"), diags.filter { it.first == "inboxDefer" }.map { it.second["reason"] }.toSet())
        pass = 1; nextMinute()
        val r2 = round()
        c.run(r2)
        assertEquals(2, c.takeDone(r2).size)
        assertEquals(2, h1().cUp)
    }

    @Test fun applyThrowing_isAnError_keptClaimed() = runBlocking {
        drop(id(11))
        val c = consumerWith { _, _ -> throw IllegalStateException("boom") }
        val r = round()
        c.run(r)
        assertTrue(c.takeDone(r).isEmpty())
        assertEquals(listOf("${id(11)}.json"), inbox.names(claimed))
    }

    @Test fun alreadyApplied_isFinishOnly_noSecondCount() = runBlocking {
        val bodyTs = at(25, 9)
        dev.repository.applyHabitTap(TapRecord(id(12), "h1", 1, bodyTs, -120, "android-shortcut"), tapNow, ZONE) { false }
        drop(id(12), ts = bodyTs)
        val r = round()
        consumer.run(r)
        assertEquals(1, h1().cUp)
        assertEquals(listOf("$claimed/${id(12)}.json"), consumer.takeDone(r))
        assertTrue("inboxDup" in diagKinds())
    }

    @Test fun gateClosed_skipsTheRun_withoutTouchingTheThrottle() = runBlocking {
        gateClosed = true
        drop(id(13))
        consumer.run(round())
        assertTrue(inbox.calls.isEmpty())
        assertEquals(0L, prefs.last)
        assertTrue(diags.any { it.first == "inboxSkip" && it.second["reason"] == "bootGate" })
        gateClosed = false
        consumer.run(round())
        assertEquals(1, h1().cUp)
    }

    @Test fun throttle_oneRunPerMinute() = runBlocking {
        consumer.run(round())
        assertEquals(2, inbox.count("files/list_folder"))
        drop(id(14))
        throttleNow += 59_999L
        consumer.run(round())
        assertEquals("inside 60 s: no listing", 2, inbox.count("files/list_folder"))
        throttleNow += 1L
        consumer.run(round())
        assertEquals(4, inbox.count("files/list_folder"))
        assertEquals(1, h1().cUp)
    }

    @Test fun throttle_storedTimeInTheFuture_countsAsZero() = runBlocking {
        prefs.last = throttleNow + 3L * 24 * 3600 * 1000 // clock was once set 3 days ahead
        drop(id(22))
        consumer.run(round())
        assertEquals(2, inbox.count("files/list_folder"))
        assertEquals(1, h1().cUp)
        assertEquals("re-stamped with the corrected clock", throttleNow, prefs.last)
    }

    @Test fun lostClaim_isSkipped() = runBlocking {
        drop(id(15))
        inbox.lostNext.add("${InboxPaths.INBOX_DIR}/${id(15)}.json")
        val r = round()
        consumer.run(r)
        assertEquals(0, h1().cUp)
        assertTrue("inboxClaimLost" in diagKinds())
        assertEquals(0, inbox.count("files/download"))
    }

    @Test fun claimFailure_isSkipped_othersStillProcessed() = runBlocking {
        drop(id(16)); drop(id(17))
        inbox.failNext["files/move_v2 ${InboxPaths.INBOX_DIR}/${id(16)}.json"] = 500
        consumer.run(round())
        assertTrue("inboxClaimFailed" in diagKinds())
        assertEquals(1, h1().cUp)
        assertEquals(listOf("${id(16)}.json"), inbox.names(InboxPaths.INBOX_DIR))
    }

    @Test fun readFailure_keepsTheClaim() = runBlocking {
        drop(id(18), where = claimed)
        inbox.failNext["files/download $claimed/${id(18)}.json"] = 500
        val r = round()
        consumer.run(r)
        assertTrue("inboxReadFailed" in diagKinds())
        assertTrue(consumer.takeDone(r).isEmpty())
        assertEquals(listOf("${id(18)}.json"), inbox.names(claimed))
    }

    @Test fun listingFailure_endsTheStep_withNoDonePaths() = runBlocking {
        drop(id(19))
        inbox.failNext["files/list_folder ${InboxPaths.INBOX_DIR}"] = 500
        val r = round()
        consumer.run(r)
        assertTrue("inboxConsumeFailed" in diagKinds())
        assertTrue(consumer.takeDone(r).isEmpty())
        assertEquals(0, h1().cUp)
    }

    @Test fun nonJsonNames_areIgnored_upperCaseJsonIsTaken() = runBlocking {
        inbox.put("${InboxPaths.INBOX_DIR}/notes.txt", "x")
        drop(id(20))
        inbox.put("${InboxPaths.INBOX_DIR}/${id(21)}.JSON", bubblewrapJson(id(21), "h1", 1, at(25, 9)))
        consumer.run(round())
        assertEquals(listOf("notes.txt"), inbox.names(InboxPaths.INBOX_DIR))
        assertEquals(1, h1().cUp)
        // "<id>.JSON" is claimed, then rejected: the parser wants "<id>.json" (sync.js:551).
        assertEquals(listOf("${id(21)}.JSON"), inbox.names(InboxPaths.INBOX_REJECTED_DIR))
    }

    @Test fun pagedListing_takesEveryPage() = runBlocking {
        inbox.pageSize = 2
        for (n in 30..34) drop(id(n))
        consumer.run(round())
        assertEquals(5, h1().cUp)
        assertTrue(inbox.count("files/list_folder/continue") >= 2)
    }

    @Test fun claimedFirst_thenNew_inThatOrder() = runBlocking {
        drop(id(40), where = claimed)
        drop(id(41))
        val r = round()
        consumer.run(r)
        assertEquals(listOf("$claimed/${id(40)}.json", "$claimed/${id(41)}.json"), consumer.takeDone(r))
    }

    @Test fun watermark_isLoweredToTsMinus1_neverRaised() = runBlocking {
        val wm = DataStoreEventWatermark(dev.syncDataStore)
        wm.advanceTo(at(25, 11))
        drop(id(50), ts = at(25, 9))
        consumer.run(round())
        assertEquals(at(25, 9) - 1, wm.get())
        nextMinute()
        drop(id(51), ts = at(25, 10))
        consumer.run(round())
        assertEquals("a later tap never raises it", at(25, 9) - 1, wm.get())
    }

    @Test fun downTap_scoresDown() = runBlocking {
        drop(id(52), dir = -1)
        consumer.run(round())
        assertEquals(1, h1().cDown)
        assertEquals(0, h1().cUp)
    }

    @Test fun finisher_deletesDonePaths_andLogsAFailedDelete() = runBlocking {
        drop(id(60)); drop(id(61))
        val r = round()
        consumer.run(r)
        inbox.failNext["files/delete_v2 $claimed/${id(60)}.json"] = 500
        finisher.run(r)
        assertEquals(listOf("${id(60)}.json"), inbox.names(claimed))
        assertTrue("inboxFinishFailed" in diagKinds())
        assertTrue("inboxFinished" in diagKinds())
        nextMinute()
        val r2 = round()
        consumer.run(r2)
        finisher.run(r2)
        assertTrue("the leftover is a finish-only pass", inbox.names(claimed).isEmpty())
        assertEquals(2, h1().cUp)
    }
}

/** Card: crash after claim -> +1 exactly once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxCrashRecoveryTest : InboxTestBase() {
    @Test fun uploadFailsAfterApply_nextRoundFinishes_countedOnce() = runBlocking {
        drop(id(1))
        fake.scriptNext("/2/files/upload", 400, "{\"error_summary\":\"bad/\"}")
        val first = dev.manager.sync()
        assertTrue("$first", first is SyncResult.Error)
        assertEquals(1, h1().cUp)
        assertEquals("not finished without an upload", listOf("${id(1)}.json"), inbox.names(claimed))
        nextMinute()
        assertEquals(SyncResult.Ok, dev.manager.sync())
        assertEquals("+1 exactly once", 1, h1().cUp)
        assertTrue(inbox.names(claimed).isEmpty())
        assertTrue(inbox.names(InboxPaths.INBOX_DIR).isEmpty())
        assertEquals(1, dev.repository.allLogs.first().count { it.uid == "inbox-${id(1)}" })
    }

    @Test fun claimedButNeverApplied_isRecoveredAndApplied() = runBlocking {
        drop(id(2), where = claimed) // the process died between move_v2 and the apply
        assertEquals(SyncResult.Ok, dev.manager.sync())
        assertEquals(1, h1().cUp)
        assertTrue(inbox.names(claimed).isEmpty())
    }

    @Test fun theAppliedTap_ridesInTheSameUpload() = runBlocking {
        drop(id(3))
        assertEquals(SyncResult.Ok, dev.manager.sync())
        val uploaded = fake.stateUploads().last().body
        assertTrue(uploaded, Regex("\"cUp\":1\\b").containsMatchIn(uploaded))
    }
}

/** Card: a late record -> EVENT_ONLY, and the file is finished (on the nothingToPush path too). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxLateRecordEventOnlyTest : InboxTestBase() {
    @Test fun lateRecord_eventOnly_fileFinished_onNothingToPush() = runBlocking {
        assertEquals(SyncResult.Ok, dev.manager.sync()) // remote + base now exist
        val wm = DataStoreEventWatermark(dev.syncDataStore)
        wm.advanceTo(at(25, 11))
        val late = at(24, 21)
        drop(id(1), ts = late)
        nextMinute()
        assertEquals(SyncResult.NothingToPush, dev.manager.sync())
        assertEquals(0, h1().cUp + h1().cDown)
        val e = dev.repository.allLogs.first().single { it.uid == "inbox-${id(1)}" }
        assertEquals("habitTap", e.kind)
        assertTrue(e.payloadJson!!.contains("\"late\":true"))
        assertTrue(e.payloadJson!!.contains("\"scored\":false"))
        assertTrue("finished", inbox.names(claimed).isEmpty())
        assertEquals(late - 1, wm.get())
    }
}

/** Card: a second sync with nothing new is a no-op for the inbox and the meta files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxSecondSyncNoopTest : InboxTestBase() {
    @Test fun secondRound_listsOnly_andUploadsNoMeta() = runBlocking {
        drop(id(1))
        assertEquals(SyncResult.Ok, dev.manager.sync())
        assertEquals(1, h1().cUp)
        assertTrue(inbox.files.containsKey(InboxPaths.INBOX_META_PATH))
        assertTrue(inbox.files.containsKey(InboxPaths.INBOX_REMINDERS_PATH))
        val before = inbox.calls.size
        val stateUploads = fake.stateUploads().size
        nextMinute()
        assertEquals(SyncResult.NothingToPush, dev.manager.sync())
        val after = inbox.calls.drop(before)
        assertEquals(listOf("files/list_folder", "files/list_folder"), after.map { it.endpoint })
        assertEquals(stateUploads, fake.stateUploads().size)
        assertEquals(1, h1().cUp)
    }

    @Test fun withinAMinute_noInboxCallsAtAll() = runBlocking {
        assertEquals(SyncResult.Ok, dev.manager.sync())
        val before = inbox.calls.size
        assertEquals(SyncResult.NothingToPush, dev.manager.sync())
        assertEquals(before, inbox.calls.size)
    }

    @Test fun metaIsNotWritten_afterAFailedRound() = runBlocking {
        fake.scriptNext("/2/files/upload", 400, "{\"error_summary\":\"bad/\"}")
        assertFalse(dev.manager.sync() == SyncResult.Ok)
        assertFalse(inbox.files.containsKey(InboxPaths.INBOX_META_PATH))
    }
}
