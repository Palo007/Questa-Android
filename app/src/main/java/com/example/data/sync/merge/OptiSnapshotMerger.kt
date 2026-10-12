package com.example.data.sync.merge

import com.example.data.clock.DayStamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * WP-8.5 -- the merge core: sync.js `merge()` / `_mergeInner()` (:2111-2324) and the helpers
 * it calls (mergeCollection :1324, mergeChecklist :1100, mergeDevices :1598, cleanDevices
 * :1571, mergeDayArray :2028, resolveDailyConflict :997, normalizeDailyResets :1039,
 * _isCronEchoReset :1071, doneDayOf :982, dayStampOf :967), ported 1:1 at Opti 7d5d4f3.
 *
 * Built on the WP-8.4 primitives ([CharTiebreak], [CharAccumulate], [CounterAccumulate],
 * [PauseMerge], [ClampPolicy]). Values are handled as JSON with the JS coercions in [Js], so
 * malformed input behaves as it does in Opti; where Opti throws a TypeError, this throws
 * [OptiMergeTypeError] (the caller aborts the round, as `_syncNowAttempt`'s catch does).
 *
 * D18: no clock is read here. The physical "now" of the round, the future ceiling, the local
 * day and the zone all come from [MergeContext].
 *
 * Not ported, [DEFERRED_OPEN_QUESTION]: the per-round skew diagnostics (`_skewDiagNote` /
 * `_skewDiagFlush` -> `_qDiagPush`). They never change a merge result.
 *
 * Throttle: `conflictResolved` events go through [MergeContext.throttle]; `missReverted` is not
 * throttled (sync.js :1007, :1011).
 */
class OptiSnapshotMerger : SnapshotMerger {

    override fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, ctx: MergeContext, sink: ConflictSink): JsonObject {
        val b: JsonObject = base ?: EMPTY
        val baseAn = b["an"].orEmptyObj()
        val localAn = local["an"].orEmptyObj()
        val remoteAn = remote["an"].orEmptyObj()

        // K1 (:2141-2146): a remote day in THIS device's future has no authority.
        val lCron = Js.orZeroEl(local["lastCron"])
        val rCron = Js.orZeroEl(remote["lastCron"])
        val todayEl = JsonPrimitive(ctx.localToday)
        val rEff = if (Js.gt(rCron, todayEl)) lCron else rCron
        val mergedLastCron: JsonElement = if (Js.ge(lCron, rEff)) lCron else rEff

        // Tombstones (:2150-2167): union base+remote+local, newest `at` per id, 180-day GC.
        val tomb = LinkedHashMap<Any, Pair<JsonElement, Double>>()
        for (d in Js.concatArg(b["deletions"]) + Js.concatArg(remote["deletions"]) + Js.concatArg(local["deletions"])) {
            if (!Js.truthy(d)) continue
            val id = Js.get(d, "id")
            if (id == null || id is JsonNull) continue
            val at = Js.num(Js.get(d, "at"))
            val k = Js.mapKey(id)
            val prev = tomb[k]
            if (prev == null || at > prev.second) {
                // JS Map.set on an existing key keeps its insertion position AND its key.
                tomb[k] = Pair(prev?.first ?: id, at)
            }
        }
        val gcNow = ctx.nowPhysical.toDouble()
        val gcIt = tomb.values.iterator()
        while (gcIt.hasNext()) if (gcNow - gcIt.next().second > TOMBSTONE_MAX_AGE_MS) gcIt.remove()
        val tombAt: Map<Any, Double> = tomb.mapValues { it.value.second }

        val out = LinkedHashMap<String, JsonElement>()
        out["tasks"] = normalizeDailyResetsRaw(mergeCollection(b["tasks"], local["tasks"], remote["tasks"], tombAt, ctx, sink), mergedLastCron, ctx.zone)
        out["rewards"] = mergeCollection(b["rewards"], local["rewards"], remote["rewards"], tombAt, ctx, sink)
        out["tags"] = mergeCollection(b["tags"], local["tags"], remote["tags"], tombAt, ctx, sink)
        out["devices"] = mergeDevicesRaw(b["devices"], local["devices"], remote["devices"], ctx)
        out["an"] = JsonObject(linkedMapOf(
            "views" to mergeCollection(Js.get(baseAn, "views"), Js.get(localAn, "views"), Js.get(remoteAn, "views"), tombAt, ctx, sink),
            "metrics" to mergeCollection(Js.get(baseAn, "metrics"), Js.get(localAn, "metrics"), Js.get(remoteAn, "metrics"), tombAt, ctx, sink),
        ))
        out["pause"] = PauseMerge.merge(b["pause"] as? JsonObject, local["pause"] as? JsonObject, remote["pause"] as? JsonObject, ctx.futureCeil)
            ?: JsonObject(emptyMap())
        out["history"] = mergeDayArrayRaw(local["history"], remote["history"], ctx.zone)
        out["charHistory"] = mergeDayArrayRaw(local["charHistory"], remote["charHistory"], ctx.zone)
        out["monthlyBackups"] = monthlyBackups(local["monthlyBackups"], remote["monthlyBackups"])
        out["lastCron"] = mergedLastCron
        out["deletions"] = JsonArray(tomb.values.map { (id, at) -> JsonObject(linkedMapOf("id" to id, "at" to Js.jsonNum(at))) })
        out["char"] = mergeChar(b, local, remote, mergedLastCron, ctx, sink)
        return JsonObject(out)
    }

    // ---------------------------------------------------------------------------------------
    // Public card API (plan §5 WP-8.5)
    // ---------------------------------------------------------------------------------------

    /** sync.js `doneDayOf` (:982): `Number(doneDay) || dayStampOf(doneAt || 0)`. 0 when none. */
    fun doneDayOf(t: JsonObject?, zone: ZoneId): DayStamp = toDay(doneDayOfD(t, zone))

    /** sync.js `resolveDailyConflict(l, r, localDeviceId, remoteDeviceId)` (:997). Emits missReverted to [sink]. */
    fun resolveDailyConflict(l: JsonObject, r: JsonObject, ctx: MergeContext, sink: ConflictSink = ConflictSink { }): JsonObject =
        resolveDailyConflictRaw(l, r, ctx, sink) as JsonObject

    /** sync.js `_isCronEchoReset(l, r)` (:1071); `today` is [MergeContext.localToday]. */
    fun isCronEchoReset(side: JsonObject, other: JsonObject, ctx: MergeContext): Boolean = isCronEchoResetRaw(side, other, ctx)

    /** sync.js `mergeChecklist(baseArr, localArr, remoteArr, preferLocal)` (:1100); [splice] is `preferLocal`. */
    fun mergeChecklist(base: JsonArray?, l: JsonArray, r: JsonArray, splice: Boolean, ctx: MergeContext): JsonArray =
        mergeChecklistRaw(base, l, r, splice, ctx)

    /**
     * CR-KT-015 `mergeAttachments(base, local, remote)`: union of local + remote ids; an id in
     * [base] and missing on one side is removed; [base] null = pure union (nothing is treated as
     * removed). Items are immutable, so each one is taken from whichever side has it (local
     * first). Order: `addedAt` ascending, then `id`. Idempotent. Fixture shared/fixtures/CR-KT-015.
     */
    fun mergeAttachments(base: JsonArray?, l: JsonArray, r: JsonArray): JsonArray =
        mergeAttachmentsRaw(base, l, r)

    /** sync.js `cleanDevices(arr)` (:1571). */
    fun cleanDevices(a: JsonArray): JsonArray = cleanDevicesRaw(a)

    /** sync.js `mergeDevices(baseArr, localArr, remoteArr, localDeviceId, remoteDeviceId)` (:1598). */
    fun mergeDevices(b: JsonArray?, l: JsonArray, r: JsonArray, ctx: MergeContext): JsonArray = mergeDevicesRaw(b, l, r, ctx)

    /**
     * sync.js `mergeDayArray(localArr, remoteArr)` (:2028). The base is accepted for the card's
     * signature but, exactly as in Opti, never read.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mergeDayArray(b: JsonArray?, l: JsonArray, r: JsonArray, zone: ZoneId): JsonArray = mergeDayArrayRaw(l, r, zone)

    /** sync.js `normalizeDailyResets(tasks, mergedLastCron)` (:1039). */
    fun normalizeDailyResets(tasks: JsonArray, mergedLastCron: DayStamp, zone: ZoneId): JsonArray =
        normalizeDailyResetsRaw(tasks, JsonPrimitive(mergedLastCron), zone)

    /** sync.js mergedLastCron (:2141-2146), on day stamps. */
    fun mergedLastCron(local: DayStamp, remote: DayStamp, localToday: DayStamp): DayStamp {
        val rEff = if (remote > localToday) local else remote
        return if (local >= rEff) local else rEff
    }

    // ---------------------------------------------------------------------------------------
    // mergeCollection (:1324-1560)
    // ---------------------------------------------------------------------------------------

    private class Keyed(val id: JsonElement?, val value: JsonElement)

    /** `new Map((arr || []).map(x => [x.id, x]))`: later duplicates replace the value, keep the slot. */
    private fun keyedMap(arr: JsonElement?, what: String): LinkedHashMap<Any, Keyed> {
        val m = LinkedHashMap<Any, Keyed>()
        for (x in Js.arrayOrThrow(arr, what)) {
            val id = Js.getOrThrow(x, "id")
            val k = Js.mapKey(id)
            m[k] = Keyed(m[k]?.id ?: id, x)
        }
        return m
    }

    internal fun mergeCollection(
        baseArr: JsonElement?,
        localArr: JsonElement?,
        remoteArr: JsonElement?,
        tomb: Map<Any, Double>?,
        ctx: MergeContext,
        sink: ConflictSink,
    ): JsonArray {
        val localDev = ctx.localDev
        val remoteDev = ctx.remoteDev
        val baseMap = keyedMap(baseArr, "base")
        val localMap = keyedMap(localArr, "local")
        val remoteMap = keyedMap(remoteArr, "remote")
        val ids = LinkedHashMap<Any, JsonElement?>()
        for (m in listOf(baseMap, localMap, remoteMap)) for ((k, v) in m) if (k !in ids) ids[k] = v.id
        val result = LinkedHashMap<Any, JsonElement>()

        for ((key, idEl) in ids) {
            val b = baseMap[key]?.value
            val localHad = key in localMap
            val remoteHad = key in remoteMap
            val l = localMap[key]?.value
            val r = remoteMap[key]?.value
            val localChanged = !Js.deepEqual(l, b)
            val remoteChanged = !Js.deepEqual(r, b)

            if (!localChanged && !remoteChanged) {
                if (Js.truthy(b)) result[key] = b!!
                continue
            }
            if (localChanged && !remoteChanged) {
                // GUARD 3 (:1381-1383): RAW operand (inverted polarity).
                val savedAt = ctx.localSavedAt
                if (Js.truthy(b) && localHad && savedAt != null) {
                    if (ClampPolicy.uaRawD(b) >= savedAt.toDouble()) { result[key] = b!!; continue }
                }
                if (localHad) result[key] = l!!
                else if (Js.truthy(b)) result[key] = b!! // TOMBSTONE MODEL: local absence is not a deletion
                continue
            }
            if (!localChanged && remoteChanged) {
                if (remoteHad) {
                    // GUARD 2 (K5): the cross-TZ cron echo keeps local wholesale.
                    if (localHad && isCronEchoResetRaw(l, r, ctx)) { result[key] = l!!; continue }
                    // GUARD 1: RAW operands (inverted polarity).
                    if (localHad && ClampPolicy.uaRawD(l) > ClampPolicy.uaRawD(r)) {
                        var w: JsonElement = l!!
                        val lc = Js.get(l, "checklist"); val rc = Js.get(r, "checklist")
                        if (lc is JsonArray || rc is JsonArray) {
                            // NO base (2026-09-18 round 2): GUARD 1 has declared the base unreliable.
                            w = Js.assign(l, "checklist" to mergeChecklistRaw(null, Js.orEmptyArr(lc), Js.orEmptyArr(rc), true, ctx))
                        }
                        // CR-KT-015: same as the checklist, base = null here (union; a remove may come back).
                        w = spliceAttachments(w, null, l, r)
                        result[key] = w
                    } else {
                        result[key] = r!!
                    }
                } else {
                    if (localHad) result[key] = l!! // TOMBSTONE MODEL: remote absence is not a deletion
                }
                continue
            }
            // both changed relative to base
            if (localHad && !remoteHad) { result[key] = l!!; continue }
            if (!localHad && remoteHad) { result[key] = r!!; continue }
            if (!localHad && !remoteHad) { if (Js.truthy(b)) result[key] = b!!; continue }

            var winner: JsonElement
            if (isDaily(l) || isDaily(r)) {
                winner = resolveDailyConflictRaw(l!!, r!!, ctx, sink)
            } else {
                val luc = ClampPolicy.clampFutureD(Js.orZeroEl(Js.get(l, "updatedAt")), ctx.futureCeil)
                val ruc = ClampPolicy.clampFutureD(Js.orZeroEl(Js.get(r, "updatedAt")), ctx.futureCeil)
                winner = if (luc == ruc && remoteDev != null) {
                    if (localDev != remoteDev) (if (remoteDev > localDev) r!! else l!!)
                    else (if (luc > ruc) l!! else r!!)
                } else {
                    if (luc > ruc) l!! else r!!
                }
            }
            // Winner identity captured BEFORE the rebuilds below (2026-09-18 round 2).
            val winIsLocal = winner === l

            // K5 round 2: GUARD 2's echo rule inside the both-changed branch.
            val echoKeep: JsonElement? = when {
                isCronEchoResetRaw(l, r, ctx) -> l
                isCronEchoResetRaw(r, l, ctx) -> r
                else -> null
            }
            val lc = Js.get(l, "checklist"); val rc = Js.get(r, "checklist")
            if (lc is JsonArray || rc is JsonArray) {
                val cl: JsonArray = if (echoKeep != null) {
                    JsonArray(Js.arrayOrThrow(Js.get(echoKeep, "checklist"), "checklist").map { Js.assign(it) })
                } else {
                    mergeChecklistRaw(if (Js.truthy(b)) Js.get(b, "checklist") else null, Js.orEmptyArr(lc), Js.orEmptyArr(rc), winIsLocal, ctx)
                }
                winner = Js.assign(winner, "checklist" to cl)
            }
            // CR-KT-015: the attachments splice, next to the checklist splice (sync.js :1498-1504).
            winner = spliceAttachments(winner, if (Js.truthy(b)) Js.get(b, "attachments") else null, l, r)
            // K3: habit counters accumulate, AFTER the checklist splice.
            if (l is JsonObject && r is JsonObject && winner is JsonObject) {
                winner = CounterAccumulate.accumulate(b as? JsonObject, l, r, winner, localDev, remoteDev, ctx.localToday)
            }
            // T4: one conflictResolved per task id per round.
            if (ctx.throttle.firstTime("task:" + Js.jsString(idEl))) {
                val rd: JsonElement = remoteDev?.let { JsonPrimitive(it) } ?: JsonNull
                val ld: JsonElement = JsonPrimitive(localDev)
                val type = Js.get(winner, "type")
                val title = Js.get(winner, "title")
                sink.emit(ConflictEvent(
                    kind = ConflictEvent.CONFLICT_RESOLVED,
                    taskType = if (Js.truthy(type)) type else JsonPrimitive("task"),
                    taskId = idEl,
                    taskTitle = if (Js.truthy(title)) title else JsonPrimitive(""),
                    winner = if (winIsLocal) "local" else "remote",
                    loser = if (winIsLocal) "remote" else "local",
                    winnerDev = if (winIsLocal) ld else rd,
                    loserDev = if (winIsLocal) rd else ld,
                    reason = ConflictEvent.REASON_RECENCY,
                ))
            }
            result[key] = winner
        }

        // TOMBSTONE OVERLAY (:1534-1550): RAW on both operands.
        if (!tomb.isNullOrEmpty()) {
            val it = result.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val ts = tomb[e.key] ?: continue
                if (ts >= ClampPolicy.uaRawD(e.value)) it.remove()
            }
        }
        return JsonArray(orderLocalRemoteRest(localArr, remoteArr, result))
    }

    /** Local order, then remote-only, then the rest (:1554-1558, :1648-1652). */
    private fun orderLocalRemoteRest(localArr: JsonElement?, remoteArr: JsonElement?, result: LinkedHashMap<Any, JsonElement>): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        val placed = HashSet<Any>()
        for (arr in listOf(localArr, remoteArr)) {
            for (x in Js.arrayOrThrow(arr, "collection")) {
                val k = Js.mapKey(Js.getOrThrow(x, "id"))
                val v = result[k]
                if (v != null && placed.add(k)) out += v
            }
        }
        for ((k, v) in result) if (placed.add(k)) out += v
        return out
    }

    private fun isDaily(x: JsonElement?): Boolean = Js.truthy(x) && Js.strictEq(Js.get(x, "type"), DAILY)

    // ---------------------------------------------------------------------------------------
    // Daily helpers (:967-1086)
    // ---------------------------------------------------------------------------------------

    private fun doneDayOfD(x: JsonElement?, zone: ZoneId): Double {
        if (!Js.truthy(x)) return 0.0
        val d = Js.num(Js.get(x, "doneDay"))
        return if (d != 0.0) d else dayStampOfD(Js.orZeroEl(Js.get(x, "doneAt")), zone)
    }

    private fun dailyEventDay(x: JsonElement?, zone: ZoneId): Double {
        if (!Js.truthy(x)) return 0.0
        return Js.max(doneDayOfD(x, zone), Js.number(Js.orZeroEl(Js.get(x, "missedOn"))))
    }

    /** `(x.missedOn || 0) === day` (strict: a string missedOn never equals a number). */
    private fun missedOnIs(x: JsonElement?, day: Double): Boolean {
        val m = Js.orZeroEl(Js.get(x, "missedOn"))
        return Js.isNumber(m) && Js.number(m) == day
    }

    private fun resolveDailyConflictRaw(l: JsonElement, r: JsonElement, ctx: MergeContext, sink: ConflictSink): JsonElement {
        val zone = ctx.zone
        val led = dailyEventDay(l, zone)
        val red = dailyEventDay(r, zone)
        if (led != red) return if (led > red) l else r // rule 1 (NaN: != true, > false -> r, as JS)
        val ledT = Js.orZero(led) != 0.0
        val redT = Js.orZero(red) != 0.0
        val lDoneToday = Js.truthy(l) && ledT && doneDayOfD(l, zone) == led
        val rDoneToday = Js.truthy(r) && redT && doneDayOfD(r, zone) == red
        val lMissToday = Js.truthy(l) && ledT && missedOnIs(l, led)
        val rMissToday = Js.truthy(r) && redT && missedOnIs(r, red)
        if (lDoneToday && rMissToday && !rDoneToday) {
            sink.emit(ConflictEvent(ConflictEvent.MISS_REVERTED, taskType = DAILY, taskId = Js.get(l, "id"), taskTitle = Js.get(l, "title"), day = Js.jsonNum(led)))
            return l
        }
        if (rDoneToday && lMissToday && !lDoneToday) {
            sink.emit(ConflictEvent(ConflictEvent.MISS_REVERTED, taskType = DAILY, taskId = Js.get(r, "id"), taskTitle = Js.get(r, "title"), day = Js.jsonNum(red)))
            return r
        }
        // rule 3: clamped updatedAt, deviceId tiebreak on an exact tie.
        val luc = ClampPolicy.clampFutureD(Js.orZeroEl(Js.get(l, "updatedAt")), ctx.futureCeil)
        val ruc = ClampPolicy.clampFutureD(Js.orZeroEl(Js.get(r, "updatedAt")), ctx.futureCeil)
        val rd = ctx.remoteDev
        if (luc == ruc && rd != null) {
            val ld = ctx.localDev
            if (ld != rd) return if (rd > ld) r else l
        }
        return if (luc > ruc) l else r
    }

    private fun normalizeDailyResetsRaw(tasks: JsonArray, mergedLastCron: JsonElement, zone: ZoneId): JsonArray {
        val cron = Js.number(Js.orZeroEl(mergedLastCron))
        return JsonArray(tasks.map { t ->
            if (!Js.truthy(t) || !Js.strictEq(Js.get(t, "type"), DAILY) || !Js.truthy(Js.get(t, "done"))) return@map t
            if (doneDayOfD(t, zone) < cron) {
                val cl = Js.get(t, "checklist")
                if (cl is JsonArray) Js.assign(t, "done" to FALSE, "checklist" to JsonArray(cl.map { c -> Js.assign(c, "done" to FALSE) }))
                else Js.assign(t, "done" to FALSE)
            } else t
        })
    }

    private fun isCronEchoResetRaw(l: JsonElement?, r: JsonElement?, ctx: MergeContext): Boolean {
        if (!Js.truthy(l) || !Js.truthy(r)) return false
        if (!Js.strictEq(Js.get(l, "type"), DAILY) && !Js.strictEq(Js.get(r, "type"), DAILY)) return false
        if (!Js.strictEq(Js.get(l, "done"), TRUE) || !Js.strictEq(Js.get(r, "done"), FALSE)) return false
        if (ClampPolicy.uaRawD(l) != ClampPolicy.uaRawD(r)) return false // RAW: a real edit bumps updatedAt
        val da = Js.num(Js.get(r, "doneAt"))
        if (da == 0.0) return false // doneAt retracted -> a real un-tick
        return doneDayOfD(r, ctx.zone) >= ctx.localToday.toDouble()
    }

    // ---------------------------------------------------------------------------------------
    // mergeChecklist (:1100-1188)
    // ---------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------
    // CR-KT-015 attachments splice
    // ---------------------------------------------------------------------------------------

    /**
     * sync.js call sites (:1466, :1550) + `_withAttachments` (:1222): when either side holds an
     * array, the winner is copied with the merged `attachments`, or with the key DELETED when the
     * merge is empty (writers never write `[]`). No side has an array -> winner unchanged.
     */
    private fun spliceAttachments(winner: JsonElement, baseArr: JsonElement?, l: JsonElement?, r: JsonElement?): JsonElement {
        val la = Js.get(l, "attachments"); val ra = Js.get(r, "attachments")
        if (la !is JsonArray && ra !is JsonArray) return winner
        val merged = mergeAttachmentsRaw(baseArr, la, ra)
        // Js.assign with a null value = `delete t.attachments`.
        return Js.assign(winner, "attachments" to merged.takeIf { it.isNotEmpty() })
    }

    private fun attachmentsSafe(arr: JsonElement?): List<JsonObject> =
        (arr as? JsonArray).orEmpty().mapNotNull { x ->
            (x as? JsonObject)?.takeIf { (it["id"] as? JsonPrimitive)?.let { p -> p.isString && p.content.isNotEmpty() } == true }
        }

    private fun attachmentId(x: JsonObject): String = (x["id"] as JsonPrimitive).content

    private fun mergeAttachmentsRaw(baseArr: JsonElement?, localArr: JsonElement?, remoteArr: JsonElement?): JsonArray {
        val baseIds: Set<String>? = if (baseArr == null || baseArr is JsonNull) null
            else attachmentsSafe(baseArr).map { attachmentId(it) }.toSet()
        val local = LinkedHashMap<String, JsonObject>(); for (x in attachmentsSafe(localArr)) local.putIfAbsent(attachmentId(x), x)
        val remote = LinkedHashMap<String, JsonObject>(); for (x in attachmentsSafe(remoteArr)) remote.putIfAbsent(attachmentId(x), x)
        val out = ArrayList<JsonObject>()
        for (id in LinkedHashSet(local.keys + remote.keys)) {
            val inL = id in local; val inR = id in remote
            if (baseIds != null && id in baseIds && !(inL && inR)) continue // removed on one side
            out.add(local[id] ?: remote.getValue(id))
        }
        out.sortWith(compareBy<JsonObject>({ Js.num(it["addedAt"]) }, { attachmentId(it) }))
        return JsonArray(out)
    }

    private fun checklistSafe(arr: JsonElement?): List<JsonElement> =
        Js.arrayOrThrow(arr, "checklist").filter { x -> Js.truthy(x) && Js.get(x, "id").let { it != null && it !is JsonNull } }

    private fun mergeChecklistRaw(baseArr: JsonElement?, localArr: JsonElement?, remoteArr: JsonElement?, preferLocal: Boolean, ctx: MergeContext): JsonArray {
        val baseMap = LinkedHashMap<Any, JsonElement>()
        for (x in checklistSafe(baseArr)) baseMap[Js.mapKey(Js.get(x, "id"))] = x
        val localSafe = checklistSafe(localArr)
        val remoteSafe = checklistSafe(remoteArr)
        val localMap = LinkedHashMap<Any, JsonElement>(); for (x in localSafe) localMap[Js.mapKey(Js.get(x, "id"))] = x
        val remoteMap = LinkedHashMap<Any, JsonElement>(); for (x in remoteSafe) remoteMap[Js.mapKey(Js.get(x, "id"))] = x
        val idEls = LinkedHashMap<Any, JsonElement?>()
        for (x in localSafe + remoteSafe) { val k = Js.mapKey(Js.get(x, "id")); if (k !in idEls) idEls[k] = Js.get(x, "id") }

        val result = LinkedHashMap<Any, JsonElement>()
        for ((key, idEl) in idEls) {
            val b = baseMap[key]
            val l = localMap[key]
            val r = remoteMap[key]
            if (l != null && r != null) {
                if (Js.deepEqual(l, r)) { result[key] = l; continue }
                val bText: JsonElement? = if (b != null) Js.get(b, "text") else null
                val bDone: Boolean? = if (b != null) Js.truthy(Js.get(b, "done")) else null
                val lText = Js.get(l, "text"); val rText = Js.get(r, "text")
                val lTextChanged = !Js.strictEq(lText, bText)
                val rTextChanged = !Js.strictEq(rText, bText)
                val lDone = Js.truthy(Js.get(l, "done")); val rDone = Js.truthy(Js.get(r, "done"))
                val lDoneChanged = lDone != bDone
                val rDoneChanged = rDone != bDone
                val lt = Js.orZeroEl(Js.get(l, "touchedAt")); val rt = Js.orZeroEl(Js.get(r, "touchedAt"))
                // F1: clamped copies for the two comparisons ONLY; Math.max below stays raw.
                val ltc = ClampPolicy.clampFutureD(lt, ctx.futureCeil)
                val rtc = ClampPolicy.clampFutureD(rt, ctx.futureCeil)
                val text: JsonElement? = when {
                    lTextChanged && !rTextChanged -> lText
                    !lTextChanged && rTextChanged -> rText
                    !lTextChanged && !rTextChanged -> if (bText != null) bText else if (rText != null && rText !is JsonNull) rText else lText
                    Js.strictEq(lText, rText) -> lText
                    ltc != rtc -> if (ltc > rtc) lText else rText
                    else -> if (preferLocal) lText else rText
                }
                val done: Boolean = when {
                    lDoneChanged && !rDoneChanged -> lDone
                    !lDoneChanged && rDoneChanged -> rDone
                    !lDoneChanged && !rDoneChanged -> bDone ?: rDone
                    lDone == rDone -> lDone
                    ltc != rtc -> if (ltc > rtc) lDone else rDone
                    else -> if (preferLocal) lDone else rDone
                }
                val touchedAt = Js.max(Js.number(lt), Js.number(rt))
                val touched = !(touchedAt.isNaN() || touchedAt == 0.0)
                result[key] = Js.assign(if (preferLocal) l else r,
                    "id" to idEl, "text" to text, "done" to JsonPrimitive(done),
                    "touchedAt" to (if (touched) Js.jsonNum(touchedAt) else null))
                continue
            }
            val survivor = l ?: r!!
            if (b == null) { result[key] = survivor; continue } // pure addition: never a deletion
            // Site 1 of 4: RAW -- the else branch is "deletion wins".
            val survivorTouchedAt = Js.orZeroEl(Js.get(survivor, "touchedAt"))
            val baseTouchedAt = Js.orZeroEl(Js.get(b, "touchedAt"))
            if (Js.gt(survivorTouchedAt, baseTouchedAt)) result[key] = survivor
        }

        val out = ArrayList<JsonElement>()
        val placed = HashSet<Any>()
        for (x in (if (preferLocal) localSafe else remoteSafe) + (if (preferLocal) remoteSafe else localSafe)) {
            val k = Js.mapKey(Js.get(x, "id"))
            val v = result[k]
            if (v != null && placed.add(k)) out += v
        }
        return JsonArray(out)
    }

    // ---------------------------------------------------------------------------------------
    // Devices (:1571-1654)
    // ---------------------------------------------------------------------------------------

    private fun nameOf(x: JsonElement?): String {
        val n = Js.get(x, "name")
        return if (n is JsonPrimitive && n !is JsonNull && n.isString) Js.jsTrim(n.content) else ""
    }

    /** `!nameOf(x) && ((x.updatedAt) || 0) === 0`: a truthy updatedAt is never the number 0. */
    private fun isJunk(x: JsonElement?): Boolean = nameOf(x).isEmpty() && !Js.truthy(Js.get(x, "updatedAt"))

    // [DEFERRED_OPEN_QUESTION] a string updatedAt would make JS `+` concatenate; numeric here.
    private fun score(x: JsonElement?): Double =
        if (isJunk(x)) -1.0 else Js.num(Js.get(x, "updatedAt")) + (if (nameOf(x).isNotEmpty()) 0.5 else 0.0)

    private fun cleanDevicesRaw(arr: JsonElement?): JsonArray {
        if (arr !is JsonArray) return JsonArray(emptyList())
        val byId = LinkedHashMap<Any, JsonElement>()
        for (d in arr) {
            if (!Js.truthy(d) || !Js.truthy(Js.get(d, "id"))) continue
            val k = Js.mapKey(Js.get(d, "id"))
            val prev = byId[k]
            if (prev == null) { byId[k] = d; continue }
            if (score(d) > score(prev)) byId[k] = d
        }
        return JsonArray(byId.values.toList())
    }

    private fun mergeDevicesRaw(baseArr: JsonElement?, localArr: JsonElement?, remoteArr: JsonElement?, ctx: MergeContext): JsonArray {
        val baseMap = keyedMap(baseArr, "base.devices")
        val localMap = keyedMap(localArr, "local.devices")
        val remoteMap = keyedMap(remoteArr, "remote.devices")
        val ids = LinkedHashSet<Any>().apply { addAll(baseMap.keys); addAll(localMap.keys); addAll(remoteMap.keys) }
        val result = LinkedHashMap<Any, JsonElement>()
        fun sc(d: JsonElement?): Double = if (!Js.truthy(d)) -2.0 else score(d)
        for (key in ids) {
            val b = baseMap[key]?.value
            val l = localMap[key]?.value
            val r = remoteMap[key]?.value
            val localChanged = !Js.deepEqual(l, b)
            val remoteChanged = !Js.deepEqual(r, b)
            if (!localChanged && !remoteChanged) { if (Js.truthy(b)) result[key] = b!!; continue }
            val sl = sc(l); val sr = sc(r)
            val winner: JsonElement? = when {
                sl > sr -> l
                sr > sl -> r
                else -> {
                    val ld = ctx.localDev
                    val rd = ctx.remoteDev ?: firstOtherId(remoteArr, ld)
                    if (rd != null && ld != rd) (if (rd > ld) r else l) else l
                }
            }
            if (Js.truthy(winner)) result[key] = winner!!
        }
        return cleanDevicesRaw(JsonArray(orderLocalRemoteRest(localArr, remoteArr, result)))
    }

    /** `(arr||[]).map(d => d && d.id).filter(id => id && id !== ld)[0] || null` (:1641-1642, :2285-2286). */
    private fun firstOtherId(arr: JsonElement?, ld: String): String? {
        for (d in Js.arrayOrThrow(arr, "devices")) {
            val id = if (Js.truthy(d)) Js.get(d, "id") else d
            if (!Js.truthy(id)) continue
            val s = (id as? JsonPrimitive)?.content ?: continue
            if ((id as JsonPrimitive).isString && s == ld) continue
            return s
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // mergeDayArray (:2028-2100)
    // ---------------------------------------------------------------------------------------

    private fun mergeDayArrayRaw(localArr: JsonElement?, remoteArr: JsonElement?, zone: ZoneId): JsonArray {
        val buckets = LinkedHashMap<Double, JsonObject>()
        fun rowWins(cand: JsonObject, cur: JsonObject): Boolean {
            fun bothNum(k: String) = Js.isNumber(cand[k]) && Js.isNumber(cur[k])
            if (bothNum("lvl") && Js.number(cand["lvl"]) != Js.number(cur["lvl"])) return Js.number(cand["lvl"]) > Js.number(cur["lvl"])
            if (bothNum("xp") && Js.number(cand["xp"]) != Js.number(cur["xp"])) return Js.number(cand["xp"]) > Js.number(cur["xp"])
            return Js.gt(Js.orZeroEl(cand["date"]), Js.orZeroEl(cur["date"]))
        }
        fun fold(entry: JsonElement) {
            if (!Js.truthy(entry) || !Js.isObjectLike(entry)) return // malformed row skipped
            val row = Js.assign(entry)
            val k = dayStampOfD(Js.orZeroEl(Js.get(entry, "date")), zone).let { if (it == 0.0) 0.0 else it }
            val cur = buckets[k]
            if (cur == null || rowWins(row, cur)) buckets[k] = row
        }
        (localArr as? JsonArray)?.forEach(::fold)
        (remoteArr as? JsonArray)?.forEach(::fold)
        // .sort((a, b) => (a.date || 0) - (b.date || 0)); a NaN difference counts as equal.
        return JsonArray(buckets.values.sortedWith { a, b ->
            val d = Js.number(Js.orZeroEl(a["date"])) - Js.number(Js.orZeroEl(b["date"]))
            if (d.isNaN() || d == 0.0) 0 else if (d < 0) -1 else 1
        })
    }

    // ---------------------------------------------------------------------------------------
    // monthlyBackups (:2194-2198)
    // ---------------------------------------------------------------------------------------

    private fun monthlyBackups(l: JsonElement?, r: JsonElement?): JsonArray {
        val seen = LinkedHashMap<Any, JsonElement>()
        for (e in spread(Js.orEmptyArr(l)) + spread(Js.orEmptyArr(r))) seen.putIfAbsent(Js.mapKey(e), e)
        // Array.prototype.sort default: by String(x), UTF-16 code units; stable.
        return JsonArray(seen.values.sortedWith { a, b -> Js.jsString(a).compareTo(Js.jsString(b)) })
    }

    /** `[...x]`: an array spreads, a string spreads by code point, anything else throws. */
    private fun spread(x: JsonElement): List<JsonElement> = when {
        x is JsonArray -> x
        x is JsonPrimitive && x !is JsonNull && x.isString -> {
            val out = ArrayList<JsonElement>(); var i = 0; val s = x.content
            while (i < s.length) { val cp = s.codePointAt(i); out += JsonPrimitive(String(Character.toChars(cp))); i += Character.charCount(cp) }
            out
        }
        else -> throw OptiMergeTypeError("monthlyBackups is not iterable")
    }

    // ---------------------------------------------------------------------------------------
    // char (:2201-2321)
    // ---------------------------------------------------------------------------------------

    private fun mergeChar(base: JsonObject, local: JsonObject, remote: JsonObject, mergedLastCron: JsonElement, ctx: MergeContext, sink: ConflictSink): JsonElement {
        val b = base["char"].orEmptyObj()
        val l = local["char"].orEmptyObj()
        val r = remote["char"].orEmptyObj()
        when (CharTiebreak.oneSided(b, l, r)) {
            Side.BASE -> return b
            Side.LOCAL -> return l
            Side.REMOTE -> return r
            Side.BOTH_CHANGED -> Unit
        }
        // K3: merge()'s RAW device ids (a remote without deviceId gets no accumulation).
        val acc = CharAccumulate.accumulate(b, l, r, ctx.localDev, ctx.remoteDev)
        fun over(w: JsonObject): JsonObject = if (acc != null) CharAccumulate.overlay(w, acc) else w

        val lUa = ClampPolicy.clampFutureD(l["updatedAt"], ctx.futureCeil)
        val rUa = ClampPolicy.clampFutureD(r["updatedAt"], ctx.futureCeil)
        val charId: JsonElement? = if (Js.truthy(l["id"])) l["id"] else r["id"]
        val charTitle: JsonElement? = if (Js.truthy(l["name"])) l["name"] else r["name"]
        val day: JsonElement = Js.orZeroEl(mergedLastCron)
        val throttleKey = "char:" + Js.jsString(charId)
        fun ev(winner: String, winnerDev: String?, loserDev: String?, reason: String) {
            if (!ctx.throttle.firstTime(throttleKey)) return
            sink.emit(ConflictEvent(
                kind = ConflictEvent.CONFLICT_RESOLVED, taskType = CHAR,
                winner = winner, loser = if (winner == "local") "remote" else "local",
                winnerDev = winnerDev?.let { JsonPrimitive(it) } ?: JsonNull,
                loserDev = loserDev?.let { JsonPrimitive(it) } ?: JsonNull,
                reason = reason, charId = charId, charTitle = charTitle, day = day,
            ))
        }

        if (rUa > lUa) {
            ev("remote", ctx.remoteDev, ctx.localDev, ConflictEvent.REASON_RECENCY)
            return over(r)
        }
        // The RESOLVED ids: _ld = localDeviceId, _rd = remoteDeviceId ?? first other remote.devices id.
        val ld = ctx.localDev
        val rd = ctx.remoteDev ?: firstOtherId(remote["devices"], ld)
        if (lUa == rUa && rd != null && ld != rd) {
            val remoteWins = rd > ld
            ev(if (remoteWins) "remote" else "local", if (remoteWins) rd else ld, if (remoteWins) ld else rd, ConflictEvent.REASON_DEVICE_ID)
            return over(if (remoteWins) r else l)
        }
        ev("local", ld, rd, if (lUa > rUa) ConflictEvent.REASON_RECENCY else ConflictEvent.REASON_UNRESOLVED)
        return over(l)
    }

    // ---------------------------------------------------------------------------------------

    companion object {
        /** sync.js TOMBSTONE_MAX_AGE_MS (:2162): 180 days. [DEFERRED_OPEN_QUESTION] a device offline >180 d may resurrect. */
        const val TOMBSTONE_MAX_AGE_MS: Double = 180.0 * 86_400_000.0

        /** sync.js `dayStampOf(ms)` (:967) in [zone]: local `y*10000 + m*100 + d`, 0 for a falsy ms. */
        fun dayStampOf(ms: Long, zone: ZoneId): DayStamp = toDay(dayStampOfD(JsonPrimitive(ms), zone))

        /** JS: `if(!ms) return 0; const d = new Date(ms); ...` -- NaN for an Invalid Date. */
        internal fun dayStampOfD(ms: JsonElement?, zone: ZoneId): Double {
            if (!Js.truthy(ms)) return 0.0
            // [DEFERRED_OPEN_QUESTION] a string ms would go through Date.parse in JS; treated as Invalid Date.
            if (ms !is JsonPrimitive || (ms.isString)) return Double.NaN
            val v = Js.number(ms)
            if (v.isNaN() || v.isInfinite() || Math.abs(v) > 8.64e15) return Double.NaN
            val t = if (v < 0) Math.ceil(v) else Math.floor(v) // TimeClip: ToIntegerOrInfinity
            val z = Instant.ofEpochMilli(t.toLong()).atZone(zone)
            return (z.year * 10000 + z.monthValue * 100 + z.dayOfMonth).toDouble()
        }

        private fun toDay(d: Double): Int = if (d.isNaN()) 0 else d.toInt()

        private val EMPTY = JsonObject(emptyMap())
        private val DAILY = JsonPrimitive("daily")
        private val CHAR = JsonPrimitive("char")
        private val TRUE = JsonPrimitive(true)
        private val FALSE = JsonPrimitive(false)

        /** `x || {}` for a value whose fields are then read (a non-object yields only undefined fields). */
        private fun JsonElement?.orEmptyObj(): JsonObject = this as? JsonObject ?: EMPTY
    }
}
