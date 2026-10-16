package com.example.data

import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * CR-KT-022: replay the local `event_logs` completion rows into per-task `history` points that
 * pre-1.3 never wrote (the repository logged the events but no point; see CR-KT-020). Pure and
 * add-only — [RpgRepository.backfillCompletionHistory] runs it once after the upgrade and on the
 * Diagnostics manual re-run.
 *
 * The spec, exactly as the golden fixture (`fixtures/cr_kt_022/backfill_history.json`, generated
 * against the real live PWA code at Opti 338d430) pins it:
 *
 *  1. `complete` rows pair with `uncomplete` rows LIFO, in event order (`ORDER BY timestamp ASC,
 *     id ASC` — the same-millisecond pair relies on the `id` tiebreak): an un-tick cancels its
 *     completion wherever the days fall (the live CR-PWA-004 rule: the fixed `unlogToday` retracts
 *     the last point no matter its date). A `complete` whose `payloadJson` carries `{late:true}`
 *     is skipped: `creditYesterday` wrote its own backdated point (RpgEngine.creditYesterday).
 *  2. The standing completions become one point per UTC day (the last one wins), written with the
 *     `logHistory` merge rules: daily `{date, value, completed:true, isDue:true, repeat}`; todo
 *     `{date, value, completed:true}`. `reward`/`checklist` are omitted — they were never recorded
 *     in the event log and are not fabricated.
 *  3. A UTC day that already carries a `completed:true` point is never modified (that day was
 *     recorded on the PWA — synced in — or by a 1.3 completion; this also makes re-runs
 *     idempotent). A non-completed same-day point (a cron miss) is merged in place exactly as
 *     `logHistory` would: `date`/`value`/`completed` update, every other field stays.
 *  4. Add-only: nothing is ever deleted or un-marked. `_gr`, `completedAt`, done/streak/
 *     `updatedAt` and `_histCreated` are never touched here (the caller compares lists, skips a
 *     task whose history is already correct, and stamps a fresh HLC `updatedAt` on a changed one).
 */
object HistoryBackfill {

    /** The repaired `history` for [task] given its `complete`/`uncomplete` rows in DAO order. */
    fun repair(task: TaskEntity, events: List<EventLogEntity>): List<TaskHistoryPoint> {
        // 1. LIFO pairing: an un-tick cancels the latest still-standing completion.
        val open = ArrayDeque<EventLogEntity>()
        for (e in events) {
            when (e.kind) {
                "complete" -> if (!isLate(e)) open.addLast(e)
                "uncomplete" -> if (open.isNotEmpty()) open.removeLast()
            }
        }
        // 2. One point per UTC day, the last standing completion of the day wins.
        val perDay = LinkedHashMap<Long, EventLogEntity>()
        for (e in open) perDay[utcDay(e.timestamp)] = e

        val history = task.history.toMutableList()
        val days = perDay.entries.sortedBy { it.value.timestamp }
        for ((day, e) in days) {
            val idx = history.indexOfFirst { utcDay(it.date) == day }
            if (idx >= 0) {
                if (history[idx].completed == true) continue // counted day: never modify
                history[idx] = history[idx].copy(date = e.timestamp, value = e.value, completed = true)
            } else {
                val p = if (task.type == "daily") {
                    TaskHistoryPoint(
                        date = e.timestamp,
                        value = e.value,
                        completed = true,
                        isDue = true,
                        repeat = task.repeat ?: emptyList(),
                    )
                } else {
                    TaskHistoryPoint(date = e.timestamp, value = e.value, completed = true)
                }
                // logHistory's out-of-order branch: keep the list ascending by date.
                var j = history.size
                while (j > 0 && history[j - 1].date > p.date) j--
                history.add(j, p)
            }
        }
        return history
    }

    /** JS `Math.floor(ms / 86400000)`, the fixture's day key. */
    private fun utcDay(ms: Long): Long = Math.floorDiv(ms, 86_400_000L)

    /** `!!JSON.parse(payloadJson).late`: parse failure (and null) = false, JS truthiness for the value. */
    private fun isLate(e: EventLogEntity): Boolean {
        val payload = e.payloadJson ?: return false
        val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return false
        return when (val v = obj["late"]) {
            null, is JsonNull -> false
            is JsonPrimitive -> when {
                v.isString -> v.content.isNotEmpty()          // JS: any non-empty string is truthy
                v.booleanOrNull != null -> v.booleanOrNull!!  // literal true/false
                else -> v.doubleOrNull?.let { it != 0.0 } ?: true
            }
            else -> true                                      // an object/array is truthy in JS
        }
    }
}
