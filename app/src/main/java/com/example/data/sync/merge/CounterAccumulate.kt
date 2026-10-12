package com.example.data.sync.merge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * WP-8.4 -- sync.js habit period counters (K3b + cResetOn markers + cAbs watermark + F1),
 * :1835-2023, ported exactly.
 *
 * Applied by the merge core in mergeCollection's BOTH-CHANGED arm only, AFTER the checklist
 * splice, on the arbitration winner (sync.js :1504-1510). Gated on field presence (a numeric
 * `cUp`/`cDown` on either side), never on task `type`.
 *
 * INVERTED POLARITY (:1862-1865): every `cAbs` operand is an absorbed watermark and stays RAW.
 */
object CounterAccumulate {

    /**
     * sync.js `_accumCounters(b, l, r, winner, localDeviceId, remoteDeviceId)` (:1959-2023).
     *
     * @param localDev / remoteDev nullable (widened from the card's `String`): Opti degrades to
     *   the shared-base arithmetic and writes no `cAbs` when either id is missing or they are
     *   equal (:1972-1976). Any String argument is accepted unchanged.
     * @param localToday `dayStampOf(Date.now())` for the future-`cResetOn` guard (:1990-2001);
     *   added to the card's signature because the guard cannot run without it and D18 forbids
     *   a clock read here. Pass [MergeContext.localToday].
     * @return [winner] itself when neither side carries counters, else a copy with the
     *   `cUp`/`cDown`/`cResetOn`/`cAbs` patch applied (`Object.assign({}, winner, patch)`).
     */
    fun accumulate(
        base: JsonObject?,
        local: JsonObject,
        remote: JsonObject,
        winner: JsonObject,
        localDev: String?,
        remoteDev: String?,
        localToday: Int,
    ): JsonObject {
        val hU = Js.isNumber(local["cUp"]) || Js.isNumber(remote["cUp"])
        val hD = Js.isNumber(local["cDown"]) || Js.isNumber(remote["cDown"])
        if (!hU && !hD) return winner

        // Markers are only trustworthy once the BASE carries one (:1965-1970).
        val useMarkers = Js.orZero(Js.number(base?.get("cResetOn"))) > 0
        val lReset: Boolean? = if (useMarkers) didReset(local, base) else null
        val rReset: Boolean? = if (useMarkers) didReset(remote, base) else null

        val idsOk = localDev != null && remoteDev != null && localDev != remoteDev
        val cUpBase = if (idsOk) cntCommon(base?.get("cUp"), local, remote, localDev!!, remoteDev!!, "cUp") else null
        val cDnBase = if (idsOk) cntCommon(base?.get("cDown"), local, remote, localDev!!, remoteDev!!, "cDown") else null

        val patch = LinkedHashMap<String, JsonElement>()
        if (hU) patch["cUp"] = Js.jsonNum(accumCounter(base?.get("cUp"), local["cUp"], remote["cUp"], lReset, rReset, cUpBase, cUpBase))
        if (hD) patch["cDown"] = Js.jsonNum(accumCounter(base?.get("cDown"), local["cDown"], remote["cDown"], lReset, rReset, cDnBase, cDnBase))

        // Newest reset stamp forward, dropping one in THIS device's future first (:1988-2003).
        val lRes = Js.num(local["cResetOn"])
        val rRes = Js.num(remote["cResetOn"])
        val today = localToday.toDouble()
        val newest = maxOf(if (lRes > today) 0.0 else lRes, if (rRes > today) 0.0 else rRes)
        val bRes = Js.num(base?.get("cResetOn"))
        if (newest > 0) patch["cResetOn"] = Js.jsonNum(newest)
        else if (bRes != 0.0) patch["cResetOn"] = Js.jsonNum(bRes)

        if (idsOk) {
            val cAbs = LinkedHashMap<String, Entry>()
            for (src in listOf(base, local, remote)) {
                val a = src?.get("cAbs")
                if (!Js.truthy(a) || !Js.isObjectLike(a)) continue
                for (k in Js.keys(a)) {
                    val e = cAbsEntry(src, k) ?: continue
                    if (cAbsBetter(e, cAbs[k])) cAbs[k] = e
                }
            }
            cAbs[localDev!!] = Entry(ClampPolicy.uaRawD(local), maxOf(0.0, Js.num(local["cUp"])), maxOf(0.0, Js.num(local["cDown"])))
            cAbs[remoteDev!!] = Entry(ClampPolicy.uaRawD(remote), maxOf(0.0, Js.num(remote["cUp"])), maxOf(0.0, Js.num(remote["cDown"])))
            val json = LinkedHashMap<String, JsonElement>()
            for ((k, e) in cAbs) json[k] = e.toJson()
            patch["cAbs"] = Js.jsObject(json)
        }

        val out = LinkedHashMap<String, JsonElement>(winner)
        out.putAll(patch)
        return Js.jsObject(out)
    }

