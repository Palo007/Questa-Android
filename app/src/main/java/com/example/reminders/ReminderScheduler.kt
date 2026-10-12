package com.example.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import java.util.TimeZone

/** The AlarmManager calls the scheduler makes, keyed by reminder item key. Tests pass a fake. */
interface AlarmApi {
    fun canScheduleExactAlarms(): Boolean
    fun setExactAndAllowWhileIdle(key: String, triggerAtMs: Long)
    fun setAndAllowWhileIdle(key: String, triggerAtMs: Long)
    fun setExact(key: String, triggerAtMs: Long)
    fun set(key: String, triggerAtMs: Long)
    fun cancel(key: String)
}

/** The set of armed item keys. Local only, never synced (D10). */
interface ArmedKeyStore {
    fun read(): Set<String>
    fun write(keys: Set<String>)
    /** WP-8.22-B: when [ReminderScheduler.rearm] last re-derived the alarms (Settings "Last checked"). */
    fun markChecked(atMs: Long) {}
    fun lastCheckedAt(): Long = 0L
}

/**
 * WP-8.11 (PX-73): port of Bubblewrap `quicklog/ReminderScheduler.kt`. [rearm] re-derives every
 * alarm from the items: stale keys are cancelled, each item gets its next slot
 * ([ReminderEngine.nextFireMs]) or is cancelled when it has none.
 *
 * Tiers (minSdk 24): API 31+ with exact permission -> setExactAndAllowWhileIdle; API 31+ without
 * -> setAndAllowWhileIdle; API 23-30 -> setExactAndAllowWhileIdle; older -> setExact. A
 * SecurityException (permission revoked mid-flight) falls back to set.
 */
class ReminderScheduler(
    private val alarms: AlarmApi,
    private val store: ArmedKeyStore,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val clock: ReminderClock = SystemReminderClock,
    private val tz: () -> TimeZone = { TimeZone.getDefault() }
) {
    constructor(ctx: Context, am: AlarmManager) :
        this(AndroidAlarmApi(ctx.applicationContext, am), PrefsArmedKeyStore(ctx.applicationContext))

    @Synchronized
    fun rearm(items: List<ReminderItem>, nowMs: Long = clock.nowMs()) {
        val zone = tz()
        val before = store.read()
        val desired = items.map { it.key }.toSet()
        for (stale in before - desired) alarms.cancel(stale)
        val after = LinkedHashSet<String>()
        for (item in items) {
            val fireAt = ReminderEngine.nextFireMs(item, nowMs, zone)
            if (fireAt == null) {
                alarms.cancel(item.key)
                continue
            }
            schedule(item.key, fireAt)
            after.add(item.key)
        }
        store.write(after)
        store.markChecked(nowMs)
    }

    fun armedCount(): Int = store.read().size

    fun lastCheckedAt(): Long = store.lastCheckedAt()

    private fun schedule(key: String, fireAt: Long) {
        try {
            when {
                sdkInt >= 31 && alarms.canScheduleExactAlarms() -> alarms.setExactAndAllowWhileIdle(key, fireAt)
                sdkInt >= 31 -> alarms.setAndAllowWhileIdle(key, fireAt)
                sdkInt >= 23 -> alarms.setExactAndAllowWhileIdle(key, fireAt)
                else -> alarms.setExact(key, fireAt)
            }
        } catch (e: SecurityException) {
            alarms.set(key, fireAt)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.reminders.ACTION_REMINDER_FIRE"
        const val EXTRA_KEY = "key"
    }
}

/** The real [AlarmApi]: one broadcast PendingIntent per key (request code = key.hashCode()). */
class AndroidAlarmApi(private val ctx: Context, private val am: AlarmManager) : AlarmApi {
    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    override fun setExactAndAllowWhileIdle(key: String, triggerAtMs: Long) =
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(key))

    override fun setAndAllowWhileIdle(key: String, triggerAtMs: Long) =
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(key))

    override fun setExact(key: String, triggerAtMs: Long) =
        am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(key))

    override fun set(key: String, triggerAtMs: Long) =
        am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent(key))

    override fun cancel(key: String) = am.cancel(pendingIntent(key))

    private fun pendingIntent(key: String): PendingIntent {
        val intent = Intent(ReminderScheduler.ACTION_FIRE)
            .setClass(ctx, ReminderReceiver::class.java)
            .putExtra(ReminderScheduler.EXTRA_KEY, key)
        return PendingIntent.getBroadcast(
            ctx, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Armed keys in a private SharedPreferences file, one string-set entry. */
class PrefsArmedKeyStore(ctx: Context) : ArmedKeyStore {
    private val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    override fun write(keys: Set<String>) {
        prefs.edit().putStringSet(KEY, HashSet(keys)).apply()
    }

    override fun markChecked(atMs: Long) {
        prefs.edit().putLong(KEY_CHECKED, atMs).apply()
    }

    override fun lastCheckedAt(): Long = prefs.getLong(KEY_CHECKED, 0L)

    private companion object {
        const val PREFS = "questa_reminders"
        const val KEY = "armed_keys"
        const val KEY_CHECKED = "last_checked_at"
    }
}
