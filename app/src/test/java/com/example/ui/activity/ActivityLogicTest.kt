package com.example.ui.activity

import com.example.data.local.entities.EventLogEntity
import com.example.ui.settings.FeedFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** CR-KT-012: the Activity tab's pure parts (day headers, filters, nav, detail fields). */
class ActivityLogicTest {

    private val zone = ZoneId.of("Europe/Bratislava")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 8, 12)
    private val DAY = 86_400_000L

    private fun ev(id: Long, ts: Long, kind: String = "habitTap", type: String? = "habit", taskId: String? = "t1", title: String? = "Water") =
        EventLogEntity(id = id, timestamp = ts, kind = kind, taskType = type, taskId = taskId, taskTitle = title)

    // ---- groupByDay ---------------------------------------------------------------------------------
    @Test fun `day headers are Today, Yesterday, then EEE d MMM, with the year when not this year`() {
        val events = listOf(
            ev(1, at(2026, 10, 8, 10)),
            ev(2, at(2026, 10, 8, 0, 0, 0)),
            ev(3, at(2026, 10, 7, 23, 59, 59)),
            ev(4, at(2026, 10, 1, 9)),
            ev(5, at(2025, 12, 31, 9)),
        )
        val groups = groupByDay(events, zone, now)
        assertEquals(listOf("Today", "Yesterday", "Thu 1 Oct", "Wed 31 Dec 2025"), groups.map { it.label })
        assertEquals(listOf(1L, 2L), groups[0].events.map { it.id })
        assertEquals(listOf(3L), groups[1].events.map { it.id })
        assertEquals(listOf(5L), groups[3].events.map { it.id })
    }

    @Test fun `groupByDay keeps the given order and returns nothing for no events`() {
        assertTrue(groupByDay(emptyList(), zone, now).isEmpty())
        val g = groupByDay(listOf(ev(2, at(2026, 10, 8, 9)), ev(1, at(2026, 10, 8, 11))), zone, now)
        assertEquals(1, g.size)
        assertEquals(listOf(2L, 1L), g[0].events.map { it.id })
    }

    @Test fun `row time is HH mm ss`() {
        assertEquals("07:05:09", rowTime(at(2026, 10, 8, 7, 5, 9), zone))
    }

    // ---- activityFilter -----------------------------------------------------------------------------
    private val all = Long.MIN_VALUE to Long.MAX_VALUE
    private val sample = listOf(
        ev(1, at(2026, 10, 8, 9), "habitTap", "habit", "h1", "Water"),
        ev(2, at(2026, 10, 7, 9), "complete", "daily", "d1", "Stretch"),
        ev(3, at(2026, 9, 1, 9), "complete", "todo", "o1", "Buy milk"),
        ev(4, at(2026, 10, 8, 8), "export", null, null, null),
        ev(5, at(2026, 10, 8, 7), "clockSkew", null, null, null),
        ev(6, at(2026, 10, 8, 6), "conflictResolved", "habit", "h1", "Water"),
        ev(7, at(2026, 10, 8, 5), "syncCompleted", null, null, null),
        ev(8, at(2026, 10, 6, 9), "habitTap", "habit", "h1", "Water"),
    )
    private val ff = FeedFilters() // hideSyncDiag = true, hideConflictDecisions = false
    private val none = FeedFilters(hideSyncDiag = false, hideConflictDecisions = false)

    @Test fun `no filter shows everything newest first`() {
        val r = activityFilter(sample, all, "all", "", null, none)
        assertEquals(listOf(1L, 4L, 5L, 6L, 7L, 2L, 8L, 3L), r.events.map { it.id })
        assertEquals(0, r.hiddenDiagnostics)
    }

    @Test fun `range alone`() {
        val r = activityFilter(sample, at(2026, 10, 7, 0) to at(2026, 10, 7, 23), "all", "", null, none)
        assertEquals(listOf(2L), r.events.map { it.id })
    }

    @Test fun `category alone`() {
        assertEquals(listOf(1L, 8L), activityFilter(sample, all, "habit", "", null, none).events.map { it.id })
        assertEquals(listOf(2L), activityFilter(sample, all, "daily", "", null, none).events.map { it.id })
        assertEquals(listOf(3L), activityFilter(sample, all, "todo", "", null, none).events.map { it.id })
        assertEquals(listOf(4L, 5L, 7L), activityFilter(sample, all, "system", "", null, none).events.map { it.id })
    }

    @Test fun `query alone matches the title case-insensitively`() {
        assertEquals(listOf(3L), activityFilter(sample, all, "all", "  MILK ", null, none).events.map { it.id })
        // no title: falls back to the kind (Opti `taskTitle||taskType||kind`)
        assertEquals(listOf(4L), activityFilter(sample, all, "all", "expo", null, none).events.map { it.id })
    }

    @Test fun `task id alone`() {
        assertEquals(listOf(1L, 6L, 8L), activityFilter(sample, all, "all", "", "h1", none).events.map { it.id })
    }

    @Test fun `feed filters hide diagnostics and count them`() {
        val r = activityFilter(sample, all, "all", "", null, ff)
        assertFalse(r.events.any { it.id == 5L || it.id == 7L })
        assertTrue(r.events.any { it.id == 6L })
        assertEquals(2, r.hiddenDiagnostics)
        assertEquals(0, r.shownDiagnostics)
        val c = activityFilter(sample, all, "all", "", null, FeedFilters(hideSyncDiag = false, hideConflictDecisions = true))
        assertFalse(c.events.any { it.id == 6L })
        assertEquals(0, c.hiddenDiagnostics)
        assertEquals(2, c.shownDiagnostics)
    }

    // Opti renderEventDetail (app.js:5142-5150, ffdce91): only hideSyncDiag rows, conflictResolved has its own pref.
    @Test fun `conflict decisions hidden by their own pref are not in the hidden count`() {
        val r = activityFilter(sample, all, "all", "", null, FeedFilters(hideSyncDiag = true, hideConflictDecisions = true))
        assertFalse(r.events.any { it.id == 5L || it.id == 6L || it.id == 7L })
        assertEquals(2, r.hiddenDiagnostics)
    }

    @Test fun `hidden count is over the whole range, before chip, search and task filters`() {
        assertEquals(2, activityFilter(sample, all, "habit", "", null, ff).hiddenDiagnostics)
        assertEquals(2, activityFilter(sample, all, "system", "", null, ff).hiddenDiagnostics)
        assertEquals(2, activityFilter(sample, all, "all", "milk", null, ff).hiddenDiagnostics)
        assertEquals(2, activityFilter(sample, all, "all", "", "h1", ff).hiddenDiagnostics)
        assertEquals(2, activityFilter(sample, all, "all", "", "h1", none).shownDiagnostics)
        // the range still applies
        assertEquals(0, activityFilter(sample, at(2026, 10, 7, 0) to at(2026, 10, 7, 23), "all", "", null, ff).hiddenDiagnostics)
    }

    // Opti search (app.js:5156-5158): `taskTitle||taskType||kind||subText`, or the notes.
    @Test fun `query also matches the notes and falls back to subText`() {
        val evs = listOf(
            ev(1, at(2026, 10, 8, 9), "import", null, null, null).copy(payloadJson = """{"notes":"Restored 9 events"}"""),
            ev(2, at(2026, 10, 8, 8), "", null, null, null).copy(payloadJson = """{"subText":"Floss"}"""),
            ev(3, at(2026, 10, 8, 7), "complete", "daily", "d1", "Stretch").copy(payloadJson = """{"subText":"Floss"}"""),
            ev(4, at(2026, 10, 8, 6), "edit", null, null, "").copy(payloadJson = "not json"),
        )
        assertEquals(listOf(1L), activityFilter(evs, all, "all", "RESTORED", null, none).events.map { it.id })
        // subText only when title, type and kind are all empty (JS `||`)
        assertEquals(listOf(2L), activityFilter(evs, all, "all", "floss", null, none).events.map { it.id })
        // an empty title falls back to the kind; a bad payload is ignored
        assertEquals(listOf(4L), activityFilter(evs, all, "all", "edit", null, none).events.map { it.id })
    }

    @Test fun `filters combine`() {
        val r = activityFilter(sample, at(2026, 10, 7, 0) to now, "habit", "wat", "h1", ff)
        assertEquals(listOf(1L), r.events.map { it.id })
    }

    // ---- range + nav --------------------------------------------------------------------------------
    @Test fun `range bounds`() {
        assertEquals(Long.MIN_VALUE to Long.MAX_VALUE, ActivityRange().bounds(now))
        assertEquals((now - 7 * DAY) to Long.MAX_VALUE, ActivityRange(snap = "7d").bounds(now))
        assertEquals((now - 365 * DAY) to Long.MAX_VALUE, ActivityRange(snap = "365d").bounds(now))
        assertEquals(100L to 200L, ActivityRange.window(100L, 200L).bounds(now))
        assertTrue(ActivityRange.window(100L, 200L).isCustom)
        assertFalse(ActivityRange().isCustom)
        assertEquals("all", ActivityRange().snap)
    }

    @Test fun `analytics window opens Activity with the same range and no task filter`() {
        val nav = ActivityNav(task = TaskFilter("h1", "Water")).forWindow(100L, 200L)
        assertEquals(100L to 200L, nav.range.bounds(now))
        assertNull(nav.task)
    }

    @Test fun `Analytics All opens the All chip, an open-ended window stays open, a past window stays fixed`() {
        assertEquals(ActivityRange(), analyticsActivityRange("all", 0, 0L, now))
        val open = analyticsActivityRange("90d", 0, now - 90 * DAY, now)
        assertEquals(ActivityRange(snap = null, fromMs = now - 90 * DAY, toMs = null), open)
        assertTrue(open.isCustom)
        // an event logged after the tap is still inside
        assertTrue(open.bounds(now).second >= now + 60_000L)
        assertEquals(ActivityRange.window(100L, 200L), analyticsActivityRange(null, 5, 100L, 200L))
    }

    @Test fun `opening Activity from the nav bar clears the task filter, other tabs keep it`() {
        val nav = ActivityNav(range = ActivityRange(snap = "7d")).forTask("h1", "Water").copy(range = ActivityRange(snap = "7d"))
        assertEquals(ActivityNav(range = ActivityRange(snap = "7d")), activityNavOnTabSelect(nav, ACTIVITY_TAB))
        assertEquals(nav, activityNavOnTabSelect(nav, 4))
    }

    @Test fun `ActivityNav survives save and restore`() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        listOf(
            ActivityNav(),
            ActivityNav().forTask("h1", "Water"),
            ActivityNav(range = ActivityRange(snap = null, fromMs = 5L, toMs = null)),
            ActivityNav(range = ActivityRange.window(5L, 9L), task = TaskFilter("t", "T")),
        ).forEach { nav ->
            val saved = with(ActivityNavSaver) { scope.save(nav) }!!
            assertEquals(nav, ActivityNavSaver.restore(saved))
        }
    }

    @Test fun `task history opens Activity filtered by the task over all time, clearing removes it`() {
        val nav = ActivityNav(range = ActivityRange(snap = "7d")).forTask("h1", "Water")
        assertEquals(TaskFilter("h1", "Water"), nav.task)
        assertEquals(ActivityRange(), nav.range)
        assertNull(nav.clearTask().task)
    }

    // ---- detail fields ------------------------------------------------------------------------------
    @Test fun `detail shows every non-null field`() {
        val e = EventLogEntity(
            id = 9, timestamp = at(2026, 10, 8, 7, 5, 9), kind = "habitReps", taskType = "habit", taskId = "h1", taskTitle = "Water",
            streak = 3, damage = 1.5, direction = -1, reps = 4, value = 2.0, cost = 10.0, payloadJson = "{\"a\":1}",
            uid = "u-1", dev = "pixel", imported = true, republish = false, synthetic = true,
        )
        val f = eventDetailFields(e, zone).toMap()
        assertEquals("habitReps", f["Kind"])
        assertEquals("Water (habit)", f["Task"])
        assertEquals("2026-10-08 07:05:09", f["Time"])
        assertEquals("1.5", f["Damage"])
        assertEquals("4", f["Reps"])
        assertEquals("2.0", f["Value"])
        assertEquals("10.0", f["Cost"])
        assertEquals("3", f["Streak"])
        assertEquals("-1", f["Direction"])
        assertEquals("pixel", f["Device"])
        assertEquals("u-1", f["UID"])
        assertEquals("true", f["Imported"])
        assertEquals("false", f["Republish"])
        assertEquals("true", f["Synthetic"])
        assertEquals("{\n    \"a\": 1\n}", prettyPayload(e.payloadJson))
    }

    @Test fun `detail hides null fields`() {
        val e = EventLogEntity(id = 1, timestamp = at(2026, 10, 8, 7), kind = "export")
        assertEquals(listOf("Kind", "Time"), eventDetailFields(e, zone).map { it.first })
        assertNull(prettyPayload(null))
        assertEquals("not json", prettyPayload("not json"))
    }
}