    // ---------------------------------------------------------------------------------------

    private data class Entry(val ua: Double, val cUp: Double, val cDown: Double) {
        operator fun get(key: String): Double = if (key == "cUp") cUp else cDown
        fun toJson(): JsonObject = JsonObject(linkedMapOf("ua" to Js.jsonNum(ua), "cUp" to Js.jsonNum(cUp), "cDown" to Js.jsonNum(cDown)))
    }

    /** `_counterDidReset` (:1847-1849). */
    private fun didReset(side: JsonObject, base: JsonObject?): Boolean =
        Js.num(side["cResetOn"]) > Js.num(base?.get("cResetOn"))

    /** `_cAbsEntry` (:1866-1872). */
    private fun cAbsEntry(t: JsonObject?, dev: String): Entry? {
        val a = t?.get("cAbs")
        if (!Js.truthy(a) || !Js.isObjectLike(a)) return null
        val e = Js.get(a, dev)
        if (!Js.truthy(e) || !Js.isObjectLike(e)) return null
        return Entry(Js.num(Js.get(e, "ua")), maxOf(0.0, Js.num(Js.get(e, "cUp"))), maxOf(0.0, Js.num(Js.get(e, "cDown"))))
    }

    /** `_cAbsBetter` (:1875-1880): lexicographic on (ua, cUp, cDown). */
    private fun cAbsBetter(e: Entry, cur: Entry?): Boolean {
        if (cur == null) return true
        val a = doubleArrayOf(e.ua, e.cUp, e.cDown); val c = doubleArrayOf(cur.ua, cur.cUp, cur.cDown)
        for (i in 0 until 3) if (a[i] != c[i]) return a[i] > c[i]
        return false
    }

    /** `_cntCommon` (:1889-1910): max over devices recorded on BOTH sides of min(L_k, R_k), floored at base. */
    private fun cntCommon(bv: JsonElement?, l: JsonObject, r: JsonObject, localDev: String, remoteDev: String, key: String): Double {
        var c = maxOf(0.0, Js.num(bv))
        fun sideVal(doc: JsonObject, selfDev: String, k: String): Double? {
            val e = cAbsEntry(doc, k)
            if (k != selfDev) return e?.get(key)
            return maxOf(e?.get(key) ?: 0.0, maxOf(0.0, Js.num(doc[key])))
        }
        val seen = LinkedHashSet<String>()
        for (d in listOf(l, r)) {
            val a = d["cAbs"]
            if (Js.truthy(a) && Js.isObjectLike(a)) seen += Js.keys(a)
        }
        seen += localDev; seen += remoteDev
        for (k in seen) {
            val lv = sideVal(l, localDev, k) ?: continue
            val rv = sideVal(r, remoteDev, k) ?: continue
            val m = minOf(lv, rv)
            if (m > c) c = m
        }
        return c
    }

    /** `_accumCounter` (:1917-1958). `lReset`/`rReset` null = legacy heuristic; bases null = shared base. */
    private fun accumCounter(bv: JsonElement?, lv: JsonElement?, rv: JsonElement?, lReset: Boolean?, rReset: Boolean?, lBaseIn: Double?, rBaseIn: Double?): Double {
        val b = maxOf(0.0, Js.num(bv)); val l = maxOf(0.0, Js.num(lv)); val r = maxOf(0.0, Js.num(rv))
        val lb = if (lBaseIn == null) b else maxOf(0.0, Js.orZero(lBaseIn))
        val rb = if (rBaseIn == null) b else maxOf(0.0, Js.orZero(rBaseIn))
        val carry = maxOf(lb, rb)
        if (lReset == null || rReset == null) {
            if (l < lb && r < rb) return maxOf(l + r, l, r) // both restarted from 0
            return maxOf(carry + maxOf(0.0, l - lb) + maxOf(0.0, r - rb), l, r)
        }
        val effL = if (lReset) 0.0 else lb
        val effR = if (rReset) 0.0 else rb
        return maxOf(0.0, (if (lReset || rReset) 0.0 else carry) + (l - effL) + (r - effR))
    }
}
