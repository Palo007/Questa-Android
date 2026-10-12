package com.example.data

import com.example.data.boot.BootGate
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * WP-8.6 (PX-58 apply) -- one habit tap from the launcher / inbox. Port of Opti `applyInboxLog`
 * (app.js:2436-2459). [ts] is the PHYSICAL wall-clock time of the tap (D23,
 * clock-ok:tap-physical at the producer); the event uid is `"inbox-$id"`.
 */
@Serializable
data class TapRecord(
    val id: String,
    val habitId: String,
    val dir: Int,
    val ts: Long,
    val tzOffsetMin: Int,
    val src: String,
) {
    val uid: String get() = "inbox-$id"
}

/** Opti's 5 outcomes ('applied' | 'eventOnly' | 'unknown' | 'gated' | 'error') plus the consumer's duplicate check. */
enum class TapResult { APPLIED, EVENT_ONLY, UNKNOWN, GATED, ERROR, ALREADY_APPLIED }

/** app.js:2422 `quickLogTargetOk`. */
internal fun quickLogTargetOk(t: TaskEntity?, dir: Int): Boolean = t != null && t.type == "habit" && (dir == 1 || dir == -1)

/** app.js:3434 `localDayKey`: the local calendar day of [ms]. */
internal fun localDayKey(ms: Long, zone: ZoneId): java.time.LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/**
 * Applies [rec] once. The duplicate check (Opti's consumer, `evtHasUid`, app.js:1038) and the
 * writes run in ONE Room transaction (m14), so two concurrent applies of the same tap score once.
 * [nowMs] is PHYSICAL (NearMidnightTapTest: an HLC ahead of the wall clock must not move a tap
 * made at 23:59:30 to the next day). [zone] is the device zone.
 *
 * Order: ALREADY_APPLIED, UNKNOWN (`!quickLogTargetOk`), GATED (`BootGate.blocksInput()`), then
 * same local day -> score (APPLIED, or ERROR when the counters did not rise), else a history
 * event only (EVENT_ONLY: `late:true, scored:false`, counters unchanged).
 *
 * ERROR rolls the transaction back (review 26 minor a), so no `inbox-<id>` event is stored and
 * the retry really retries: Opti's consumer defers an 'error' to the next round (sync.js:650),
 * and [LocalTapQueue] retries it and emits `tapError` after its last attempt. Keeping the event
 * would turn the retry into ALREADY_APPLIED and drop the tap silently.
 *
 * [DEFERRED_OPEN_QUESTION] Opti's `logHistory(t, …, atMs)` backdating and `reps` are not ported:
 * Kotlin's `RpgEngine.scoreHabitUp/Down` (WP-8.10's file) writes no task history point.
 */
suspend fun RpgRepository.applyHabitTap(
    rec: TapRecord,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    gateBlocks: () -> Boolean = { BootGate.blocksInput() },
): TapResult = applyHabitTapWith(rec, nowMs, zone, gateBlocks) { t, d, u, ts, s, dv -> scoreHabitTapped(t, d, u, ts, s, dv) }

/** Thrown inside the tap transaction to roll it back when the counters did not rise. */
private class CountersDidNotRise : RuntimeException()

/** [applyHabitTap] with the scoring step as a test seam. */
internal suspend fun RpgRepository.applyHabitTapWith(
    rec: TapRecord,
    nowMs: Long,
    zone: ZoneId,
    gateBlocks: () -> Boolean,
    scoreTap: suspend (TaskEntity, Int, String, Long, String, String) -> TaskEntity,
): TapResult = try {
    val deviceId = deviceIdForTaps()
    inTransaction {
        if (rec.uid in getUidsInRangeSync(rec.ts, rec.ts)) return@inTransaction TapResult.ALREADY_APPLIED
        val dir = when (rec.dir) { 1 -> 1; -1 -> -1; else -> 0 }
        val t = getTaskByIdSync(rec.habitId)
        if (!quickLogTargetOk(t, dir)) return@inTransaction TapResult.UNKNOWN
        val gated = try { gateBlocks() } catch (e: Exception) { true }
        if (gated) return@inTransaction TapResult.GATED
        t!!
        if (localDayKey(rec.ts, zone) == localDayKey(nowMs, zone)) {
            val before = t.cUp + t.cDown
            val after = scoreTap(t, dir, rec.uid, rec.ts, rec.src, deviceId)
            if (after.cUp + after.cDown <= before) throw CountersDidNotRise() // rollback -> ERROR
            TapResult.APPLIED
        } else {
            insertLog(EventLogEntity(
                timestamp = rec.ts,
                kind = "habitTap",
                taskType = "habit",
                taskId = t.id,
                taskTitle = t.title,
                direction = dir,
                uid = rec.uid,
                dev = deviceId,
                payloadJson = buildJsonObject {
                    put("late", JsonPrimitive(true))
                    put("scored", JsonPrimitive(false))
                    put("src", JsonPrimitive(rec.src))
                }.toString()
            ))
            TapResult.EVENT_ONLY
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    TapResult.ERROR
}
