package com.example.data.sync.merge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * WP-8.4 -- sync.js K3 + F1 char earnings accumulate (:1656-1834), ported exactly.
 *
 * The accumulating quantities are TOTAL xp (re-levelled afterwards), gold and mp. Both sides'
 * deltas are measured from the largest aggregate BOTH sides already folded in (`_accCommon`,
 * F1), and `char.abs[deviceId] = {ua, xp, gold, mp}` records what each result absorbed.
 * INVERTED POLARITY: every abs operand is a watermark and stays RAW (`_uaRaw`), sync.js :1721-1730.
 */
object CharAccumulate {

    /**
     * sync.js `_charAccumulate` (:1783-1833). Returns null ("not applicable" -> plain LWW) when
     * a precondition fails: empty/absent base, a non-canonical level on any side, device ids
     * missing or equal, or no numeric delta.
     *
     * `localDev`/`remoteDev` are nullable (widened from the card's `String`) because Opti's
     * precondition 3 is defined on a null id; any String argument is accepted unchanged.
     */
    fun accumulate(base: JsonObject?, local: JsonObject, remote: JsonObject, localDev: String?, remoteDev: String?): AccResult? {
        if (base == null || base.isEmpty()) return null
        if (!canonical(base) || !canonical(local) || !canonical(remote)) return null
        if (localDev == null || remoteDev == null || localDev == remoteDev) return null

        val bT = totals(base); val lT = totals(local); val rT = totals(remote)
        val cB = accCommon(bT, local, remote, localDev, remoteDev)
        val dXp = (lT.xp - cB.xp) + (rT.xp - cB.xp)
        val dGold = (lT.gold - cB.gold) + (rT.gold - cB.gold)
        val dMp = (lT.mp - cB.mp) + (rT.mp - cB.mp)
        if (dXp == 0.0 && dGold == 0.0 && dMp == 0.0) return null

        var totXp = maxOf(0.0, cB.xp + dXp)
        val gold = maxOf(0.0, cB.gold + dGold)
        var mp = maxOf(0.0, cB.mp + dMp)
        // Earnings-only safety floor; gold is spendable and never floored (:1809-1816).
        val lostGround = (lT.xp < cB.xp) || (rT.xp < cB.xp) || (lT.mp < cB.mp) || (rT.mp < cB.mp)
        if (!lostGround) { totXp = maxOf(totXp, lT.xp, rT.xp); mp = maxOf(mp, lT.mp, rT.mp) }
        val (lvl, xp) = fromTotalXp(totXp)

        // abs: third-party entries carried by the strict total order _absBetter, then each
        // participant writes its own totals verbatim (:1818-1831).
        val abs = LinkedHashMap<String, Entry>()
        for (src in listOf<JsonObject>(base, local, remote)) {
            val a = src["abs"]
            if (!Js.truthy(a) || !Js.isObjectLike(a)) continue
            for (k in Js.keys(a)) {
                val e = absEntry(src, k) ?: continue
                if (absBetter(e, abs[k])) abs[k] = e
            }
        }
        abs[localDev] = Entry(ClampPolicy.uaRawD(local), lT.xp, lT.gold, lT.mp)
        abs[remoteDev] = Entry(ClampPolicy.uaRawD(remote), rT.xp, rT.gold, rT.mp)
        val absJson = LinkedHashMap<String, JsonElement>()
        for ((k, e) in abs) absJson[k] = e.toJson()
        return AccResult(lvl = lvl, xp = xp, gold = toFixed2(gold), mp = mp, abs = Js.jsObject(absJson))
    }

