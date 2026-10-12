package com.example.ui.visuals

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.CardPrefs
import com.example.ui.theme.OptiRootTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-8.18: the pure Opti visual rules (index.html / app.js at 7d5d4f3). */
class OptiVisualsTest {
    @Test fun `valColor follows Opti's thresholds`() {
        assertEquals(OptiRootTokens.DarkRed, valColor(-17.0))
        assertEquals(OptiRootTokens.Red, valColor(-16.0))
        assertEquals(OptiRootTokens.Red, valColor(-10.0))
        assertEquals(OptiRootTokens.Orange, valColor(-9.0))
        assertEquals(OptiRootTokens.Orange, valColor(-2.0))
        assertEquals(OptiRootTokens.Yellow, valColor(-1.0))
        assertEquals(OptiRootTokens.Yellow, valColor(0.0))
        assertEquals(OptiRootTokens.Green, valColor(1.0))
        assertEquals(OptiRootTokens.Green, valColor(5.9))
        assertEquals(OptiRootTokens.LBlue, valColor(6.0))
        assertEquals(OptiRootTokens.Blue, valColor(12.0))
    }

    @Test fun `card thickness is 32 plus cardThick, clamped`() {
        assertEquals(32.dp, cardMinHeight(CardPrefs()))
        assertEquals(52.dp, cardMinHeight(CardPrefs(cardThick = 20)))
        assertEquals(92.dp, cardMinHeight(CardPrefs(cardThick = 99)))
        assertEquals(30.dp, cardRowMinHeight(CardPrefs()))
    }

    @Test fun `interface width is the width pref or 480`() {
        assertEquals(480.dp, appWidth(CardPrefs()))
        assertEquals(720.dp, appWidth(CardPrefs(widthPx = 720)))
        assertEquals(480.dp, appWidth(CardPrefs(widthPx = 0)))
    }

    @Test fun `notes clamp is notesLines, none when off or empty`() {
        assertEquals(3, notesClamp("x", CardPrefs()))
        assertEquals(5, notesClamp("x", CardPrefs(notesLines = 5)))
        assertNull(notesClamp("x", CardPrefs(notesLines = 0)))
        assertNull(notesClamp("", CardPrefs(notesLines = 3)))
    }

    @Test fun `difficulty rail styles`() {
        assertEquals(OptiRootTokens.Green, diffStyle("easy").fg)
        assertEquals(Color(0x4048B677), diffStyle("easy").border)
        assertEquals(Color(0x1448B677), diffStyle("easy").bg)
        assertEquals(OptiRootTokens.Orange, diffStyle("medium").fg)
        assertEquals(OptiRootTokens.Red, diffStyle("hard").fg)
        assertEquals(OptiRootTokens.Muted, diffStyle("trivial").fg)
        assertEquals(OptiRootTokens.LBlue, diffStyle("log").fg)
        assertEquals(RailDefaultBg, diffStyle("weird").bg)
        assertEquals("EASY", railText("easy"))
        assertEquals("⏳ MON", railText("⏳ Mon"))
    }

