package com.example.data.sync.events

import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.EventSyncEngine
import com.example.data.sync.merge.Js
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WP-8.7 (PX-54, SY-36) -- the pull dedup: Opti `evtIncomingSig`, `evtUidDisambiguate` and
 * `evtIncomingFilter` (sync.js:3087-3148, 7d5d4f3). LOCKSTEP with app.js `eventMergeSig`.
 *
 * Records are handled as raw JSON (a downloaded file is a bare JSON array of Opti event
 * objects), so every JS coercion (`||`, `String(x)`, `typeof`) is ported with [Js].
 */
object EvtDedup {

    /** JS `String(v)` as `Array.prototype.join` writes it: undefined and null become "". */
    private fun part(v: JsonElement?): String = if (v == null || v is JsonNull) "" else Js.jsString(v)

    /** JS `(v || dflt)` inside the join. */
    private fun orPart(v: JsonElement?, dflt: String): String = if (Js.truthy(v)) part(v) else dflt

    /**
     * `[r.ts, r.kind, r.taskId || '', r.dir || 0, r.reps || 0, r.detail || '', r.subId || '',
     * (r.done === undefined ? '' : (r.done ? 1 : 0))].join('|')` (sync.js:3093-3094).
     */
    fun incomingSig(r: JsonObject): String = listOf(
        part(r["ts"]),
        part(r["kind"]),
        orPart(r["taskId"], ""),
        orPart(r["dir"], "0"),
        orPart(r["reps"], "0"),
        orPart(r["detail"], ""),
        orPart(r["subId"], ""),
        if (!r.containsKey("done")) "" else if (Js.truthy(r["done"])) "1" else "0",
    ).joinToString("|")

    /** The signature of a stored row: the row as Opti holds it (typed columns + payload keys). */
    fun entitySig(e: EventLogEntity): String = incomingSig(EventSyncEngine.buildEventPushJson(e))

    /**
     * FNV-1a over the UTF-16 code units, `uid + '~' + (h>>>0).toString(16)` (sync.js:3097-3102).
     * Kotlin Int multiplication wraps exactly like `Math.imul`.
     */
    fun disambiguate(uid: String, sig: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in sig) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return uid + "~" + Integer.toUnsignedString(h, 16)
    }

    /**
     * `evtIncomingFilter(records, existingUidSet, myDev, nowMs, ageLimitMs)`. [uidSigs] non-null
     * is Opti's Map form (uid -> signature, the production pull); null is the legacy Set form
     * ([existingUids] only, uid-only dedup, G5's call shape). Survivors lose `id`; a colliding
     * uid is rewritten to its disambiguated form in place.
     *
     * A uid is read as its JS string; a non-string truthy uid (never written by Opti) keys by its
     * text (guess, see WP-8.7 notes).
     */
    fun incomingFilter(
        records: List<JsonElement?>,
        existingUids: Set<String>,
        uidSigs: Map<String, String>?,
        myDev: String,
        nowMs: Long,
        ageLimitMs: Long,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val seen = LinkedHashMap<String, String?>()
        val existingSigs: Set<String>? = uidSigs?.values?.toSet()
        val seenSigs = HashSet<String>()
        val has: (String) -> Boolean = if (uidSigs != null) { u -> uidSigs.containsKey(u) } else { u -> existingUids.contains(u) }
        for (el in records) {
            val r = el as? JsonObject ?: continue
            if (!Js.truthy(r["uid"]) || !Js.isNumber(r["ts"])) continue
            if (Js.truthy(r["synthetic"])) continue
            if (isString(r["kind"], "lifecycle")) continue
            if (isString(r["dev"], myDev)) continue
            val ts = Js.number(r["ts"])
            if (nowMs - ts > ageLimitMs) continue
            val rUid = Js.jsString(r["uid"])
            val isRep = rUid.take(4) == "rep-"
            val sig = if (uidSigs != null) incomingSig(r) else null
            if (isRep && existingSigs != null && (existingSigs.contains(sig) || seenSigs.contains(sig))) continue
            var uid = rUid
            if (has(uid) || seen.containsKey(uid)) {
                val knownSig = if (has(uid)) uidSigs?.get(uid) else seen[uid]
                if (uidSigs == null || knownSig == sig) continue
                uid = disambiguate(uid, sig!!)
                if (has(uid) || seen.containsKey(uid)) continue
            }
            seen[uid] = sig
            if (sig != null) seenSigs.add(sig)
            val rec = LinkedHashMap<String, JsonElement>(r)
            rec.remove("id")
            if (uid != rUid) rec["uid"] = JsonPrimitive(uid)
            out.add(JsonObject(rec))
        }
        return out
    }

    private fun isString(v: JsonElement?, s: String): Boolean =
        v is JsonPrimitive && v !is JsonNull && v.isString && v.content == s
}