    /** sync.js `_charOver` (:1834): `Object.assign({}, winner, acc)`. */
    fun overlay(winner: JsonObject, acc: AccResult): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(winner)
        out["lvl"] = Js.jsonNum(acc.lvl.toDouble())
        out["xp"] = Js.jsonNum(acc.xp)
        out["gold"] = Js.jsonNum(acc.gold)
        out["mp"] = Js.jsonNum(acc.mp)
        out["abs"] = acc.abs
        return Js.jsObject(out)
    }

    // ---------------------------------------------------------------------------------------

    /** sync.js _XP_LVL_CAP (:1671): loop bound only. */
    private const val XP_LVL_CAP = 9999

    internal data class Totals(val xp: Double, val gold: Double, val mp: Double)
    internal data class Entry(val ua: Double, val xp: Double, val gold: Double, val mp: Double) {
        fun toJson(): JsonObject = JsonObject(linkedMapOf(
            "ua" to Js.jsonNum(ua), "xp" to Js.jsonNum(xp), "gold" to Js.jsonNum(gold), "mp" to Js.jsonNum(mp)))
    }

    /** `_xpNeed` (:1667) == app.js `xpToLevel` (:2210): `Math.round(0.25 l^2 + 10 l + 139.75)`. */
    internal fun xpNeed(lvl: Int): Double = Math.floor(0.25 * lvl * lvl + 10.0 * lvl + 139.75 + 0.5)

    private fun lvlOf(c: JsonObject): Int {
        val v = Js.num(c["lvl"]).takeIf { it != 0.0 } ?: 1.0
        val f = Math.floor(v)
        return if (f < 1.0) 1 else if (f > Int.MAX_VALUE) Int.MAX_VALUE else f.toInt()
    }

    /** `_charTotalXp` (:1677-1682). */
    internal fun totalXp(c: JsonObject): Double {
        val lvl = lvlOf(c)
        var tot = maxOf(0.0, Js.num(c["xp"]))
        var k = 1
        while (k < lvl && k <= XP_LVL_CAP) { tot += xpNeed(k); k++ }
        return tot
    }

    /** `_charFromTotalXp` (:1684-1688), gainXp's own `>=` boundary. */
    internal fun fromTotalXp(total: Double): Pair<Int, Double> {
        var rem = maxOf(0.0, Js.orZero(total))
        var lvl = 1
        var need = xpNeed(1)
        while (rem >= need && lvl <= XP_LVL_CAP) { rem -= need; lvl++; need = xpNeed(lvl) }
        return lvl to rem
    }

    /** `_charCanonical` (:1692-1695). */
    private fun canonical(c: JsonObject): Boolean = maxOf(0.0, Js.num(c["xp"])) < xpNeed(lvlOf(c))

    /** `_charTotals` (:1696-1698). */
    private fun totals(c: JsonObject) = Totals(totalXp(c), maxOf(0.0, Js.num(c["gold"])), maxOf(0.0, Js.num(c["mp"])))

    /** `_absEntry` (:1706-1712). */
    private fun absEntry(c: JsonObject, dev: String): Entry? {
        val a = c["abs"]
        if (!Js.truthy(a) || !Js.isObjectLike(a)) return null
        val e = Js.get(a, dev)
        if (!Js.truthy(e) || !Js.isObjectLike(e)) return null
        return Entry(Js.num(Js.get(e, "ua")), maxOf(0.0, Js.num(Js.get(e, "xp"))),
            maxOf(0.0, Js.num(Js.get(e, "gold"))), maxOf(0.0, Js.num(Js.get(e, "mp"))))
    }

    /** `_absBetter` (:1715-1720): lexicographic on (ua, xp, gold, mp). */
    private fun absBetter(e: Entry, cur: Entry?): Boolean {
        if (cur == null) return true
        val a = doubleArrayOf(e.ua, e.xp, e.gold, e.mp); val c = doubleArrayOf(cur.ua, cur.xp, cur.gold, cur.mp)
        for (i in 0 until 4) if (a[i] != c[i]) return a[i] > c[i]
        return false
    }

    /** `_accCommon` (:1755-1780), the F1 common baseline. */
    private fun accCommon(bT: Totals, l: JsonObject, r: JsonObject, localDev: String, remoteDev: String): Totals {
        var xp = bT.xp; var gold = bT.gold; var mp = bT.mp
        val lT = totals(l); val rT = totals(r)
        fun sideEntry(doc: JsonObject, selfDev: String, own: Totals, k: String): Totals? {
            val e = absEntry(doc, k)
            if (k != selfDev) return e?.let { Totals(it.xp, it.gold, it.mp) }
            if (e == null) return own
            return Totals(maxOf(e.xp, own.xp), maxOf(e.gold, own.gold), maxOf(e.mp, own.mp))
        }
        val seen = LinkedHashSet<String>()
        for (d in listOf(l, r)) {
            val a = d["abs"]
            if (Js.truthy(a) && Js.isObjectLike(a)) seen += Js.keys(a)
        }
        seen += localDev; seen += remoteDev
        for (k in seen) {
            val le = sideEntry(l, localDev, lT, k) ?: continue
            val re = sideEntry(r, remoteDev, rT, k) ?: continue
            xp = maxOf(xp, minOf(le.xp, re.xp))
            gold = maxOf(gold, minOf(le.gold, re.gold))
            mp = maxOf(mp, minOf(le.mp, re.mp))
        }
        return Totals(xp, gold, mp)
    }

    /**
     * `+gold.toFixed(2)`: Number.prototype.toFixed rounds the EXACT binary value, taking the
     * larger candidate on an exact tie (HALF_UP for the non-negative gold here), and `+` re-parses.
     */
    private fun toFixed2(v: Double): Double {
        if (v.isNaN() || v.isInfinite() || kotlin.math.abs(v) >= 1e21) return v
        return BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString().toDouble()
    }
}
