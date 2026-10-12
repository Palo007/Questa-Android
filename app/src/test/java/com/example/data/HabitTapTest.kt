package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.boot.BootGate
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.RoundDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
private fun at(d: Int, h: Int, mi: Int = 0, s: Int = 0): Long =
    LocalDateTime.of(2026, 9, d, h, mi, s).atZone(ZONE).toInstant().toEpochMilli()

abstract class TapTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var dev: RoundDevice
    protected val repo get() = dev.repository

    @Before fun tapSetUp() = runBlocking {
        dev = RoundDevice(context, "dev-tap")
        dev.syncDataStore.saveLocalDeviceId("dev-tap")
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { at(25, 12) }, ZONE)
        repo.insertTask(TaskEntity(id = "h1", type = "habit", title = "Water", difficulty = "easy", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
        repo.insertTask(TaskEntity(id = "d1", type = "daily", title = "Read", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
    }

    @After fun tapTearDown() { dev.close(); com.example.data.boot.restoreRealBootClock() }

    protected fun rec(id: String, habit: String = "h1", dir: Int = 1, ts: Long = at(25, 9)) =
        TapRecord(id = id, habitId = habit, dir = dir, ts = ts, tzOffsetMin = 120, src = "android-shortcut")

    protected suspend fun h1() = repo.getTaskByIdSync("h1")!!
    protected suspend fun tapEvents() = repo.allLogs.first().filter { it.kind == "habitTap" }
}

/** WP-8.6 card: ApplyHabitTapOutcomesTest (6 rows), app.js:2436-2459 plus the consumer's uid check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApplyHabitTapOutcomesTest : TapTestBase() {
    @Test fun row1_sameDay_applied_countersRise_eventCarriesUidTsSrc() = runBlocking {
        val r = rec("a")
        assertEquals(TapResult.APPLIED, repo.applyHabitTap(r, at(25, 12), ZONE) { false })
        assertEquals(1, h1().cUp)
        val e = tapEvents().single()
        assertEquals("inbox-a", e.uid)
        assertEquals(r.ts, e.timestamp)
        assertEquals("dev-tap", e.dev)
        assertTrue(e.payloadJson!!.contains("\"src\":\"android-shortcut\""))
    }

    @Test fun row2_earlierDay_eventOnly_lateUnscored_countersUnchanged() = runBlocking {
        val r = rec("b", ts = at(24, 21))
        assertEquals(TapResult.EVENT_ONLY, repo.applyHabitTap(r, at(25, 12), ZONE) { false })
        assertEquals(0, h1().cUp + h1().cDown)
        val e = tapEvents().single()
        assertEquals("inbox-b", e.uid)
        assertTrue(e.payloadJson!!.contains("\"late\":true"))
        assertTrue(e.payloadJson!!.contains("\"scored\":false"))
    }

    @Test fun row3_unknownHabitOrDir_unknown() = runBlocking {
        assertEquals(TapResult.UNKNOWN, repo.applyHabitTap(rec("c", habit = "nope"), at(25, 12), ZONE) { false })
        assertEquals(TapResult.UNKNOWN, repo.applyHabitTap(rec("c2", habit = "d1"), at(25, 12), ZONE) { false })
        assertEquals(TapResult.UNKNOWN, repo.applyHabitTap(rec("c3", dir = 0), at(25, 12), ZONE) { false })
        assertTrue(tapEvents().isEmpty())
    }

    @Test fun row4_gateClosed_gated_bootGateIsTheDefault() = runBlocking {
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertTrue(BootGate.blocksInput())
        assertEquals(TapResult.GATED, repo.applyHabitTap(rec("d"), at(25, 12), ZONE))
        assertEquals(0, h1().cUp)
        BootGate.onFirstRoundSettled(null)
        assertEquals(TapResult.APPLIED, repo.applyHabitTap(rec("d"), at(25, 12), ZONE))
    }

    @Test fun row5_applyFailure_error_neverAThrow() = runBlocking {
        val broken: () -> Boolean = { throw IllegalStateException("gate read failed") }
        // A gate read that throws is treated as gated (Opti: catch -> gated = true).
        assertEquals(TapResult.GATED, repo.applyHabitTap(rec("e"), at(25, 12), ZONE, broken))
        // Any failure inside the apply (here: the event table is gone) is ERROR (Opti: catch(e) -> 'error').
        dev.db.openHelper.writableDatabase.execSQL("DROP TABLE event_logs")
        assertEquals(TapResult.ERROR, repo.applyHabitTap(rec("e2"), at(25, 12), ZONE) { false })
    }

    @Test fun row5b_countersDidNotRise_error_rollsBack_soTheRetryScores() = runBlocking {
        // review 26 minor (a): the event must not stay behind on ERROR, or the retry would be
        // ALREADY_APPLIED and the tap would be dropped without a tapError.
        val noRise: suspend (TaskEntity, Int, String, Long, String, String) -> TaskEntity = { t, d, u, ts, s, dv ->
            repo.scoreHabitTapped(t, d, u, ts, s, dv)
            t // counters "did not rise"
        }
        assertEquals(TapResult.ERROR, repo.applyHabitTapWith(rec("g"), at(25, 12), ZONE, { false }, noRise))
        assertTrue("no event, so no uid stored", tapEvents().isEmpty())
        assertEquals("the partial score is rolled back", 0, h1().cUp)
        assertEquals(TapResult.APPLIED, repo.applyHabitTap(rec("g"), at(25, 12), ZONE) { false })
        assertEquals(1, h1().cUp)
        assertEquals(1, tapEvents().size)
    }

    @Test fun row6_duplicateUid_alreadyApplied_scoredOnce_evenConcurrently() = runBlocking {
        val r = rec("f")
        val results = (1..4).map { async(Dispatchers.Default) { repo.applyHabitTap(r, at(25, 12), ZONE) { false } } }.awaitAll()
        assertEquals(1, results.count { it == TapResult.APPLIED })
        assertEquals(3, results.count { it == TapResult.ALREADY_APPLIED })
        assertEquals(1, h1().cUp)
        assertEquals(1, tapEvents().size)
    }
}

/** WP-8.6 card: NearMidnightTapTest (D23: the day is judged on the PHYSICAL clock, not the HLC). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearMidnightTapTest : TapTestBase() {
    @Test fun hlcSixtySecondsAhead_tapAt235930_isApplied_notEventOnly() = runBlocking {
        val tap = at(25, 23, 59, 30)
        val physicalNow = tap + 10_000L // 23:59:40, same local day
        // A device whose HLC is 60 s ahead, i.e. already past midnight.
        val ahead = RoundDevice(context, "dev-mid", hlcSeed = physicalNow + 60_000L)
        try {
            assertTrue(ahead.hlc.lastIssued.value > at(26, 0))
            ahead.repository.insertTask(TaskEntity(id = "h1", type = "habit", title = "Water", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
            assertEquals(TapResult.APPLIED, ahead.repository.applyHabitTap(rec("m", ts = tap), physicalNow, ZONE) { false })
            assertEquals(1, ahead.repository.getTaskByIdSync("h1")!!.cUp)
        } finally {
            ahead.close()
        }
    }
}

/** WP-8.6 card: LocalTapQueueTest (GATED kept, ERROR retried 3 times then dropped). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTapQueueTest : TapTestBase() {
    @Test fun gatedIsKept_thenAppliedWhenTheGateOpens() = runBlocking {
        val q = LocalTapQueue(dev.syncDataStore, repo, { ZONE }, { at(25, 12) })
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertEquals(TapResult.GATED, q.enqueue(rec("g")))
        assertEquals(listOf("g"), q.pending().map { it.id })
        BootGate.onFirstRoundSettled(null)
        val drained = q.drain(at(25, 12))
        assertEquals(TapResult.APPLIED, drained.single().second)
        assertTrue(q.pending().isEmpty())
        assertEquals(1, h1().cUp)
    }

    @Test fun errorIsRetriedThreeTimes_thenDroppedWithADiagEvent() = runBlocking {
        val q = LocalTapQueue(dev.syncDataStore, repo, { ZONE }, { at(25, 12) }, apply = { _, _ -> TapResult.ERROR })
        val bad = rec("x")
        assertEquals(TapResult.ERROR, q.enqueue(bad))
        assertEquals(1, q.pending().size)
        assertEquals(TapResult.ERROR, q.drain(at(25, 12)).single().second)
        assertEquals(1, q.pending().size)
        assertEquals(TapResult.ERROR, q.drain(at(25, 12)).single().second)
        assertTrue("dropped after 3 attempts", q.pending().isEmpty())
        assertEquals(1, repo.allLogs.first().count { it.kind == "tapError" && it.synthetic })
    }

    @Test fun appliedEventOnlyUnknownAlreadyApplied_areDropped() = runBlocking {
        val q = LocalTapQueue(dev.syncDataStore, repo, { ZONE }, { at(25, 12) })
        assertEquals(TapResult.APPLIED, q.enqueue(rec("1")))
        assertEquals(TapResult.EVENT_ONLY, q.enqueue(rec("2", ts = at(24, 8))))
        assertEquals(TapResult.UNKNOWN, q.enqueue(rec("3", habit = "none")))
        repo.applyHabitTap(rec("4"), at(25, 12), ZONE) { false }
        assertEquals(TapResult.ALREADY_APPLIED, q.enqueue(rec("4")))
        assertTrue(q.pending().isEmpty())
    }

    @Test fun sameIdEnqueuedTwice_isQueuedOnce() = runBlocking {
        val q = LocalTapQueue(dev.syncDataStore, repo, { ZONE }, { at(25, 12) })
        BootGate.bootStartDay(configured = true, barrierDone = true)
        q.enqueue(rec("dup")); q.enqueue(rec("dup"))
        assertEquals(1, q.pending().size)
    }
}
