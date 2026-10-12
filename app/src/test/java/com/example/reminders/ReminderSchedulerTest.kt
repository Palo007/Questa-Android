package com.example.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** PX-73: AlarmManager tiers per SDK (§4.5: SDK 24/30/31/34, one call per item), stale cancel. */
class ReminderSchedulerTest {

    private val items = listOf(
        ReminderItem("a#0", "a", "habit", "A", "", "13:00", null, null),
        ReminderItem("b#0", "b", "todo", "B", "", "14:00", "2026-09-24", null)
    )

    private fun run(sdk: Int, exact: Boolean): FakeAlarmApi {
        val api = FakeAlarmApi(exactAllowed = exact)
        ReminderScheduler(api, MemoryArmedKeyStore(), sdkInt = sdk, clock = { FIXED_NOW_MS }, tz = { UTC }).rearm(items)
        return api
    }

    private fun onlyMethod(api: FakeAlarmApi, method: String) {
        assertEquals(listOf(method to "a#0", method to "b#0"), api.calls)
    }

    @Test fun `sdk 24 uses setExactAndAllowWhileIdle`() = onlyMethod(run(24, exact = false), "setExactAndAllowWhileIdle")
    @Test fun `sdk 30 uses setExactAndAllowWhileIdle`() = onlyMethod(run(30, exact = false), "setExactAndAllowWhileIdle")
    @Test fun `sdk 31 without exact permission uses setAndAllowWhileIdle`() = onlyMethod(run(31, exact = false), "setAndAllowWhileIdle")
    @Test fun `sdk 31 with exact permission uses setExactAndAllowWhileIdle`() = onlyMethod(run(31, exact = true), "setExactAndAllowWhileIdle")
    @Test fun `sdk 34 with exact permission uses setExactAndAllowWhileIdle`() = onlyMethod(run(34, exact = true), "setExactAndAllowWhileIdle")
    @Test fun `sdk 34 without exact permission uses setAndAllowWhileIdle`() = onlyMethod(run(34, exact = false), "setAndAllowWhileIdle")

    @Test
    fun `security exception falls back to set`() {
        val api = FakeAlarmApi(exactAllowed = true, throwOnExact = true)
        ReminderScheduler(api, MemoryArmedKeyStore(), sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { UTC }).rearm(items)
        onlyMethod(api, "set")
    }

    @Test
    fun `fire times are the next local slots`() {
        val api = run(34, exact = true)
        assertEquals(utcMs(2026, 9, 24, 13, 0), api.armed["a#0"])
        assertEquals(utcMs(2026, 9, 24, 14, 0), api.armed["b#0"])
    }

    @Test
    fun `stale keys and slotless items are cancelled and not counted`() {
        val api = FakeAlarmApi()
        val store = MemoryArmedKeyStore()
        val s = ReminderScheduler(api, store, sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { UTC })
        s.rearm(items)
        assertEquals(2, s.armedCount())
        api.calls.clear()
        val pastOnce = items[1].copy(time = "11:00")
        s.rearm(listOf(pastOnce))
        assertEquals(listOf("cancel" to "a#0", "cancel" to "b#0"), api.calls)
        assertEquals(0, s.armedCount())
        assertFalse(api.armed.containsKey("b#0"))
    }

    /** WP-8.22-B: "Last checked" (Bubblewrap SettingsActivity.kt:146) = the last full re-derive. */
    @Test
    fun `rearm records the check time, also when nothing is armed`() {
        val store = MemoryArmedKeyStore()
        val s = ReminderScheduler(FakeAlarmApi(), store, sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { UTC })
        assertEquals(0L, s.lastCheckedAt())
        s.rearm(items)
        assertEquals(FIXED_NOW_MS, s.lastCheckedAt())
        s.rearm(emptyList(), nowMs = FIXED_NOW_MS + 60_000L)
        assertEquals(FIXED_NOW_MS + 60_000L, store.checkedAt)
        assertEquals(0, s.armedCount())
    }

    @Test
    fun `timezone change re-arms at the new local time`() {
        val api = FakeAlarmApi()
        var zone = UTC
        val s = ReminderScheduler(api, MemoryArmedKeyStore(), sdkInt = 34, clock = { FIXED_NOW_MS }, tz = { zone })
        s.rearm(items.take(1))
        zone = java.util.TimeZone.getTimeZone("Europe/Bratislava") // UTC+2 in September: 14:00 local now
        s.rearm(items.take(1))
        assertEquals("13:00 Bratislava tomorrow = 11:00Z", utcMs(2026, 9, 25, 11, 0), api.armed["a#0"])
    }
}