    @Test fun `habit counter label per direction`() {
        val t = TaskEntity(id = "h", type = "habit", title = "x", cUp = 3, cDown = 1, createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertEquals("+3|−1", habitCountLabel(t))
        assertEquals("+3", habitCountLabel(t.copy(down = false)))
        assertEquals("−1", habitCountLabel(t.copy(up = false)))
        assertNull(habitCountLabel(t.copy(up = false, down = false)))
    }

    @Test fun `bell shows only for an enabled first reminder`() {
        val t = TaskEntity(id = "d", type = "daily", title = "x", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs())
        assertFalse(hasReminder(t))
        assertTrue(hasReminder(t.copy(reminders = listOf(ReminderSpec(enabled = true)))))
        assertFalse(hasReminder(t.copy(reminders = listOf(ReminderSpec(enabled = false), ReminderSpec(enabled = true)))))
    }

    @Test fun `next due weekday`() {
        val monWed = listOf(false, true, false, true, false, false, false)
        assertEquals("Wed", nextDueWeekday(monWed, 1))
        assertEquals("Mon", nextDueWeekday(monWed, 3))
        assertEquals("Never", nextDueWeekday(List(7) { false }, 2))
        assertNull(nextDueWeekday(null, 2))
        assertTrue(isDueOn(null, 4))
        assertFalse(isDueOn(monWed, 0))
    }

    @Test fun `HUD labels are renderStats'`() {
        assertEquals("13 / 50 HP", hpLabel(12.2, 50.0))
        assertEquals("0 / 50 HP", hpLabel(-3.0, 50.0))
        assertEquals("7 / 50.5 HP", hpLabel(7.0, 50.5))
        assertEquals("12 / 150 XP", xpLabel(12.0, 150.0))
        assertEquals("41", goldLabel(41.99))
        assertEquals(0.5f, barFraction(25.0, 50.0))
        assertEquals(1f, barFraction(80.0, 50.0))
        assertEquals(0f, barFraction(-1.0, 50.0))
        assertTrue(isLowHp(15.0, 50.0))
        assertFalse(isLowHp(15.1, 50.0))
    }

    @Test fun `toast pop keyframes`() {
        assertEquals(0f to 8f, toastPop(0f))
        assertEquals(1f to 0f, toastPop(0.1f))
        // Review 38 item 11: transform has keyframes 0/10/100 only, so it drifts up from 10 %.
        val half = toastPop(0.5f)
        assertEquals(1f, half.first)
        assertTrue("drifting up at 50 %: ${half.second}", half.second < -1f && half.second > -6f)
        val eighty = toastPop(0.8f)
        assertEquals(1f, eighty.first)
        assertTrue(eighty.second < half.second)
        assertEquals(0f to -6f, toastPop(1f))
        val mid = toastPop(0.9f)
        assertTrue(mid.first in 0.01f..0.99f && mid.second < 0f)
    }

    @Test fun `gear is stale with no export or one older than 7 days`() {
        val day = 86_400_000L
        assertTrue(gearStale(0L, 100 * day))
        assertTrue(gearStale(1L, 1L + 7 * day + 1))
        assertFalse(gearStale(1L, 1L + 7 * day))
        assertFalse(gearStale(5 * day, 6 * day))
    }

    @Test fun `shine rests for 60 percent then sweeps to 220 percent`() {
        assertEquals(-1f, shineOffset(0f))
        assertEquals(-1f, shineOffset(0.6f))
        assertEquals(2.2f, shineOffset(1f), 1e-4f)
        assertTrue(shineOffset(0.8f) in -1f..2.2f)
    }

    @Test fun `rep draft follows repAdjust, repQuick and repInput`() {
        var d = RepDraft.open(positive = true)
        assertEquals(RepDraft(1, 0), d)
        assertFalse(d.canConfirm)
        d = d.adjust(-1)
        assertEquals(0, d.value)
        d = d.adjust(1).adjust(1)
        assertEquals(2, d.value)
        assertTrue(d.canConfirm)
        assertEquals(7, d.quick(7).value)
        assertEquals(12, d.input("12abc").value)
        assertEquals(0, d.input("-4").value)
        assertEquals(0, d.input("x").value)
        var m = RepDraft.open(positive = false)
        m = m.adjust(-1).adjust(-1)
        assertEquals(-2, m.value)
        assertEquals(0, m.adjust(5).value)
        assertEquals(-4, m.input("-4").value)
        assertEquals(0, m.input("4").value)
        assertEquals(listOf("+2", "+3", "+4", "+5", "+6", "+7", "+8", "+9", "+10"), RepDraft.open(true).quickOptions.map { it.first })
        assertEquals(listOf(-2, -3, -4, -5, -6, -7, -8, -9, -10), RepDraft.open(false).quickOptions.map { it.second })
        assertEquals("−2", RepDraft.open(false).quickOptions.first().first)
    }

    @Test fun `jsParseInt matches parseInt base 10`() {
        assertEquals(12, jsParseInt("  12.7"))
        assertEquals(-3, jsParseInt("-3x"))
        assertEquals(5, jsParseInt("+5"))
        assertNull(jsParseInt(""))
        assertNull(jsParseInt("a1"))
    }

    @Test fun `tags resolve by id, then by name, unknown skipped`() {
        val tags = listOf(TagEntity("t1", "Work", "#bda8ff", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()), TagEntity("t2", "Home", "#48b677", createdAt = com.example.data.clock.PhysicalClock.nowMs(), updatedAt = com.example.data.clock.PhysicalClock.nowMs()))
        assertEquals(listOf("t2", "t1"), resolveTags(listOf("t2", "nope", "work"), tags).map { it.id })
        assertEquals(Color(0xFFBDA8FF), tagColor("#bda8ff"))
        assertEquals(Color(0xFFFFAA00), tagColor("#fa0"))
        assertNull(tagColor("var(--x)"))
        assertNull(tagColor(null))
    }

    @Test fun `nav items are Opti's`() {
        assertEquals("🔁" to "Habits", navItem("Habits"))
        assertEquals("✅" to "To-Dos", navItem("Todos"))
        assertEquals("🏪" to "Rewards", navItem("Rewards"))
        assertEquals("📊" to "Analytics", navItem("Analytics"))
        assertEquals("📋" to "Activity", navItem("Activity"))
    }
}
