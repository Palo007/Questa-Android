package com.example.reminders

import java.util.TimeZone

internal val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/** 2026-09-24T12:00:00Z, a Thursday (JS getDay 4); the G11 fixture instant. */
internal const val FIXED_NOW_MS = 1790251200000L

internal fun utcMs(y: Int, mo: Int, d: Int, h: Int, m: Int): Long {
    val c = java.util.Calendar.getInstance(UTC)
    c.clear()
    c.set(y, mo - 1, d, h, m, 0)
    return c.timeInMillis
}

internal class FakeAlarmApi(var exactAllowed: Boolean = true, var throwOnExact: Boolean = false) : AlarmApi {
    val calls = mutableListOf<Pair<String, String>>() // method to key
    val armed = linkedMapOf<String, Long>()

    override fun canScheduleExactAlarms(): Boolean = exactAllowed

    private fun rec(method: String, key: String, t: Long) {
        calls.add(method to key)
        armed[key] = t
    }

    override fun setExactAndAllowWhileIdle(key: String, triggerAtMs: Long) {
        if (throwOnExact) throw SecurityException("no exact")
        rec("setExactAndAllowWhileIdle", key, triggerAtMs)
    }

    override fun setAndAllowWhileIdle(key: String, triggerAtMs: Long) = rec("setAndAllowWhileIdle", key, triggerAtMs)
    override fun setExact(key: String, triggerAtMs: Long) = rec("setExact", key, triggerAtMs)
    override fun set(key: String, triggerAtMs: Long) = rec("set", key, triggerAtMs)
    override fun cancel(key: String) {
        calls.add("cancel" to key)
        armed.remove(key)
    }
}

internal class MemoryArmedKeyStore : ArmedKeyStore {
    var keys: Set<String> = emptySet()
    override fun read(): Set<String> = keys
    override fun write(keys: Set<String>) {
        this.keys = keys.toSet()
    }
    var checkedAt = 0L
    override fun markChecked(atMs: Long) { checkedAt = atMs }
    override fun lastCheckedAt(): Long = checkedAt
}

internal class RecordingNotifier : ReminderNotifier {
    val posted = mutableListOf<ReminderPayload>()
    override fun post(payload: ReminderPayload) {
        posted.add(payload)
    }
}
