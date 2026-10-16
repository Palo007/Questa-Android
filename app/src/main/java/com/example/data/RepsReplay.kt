package com.example.data

import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint

/**
 * CR-KT-023: replay the `habitReps` event rows the pre-CR-KT-023 `addHabitReps` wrote WITHOUT a
 * history point into the per-task `history`, so Analytics' reps metric (which reads only history
 * points) sees them. Pure; [RpgRepository.backfillRepsHistory] runs it once per install.
 *
 * The spec, as the golden fixture (`fixtures/cr_kt_023/reps_history.json`, generated against the
 * real Opti 3581f49 `addReps`/`logHistory`) pins it -- the result equals the history the PWA's
 * `addReps` would have written had the fix been in place:
 *
 *  1. Rows: only [isLocal] rows (this device's own, never imported, not synthetic) up to the
 *     pinned boundary id; pulled (other `dev`) and imported rows came from the PWA, which already
 *     wrote the point -- replaying them would double count. Order `timestamp ASC, id ASC`.
 *  2. Each row is the `addReps` patch `{value: row.value, reps: n, repCounted: true,
 *     scored: false}` with `n` signed (`direction < 0` -> `-|reps|`), dated `row.timestamp`.
 *  3. A UTC day that already has a point (the LAST one of that day): `logHistory`'s merge --
 *     `reps` adds, `repCounted` ORs to true, `scored` ORs (`last.scored || false`). `date`/`value`
 *     move to the row only when the row is not older than the point (`logHistory` sets them from
 *     the latest event of the day; a later tap that merged into the point keeps its own).
 *  4. No point on that day: insert `{date, value, reps, repCounted:true, scored:false}` in date
 *     order (after equal dates, as `logHistory`'s stable re-sort).
 *  5. Add-only: no point is removed; `cUp`/`cDown`/`value`/`updatedAt`/`_gr`/done/streak of the
 *     task are never touched (the bulk sheet already bumped the counters).
 */
/**
 * CR-KT-023: the one-shot state of the bulk-reps replay. Implemented by
 * [com.example.data.sync.SyncDataStore] (the `questa_sync_preferences` file, which Android backup
 * restores TOGETHER with the Room data: a restored install that already replayed sees the flag
 * and never replays again -- reps are additive, a second replay would double count).
 *
 * [pinBoundary] fixes, ONCE, the highest `event_logs.id` the pre-fix `addHabitReps` can have
 * written: whoever comes first pins it -- the replay or the fixed `RpgRepository.addHabitReps`
 * (`MAX(id)` BEFORE its own insert) -- so a fixed row is never replayed.
 */
interface RepsReplayStore {
    /** Stores [candidate] if no boundary is stored yet; returns the stored boundary. */
    suspend fun pinBoundary(candidate: Long): Long

    /**
     * Atomic test-and-set (one read-modify-write): if the replay is already done, null; else mark
     * it done, pin the boundary ([candidate] if none yet) and return it. Exactly one caller wins.
     */
    suspend fun claimReplay(candidate: Long): Long?

    /** Un-marks the done flag after a failed replay write (the boundary stays pinned). */
    suspend fun releaseReplay()
}

object RepsReplay {

    /** Rows of THIS device's buggy `addHabitReps`, excluding pulled/imported/synthetic rows. */
    fun isLocal(e: EventLogEntity, myDev: String, boundaryId: Long): Boolean =
        e.kind == "habitReps" && e.dev == myDev && e.imported != true && !e.synthetic && e.id <= boundaryId

    /** `addReps`' signed `n` from the Kotlin row (reps = |n|, direction = sign). */
    fun signedReps(e: EventLogEntity): Int {
        val r = kotlin.math.abs(e.reps ?: 0)
        return if ((e.direction ?: 0) < 0) -r else r
    }

    /** The repaired `history` for [task] given its replayable rows in DAO order. */
    fun replay(task: TaskEntity, rows: List<EventLogEntity>): List<TaskHistoryPoint> {
        val history = task.history.toMutableList()
        for (e in rows) {
            val n = signedReps(e)
            val ts = e.timestamp
            val day = utcDay(ts)
            val idx = history.indexOfLast { utcDay(it.date) == day }
            if (idx >= 0) {
                val p = history[idx]
                val later = ts >= p.date
                history[idx] = p.copy(
                    date = if (later) ts else p.date,
                    value = if (later) (e.value ?: p.value) else p.value,
                    reps = (p.reps ?: 0) + n,
                    repCounted = true,
                    scored = p.scored == true,
                )
            } else {
                val point = TaskHistoryPoint(date = ts, value = e.value, reps = n, repCounted = true, scored = false)
                var j = history.size
                while (j > 0 && history[j - 1].date > ts) j--
                history.add(j, point)
            }
        }
        return history
    }

    /**
     * JS `Math.floor(ms / 86400000)`, Opti `logHistory`'s day key.
     * [DEFERRED_OPEN_QUESTION] the merge key is the UTC day (parity-equal with the PWA
     * `logHistory`), while Analytics buckets and the snapshot merge use the LOCAL day: outside UTC a
     * row near midnight can merge into a point of the neighbouring local day. Kept as the PWA does.
     */
    private fun utcDay(ms: Long): Long = Math.floorDiv(ms, 86_400_000L)
}
