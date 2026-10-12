package com.example.data.sync.merge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Test support for WP-8.4.
 *
 * The G2/G3 golden fixtures (`fixtures/opti/G2_G3`, WP-8.0) store only merge() OUTPUTS. Their
 * inputs live in `tools/gen_opti_fixtures.mjs` (`genMergeVectors`); `merge/g2_g3_inputs.json`
 * is a mechanical dump of exactly those `merge(base, local, remote, rSavedAt, lSavedAt, lDev,
 * rDev)` argument lists, keyed by fixture name, in call order (G3: device A then device B;
 * the three g2_conflict_retry_idempotence* sets hold ROUND 0 ONLY -- Opti fed rounds 1-3 the
 * previous round's merge() output as `local`, so the test CHAINS those locals from the
 * fixture's own `rounds`).
 */
internal object MergeTestSupport {
    /** gen_opti_fixtures.mjs FIXED_NOW_MS = Date.UTC(2026, 8, 24, 12, 0, 0). */
    const val FIXED_NOW_MS = 1790251200000L
    /** merge() -> _skewDiagReset(): _clampCeil = Date.now() + MAX_FUTURE_SKEW_MS. */
    const val FUTURE_CEIL = FIXED_NOW_MS + 120000L
    /** dayStampOf(Date.now()) under the generator's TZ=UTC. */
    const val TODAY = 20260924

    val json = Json { isLenient = false }

    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/opti/manifest.json").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (fixtures/opti/manifest.json) not found from ${File(".").absoluteFile}")
    }

    fun fixtureDir(): File = File(repoRoot(), "fixtures/opti/G2_G3")

    fun inputs(): JsonObject {
        val text = MergeTestSupport::class.java.classLoader!!.getResource("merge/g2_g3_inputs.json")!!.readText()
        return json.parseToJsonElement(text).jsonObject
    }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    fun obj(text: String): JsonObject = parse(text)

    fun stable(e: JsonElement?): String = Js.stableStringify(e)

    fun JsonObject.objOrNull(k: String): JsonObject? = this[k] as? JsonObject
    fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun tasksById(snap: JsonObject?): Map<String, JsonObject> =
        ((snap?.get("tasks") as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .associateBy { it["id"]!!.jsonPrimitive.content }

    /** The char pipeline exactly as sync.js :2201-2321 composes the WP-8.4 primitives. */
    data class CharOutcome(val char: JsonObject, val side: Side, val tiebreak: Side?)

    fun mergeChar(base: JsonObject?, local: JsonObject?, remote: JsonObject?, lDev: String, rDev: String?, ceil: Long = FUTURE_CEIL): CharOutcome {
        val b = base?.objOrNull("char") ?: JsonObject(emptyMap())
        val l = local?.objOrNull("char") ?: JsonObject(emptyMap())
        val r = remote?.objOrNull("char") ?: JsonObject(emptyMap())
        return when (val side = CharTiebreak.oneSided(b, l, r)) {
            Side.BASE -> CharOutcome(b, side, null)
            Side.LOCAL -> CharOutcome(l, side, null)
            Side.REMOTE -> CharOutcome(r, side, null)
            Side.BOTH_CHANGED -> {
                // :2248 -- the RAW merge parameters; a null remote id means no accumulation.
                val acc = CharAccumulate.accumulate(b, l, r, lDev, rDev)
                // :2283-2286 -- the RESOLVED _rd: remote id, else the first remote.devices id != _ld.
                val rd = rDev ?: firstOtherDeviceId(remote, lDev)
                val t = CharTiebreak.bothChanged(l, r, lDev, rd, ceil)
                val w = if (t == Side.REMOTE) r else l
                CharOutcome(if (acc != null) CharAccumulate.overlay(w, acc) else w, side, t)
            }
        }
    }

    /** `(remote.devices||[]).map(d => d && d.id).filter(id => id && id !== _ld)[0] || null` (sync.js :2284-2286). */
    fun firstOtherDeviceId(remote: JsonObject?, ld: String): String? =
        ((remote?.get("devices") as? JsonArray) ?: JsonArray(emptyList()))
            .map { Js.get(it, "id") }
            .firstOrNull { Js.truthy(it) && (it as? JsonPrimitive)?.content != ld }
            ?.let { (it as? JsonPrimitive)?.content }

    /** JS `_charTotalXp` recomputed independently, for simulation assertions. */
    fun totalXp(c: JsonObject): Double {
        val lvl = maxOf(1, (c["lvl"]?.jsonPrimitive?.content?.toDouble() ?: 1.0).toInt())
        var tot = c["xp"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
        for (k in 1 until lvl) tot += Math.round(0.25 * k * k + 10 * k + 139.75).toDouble()
        return tot
    }

    fun levelFor(total: Double): Pair<Int, Double> {
        var rem = total; var lvl = 1
        var need = Math.round(0.25 * lvl * lvl + 10 * lvl + 139.75).toDouble()
        while (rem >= need) { rem -= need; lvl++; need = Math.round(0.25 * lvl * lvl + 10 * lvl + 139.75).toDouble() }
        return lvl to rem
    }

    fun num(o: JsonObject, k: String): Double = o[k]?.jsonPrimitive?.content?.toDouble() ?: 0.0
}
