package com.example.data.sync.merge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/**
 * WP-8.4 / WP-8.5 -- merge-round inputs shared by every primitive and by the merge core.
 *
 * @property localDev sync.js `syncDeviceId()`. Production passes it as merge()'s
 *   `localDeviceId` too (sync.js:2453, 2537), so the core uses it for both.
 * @property futureCeil sync.js `_futureCeil()` (:1217): the PHYSICAL `Date.now() +
 *   MAX_FUTURE_SKEW_MS`, fixed ONCE per round (`_skewDiagReset`, :1287). Never an HLC value.
 * @property nowPhysical the physical `Date.now()` of the round (tombstone GC, category
 *   merge-now-physical).
 * @property localToday `dayStampOf(Date.now())` in the device's local zone (local YYYYMMDD,
 *   the WP-8.2 `DayStamp`, which is `Int`).
 * @property localSavedAt WP-8.5: sync.js `S.__savedAt || null` (:2437), GUARD 3's operand
 *   (:1381). Null when never saved (0 maps to null, as `|| null` does).
 * @property zone WP-8.5: the zone `dayStampOf` reads (JS `new Date(ms).getDate()` is local).
 * @property throttle WP-8.5: `_conflictLogThrottle` (:2353). Opti clears it once per
 *   syncNow() round (:2368), so a conflict retry inside the same round SHARES it: pass the
 *   same instance to every merge of one round.
 */
data class MergeContext(
    val localDev: String,
    /** sync.js `remote.deviceId || null` (:2453, :2537): the RAW remote id, null when the remote wrapper has none. */
    val remoteDev: String?,
    val futureCeil: Long,
    val nowPhysical: Long,
    val localToday: Int,
    val localSavedAt: Long? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val throttle: ConflictThrottle = ConflictThrottle(),
) {
    companion object {
        /**
         * The context of one merge() call, derived from ONE physical-clock read taken by the
         * caller (D18: the merge core itself never reads a clock).
         */
        fun forRound(
            localDev: String,
            remoteDev: String?,
            nowPhysical: Long,
            localSavedAt: Long?,
            zone: ZoneId = ZoneId.systemDefault(),
            throttle: ConflictThrottle = ConflictThrottle(),
        ): MergeContext = MergeContext(
            localDev = localDev,
            remoteDev = remoteDev?.takeIf { it.isNotEmpty() },
            futureCeil = nowPhysical + ClampPolicy.MAX_FUTURE_SKEW_MS,
            nowPhysical = nowPhysical,
            localToday = OptiSnapshotMerger.dayStampOf(nowPhysical, zone),
            localSavedAt = localSavedAt?.takeIf { it != 0L },
            zone = zone,
            throttle = throttle,
        )
    }
}

/** sync.js `_conflictLogThrottle` (:2353): one conflictResolved per `'task:'+id` / `'char:'+id` per round. */
class ConflictThrottle {
    private val seen = HashSet<String>()

    /** `!_conflictLogThrottle.has(k)` followed by `.add(k)`. */
    fun firstTime(key: String): Boolean = synchronized(seen) { seen.add(key) }
}

/**
 * One `logEvent({...})` payload emitted by the merge core, field for field (sync.js :1007,
 * :1011, :1523, :2277, :2301, :2316). A null property is JS `undefined` (the key is absent
 * from the event); `JsonNull` is a JS `null` that IS written (winnerDev/loserDev).
 *
 * - `conflictResolved` (task): taskType, taskId, taskTitle, winner, loser, winnerDev, loserDev, reason.
 * - `conflictResolved` (char): taskType "char", winner, loser, winnerDev, loserDev, reason, charId, charTitle, day.
 * - `missReverted`: taskType "daily", taskId, taskTitle, day.
 */
data class ConflictEvent(
    val kind: String,
    val taskType: JsonElement? = null,
    val taskId: JsonElement? = null,
    val taskTitle: JsonElement? = null,
    val winner: String? = null,
    val loser: String? = null,
    val winnerDev: JsonElement? = null,
    val loserDev: JsonElement? = null,
    val reason: String? = null,
    val charId: JsonElement? = null,
    val charTitle: JsonElement? = null,
    val day: JsonElement? = null,
) {
    /** The event object exactly as Opti's literal builds it (absent keys omitted). */
    fun toJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["kind"] = JsonPrimitive(kind)
        taskType?.let { m["taskType"] = it }
        taskId?.let { m["taskId"] = it }
        taskTitle?.let { m["taskTitle"] = it }
        winner?.let { m["winner"] = JsonPrimitive(it) }
        loser?.let { m["loser"] = JsonPrimitive(it) }
        winnerDev?.let { m["winnerDev"] = it }
        loserDev?.let { m["loserDev"] = it }
        reason?.let { m["reason"] = JsonPrimitive(it) }
        charId?.let { m["charId"] = it }
        charTitle?.let { m["charTitle"] = it }
        day?.let { m["day"] = it }
        return JsonObject(m)
    }

    companion object {
        const val CONFLICT_RESOLVED = "conflictResolved"
        const val MISS_REVERTED = "missReverted"
        const val REASON_RECENCY = "updatedAt recency"
        const val REASON_DEVICE_ID = "deviceId tiebreak"
        const val REASON_UNRESOLVED = "unresolved tie, local kept"
    }
}

fun interface ConflictSink { fun emit(e: ConflictEvent) }

fun interface SnapshotMerger {
    fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, ctx: MergeContext, sink: ConflictSink): JsonObject
}

/** A JS TypeError that Opti's merge() would throw on malformed input (it aborts the round). */
class OptiMergeTypeError(message: String) : RuntimeException(message)

/**
 * Which snapshot an arbitration picked.
 *
 * [BOTH_CHANGED] is returned only by [CharTiebreak.oneSided]: both sides are real characters
 * edited since the base, so the caller must go on to [CharTiebreak.bothChanged].
 */
enum class Side { BASE, LOCAL, REMOTE, BOTH_CHANGED }

/**
 * sync.js `_charAccumulate`'s return value (:1832): the accumulating fields that
 * [CharAccumulate.overlay] lays over the arbitration winner.
 *
 * @property abs the rebuilt `char.abs` map, `abs[deviceId] = {ua, xp, gold, mp}`.
 */
data class AccResult(
    val lvl: Int,
    val xp: Double,
    val gold: Double,
    val mp: Double,
    val abs: JsonObject,
)
