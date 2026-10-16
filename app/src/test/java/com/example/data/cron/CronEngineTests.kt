package com.example.data.cron

import com.example.data.LocalDay
import com.example.data.PauseState
import com.example.data.RpgEngine
import com.example.data.Week
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.sync.SerializationEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * WP-8.10 engine tests (pure JVM, fake clock values, explicit zones). Opti anchors: app.js at
 * 7d5d4f3. Card tests: WeekBoundaryTest, CronDayRewindTest, PauseFeatureTest, CreditYesterdayTest,
 * G12 (LogHabitRepsTest) and ShopConstantsTest.
 */
internal val BRATISLAVA: ZoneId = ZoneId.of("Europe/Bratislava")
internal val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
internal const val HLC = 9_000_000_000_000L

internal fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = BRATISLAVA): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

internal fun cronIn(char: CharacterEntity, tasks: List<TaskEntity>, lastCron: Int, nowMs: Long, zone: ZoneId = BRATISLAVA) =
    RpgEngine.CronInput(char, tasks, lastCron, nowMs, zone, HLC)

/** PX-67 (G9): app.js:2622-2635, Monday-anchored, independent of Locale. */
class WeekBoundaryTest {
    private var savedLocale: Locale = Locale.getDefault()
    @Before fun us() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.US) } // Sunday-first locale
    @After fun restore() { Locale.setDefault(savedLocale) }

    private fun weekly(last: Int, now: Long, z: ZoneId = BRATISLAVA) = Week.periodBoundaryCrossed("weekly", last, now, z)

    @Test fun sundayBelongsToTheMondayWeek_resetOnMondayOnly() {
        // last = Mon 2026-09-21
        assertFalse(weekly(20260921, at(2026, 9, 26, 10))) // Sat
        assertFalse(weekly(20260921, at(2026, 9, 27, 23, 59))) // Sun (Calendar.US would call it a new week)
        assertTrue(weekly(20260921, at(2026, 9, 28, 0, 1))) // Mon
        // last = Sun 2026-09-20 -> Mon 21 is a new week
        assertTrue(weekly(20260920, at(2026, 9, 21, 8)))
    }

    @Test fun yearBoundary_sameIsoWeek_noReset() {
        assertFalse(weekly(20261231, at(2027, 1, 3, 12))) // Thu -> Sun, same Monday (2026-12-28)
        assertTrue(weekly(20261231, at(2027, 1, 4, 12)))
    }

    @Test fun dstFallBackWeekend() {
        // 2026-10-25 is the CEST->CET Sunday in Bratislava.
        assertFalse(weekly(20261024, at(2026, 10, 25, 23, 30)))
        assertTrue(weekly(20261024, at(2026, 10, 26, 0, 30)))
    }

    @Test fun monthlyAndDailyAndFreshAccount() {
        assertTrue(Week.periodBoundaryCrossed("monthly", 20260930, at(2026, 10, 1, 0, 5), BRATISLAVA))
        assertFalse(Week.periodBoundaryCrossed("monthly", 20260901, at(2026, 9, 30, 23), BRATISLAVA))
        assertTrue(Week.periodBoundaryCrossed("monthly", 20261231, at(2027, 1, 1, 1), BRATISLAVA))
        assertTrue(Week.periodBoundaryCrossed("daily", 20260925, at(2026, 9, 25, 12), BRATISLAVA))
        assertTrue(Week.periodBoundaryCrossed("bogus", 20260925, at(2026, 9, 25, 12), BRATISLAVA))
        // lastCron = 0 is JS new Date(0,-1,0) = 1899-11-30.
        assertTrue(weekly(0, at(2026, 9, 25, 12)))
        assertEquals(java.time.LocalDate.of(1899, 11, 30), LocalDay.jsDateOfStamp(0))
    }

    @Test fun zoneDecidesTheLocalDay() {
        // 2026-09-28 03:00 Bratislava is still Sun 27 in New York.
        val ms = at(2026, 9, 28, 3)
        assertTrue(weekly(20260921, ms, BRATISLAVA))
        assertFalse(weekly(20260921, ms, NEW_YORK))
    }
}

