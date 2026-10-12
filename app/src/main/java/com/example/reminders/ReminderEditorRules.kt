package com.example.reminders

import com.example.data.local.entities.ReminderSpec
import java.util.TimeZone
import java.util.UUID

/**
 * WP-8.11: the task-sheet reminder editor rules, ported from Opti (7d5d4f3):
 *  - [draftFrom]: sheet open, app.js:5863-5873 (`_reminderEnabled` / `_tempReminder*`);
 *  - [onSave]: sheet save, app.js:6058-6083 (burn rule :6068-6075, kind branches :6076-6082);
 *  - [save]: app.js:6058-6086, the whole `EDIT.reminders = [r]` / `[]` step.
 *
 * The editor only ever edits `reminders[0]`, as Opti does; saving replaces the list with `[r]`.
 */
object ReminderEditorRules {

    /** The sheet's temporary reminder state (Opti's `EDIT._reminderEnabled` / `_tempReminder*`). */
    data class Draft(
        val enabled: Boolean,
        val time: String,
        val date: String,
        val days: List<Boolean>
    )

    /** app.js:6059: todo -> once, daily -> daily, anything else -> weekly. */
    fun kindFor(type: String): String = when (type) {
        "todo" -> "once"
        "daily" -> "daily"
        else -> "weekly"
    }

    /** app.js:5863-5873. `hasRem` is `reminders[0].enabled`; a disabled one opens with defaults. */
    fun draftFrom(reminders: List<ReminderSpec>?, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Draft {
        val today = LocalNow.of(nowMs, tz).dateDash
        val first = reminders?.firstOrNull()
        return if (first != null && first.enabled) {
            Draft(
                enabled = true,
                // Opti copies r.time as is; the save step's `|| '09:00'` covers a missing one.
                time = first.time ?: "",
                date = first.date?.ifEmpty { null } ?: today,
                days = first.days ?: ReminderEngine.allDays()
            )
        } else {
            Draft(enabled = false, time = "09:00", date = today, days = ReminderEngine.allDays())
        }
    }

    /**
     * app.js:6060-6082. `edited` carries the kind and the temp values: `time`, `date` (once),
     * `days` (the temp days for weekly, the task's `repeat` for daily).
     *  - id: `prev ? prev.id : uid()`;
     *  - lastFiredKey: new reminder, or a changed time -> today's fire key when `time < now HH:MM`,
     *    else ""; an unchanged time keeps `prev.lastFiredKey`;
     *  - once -> `date = edited.date || today`; weekly/daily -> `days = edited.days || 7 x true`.
     * The result is a fresh object: unknown keys of `prev` are not carried (Opti builds `r` anew).
     */
    fun onSave(
        prev: ReminderSpec?,
        edited: ReminderSpec,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
        newId: () -> String = { UUID.randomUUID().toString() }
    ): ReminderSpec {
        val time = edited.time?.ifEmpty { null } ?: "09:00"
        var r = ReminderSpec(
            id = if (prev != null) prev.id else newId(),
            enabled = true,
            kind = edited.kind,
            time = time,
            lastFiredKey = prev?.lastFiredKey ?: ""
        )
        if (prev == null || prev.time != time) {
            val nowStr = LocalNow.of(nowMs, tz).hhmm
            r = r.copy(lastFiredKey = if (time < nowStr) ReminderEngine.fireKey(r, nowMs, tz) else "")
        }
        val today = LocalNow.of(nowMs, tz).dateDash
        r = when (edited.kind) {
            "once" -> r.copy(date = edited.date?.ifEmpty { null } ?: today)
            "weekly", "daily" -> r.copy(days = edited.days ?: ReminderEngine.allDays())
            else -> r
        }
        return r
    }

    /**
     * app.js:6058-6086: the list to store on save. Disabled -> `[]`; enabled -> `[onSave(...)]`
     * with `prev = reminders[0]` (enabled or not) and, for a daily, `days = repeat`.
     */
    fun save(
        prevList: List<ReminderSpec>?,
        draft: Draft,
        type: String,
        repeat: List<Boolean>?,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
        newId: () -> String = { UUID.randomUUID().toString() }
    ): List<ReminderSpec> {
        if (!draft.enabled) return emptyList()
        val kind = kindFor(type)
        val edited = ReminderSpec(
            kind = kind,
            time = draft.time,
            date = if (kind == "once") draft.date else null,
            days = when (kind) {
                "weekly" -> draft.days
                "daily" -> repeat
                else -> null
            }
        )
        // app.js:6061 `EDIT.reminders[0] || null`: a JS-falsy first element (P4 review #6) is no prev.
        val prev = prevList?.firstOrNull()?.takeUnless { it.isFalsyOpaque }
        return listOf(onSave(prev, edited, nowMs, tz, newId))
    }
}