/** PX-66 (G8): app.js:2922 `if(today <= S.lastCron) return;` (tests/cron-day-rewind.test.js). */
class CronDayRewindTest {
    private val char = CharacterEntity(hp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
    private val missed = TaskEntity(id = "d1", type = "daily", title = "Read", value = 0.0, streak = 4, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
    private val done = TaskEntity(id = "d2", type = "daily", title = "Gym", done = true, doneAt = 123L, doneDay = 20260924, streak = 3, checklist = listOf(ChecklistItem("c", "x", true)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    @Test fun lastCronAhead_orEqual_isANoOp() {
        val now = at(2026, 9, 25, 9)
        for (last in listOf(20260925, 20260926, 20261231)) {
            val out = RpgEngine.runCron(cronIn(char.copy(lastCron = "$last"), listOf(missed, done), last, now), 20260925, PauseState.NONE)
            assertFalse(out.ran)
            assertEquals(listOf(missed, done), out.tasks)
            assertEquals(char.hp, out.character.hp, 0.0)
            assertTrue(out.missLogs.isEmpty())
        }
    }

    @Test fun westwardTravel_rewindsTheLocalDay_noSecondCron() {
        // Cron ran in Bratislava on 26 Sep; at 05:00 there it is still 25 Sep 23:00 in New York.
        val now = at(2026, 9, 26, 5)
        val today = LocalDay.dayStamp(now, NEW_YORK)
        assertEquals(20260926 - 1, today)
        val out = RpgEngine.runCron(cronIn(char, listOf(missed, done), 20260926, now, NEW_YORK), today, PauseState.NONE)
        assertFalse(out.ran)
        assertTrue(out.tasks[1].done) // today's real completion is not cleared
    }

    @Test fun newDay_runs_damage_missedOn_resets_snapshot() {
        val now = at(2026, 9, 25, 9)
        val out = RpgEngine.runCron(cronIn(char, listOf(missed, done), 20260924, now), 20260925, PauseState.NONE)
        assertTrue(out.ran)
        assertEquals("20260925", out.character.lastCron)
        val d1 = out.tasks[0]
        assertEquals(0, d1.streak)
        assertEquals(20260924, d1.missedOn)
        assertEquals(missed.updatedAt, d1.updatedAt) // F3: cron never bumps updatedAt
        val point = d1.history.single()
        assertEquals(LocalDay.localDayDateAtOffset(now, -1, BRATISLAVA), point.date) // backdated to yesterday noon
        assertEquals(false, point.completed)
        assertEquals(true, point.isDue)
        val dmg = RpgEngine.missDamage("easy", 0.0)
        assertEquals(50.0 - dmg, out.character.hp, 1e-9)
        assertEquals(HLC, out.character.updatedAt)
        assertEquals(dmg, out.missLogs.single().damage!!, 0.0)
        val d2 = out.tasks[1]
        assertFalse(d2.done)
        assertFalse(d2.checklist.single().done)
        assertEquals(3, d2.streak)
        assertEquals(d2.doneAt, done.doneAt) // _resetDailies leaves doneAt (app.js:2903-2910)
        assertEquals(out.character.hp, out.charSnapshot!!.hp, 0.0)
        assertEquals(now, out.charSnapshot!!.date)
    }

    @Test fun dstSpringForward_yesterdayIsTheCalendarDay() {
        // 2026-03-29 is the CET->CEST Sunday (23 h). Cron at Mon 00:30.
        val now = at(2026, 3, 30, 0, 30)
        val out = RpgEngine.runCron(cronIn(char, listOf(missed), 20260329, now), 20260330, PauseState.NONE)
        assertEquals(20260329, out.tasks[0].missedOn)
        assertEquals(at(2026, 3, 29, 12), out.tasks[0].history.single().date)
    }

    @Test fun habitsReset_onTheirOwnBoundary_withCResetOn() {
        val weekly = TaskEntity(id = "h1", type = "habit", title = "W", resetFreq = "weekly", cUp = 3, cDown = 1, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val daily = TaskEntity(id = "h2", type = "habit", title = "D", cUp = 2, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val now = at(2026, 9, 26, 9) // Sat; last cron Fri 25 -> same week
        val out = RpgEngine.runCron(cronIn(char, listOf(weekly, daily), 20260925, now), 20260926, PauseState.NONE)
        assertEquals(3, out.tasks[0].cUp)
        assertNull(out.tasks[0].cResetOn)
        assertEquals(0, out.tasks[1].cUp)
        assertEquals(20260926, out.tasks[1].cResetOn)
    }
}

/** PX-65 (G-PAUSE, G7, G10): app.js:2923-2944, :2644-2647, :2309, :2365, :2680. */
class PauseFeatureTest {
    private val char = CharacterEntity(hp = 50.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
    private val missed = TaskEntity(id = "d1", type = "daily", title = "Read", streak = 4, checklist = listOf(ChecklistItem("c", "x", true)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
    private val weekly = TaskEntity(id = "h1", type = "habit", title = "W", resetFreq = "weekly", cUp = 3, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    @Test fun pausedCron_noDamage_streakFrozen_countersStillReset_daysRecorded() {
        val now = at(2026, 9, 28, 9) // Mon; previous cron Sun 27 -> weekly boundary crossed
        val pause = PauseState(true, listOf(20260921, 20260922, 20260923, 20260924, 20260925, 20260926, 20260927))
        val out = RpgEngine.runCron(cronIn(char, listOf(missed, weekly), 20260927, now), 20260928, pause)
        assertTrue(out.ran)
        assertEquals(50.0, out.character.hp, 0.0)
        assertEquals("20260928", out.character.lastCron)
        assertEquals(4, out.tasks[0].streak)
        assertNull(out.tasks[0].missedOn)
        assertTrue(out.tasks[0].history.isEmpty())
        assertFalse(out.tasks[0].checklist.single().done) // _resetDailies still runs
        assertEquals(0, out.tasks[1].cUp)
        assertEquals(20260928, out.tasks[1].cResetOn)
        assertEquals(listOf(20260922, 20260923, 20260924, 20260925, 20260926, 20260927, 20260928), out.pause.pausedDays)
        assertTrue(out.missLogs.isEmpty())
        assertNull(out.charSnapshot)
    }

    @Test fun pausedDays_dedupe() {
        val out = RpgEngine.runCron(cronIn(char, emptyList(), 20260927, at(2026, 9, 28, 9)), 20260928, PauseState(true, listOf(20260928)))
        assertEquals(listOf(20260928), out.pause.pausedDays)
    }

    @Test fun unpausedCron_yesterdayWasPaused_noDamage() {
        val out = RpgEngine.runCron(cronIn(char, listOf(missed), 20260927, at(2026, 9, 28, 9)), 20260928, PauseState(false, listOf(20260927)))
        assertEquals(50.0, out.character.hp, 0.0)
        assertEquals(4, out.tasks[0].streak)
    }

    @Test fun missedYesterday_gates() {
        val now = at(2026, 9, 28, 9)
        assertTrue(RpgEngine.missedYesterdayDailies(listOf(missed), PauseState(true, emptyList()), 20260927, now, BRATISLAVA).isEmpty())
        assertTrue(RpgEngine.missedYesterdayDailies(listOf(missed), PauseState(false, listOf(20260927)), 20260927, now, BRATISLAVA).isEmpty())
        assertEquals(listOf(missed), RpgEngine.missedYesterdayDailies(listOf(missed), PauseState.NONE, 20260927, now, BRATISLAVA))
    }

    @Test fun streakFrozen_onComplete_uncomplete_credit() {
        val daily = TaskEntity(id = "d", type = "daily", title = "x", streak = 4, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val (_, done) = RpgEngine.completeTask(char, daily, paused = true, nowMs = at(2026, 9, 28, 9), now = HLC, zone = BRATISLAVA)
        assertEquals(4, done.streak)
        val (_, undone) = RpgEngine.uncompleteTask(char, done, paused = true, now = HLC)
        assertEquals(4, undone.streak)
        val credit = RpgEngine.creditYesterday(char, daily, paused = true, nowMs = at(2026, 9, 28, 9), now = HLC, zone = BRATISLAVA)!!
        assertEquals(4, credit.task.streak)
        // not paused: +1 / -1
        val (_, d2) = RpgEngine.completeTask(char, daily, paused = false, nowMs = at(2026, 9, 28, 9), now = HLC, zone = BRATISLAVA)
        assertEquals(5, d2.streak)
        assertEquals(20260928, d2.doneDay)
        assertEquals(4, RpgEngine.uncompleteTask(char, d2, paused = false, now = HLC).second.streak)
        assertNull(RpgEngine.uncompleteTask(char, d2, paused = false, now = HLC).second.doneDay)
    }
}

/** PX-68 (G11, SY-18): app.js:2667-2691. */
class CreditYesterdayTest {
    private val char = CharacterEntity(xp = 0, gold = 0.0, hp = 40.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    @Test fun backdatedToLocalNoon_nearMidnight_andOnTheDstFallBackDay() {
        val daily = TaskEntity(id = "d", type = "daily", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        // 23:50 local
        val c1 = RpgEngine.creditYesterday(char, daily, false, at(2026, 9, 25, 23, 50), HLC, BRATISLAVA)!!
        assertEquals(at(2026, 9, 24, 12), c1.task.doneAt)
        assertEquals(20260924, c1.task.doneDay)
        // 2026-10-25 has 25 h: 23:30 minus a flat 24 h is still the 25th; local noon of the 24th is right.
        val now = at(2026, 10, 25, 23, 30)
        assertEquals(20261025, LocalDay.dayStamp(now - 86_400_000L, BRATISLAVA))
        val c2 = RpgEngine.creditYesterday(char, daily, false, now, HLC, BRATISLAVA)!!
        assertEquals(20261024, c2.task.doneDay)
        assertEquals(at(2026, 10, 24, 12), c2.task.doneAt)
    }

    @Test fun rewardRealizedDelta_streak_missedOn_history_event() {
        val daily = TaskEntity(
            id = "d", type = "daily", title = "Read", difficulty = "medium", value = 99.0, streak = 2, missedOn = 20260923,
            repeat = listOf(true, true, true, true, true, true, true), checklist = listOf(ChecklistItem("c1", "page", false)), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()
        )
        val now = at(2026, 9, 25, 9)
        val c = RpgEngine.creditYesterday(char, daily, false, now, HLC, BRATISLAVA)!!
        val r = RpgEngine.completionReward("medium", 99.0)
        assertEquals(r.xp, c.character.xp)
        assertEquals(40.0, c.character.hp, 0.0)
        assertEquals(99.0, c.task.value, 0.0)
        assertEquals(0.0, c.task.gr!!.delta, 0.0) // realized, clamped at 99
        assertEquals(3, c.task.streak)
        assertNull(c.task.missedOn)
        assertEquals(HLC, c.task.updatedAt)
        val h = c.task.history.single()
        assertEquals(at(2026, 9, 24, 12), h.date)
        assertEquals(true, h.completed)
        assertEquals(true, h.isDue)
        val json = SerializationEngine.buildTaskHistoryPointJson(h)
        val item = (json["checklist"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("text", "done"), item.keys) // Opti {text, done:true}, no id
        assertEquals("true", item["done"].toString())
        assertTrue(c.log.payloadJson!!.contains("\"late\":true"))
        assertEquals(now, c.log.timestamp)
    }

    @Test fun skips_done_and_notDueYesterday() {
        val now = at(2026, 9, 25, 9) // Fri -> yesterday Thu (index 4)
        assertNull(RpgEngine.creditYesterday(char, TaskEntity(id = "a", type = "daily", title = "a", done = true, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), false, now, HLC, BRATISLAVA))
        val notThu = listOf(true, true, true, true, false, true, true)
        assertNull(RpgEngine.creditYesterday(char, TaskEntity(id = "b", type = "daily", title = "b", repeat = notThu, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), false, now, HLC, BRATISLAVA))
    }

    @Test fun missedYesterday_usesYesterdaysWeekday_andTaskOrder() {
        val now = at(2026, 9, 25, 9) // yesterday Thu
        val onlyThu = listOf(false, false, false, false, true, false, false)
        val onlyFri = listOf(false, false, false, false, false, true, false)
        val tasks = listOf(
            TaskEntity(id = "1", type = "daily", title = "a", repeat = onlyThu, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "2", type = "daily", title = "b", repeat = onlyFri, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "3", type = "todo", title = "c", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "4", type = "daily", title = "d", done = true, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "5", type = "daily", title = "e", repeat = emptyList(), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
            TaskEntity(id = "6", type = "daily", title = "f", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()),
        )
        assertEquals(listOf("1", "6"), RpgEngine.missedYesterdayDailies(tasks, PauseState.NONE, 20260924, now, BRATISLAVA).map { it.id })
        assertTrue(RpgEngine.missedYesterdayDailies(tasks, PauseState.NONE, 20260925, now, BRATISLAVA).isEmpty())
        assertEquals(2, RpgEngine.missedYesterdayDailies(tasks, PauseState.NONE, 0, now, BRATISLAVA).size) // new account
    }
}

/** PX-69 (G12, G3): app.js:2209, :2464-2507, :3573. */
class LogHabitRepsTest {
    private val char = CharacterEntity(xp = 5, hp = 40.0, gold = 1.0, updatedAt = com.example.data.clock.PhysicalClock.nowMs())

    @Test fun logHabit_pureTally_withRepsHistory_mergedPerUtcDay() {
        val h = TaskEntity(id = "h", type = "habit", title = "10 pushups", difficulty = "log", value = 2.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val t1 = at(2026, 9, 25, 9)
        val (c1, a) = RpgEngine.scoreHabitUp(char, h, HLC, t1)
        assertEquals(char, c1)
        assertEquals(1, a.cUp)
        assertEquals(2.0, a.value, 0.0)
        assertEquals(TaskHistoryPoint(date = t1, value = 2.0, reps = 10, repCounted = true, scored = false), a.history.single())
        val (_, b) = RpgEngine.scoreHabitDown(char, a, HLC, t1 + 60_000)
        assertEquals(1, b.cDown)
        assertEquals(20, b.history.single().reps)
        assertEquals(t1 + 60_000, b.history.single().date)
    }

    @Test fun scoredTaps_recordScoredUpDown() {
        val h = TaskEntity(id = "h", type = "habit", title = "Water", value = 0.0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val (_, up) = RpgEngine.scoreHabitUp(char, h, HLC, at(2026, 9, 25, 9))
        val p = up.history.single()
        assertEquals(1, p.scoredUp); assertEquals(1, p.reps); assertEquals(true, p.scored); assertEquals(up.value, p.value!!, 0.0)
        val (_, down) = RpgEngine.scoreHabitDown(char, up, HLC, at(2026, 9, 26, 9))
        assertEquals(2, down.history.size)
        assertEquals(1, down.history[1].scoredDown)
        assertNull(down.history[1].reps)
    }

    @Test fun coefficients_andRepsPerTap() {
        assertEquals(1.0, RpgEngine.coefficient("log"), 0.0) // DIFF.log = 0 is falsy -> 1
        assertEquals(1.0, RpgEngine.coefficient("unknown"), 0.0)
        assertEquals(0.1, RpgEngine.coefficient("trivial"), 0.0)
        assertEquals(RpgEngine.completionReward("easy", 3.0), RpgEngine.completionReward("log", 3.0))
        assertEquals(1, RpgEngine.repsPerTap("no digits"))
        assertEquals(25, RpgEngine.repsPerTap("Squats 25 x2"))
        assertEquals(10, RpgEngine.tapReps(TaskEntity(id = "x", type = "habit", title = "10 reps", repsPerTap = 0, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())))
        assertEquals(7, RpgEngine.tapReps(TaskEntity(id = "x", type = "habit", title = "10 reps", repsPerTap = 7, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())))
    }

    @Test fun backdatedPoint_isSortedIn() {
        val h = TaskEntity(id = "h", type = "habit", title = "x", difficulty = "log",
            history = listOf(TaskHistoryPoint(date = at(2026, 9, 25, 9))), createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val out = RpgEngine.logHistory(h.history, RpgEngine.HistoryPatch(value = 1.0), at(2026, 9, 23, 9))
        assertEquals(listOf(at(2026, 9, 23, 9), at(2026, 9, 25, 9)), out.map { it.date })
    }
}

/**
 * Review follow-up: the `miss` (runCron, app.js:2971-2974) and late `complete` (creditYesterday,
 * app.js:2686-2688) events carry Opti's fields in Opti's order, on the push wire (ts/uid/dev aside).
 */
class CronEventShapeTest {
    private fun wire(e: com.example.data.local.entities.EventLogEntity) =
        com.example.data.sync.EventSyncEngine.buildEventPushJson(e).filterKeys { it !in setOf("ts", "uid", "dev") }

    private val rep = listOf(true, false, true, true, true, true, true)
    private val cl = listOf(ChecklistItem("c1", "page", false), ChecklistItem("", "notes", true))

    @Test fun miss_matchesOpti() {
        val t = TaskEntity(id = "d1", type = "daily", title = "Read", repeat = rep, checklist = cl, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val out = RpgEngine.runCron(cronIn(CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs()), listOf(t), 20260924, at(2026, 9, 25, 9)), 20260925, PauseState.NONE)
        val w = wire(out.missLogs.single())
        assertEquals(listOf("kind", "taskType", "taskId", "taskTitle", "dmg", "repeat", "checklist"), w.keys.toList())
        assertEquals("[true,false,true,true,true,true,true]", w["repeat"].toString())
        // {id: c.id||null, text, done: !!c.done}, captured before _resetDailies
        assertEquals("[{\"id\":\"c1\",\"text\":\"page\",\"done\":false},{\"id\":null,\"text\":\"notes\",\"done\":true}]", w["checklist"].toString())
        assertEquals("miss", w["kind"]!!.toString().trim('"'))
    }

    @Test fun miss_noRepeat_isEmptyArray() {
        val t = TaskEntity(id = "d1", type = "daily", title = "Read", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val out = RpgEngine.runCron(cronIn(CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs()), listOf(t), 20260924, at(2026, 9, 25, 9)), 20260925, PauseState.NONE)
        val w = wire(out.missLogs.single())
        assertEquals("[]", w["repeat"].toString())
        assertEquals("[]", w["checklist"].toString())
    }

    @Test fun lateComplete_matchesOpti() {
        val t = TaskEntity(id = "d1", type = "daily", title = "Read", difficulty = "medium", value = 1.0, streak = 2, repeat = rep, checklist = cl, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val c = RpgEngine.creditYesterday(CharacterEntity(updatedAt = com.example.data.clock.PhysicalClock.nowMs()), t, false, at(2026, 9, 25, 9), HLC, BRATISLAVA)!!
        val w = wire(c.log)
        assertEquals(listOf("kind", "taskType", "taskId", "taskTitle", "streak", "reward", "repeat", "late", "checklist"), w.keys.toList())
        val gr = c.task.gr!!
        val reward = w["reward"] as kotlinx.serialization.json.JsonObject
        assertEquals(listOf("xp", "gold", "mp", "delta"), reward.keys.toList())
        assertEquals(gr.xp.toString(), reward["xp"].toString())
        assertEquals(gr.delta, reward["delta"].toString().toDouble(), 0.0)
        assertEquals("3", w["streak"].toString())
        assertEquals("true", w["late"].toString())
        assertEquals("[{\"id\":\"c1\",\"text\":\"page\",\"done\":true},{\"id\":null,\"text\":\"notes\",\"done\":true}]", w["checklist"].toString())
    }
}

/** PX-70 (G14): SHOP_ITEMS, app.js:6466-6480. */
class ShopConstantsTest {
    @Test fun potions() {
        assertEquals(listOf("potion" to 25.0, "bigpotion" to 60.0), RpgEngine.SHOP_ITEMS.map { it.id to it.cost })
        assertEquals(listOf("Health Potion", "Greater Potion"), RpgEngine.SHOP_ITEMS.map { it.title })
        val c = CharacterEntity(hp = 10.0, maxHp = 50.0, gold = 100.25, updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        val small = RpgEngine.buyPotion(c, "potion", HLC)!!
        assertEquals(25.0, small.hp, 0.0)
        assertEquals(75.25, small.gold, 1e-9)
        assertEquals(50.0, RpgEngine.buyPotion(c.copy(hp = 45.0), "potion", HLC)!!.hp, 0.0) // clamped
        assertEquals(50.0, RpgEngine.buyPotion(c, "bigpotion", HLC)!!.hp, 0.0) // full, not +45
        assertNull(RpgEngine.buyPotion(c.copy(hp = 50.0), "potion", HLC)) // already full
        assertNull(RpgEngine.buyPotion(c.copy(gold = 59.99), "bigpotion", HLC))
        assertNull(RpgEngine.buyPotion(c, "elixir", HLC))
        assertEquals(HLC, small.updatedAt)
    }
}
