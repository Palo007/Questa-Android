package com.example.data.sync

import com.example.data.attachments.AttachmentJson
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.GrantedReward
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TaskHistoryPoint
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.DeviceEntity
import com.example.data.local.entities.CharHistoryEntity
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

data class BackupData(
    val character: CharacterEntity,
    val tasks: List<TaskEntity>,
    val rewards: List<RewardEntity>,
    val events: List<EventLogEntity>,
    val deletions: List<Pair<String, Long>>,
    val lastCron: String?,
    val tags: List<TagEntity> = emptyList(),
    val devices: List<DeviceEntity> = emptyList(),
    val charHistory: List<CharHistoryEntity> = emptyList(),
    val monthlyBackups: List<JsonElement> = emptyList(),
    val globalHistory: List<JsonElement> = emptyList(),
    // Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape", §7 D3): `S.prefs.an.views`/
    // `S.prefs.an.metrics` as parsed from the backup file. `views` are modeled (see
    // CustomViewEntity/SerializationEngine.parseViewEntity); `metrics` stay raw `JsonObject`s
    // (see MetricsExtrasEntity's class doc for why -- no Kotlin feature models metric fields).
    val views: List<CustomViewEntity> = emptyList(),
    val metrics: List<JsonObject> = emptyList(),
    // Stage 3 / T3.1 (unknown-field passthrough, spec §3 / §7 D3): verbatim copy of the ORIGINAL
    // top-level backup `S` object exactly as parsed/detokenized, before any Kotlin entity
    // projection. Carries top-level fields Kotlin doesn't model at all (`prefs` in full,
    // `__futureField`-style unknowns, the full-backup-only `__seq`/`__hlcLast`/`__savedAt`
    // bookkeeping keys) forward so `exportBackup`'s `snapshotExtras` parameter can overlay the
    // freshly-rebuilt snapshot onto it (see `SerializationEngine.overlayJson`). Empty object for
    // schema-1 (legacy) input, where `detokSnap` IS the raw root object and there is no separate
    // `S` to isolate cleanly.
    val rawSnapshot: JsonObject = JsonObject(emptyMap())
)

object SerializationEngine {

    // ---------------------------------------------------------------------
    // lastCron wire helpers (Stage 2 / T2.3, spec §7 D4)
    // ---------------------------------------------------------------------
    //
    // PWA emits `lastCron` as a bare JSON number: sync.js:396 `lastCron: S.lastCron || 0`, and
    // freshState() (app.js:109) sets it via `dayStamp(new Date())`, a local-calendar-day integer
    // `y*10000+m*100+d` (spec §6) -- e.g. `20260717`, never a quoted string, with `0` as the
    // falsy/never-set fallback. Kotlin's on-disk representation stays a `String` (Room column
    // `CharacterEntity.lastCron`, and `RpgEngine.periodBoundaryCrossed`'s substring-slicing), so
    // this is purely a wire-format shim, applied at every JSON emission/parse boundary: convert
    // the stored digit string to a bare JSON number literal on the way out, and tolerantly accept
    // either a bare number (current/PWA form) or a quoted string (pre-fix Kotlin exports) on the
    // way in.

    /**
     * Wire-encodes a Kotlin-side `lastCron` digit string as the PWA's bare-number form, per D4.
     * Null/blank/non-numeric input maps to the JSON number `0`, mirroring the PWA's `|| 0`
     * fallback (sync.js:396) for a never-set day stamp.
     */
    fun lastCronToJson(lastCron: String?): JsonPrimitive {
        val n = lastCron?.trim()?.toLongOrNull()
        return JsonPrimitive(n ?: 0L)
    }

    /**
     * Tolerant decode counterpart of [lastCronToJson]: accepts either the bare-number form (PWA,
     * and Kotlin post-D4) or a quoted-string form (pre-D4 Kotlin exports), per D4's "parsed
     * tolerantly (accept string or number on import for old Kotlin files)". Returns the digit
     * string Kotlin's Room column expects. Absent/blank/non-numeric input, and the PWA's literal
     * `0` fallback, all normalize to `""` -- Kotlin's own "never run" sentinel (see
     * `RpgEngine.periodBoundaryCrossed`'s `isNullOrEmpty()` check and
     * `MainViewModel`'s `lastCron.isEmpty()` checks).
     */
    fun lastCronFromJson(element: JsonElement?): String {
        val prim = element as? JsonPrimitive ?: return ""
        val raw = prim.longOrNull?.toString() ?: prim.contentOrNull
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == "0") "" else trimmed
    }

    /**
     * Tolerant numeric decode for merge/compare paths: same acceptance rules as
     * [lastCronFromJson] but returns the raw `Long` (0 for absent/blank/non-numeric) so callers
     * can do a genuine numeric max instead of the lexicographic-string compare that used to mask
     * the D4 type mismatch (spec §7 D4: "JS coercion masks compare bug").
     */
    fun lastCronNumeric(element: JsonElement?): Long {
        val prim = element as? JsonPrimitive ?: return 0L
        return prim.longOrNull ?: prim.contentOrNull?.trim()?.toLongOrNull() ?: 0L
    }

    val EXPORT_FIELD_MAP = mapOf(
        "ts" to "t", "uid" to "u", "dev" to "d", "kind" to "k", "taskTitle" to "n", "notes" to "no", "id" to "i",
        "taskType" to "ty", "taskId" to "ti", "streak" to "st", "reward" to "rw", "repeat" to "rp",
        "checklist" to "cl", "changes" to "ch", "subId" to "si", "subText" to "sx", "done" to "do",
        "clawback" to "cb", "deviceId" to "di", "deviceName" to "dn", "prevDeviceName" to "pn",
        "createdAt" to "ca", "completedAt" to "co", "dir" to "dr", "reps" to "rs", "value" to "v",
        "dmg" to "dg", "cost" to "ct", "effect" to "ef", "counter" to "cr", "preBumpSeq" to "ps",
        "storedSeq" to "ss", "granted" to "gr", "log" to "lg", "detail" to "dt", "visibilityState" to "vs",
        "hidden" to "hd", "dirty" to "dy", "found" to "fd", "idbSeq" to "is", "liveSeq" to "ls",
        "winner" to "wn", "loser" to "lo", "charTitle" to "ctt", "day" to "dyy", "late" to "lt",
        "source" to "o", "synthetic" to "sy", "repCounted" to "rc", "inferred" to "in"
    )

    private val REVERSE_EXPORT_FIELD_MAP = EXPORT_FIELD_MAP.entries.associate { it.value to it.key }

    /** The shape of every EXPORT_FIELD_MAP code (`/^[a-z]{1,3}$/`, app.js:7104). */
    private val SHORT_CODE_SHAPE = Regex("^[a-z]{1,3}$")

    /** Code-shaped LONG field names the import accepts (review fix 2): the phone-tap `src`. */
    val IMPORT_KNOWN_LONG_FIELDS = setOf("src")

    // Generates short field map codes recursively: a, b, ... z, ba, bb... matching nextCode() in JS
    fun generateCode(i: Int): String {
        var s = ""
        var n = i
        do {
            s = (97 + (n % 26)).toChar().toString() + s
            n = n / 26
        } while (n > 0)
        return s
    }

    // Walks snapshot recursively and gathers frequencies of all keys
    fun buildFieldMap(element: JsonElement, freq: MutableMap<String, Int>) {
        when (element) {
            is JsonObject -> {
                // `for (const k in o)`: JS own-key order (array-index keys first), WP-8.3.
                for (k in CanonicalJson.jsKeyOrder(element.keys)) {
                    freq[k] = (freq[k] ?: 0) + 1
                    buildFieldMap(element.getValue(k), freq)
                }
            }
            is JsonArray -> {
                for (v in element) {
                    buildFieldMap(v, freq)
                }
            }
            else -> {}
        }
    }

    // Tokenize recursively using field map
    fun tokenizeDeep(element: JsonElement, fm: Map<String, String>): JsonElement {
        return when (element) {
            is JsonObject -> {
                buildJsonObject {
                    for (k in CanonicalJson.jsKeyOrder(element.keys)) {
                        val code = fm[k] ?: k
                        put(code, tokenizeDeep(element.getValue(k), fm))
                    }
                }
            }
            is JsonArray -> {
                JsonArray(element.map { tokenizeDeep(it, fm) })
            }
            else -> element
        }
    }

    // Detokenize recursively using reverse field map
    fun detokenizeDeep(element: JsonElement, rmap: Map<String, String>): JsonElement {
        return when (element) {
            is JsonObject -> {
                buildJsonObject {
                    for ((k, v) in element) {
                        val longKey = rmap[k] ?: k
                        put(longKey, detokenizeDeep(v, rmap))
                    }
                }
            }
            is JsonArray -> {
                JsonArray(element.map { detokenizeDeep(it, rmap) })
            }
            else -> element
        }
    }

    // Tokenize event array matching _tokenizeEvents in JS
    fun tokenizeEvents(events: List<JsonObject>): TokenizedEventsResult {
        val kindArr = mutableListOf<String>()
        val srcArr = mutableListOf<String>()
        val tidArr = mutableListOf<String>()
        val titleArr = mutableListOf<String>()

        val kindIdx = mutableMapOf<String, Int>()
        val srcIdx = mutableMapOf<String, Int>()
        val tidIdx = mutableMapOf<String, Int>()
        val titleIdx = mutableMapOf<String, Int>()

        fun idx(arr: MutableList<String>, map: MutableMap<String, Int>, v: String?): Int {
            if (v == null) return -1
            return map.getOrPut(v) {
                val i = arr.size
                arr.add(v)
                i
            }
        }

        // WP-8.3: `v == null` (JSON null) is -1, never the string "null" (app.js:7046).
        fun key(v: JsonElement): String? = when (v) {
            JsonNull -> null
            is JsonPrimitive -> v.content
            else -> v.toString()
        }

        val out = events.map { e ->
            buildJsonObject {
                for (f in CanonicalJson.jsKeyOrder(e.keys)) {
                    val v = e.getValue(f)
                    if (f == "uid" || f == "dev" || f == "id") {
                        put(f, v)
                        continue
                    }
                    val sk = EXPORT_FIELD_MAP[f]
                    if (sk == null) {
                        put(f, v)
                        continue
                    }
                    when (f) {
                        "kind" -> put(sk, JsonPrimitive(idx(kindArr, kindIdx, key(v))))
                        "source" -> put(sk, JsonPrimitive(idx(srcArr, srcIdx, key(v))))
                        "taskId" -> put(sk, JsonPrimitive(idx(tidArr, tidIdx, key(v))))
                        "taskTitle" -> put(sk, JsonPrimitive(idx(titleArr, titleIdx, key(v))))
                        // app.js:7066: `(v==null) ? null : (v ? 1 : 0)` with JS truthiness.
                        "synthetic", "repCounted", "inferred", "done" -> {
                            if (v is JsonNull) put(sk, JsonNull)
                            else put(sk, JsonPrimitive(if (SyncSubset.jsTruthy(v)) 1 else 0))
                        }
                        else -> {
                            put(sk, v)
                        }
                    }
                }
            }
        }
        return TokenizedEventsResult(out, kindArr, srcArr, tidArr, titleArr)
    }

    // Detokenize event array matching _detokenizeEvents in JS
    fun detokenizeEvents(env: JsonObject): List<JsonObject> {
        val eArray = env["E"] as? JsonArray ?: return emptyList()
        val K = (env["K"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val SRC = (env["SRC"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val TID = (env["TID"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val TT = (env["TT"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

        return eArray.map { o ->
            if (o !is JsonObject) return@map buildJsonObject {}
            buildJsonObject {
                for ((sk, v) in o) {
                    if (sk == "uid" || sk == "dev" || sk == "id") {
                        put(sk, v)
                        continue
                    }
                    // WP-8.9 (PX-62, app.js:7085-7112): a key shaped like a short code (1-3
                    // lowercase letters) that this build does not know comes from a newer build
                    // and is refused; a longer key is an untokenized field name, kept.
                    // WP-8.9 review fix 2 (orchestrator ruling, a guess): `src` (phone-tap events,
                    // RpgRepository / HabitTap, and Opti app.js:2447) is untokenized and shaped
                    // like a code; Kotlin accepts it as a known long field name.
                    // [DEFERRED_OPEN_QUESTION] Opti's own importData refuses its own backups that
                    // hold `src` (a PWA bug; the PWA is not modified). Pali confirms before WP-8.12.
                    val f = REVERSE_EXPORT_FIELD_MAP[sk]
                        ?: if (SHORT_CODE_SHAPE.matches(sk) && sk !in IMPORT_KNOWN_LONG_FIELDS) throw UnknownFieldCodeException(sk) else sk
                    // app.js:7077-7137: index -1 is null; an index past the dictionary is
                    // `undefined`, which JSON.stringify drops; booleans keep null (WP-8.3).
                    fun lookup(dict: List<String>): JsonElement? {
                        val idx = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                        if (idx == -1) return JsonNull
                        return idx?.let { dict.getOrNull(it) }?.let { JsonPrimitive(it) }
                    }
                    when (sk) {
                        "k" -> lookup(K)?.let { put("kind", it) }
                        "o" -> lookup(SRC)?.let { put("source", it) }
                        "ti" -> lookup(TID)?.let { put("taskId", it) }
                        "n" -> lookup(TT)?.let { put("taskTitle", it) }
                        "sy", "rc", "in", "do" -> put(f, if (v is JsonNull) JsonNull else JsonPrimitive(SyncSubset.jsTruthy(v)))
                        else -> {
                            put(f, v)
                        }
                    }
                }
            }
        }
    }

    // Fallback-hash policy (Q-04, WP-8.9): a hash matching this pattern came from Opti's
    // `_hashFallback32` digest. With no `_backup.hashAlgo` it is verified as `fallback32`, as Opti
    // does (app.js:8214-8215); see [decodeBackup].
    private val FALLBACK_HASH_PREFIX_REGEX = Regex("^fallback-")

    fun computeSha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------
    // Stage 3 / T3.1 -- unknown-field passthrough (spec §3, §7 D3/D5/D8/D12's sibling gap)
    // ---------------------------------------------------------------------
    //
    // Design: "overlay", not a separate unmodeled-keys-only list. Every per-record entity
    // (character/task/reward/tag/device/checklist-item) that can come from a PWA import/merge
    // gets a nullable `extraJson` column/field holding a compact-JSON verbatim copy of the ENTIRE
    // original object exactly as parsed -- not just the fields Kotlin doesn't model. On export,
    // [overlayJson] walks that stored original object IN ITS ORIGINAL KEY ORDER, replacing the
    // value of any key Kotlin currently models with the entity's live (possibly-edited) value at
    // that SAME position, and leaving every other key (reminders[], up/down, or any future PWA
    // field) completely untouched; genuinely new keys Kotlin's live data has that the original
    // object never had (e.g. a brand-new optional field on a record that predates it) are
    // appended after, in the normative order the fresh builder itself uses.
    //
    // Chosen over a "store just the unmodeled-keys subset" design for two reasons: (1) it doesn't
    // require separately tracking each record's original key ordering (a second data structure)
    // to satisfy the "re-emit in original position" requirement -- the overlay walk over the
    // stored whole object already reconstructs that ordering for free; (2) it composes: the SAME
    // [overlayJson] helper is reused at every nesting level (checklist item -> task -> top-level
    // `S`), rather than needing a bespoke per-level "extras" shape.
    //
    // Records with no known PWA original (freshly created in the Kotlin app) have `extraJson =
    // null`; [overlayJson] treats that as "no original to overlay", returning the fresh object
    // unchanged -- i.e. exactly today's (pre-T3.1) normative-order behavior for new records.

    /**
     * Overlays [fresh]'s CURRENT values onto [original]'s key POSITIONS: iterates [original] in
     * its own stored order, substituting [fresh]'s value for any key both objects share (Kotlin's
     * current data wins the VALUE, [original] wins the ORDER), then appends any keys present only
     * in [fresh] (newly-modeled fields the original object never had) in [fresh]'s own order.
     * Keys present only in [original] (fields Kotlin doesn't model at all) pass through verbatim,
     * unmoved -- this is the "unknown-field passthrough" itself. Returns [fresh] unchanged if
     * [original] is null or empty (no known original to overlay onto).
     */
    fun overlayJson(original: JsonObject?, fresh: JsonObject): JsonObject {
        if (original == null || original.isEmpty()) return fresh
        return buildJsonObject {
            for ((k, v) in original) {
                put(k, if (fresh.containsKey(k)) fresh.getValue(k) else v)
            }
            for ((k, v) in fresh) {
                if (!original.containsKey(k)) put(k, v)
            }
        }
    }

    // ---------------------------------------------------------------------
    // WP-8.3: typed (schema v7) fields over the extraJson overlay
    // ---------------------------------------------------------------------
    //
    // A typed column is the live value; `extraJson` only remembers the original JSON. Rule per key:
    //  - the original has the key and its value decodes to the live value -> keep the original
    //    element verbatim (byte-stable round trip, and a shape Kotlin cannot decode passes through);
    //  - otherwise the live value, rendered fresh; a null live value removes the key.
    // [keepOrTyped] returns the element to write (null = remove); [applyTyped] writes them over the
    // overlay result, in place when the key exists, appended when it does not.

    private val converters by lazy { com.example.data.local.Converters() }

    private fun keepOrTyped(original: JsonObject?, key: String, typed: JsonElement?, same: (JsonElement) -> Boolean): JsonElement? {
        val o = original?.get(key)
        return if (o != null && same(o)) o else typed
    }

    private fun applyTyped(base: JsonObject, typed: Map<String, JsonElement?>): JsonObject = buildJsonObject {
        for ((k, v) in base) {
            if (typed.containsKey(k)) typed[k]?.let { put(k, it) } else put(k, v)
        }
        for ((k, v) in typed) if (!base.containsKey(k) && v != null) put(k, v)
    }

    private fun numberOf(e: JsonElement): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.doubleOrNull
    }

    private fun charAbsJson(abs: Map<String, com.example.data.local.entities.CharAbsEntry>): JsonObject = buildJsonObject {
        for ((dev, a) in abs) put(dev, buildJsonObject {
            put("ua", JsonPrimitive(a.ua))
            put("xp", CanonicalJson.jsonNumber(a.xp))
            put("gold", CanonicalJson.jsonNumber(a.gold))
            put("mp", CanonicalJson.jsonNumber(a.mp))
        })
    }

    private fun taskAbsJson(cAbs: Map<String, com.example.data.local.entities.TaskAbsEntry>): JsonObject = buildJsonObject {
        for ((dev, a) in cAbs) put(dev, buildJsonObject {
            put("ua", JsonPrimitive(a.ua))
            put("cUp", JsonPrimitive(a.cUp))
            put("cDown", JsonPrimitive(a.cDown))
        })
    }

    /** `t.quickLog` wire value: false | true | 'down' | 'both' (app.js:2408-2414). */
    fun quickLogWire(mode: com.example.data.local.entities.QuickLogMode): JsonPrimitive = when (mode) {
        com.example.data.local.entities.QuickLogMode.OFF -> JsonPrimitive(false)
        com.example.data.local.entities.QuickLogMode.UP -> JsonPrimitive(true)
        com.example.data.local.entities.QuickLogMode.DOWN -> JsonPrimitive("down")
        com.example.data.local.entities.QuickLogMode.BOTH -> JsonPrimitive("both")
    }

    private fun remindersJson(list: List<com.example.data.local.entities.ReminderSpec>): JsonArray =
        Json.parseToJsonElement(converters.fromReminderSpecList(list) ?: "[]") as JsonArray

    private fun sameGranted(a: GrantedReward?, b: GrantedReward?): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.xp == b.xp && a.gold == b.gold && a.mp == b.mp && a.delta == b.delta

    /**
     * Parses a stored `extraJson` column's compact-JSON text back into a [JsonObject], or `null`
     * if [raw] is null/blank/unparsable-as-an-object (defensive: a corrupt/foreign value here
     * must degrade to "no known original", never throw and block export).
     */
    fun parseExtraJson(raw: String?): JsonObject? {
        if (raw.isNullOrEmpty()) return null
        return try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** Compact-JSON verbatim encode of [obj] for storage in an `extraJson` column, via
     * [CanonicalJson.canonicalJsonString] (not `Json.encodeToString`) so numeric literals are
     * echoed byte-for-byte rather than reformatted -- see `CanonicalJson.kt`'s class doc. */
    fun encodeExtraJson(obj: JsonObject): String = CanonicalJson.canonicalJsonString(obj)

    fun buildChecklistItemJson(cl: ChecklistItem): JsonObject {
        val original = parseExtraJson(cl.extraJson)
        val fresh = buildJsonObject {
            if (original == null || original.containsKey("id")) {
                put("id", JsonPrimitive(cl.id))
            }
            put("text", JsonPrimitive(cl.text))
            put("done", JsonPrimitive(cl.done))
            cl.touchedAt?.let { put("touchedAt", JsonPrimitive(it)) }
        }
        return overlayJson(original, fresh)
    }

    fun parseChecklistItem(obj: JsonObject): ChecklistItem {
        return ChecklistItem(
            id = (obj["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString(),
            text = (obj["text"] as? JsonPrimitive)?.content ?: "",
            done = (obj["done"] as? JsonPrimitive)?.booleanOrNull ?: false,
            touchedAt = (obj["touchedAt"] as? JsonPrimitive)?.longOrNull,
            extraJson = encodeExtraJson(obj)
        )
    }

    /**
     * Stage 3 / T3.6 -- opus-review finding #2 (Stage 3 Log, T3.1 review): CONDITIONAL emission
     * for `character`/`task`/`reward`/`view` fields that are Kotlin-modeled but have no normative
     * PWA position and are frequently (or, for `id`, ALWAYS) absent from a genuine PWA original.
     * Before this fix, every field below was put into `fresh` UNCONDITIONALLY; since none of them
     * exist in a real PWA `char` object (spec §1.2's `char` table only lists ten keys: `name`
     * through `updatedAt`), [overlayJson]'s "append fresh-only keys after" rule would APPEND all
     * four to every re-export even when the imported original never had them -- extra keys a
     * byte/deep-equal round-trip check must never introduce. The exact rule, applied identically
     * to every field in this comment block and mirrored in [buildTaskJson]/[buildRewardJson]/
     * [buildViewJson] below (see Stage 3 Log T3.6 entry for the full per-field table):
     *   - `original == null` (no known PWA original at all -- a character/task/etc. created fresh
     *     in the Kotlin app): ALWAYS emit, unchanged from pre-fix behavior for that case.
     *   - `original` known and it ALREADY HAS this key: ALWAYS emit the CURRENT live value (never
     *     omit a key the original had -- [overlayJson] must still overwrite it with fresh data).
     *   - `original` known and it LACKS this key: emit ONLY if the live value is genuinely
     *     non-default/populated (data that must not be lost); omit otherwise, so the key does not
     *     spuriously appear where the original never had it.
     */
    fun buildCharJson(character: CharacterEntity): JsonObject {
        // Key order follows spec §1.2's normative `char` order for the fields that exist in the
        // PWA schema; Kotlin/Room-only fields (id, needsDeathAcknowledge, lastCron, dayStart)
        // have no normative position and are appended after -- same rationale as the pre-T3.1
        // inline builders this replaces. Stage 3 / T3.6: all four are now CONDITIONALLY emitted
        // -- see the class-doc comment immediately above this function for the exact rule.
        val original = parseExtraJson(character.extraJson)
        val fresh = buildJsonObject {
            put("name", JsonPrimitive(character.name))
            put("face", JsonPrimitive(character.face))
            put("cls", JsonPrimitive(character.cls))
            put("lvl", JsonPrimitive(character.lvl))
            put("xp", JsonPrimitive(character.xp))
            put("hp", CanonicalJson.jsonNumber(character.hp))
            put("maxHp", CanonicalJson.jsonNumber(character.maxHp))
            put("mp", JsonPrimitive(character.mp))
            put("gold", CanonicalJson.jsonNumber(character.gold))
            put("updatedAt", JsonPrimitive(character.updatedAt))
            // id: Room primary key, always 1 (single-row table) -- never genuine PWA data, so its
            // "non-default" escape hatch (character.id != 1) can in practice never fire today, but
            // is kept for the same defensive reasoning as the other three fields.
            if (original == null || original.containsKey("id") || character.id != 1) {
                put("id", JsonPrimitive(character.id))
            }
            if (original == null || original.containsKey("needsDeathAcknowledge") || character.needsDeathAcknowledge) {
                put("needsDeathAcknowledge", JsonPrimitive(character.needsDeathAcknowledge))
            }
            if (original == null || original.containsKey("lastCron") || character.lastCron.isNotBlank()) {
                put("lastCron", lastCronToJson(character.lastCron))
            }
            if (original == null || original.containsKey("dayStart") || character.dayStart != 0) {
                put("dayStart", JsonPrimitive(character.dayStart))
            }
        }
        // WP-8.3: char.abs = {deviceId: {ua, xp, gold, mp}} (sync.js:1699-1712), typed since v7.
        val abs = character.abs
        val typed = mapOf(
            "abs" to keepOrTyped(original, "abs", abs?.let { charAbsJson(it) }) { o ->
                // WP-8.22-E review 43 F1: compare as Room stores it (non-finite -> 0), so an
                // unchanged original keeps its bytes both before and after a Room round trip.
                converters.toCharAbsMap(o.toString())?.let { converters.storableCharAbs(it) } ==
                    abs?.let { converters.storableCharAbs(it) }
            }
        )
        return applyTyped(overlayJson(original, fresh), typed)
    }

    /** Kotlin/Room-only char keys that Opti never has (PX-23, SY-15). They stay local. */
    val CHAR_LOCAL_ONLY_KEYS = setOf("id", "needsDeathAcknowledge", "lastCron", "dayStart")

    /**
     * WP-8.3 (PX-23): the `char` as it goes on the wire and into a backup: [buildCharJson] without
     * [CHAR_LOCAL_ONLY_KEYS], even when an older Kotlin build left them in `extraJson`.
     */
    fun buildCharWireJson(character: CharacterEntity): JsonObject =
        JsonObject(buildCharJson(character).filterKeys { it !in CHAR_LOCAL_ONLY_KEYS })

    fun parseCharacterEntity(obj: JsonObject): CharacterEntity {
        return CharacterEntity(
            id = 1,
            name = (obj["name"] as? JsonPrimitive)?.content ?: "Adventurer",
            face = (obj["face"] as? JsonPrimitive)?.content ?: "🧙",
            cls = (obj["cls"] as? JsonPrimitive)?.content ?: "Warrior",
            lvl = (obj["lvl"] as? JsonPrimitive)?.intOrNull ?: 1,
            xp = (obj["xp"] as? JsonPrimitive)?.intOrNull ?: 0,
            hp = (obj["hp"] as? JsonPrimitive)?.doubleOrNull ?: 50.0,
            maxHp = (obj["maxHp"] as? JsonPrimitive)?.doubleOrNull ?: 50.0,
            mp = (obj["mp"] as? JsonPrimitive)?.intOrNull ?: 0,
            gold = (obj["gold"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            needsDeathAcknowledge = (obj["needsDeathAcknowledge"] as? JsonPrimitive)?.booleanOrNull ?: false,
            lastCron = lastCronFromJson(obj["lastCron"]),
            dayStart = (obj["dayStart"] as? JsonPrimitive)?.intOrNull ?: 0,
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            extraJson = encodeExtraJson(obj),
            abs = obj["abs"]?.let { converters.toCharAbsMap(it.toString()) }
        )
    }

    /**
     * Stage 3 / T3.1 opus-review FIX 3: overlay-aware builder for a `GrantedReward` (task-level
     * `gr` or a `history[]` entry's `reward`), matching the same pattern as
     * [buildChecklistItemJson]/[buildTagJson]/etc. -- any field this data class doesn't model
     * (e.g. a future PWA reward-shape addition) survives via [gr]'s own `extraJson`.
     */
    fun buildGrantedRewardJson(gr: GrantedReward): JsonObject {
        val fresh = buildJsonObject {
            put("xp", JsonPrimitive(gr.xp))
            put("gold", CanonicalJson.jsonNumber(gr.gold))
            put("mp", JsonPrimitive(gr.mp))
            put("delta", CanonicalJson.jsonNumber(gr.delta))
        }
        return overlayJson(parseExtraJson(gr.extraJson), fresh)
    }

    /** Parse counterpart of [buildGrantedRewardJson]; stamps `extraJson` from [obj] itself. */
    fun parseGrantedReward(obj: JsonObject): GrantedReward {
        return GrantedReward(
            xp = (obj["xp"] as? JsonPrimitive)?.intOrNull ?: 0,
            gold = (obj["gold"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            mp = (obj["mp"] as? JsonPrimitive)?.intOrNull ?: 0,
            delta = (obj["delta"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            extraJson = encodeExtraJson(obj)
        )
    }

    /**
     * Stage 3 / T3.1 opus-review FIX 3: overlay-aware builder for a task `history[]` entry
     * (`TaskHistoryPoint`), matching the same pattern as every other per-record builder. Any
     * field this data class doesn't model (flagged as "Open risk #3" in the T3.1 stage log)
     * survives via [h]'s own `extraJson`; its nested `checklist`/`reward` route through
     * [buildChecklistItemJson]/[buildGrantedRewardJson] so THEIR extras travel forward too.
     */
    fun buildTaskHistoryPointJson(h: TaskHistoryPoint): JsonObject {
        val fresh = buildJsonObject {
            put("date", JsonPrimitive(h.date))
            h.value?.let { put("value", CanonicalJson.jsonNumber(it)) }
            h.scoredUp?.let { put("scoredUp", JsonPrimitive(it)) }
            h.scoredDown?.let { put("scoredDown", JsonPrimitive(it)) }
            h.completed?.let { put("completed", JsonPrimitive(it)) }
            h.checklist?.let { cl ->
                put("checklist", JsonArray(cl.map { buildChecklistItemJson(it) }))
            }
            h.reward?.let { gr -> put("reward", buildGrantedRewardJson(gr)) }
            h.reps?.let { put("reps", JsonPrimitive(it)) }
            h.repCounted?.let { put("repCounted", JsonPrimitive(it)) }
            h.scored?.let { put("scored", JsonPrimitive(it)) }
            h.isDue?.let { put("isDue", JsonPrimitive(it)) }
            h.repeat?.let { rp ->
                put("repeat", JsonArray(rp.map { JsonPrimitive(it) }))
            }
        }
        return overlayJson(parseExtraJson(h.extraJson), fresh)
    }

    /** Parse counterpart of [buildTaskHistoryPointJson]; stamps `extraJson` from [obj] itself. */
    fun parseTaskHistoryPoint(obj: JsonObject): TaskHistoryPoint {
        val hChecklist = (obj["checklist"] as? JsonArray)?.map { parseChecklistItem(it as JsonObject) }
        val hRewardObj = obj["reward"] as? JsonObject
        val hReward = hRewardObj?.let { parseGrantedReward(it) }
        val hRepeat = (obj["repeat"] as? JsonArray)?.map { (it as JsonPrimitive).boolean }

        return TaskHistoryPoint(
            date = (obj["date"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            value = (obj["value"] as? JsonPrimitive)?.doubleOrNull,
            scoredUp = (obj["scoredUp"] as? JsonPrimitive)?.intOrNull,
            scoredDown = (obj["scoredDown"] as? JsonPrimitive)?.intOrNull,
            completed = (obj["completed"] as? JsonPrimitive)?.booleanOrNull,
            checklist = hChecklist,
            reward = hReward,
            reps = (obj["reps"] as? JsonPrimitive)?.intOrNull,
            repCounted = (obj["repCounted"] as? JsonPrimitive)?.booleanOrNull,
            scored = (obj["scored"] as? JsonPrimitive)?.booleanOrNull,
            isDue = (obj["isDue"] as? JsonPrimitive)?.booleanOrNull,
            repeat = hRepeat,
            extraJson = encodeExtraJson(obj)
        )
    }

    fun buildTaskJson(t: TaskEntity): JsonObject {
        // Key order approximates spec §1.2's task tables restricted to the fields TaskEntity
        // actually models; see the pre-T3.1 inline builder this replaces for the full caveat
        // about per-task-type mutation-history-dependent order. `reminders`, `up`, `down` are NOT
        // built here at all -- they have no TaskEntity field, so they survive purely via
        // [overlayJson] passing through `t.extraJson`'s original value untouched.
        val original = parseExtraJson(t.extraJson)
        val fresh = buildJsonObject {
            put("id", JsonPrimitive(t.id))
            put("type", JsonPrimitive(t.type))
            put("title", JsonPrimitive(t.title))
            // WP-8.3: Opti tasks often have no notes/difficulty key; do not add a default one.
            if (original == null || original.containsKey("notes") || t.notes.isNotEmpty()) {
                put("notes", JsonPrimitive(t.notes))
            }
            if (original == null || original.containsKey("difficulty") || t.difficulty != "easy") {
                put("difficulty", JsonPrimitive(t.difficulty))
            }
            put("value", CanonicalJson.jsonNumber(t.value))
            put("done", JsonPrimitive(t.done))
            if (original == null || original.containsKey("checklist") || t.checklist.isNotEmpty()) {
                put("checklist", JsonArray(t.checklist.map { buildChecklistItemJson(it) }))
            }
            t.repeat?.let { rp ->
                put("repeat", JsonArray(rp.map { JsonPrimitive(it) }))
            }
            t.resetFreq?.let { put("resetFreq", JsonPrimitive(it)) }
            if (original == null || original.containsKey("tags") || t.tags.isNotEmpty()) {
                put("tags", JsonArray(t.tags.map { JsonPrimitive(it) }))
            }
            // Stage 3 / T3.6 (discovered via real_backup.json's ~44 legacy pre-HLC tasks, which
            // genuinely have no `createdAt` at all): `createdAt` is otherwise a normal, always-
            // present field, but `TaskEntity.createdAt` is a non-nullable `Long` that falls back
            // to 0 at PARSE time when the key is absent (WP-8.5, D18; it used to be the parse-time clock) -- unlike
            // `streak`/`history`/`cUp`/`cDown` below (opus-review finding #2), there is no
            // legitimate "genuinely populated new data" case for a synthesized creation
            // timestamp, so the rule here is simpler: omit only when there's a known original
            // that never had the key at all; otherwise (no known original, OR original already
            // had it) always emit -- see [buildCharJson]'s class doc for the general rule this
            // is a variant of.
            if (original == null || original.containsKey("createdAt")) {
                put("createdAt", JsonPrimitive(t.createdAt))
            }
            put("updatedAt", JsonPrimitive(t.updatedAt))
            // streak/history/cUp/cDown: opus-review finding #2 (Stage 3 Log, T3.1 review) --
            // conditional emission, see [buildCharJson]'s class doc for the exact rule.
            // streak null = absent (v7, D9): never written as null.
            val streak = t.streak
            if (streak != null && (original == null || original.containsKey("streak") || streak != 0)) {
                put("streak", JsonPrimitive(streak))
            }
            t.doneAt?.let { put("doneAt", JsonPrimitive(it)) }
            if (original == null || original.containsKey("history") || t.history.isNotEmpty()) {
                put("history", JsonArray(t.history.map { h -> buildTaskHistoryPointJson(h) }))
            }
            t.missedOn?.let { put("missedOn", JsonPrimitive(it)) }
            if (original == null || original.containsKey("cUp") || t.cUp != 0) {
                put("cUp", JsonPrimitive(t.cUp))
            }
            if (original == null || original.containsKey("cDown") || t.cDown != 0) {
                put("cDown", JsonPrimitive(t.cDown))
            }
            t.repsPerTap?.let { put("repsPerTap", JsonPrimitive(it)) }
        }
        // WP-8.3: the schema v7 typed columns (keep-or-typed rule above) and `_gr` (PX-24).
        val typed = LinkedHashMap<String, JsonElement?>()
        typed["up"] = keepOrTyped(original, "up", t.up?.let { JsonPrimitive(it) }) { (it as? JsonPrimitive)?.booleanOrNull == t.up }
        typed["down"] = keepOrTyped(original, "down", t.down?.let { JsonPrimitive(it) }) { (it as? JsonPrimitive)?.booleanOrNull == t.down }
        typed["quickLog"] = keepOrTyped(original, "quickLog", t.quickLog?.let { quickLogWire(it) }) {
            com.example.data.local.entities.QuickLogMode.fromWire(it) == t.quickLog
        }
        val remindersTyped = t.reminders?.let { remindersJson(it) }
        typed["reminders"] = keepOrTyped(original, "reminders", remindersTyped) { o ->
            if (remindersTyped == null) converters.toReminderSpecList(o.toString()) == null
            else StableJson.deepEqual(o, remindersTyped)
        }
        typed["completedAt"] = keepOrTyped(original, "completedAt", t.completedAt?.let { JsonPrimitive(it) }) { numberOf(it) == t.completedAt?.toDouble() }
        typed["doneDay"] = keepOrTyped(original, "doneDay", t.doneDay?.let { JsonPrimitive(it) }) { numberOf(it) == t.doneDay?.toDouble() }
        typed["cResetOn"] = keepOrTyped(original, "cResetOn", t.cResetOn?.let { JsonPrimitive(it) }) { numberOf(it) == t.cResetOn?.toDouble() }
        typed["cAbs"] = keepOrTyped(original, "cAbs", t.cAbs?.let { taskAbsJson(it) }) { converters.toTaskAbsMap(it.toString()) == t.cAbs }
        // CR-KT-015: `attachments` only when non-empty (writers omit the key when empty). An
        // original array that decodes to the live list is kept verbatim (byte-stable round trip);
        // otherwise the live list is written fresh, and an empty live list removes a stale key.
        // Items this app cannot use (bad sha) are never in the live list but are carried over
        // verbatim (AttachmentJson.wireValue): another client's data is never deleted here.
        typed[AttachmentJson.KEY] = keepOrTyped(
            original, AttachmentJson.KEY,
            AttachmentJson.wireValue(t.attachments, original?.get(AttachmentJson.KEY))
        ) { AttachmentJson.listFromJson(it) == t.attachments }
        // The task key is `_gr` (app.js:2308), never `gr`. Uncompleting sets `t._gr = null`
        // (app.js:2334), so a granted reward that is gone is written as null, not removed.
        typed["gr"] = null
        val originalGr = original?.get("_gr")
        typed["_gr"] = when {
            originalGr != null && sameGranted((originalGr as? JsonObject)?.let { parseGrantedReward(it) }, t.gr) -> originalGr
            t.gr != null -> buildGrantedRewardJson(t.gr)
            originalGr != null || original?.get("gr") is JsonObject -> JsonNull
            else -> null
        }
        // CR-KT-020 (schema v9): `_histCreated` -- the daily-only completion stamp. Opti unsets
        // the key on an un-tick (`delete t._histCreated`, app.js:2546/2551), so a null live value
        // removes it; a boolean original that still matches the live value is kept verbatim
        // (byte-stable round trip, key position preserved).
        typed["_histCreated"] = keepOrTyped(original, "_histCreated", t.histCreated?.let { JsonPrimitive(it) }) { (it as? JsonPrimitive)?.booleanOrNull == t.histCreated }
        return applyTyped(overlayJson(original, fresh), typed)
    }

    fun parseTaskEntity(obj: JsonObject): TaskEntity {
        val checklist = (obj["checklist"] as? JsonArray)?.map { parseChecklistItem(it as JsonObject) } ?: emptyList()

        // WP-8.3 (PX-24): Opti's key is `_gr`; `gr` is only read from files older Kotlin builds wrote.
        val grObj = obj["_gr"] as? JsonObject ?: (if (obj.containsKey("_gr")) null else obj["gr"] as? JsonObject)
        val gr = grObj?.let { parseGrantedReward(it) }

        val repeat = (obj["repeat"] as? JsonArray)?.map { (it as JsonPrimitive).boolean }
        val tagsForTask = (obj["tags"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

        val historyForTask = (obj["history"] as? JsonArray)?.map { hItem ->
            parseTaskHistoryPoint(hItem as JsonObject)
        } ?: emptyList()

        return TaskEntity(
            id = (obj["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString(),
            type = (obj["type"] as? JsonPrimitive)?.content ?: "todo",
            title = (obj["title"] as? JsonPrimitive)?.content ?: "Untitled",
            notes = (obj["notes"] as? JsonPrimitive)?.content ?: "",
            difficulty = (obj["difficulty"] as? JsonPrimitive)?.content ?: "easy",
            value = (obj["value"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            done = (obj["done"] as? JsonPrimitive)?.booleanOrNull ?: false,
            // v7 (D9): null = absent.
            streak = if (obj.containsKey("streak")) (obj["streak"] as? JsonPrimitive)?.intOrNull ?: 0 else null,
            repeat = repeat,
            checklist = checklist,
            tags = tagsForTask,
            history = historyForTask,
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            doneAt = (obj["doneAt"] as? JsonPrimitive)?.longOrNull,
            missedOn = (obj["missedOn"] as? JsonPrimitive)?.intOrNull ?: (obj["missedOn"] as? JsonPrimitive)?.content?.toIntOrNull(),
            cUp = (obj["cUp"] as? JsonPrimitive)?.intOrNull ?: 0,
            cDown = (obj["cDown"] as? JsonPrimitive)?.intOrNull ?: 0,
            resetFreq = (obj["resetFreq"] as? JsonPrimitive)?.contentOrNull,
            repsPerTap = (obj["repsPerTap"] as? JsonPrimitive)?.intOrNull,
            gr = gr,
            // CR-KT-020 (schema v9): null = the `_histCreated` key is absent.
            histCreated = (obj["_histCreated"] as? JsonPrimitive)?.booleanOrNull,
            extraJson = encodeExtraJson(obj),
            // WP-8.3: schema v7 typed columns; null = the key is absent.
            up = (obj["up"] as? JsonPrimitive)?.booleanOrNull,
            down = (obj["down"] as? JsonPrimitive)?.booleanOrNull,
            quickLog = if (obj.containsKey("quickLog")) com.example.data.local.entities.QuickLogMode.fromWire(obj["quickLog"]) else null,
            reminders = obj["reminders"]?.let { converters.toReminderSpecList(it.toString()) },
            completedAt = obj["completedAt"]?.let { numberOf(it) }?.toLong(),
            doneDay = obj["doneDay"]?.let { numberOf(it) }?.toInt(),
            cResetOn = obj["cResetOn"]?.let { numberOf(it) }?.toInt(),
            cAbs = obj["cAbs"]?.let { converters.toTaskAbsMap(it.toString()) },
            // CR-KT-015: missing key or [] = no images.
            attachments = AttachmentJson.listFromJson(obj[AttachmentJson.KEY])
        )
    }

    fun buildRewardJson(r: RewardEntity): JsonObject {
        val original = parseExtraJson(r.extraJson)
        val fresh = buildJsonObject {
            put("id", JsonPrimitive(r.id))
            put("title", JsonPrimitive(r.title))
            put("cost", CanonicalJson.jsonNumber(r.cost))
            put("notes", JsonPrimitive(r.notes))
            // Stage 3 / T3.6 (discovered via real_backup.json, which has a genuine reward with no
            // `createdAt` key at all): same "no known original ever had it" omission rule as
            // [buildTaskJson]'s `createdAt` -- see that comment / [buildCharJson]'s class doc.
            if (original == null || original.containsKey("createdAt")) {
                put("createdAt", JsonPrimitive(r.createdAt))
            }
            put("updatedAt", JsonPrimitive(r.updatedAt))
        }
        return overlayJson(original, fresh)
    }

    fun parseRewardEntity(obj: JsonObject): RewardEntity {
        return RewardEntity(
            id = (obj["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString(),
            title = (obj["title"] as? JsonPrimitive)?.content ?: "Untitled",
            // [DEFERRED_OPEN_QUESTION] review 45 finding 1: an infinite cost goes on the wire as
            // `"cost":null` (as Opti's JSON.stringify). Opti then shows "null gold" and buys it for
            // Number(null)=0; this read falls back to 10. Kept as is until cost can hold null.
            cost = (obj["cost"] as? JsonPrimitive)?.doubleOrNull ?: 10.0,
            notes = (obj["notes"] as? JsonPrimitive)?.content ?: "",
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            extraJson = encodeExtraJson(obj)
        )
    }

    fun buildTagJson(t: TagEntity): JsonObject {
        val fresh = buildJsonObject {
            put("id", JsonPrimitive(t.id))
            put("name", JsonPrimitive(t.name))
            put("color", JsonPrimitive(t.color))
            put("createdAt", JsonPrimitive(t.createdAt))
            put("updatedAt", JsonPrimitive(t.updatedAt))
        }
        return overlayJson(parseExtraJson(t.extraJson), fresh)
    }

    fun parseTagEntity(obj: JsonObject): TagEntity {
        return TagEntity(
            id = (obj["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString(),
            name = (obj["name"] as? JsonPrimitive)?.content ?: "",
            color = (obj["color"] as? JsonPrimitive)?.content ?: "",
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            extraJson = encodeExtraJson(obj)
        )
    }

    fun buildDeviceJson(d: DeviceEntity): JsonObject {
        val fresh = buildJsonObject {
            put("id", JsonPrimitive(d.id))
            put("name", JsonPrimitive(d.name))
            put("updatedAt", JsonPrimitive(d.updatedAt))
        }
        return overlayJson(parseExtraJson(d.extraJson), fresh)
    }

    fun parseDeviceEntity(obj: JsonObject): DeviceEntity {
        return DeviceEntity(
            id = (obj["id"] as? JsonPrimitive)?.content ?: "",
            name = (obj["name"] as? JsonPrimitive)?.content ?: "",
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            extraJson = encodeExtraJson(obj)
        )
    }

    /**
     * Stage 3 / T3.1 opus-review FIX 3: overlay-aware builder for a `charHistory[]` entry
     * (`CharHistoryEntity`), matching the same pattern as [buildTagJson]/[buildDeviceJson]/etc.
     * Requires `CharHistoryEntity.extraJson` (Room column added by `MIGRATION_4_5`).
     */
    fun buildCharHistoryJson(ch: CharHistoryEntity): JsonObject {
        val fresh = buildJsonObject {
            put("date", JsonPrimitive(ch.date))
            put("hp", CanonicalJson.jsonNumber(ch.hp))
            put("maxHp", CanonicalJson.jsonNumber(ch.maxHp))
            put("xp", JsonPrimitive(ch.xp))
            put("mp", JsonPrimitive(ch.mp))
            put("gold", CanonicalJson.jsonNumber(ch.gold))
            put("lvl", JsonPrimitive(ch.lvl))
        }
        return overlayJson(parseExtraJson(ch.extraJson), fresh)
    }

    /** Parse counterpart of [buildCharHistoryJson]; stamps `extraJson` from [obj] itself. */
    fun parseCharHistoryEntity(obj: JsonObject): CharHistoryEntity {
        return CharHistoryEntity(
            date = (obj["date"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            hp = (obj["hp"] as? JsonPrimitive)?.doubleOrNull ?: 50.0,
            maxHp = (obj["maxHp"] as? JsonPrimitive)?.doubleOrNull ?: 50.0,
            xp = (obj["xp"] as? JsonPrimitive)?.intOrNull ?: 0,
            mp = (obj["mp"] as? JsonPrimitive)?.intOrNull ?: 0,
            gold = (obj["gold"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            lvl = (obj["lvl"] as? JsonPrimitive)?.intOrNull ?: 1,
            extraJson = encodeExtraJson(obj)
        )
    }

    // -------------------------------------------------------------------------------------------
    // Stage 3 / T3.2 -- `an.views` (spec §3 "an.views / an.metrics shape", extracted Stage 3
    // T3.2a). Overlay-aware builder/parser pair, same pattern as every other per-record entity
    // above. See CustomViewEntity's class doc for why this table also holds an unrelated
    // Kotlin-native "quick view" feature (`title`/`chartType`) -- [buildViewJson]'s CALLERS (not
    // this function itself) are responsible for filtering to `source != null` rows before
    // building the `an.views` JSON array, since a native-only row has no `source`/`name` at all
    // and would otherwise be built into a bogus, effectively-empty PWA view object.
    // -------------------------------------------------------------------------------------------

    /**
     * Builds one PWA-shaped `an.views[]` entry (spec §3: `id, name, source, group, chart, tags,
     * types, metricId, createdAt, updatedAt`) from a [CustomViewEntity], overlaying onto
     * `extraJson` (the last-seen original view object, if any) so a future/legacy PWA view key
     * this table doesn't model round-trips losslessly. Caller must ensure [v] is a genuinely
     * PWA-synced row (`v.source != null`) -- see class-level note above.
     */
    fun buildViewJson(v: CustomViewEntity): JsonObject {
        val original = parseExtraJson(v.extraJson)
        val fresh = buildJsonObject {
            put("id", JsonPrimitive(v.id))
            put("name", JsonPrimitive(v.name ?: v.title))
            put("source", JsonPrimitive(v.source ?: ""))
            put("group", JsonPrimitive(v.group ?: ""))
            put("chart", JsonPrimitive(v.chart ?: ""))
            put("tags", JsonArray(v.tags.map { JsonPrimitive(it) }))
            put("types", JsonArray(v.types.map { JsonPrimitive(it) }))
            // Stage 3 / T3.6 (discovered via real_backup.json, which has real `source=='metric'`
            // views with NO `metricId` key at all, as opposed to golden's synthetic view, which
            // has an EXPLICIT `metricId: null`): `CustomViewEntity.metricId` can't distinguish
            // "explicitly null" from "key absent" on its own (both parse to Kotlin `null`), so the
            // conditional-emission check here doubles as the disambiguator -- emit (as an explicit
            // JSON null, preserving golden's own shape) whenever the original HAD the key at all
            // (whatever its value), or when there's no known original (fresh-in-Kotlin row); omit
            // only when a known original never had the key AND there's no real id to report.
            if (original == null || original.containsKey("metricId") || v.metricId != null) {
                put("metricId", v.metricId?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            // createdAt: same "no known original ever had it" omission rule as [buildTaskJson]/
            // [buildRewardJson]'s `createdAt` (real_backup.json also has real metric-sourced views
            // with no `createdAt` at all -- see Stage 3 Log T3.6 entry).
            if (original == null || original.containsKey("createdAt")) {
                put("createdAt", JsonPrimitive(v.createdAt))
            }
            put("updatedAt", JsonPrimitive(v.updatedAt ?: v.createdAt))
        }
        return overlayJson(original, fresh)
    }

    /**
     * Parse counterpart of [buildViewJson]; stamps `extraJson` from [obj] itself. Always produces
     * a row with `source` non-null (defaulted to `""` only if the source object is itself
     * malformed/missing the field) so every imported/merged view is recognized as PWA-shaped by
     * the `source != null` marker described in [buildViewJson]'s doc, even in that malformed
     * case -- an imported view must never be silently mistaken for a native-only quick-view row.
     * `title`/`chartType` (the pre-existing native-feature fields) are best-effort mirrored from
     * `name`/`source` so this row still renders *something* recognizable in the pre-existing
     * "quick view" UI list (see CustomViewEntity's class doc for the known display-fidelity gap
     * this does NOT fully close).
     */
    fun parseViewEntity(obj: JsonObject): CustomViewEntity {
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: UUID.randomUUID().toString()
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val source = (obj["source"] as? JsonPrimitive)?.contentOrNull ?: ""
        return CustomViewEntity(
            id = id,
            title = name,
            chartType = source,
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, // WP-8.5 (D18): a parse fallback never invents a time
            name = name,
            source = source,
            group = (obj["group"] as? JsonPrimitive)?.contentOrNull ?: "",
            chart = (obj["chart"] as? JsonPrimitive)?.contentOrNull ?: "",
            tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            types = (obj["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            metricId = (obj["metricId"] as? JsonPrimitive)?.contentOrNull,
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull,
            extraJson = encodeExtraJson(obj)
        )
    }

    /**
     * Stage 3 / T3.2 -- opaque `an.metrics[]` codec (see `MetricsExtrasEntity`'s class doc for why
     * metrics have no per-field Kotlin modeling). [parseJsonArray] tolerantly decodes a stored
     * compact-JSON array back (empty array on null/blank/unparsable, defensive like
     * [parseExtraJson]); [encodeJsonArray] re-encodes verbatim via [CanonicalJson] (not
     * `Json.encodeToString`) so numeric literals in metric objects (`createdAt`/`updatedAt`) are
     * echoed byte-for-byte, same rationale as [encodeExtraJson].
     */
    fun parseJsonArray(raw: String?): JsonArray {
        if (raw.isNullOrEmpty()) return JsonArray(emptyList())
        return try {
            Json.parseToJsonElement(raw) as? JsonArray ?: JsonArray(emptyList())
        } catch (e: Exception) {
            JsonArray(emptyList())
        }
    }

    fun encodeJsonArray(arr: JsonArray): String = CanonicalJson.canonicalJsonString(arr)

    // Detokenizes fully and parses into entities
    /** One detokenized backup event -> [EventLogEntity] (the importBackup mapping, shared by WP-8.9's section import). */
    fun parseEventEntity(obj: JsonObject): EventLogEntity {
        val timestamp = (obj["ts"] as? JsonPrimitive)?.longOrNull
            ?: (obj["timestamp"] as? JsonPrimitive)?.longOrNull
            ?: 0L // WP-8.5 (D18): a parse fallback never invents a time
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "info"
        val taskType = (obj["taskType"] as? JsonPrimitive)?.contentOrNull
        val taskId = (obj["taskId"] as? JsonPrimitive)?.contentOrNull
        val taskTitle = (obj["taskTitle"] as? JsonPrimitive)?.contentOrNull
        val streak = (obj["streak"] as? JsonPrimitive)?.intOrNull
        val damage = (obj["dmg"] as? JsonPrimitive)?.doubleOrNull ?: (obj["damage"] as? JsonPrimitive)?.doubleOrNull
        val direction = (obj["dir"] as? JsonPrimitive)?.intOrNull ?: (obj["direction"] as? JsonPrimitive)?.intOrNull
        val reps = (obj["reps"] as? JsonPrimitive)?.intOrNull
        val value = (obj["value"] as? JsonPrimitive)?.doubleOrNull
        val cost = (obj["cost"] as? JsonPrimitive)?.doubleOrNull
        // WP-5.4 (spec §1.2 "Event object" base order `ts -> uid -> dev -> [kind-specific]`;
        // §2 line 206: `uid`/`dev` are exempt from EXPORT_FIELD_MAP tokenization, so they
        // survive detokenization under their literal names here). Previously these two typed
        // columns -- plus `synthetic` -- were never extracted from `obj`, so every imported
        // event silently lost its true origin device identity (fell into `extraFields`/
        // `payloadJson` only, opaque to `EventSyncEngine.evtUploadable`/`evtOwnMonthRecords`
        // and `EventLogDao.getUidsInRangeSync`, all of which read the TYPED columns).
        val uid = (obj["uid"] as? JsonPrimitive)?.contentOrNull
        val dev = (obj["dev"] as? JsonPrimitive)?.contentOrNull
        // `synthetic` is one of the four booleans coerced to 1/0 in tokenized form (spec §2
        // line 206); a raw/legacy/schema-1 file may still carry it as a genuine JSON boolean,
        // so accept both shapes here (mirrors EventSyncEngine.evtIncomingFilter's identical
        // coercion).
        val synthetic = (obj["synthetic"] as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: (p.intOrNull == 1) } ?: false

        val knownKeys = setOf(
            "ts", "timestamp", "kind", "taskType", "taskId", "taskTitle",
            "streak", "dmg", "damage", "dir", "direction", "reps", "value", "cost",
            "uid", "dev", "synthetic"
        )
        // Stage 3 / T3.6 -- defensive fallback found while authoring the lossless round-trip
        // test: a "known" numeric event field whose RAW key is present but whose typed
        // extraction above came back null (a shape mismatch -- the app.js call sites always
        // emit these as numbers, but `EventLogEntity` has no `extraJson` column of its own to
        // fall back on the way per-record entities do, so a wrongly-shaped value would
        // otherwise vanish silently: excluded from `extraFields` below because the key IS
        // "known", yet never re-emitted by the typed `e.xxx?.let{}` path in `exportBackup`
        // either, since the typed field is null). Treat any such key exactly like an
        // unmodeled/opaque field -- preserved verbatim via `payloadJson` -- instead of losing
        // it. Guarded on "key present AND typed value null" so a genuinely-ABSENT field isn't
        // spuriously synthesized into payloadJson.
        val typeMismatchedKnownKeys = buildSet {
            if (obj.containsKey("streak") && streak == null) add("streak")
            if (obj.containsKey("dmg") && damage == null) add("dmg")
            if (obj.containsKey("damage") && damage == null) add("damage")
            if (obj.containsKey("dir") && direction == null) add("dir")
            if (obj.containsKey("direction") && direction == null) add("direction")
            if (obj.containsKey("reps") && reps == null) add("reps")
            if (obj.containsKey("value") && value == null) add("value")
            if (obj.containsKey("cost") && cost == null) add("cost")
            // P15 (KT-57): the typed column holds only "true or not", so a present
            // `synthetic:false` / `synthetic:null` would be lost. Opti keeps the key it holds
            // (app.js:7124 detokenize, 1445-1461 owned-event import) and writes `sy` for it
            // (app.js:7069), so it is kept verbatim in `payloadJson`. An absent key adds nothing.
            if (obj.containsKey("synthetic") && !synthetic) add("synthetic")
        }
        val extraFields = obj.filterKeys { !knownKeys.contains(it) || it in typeMismatchedKnownKeys }
        // CanonicalJson instead of Json.encodeToString (Stage 2 / T2.2 defensive fix): this
        // blob is later parsed back out and re-emitted verbatim by exportBackup's eventsJson
        // builder, so if any "extra" event field is ever a Double, Json.encodeToString would
        // silently reformat it via Kotlin's native (non-JS) Double.toString when persisting
        // it here, corrupting whatever original literal it round-trips out as later.
        val payloadJson = if (extraFields.isNotEmpty()) {
            CanonicalJson.canonicalJsonString(JsonObject(extraFields))
        } else {
            null
        }

        return EventLogEntity(
            timestamp = timestamp,
            uid = uid,
            dev = dev,
            kind = kind,
            taskType = taskType,
            taskId = taskId,
            taskTitle = taskTitle,
            streak = streak,
            damage = damage,
            direction = direction,
            reps = reps,
            value = value,
            cost = cost,
            payloadJson = payloadJson,
            synthetic = synthetic
        )
    }

    /** WP-8.9: a decoded backup file: the detokenized `S`, its events and `_backup` (null for schema 1). */
    data class DecodedBackup(val snapshot: JsonObject, val events: List<JsonObject>, val meta: JsonObject?, val isSchema2: Boolean)

    /** Opti `_hashFallback32(str)` (app.js:1599-1603): a 32-bit JS string hash over UTF-16 units. */
    fun hashFallback32(str: String): String {
        var h = 0
        for (ch in str) { h = ((h shl 5) - h) + ch.code }
        val abs = kotlin.math.abs(h.toLong())
        return "fallback-" + java.lang.Long.toHexString(abs).padStart(8, '0')
    }

    /**
     * WP-8.9: Opti `importData` decode (app.js:8175-8225). Schema 2 is detokenized (an unknown
     * event code throws [UnknownFieldCodeException], PX-62) and its hash checked with Opti's
     * policy (Q-04): the algorithm is `_backup.hashAlgo`, else `fallback32` for a `fallback-`
     * hash, else `sha256`; a mismatch throws, an unknown algorithm imports without the check
     * (Opti's `.catch(() => doImport())`). A schema-1 file is used as it is.
     */
    fun decodeBackup(parsed: JsonObject): DecodedBackup {
        val backupMeta = parsed["_backup"] as? JsonObject
        val isSchema2 = backupMeta != null && (backupMeta["schema"] as? JsonPrimitive)?.intOrNull == 2
        if (!isSchema2) {
            val eventsArr = (parsed["events"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            return DecodedBackup(parsed, eventsArr, backupMeta, false)
        }
        val detokEvents = detokenizeEvents(parsed)
        val fm = (parsed["FM"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
        val rmap = fm.entries.associate { it.value to it.key }
        val snap = parsed["S"] as? JsonObject ?: buildJsonObject {}
        val detokSnap = detokenizeDeep(snap, rmap) as JsonObject
        val expectedHash = (backupMeta!!["hash"] as? JsonPrimitive)?.contentOrNull
        if (!expectedHash.isNullOrEmpty()) {
            val algo = (backupMeta["hashAlgo"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: if (FALLBACK_HASH_PREFIX_REGEX.containsMatchIn(expectedHash)) "fallback32" else "sha256"
            val hashless = JsonObject(backupMeta.filterKeys { it != "hash" })
            // Routed through CanonicalJson (Stage 2 / T2.2): the parsed literals are echoed verbatim.
            val preimage = CanonicalJson.buildHashPreimage(detokSnap, detokEvents, hashless)
            val check = when (algo) {
                "sha256" -> computeSha256(preimage)
                "fallback32" -> hashFallback32(preimage)
                else -> null // QUESTA_HASH_ALGO_UNKNOWN: Opti imports without the integrity gate
            }
            if (check != null && check != expectedHash) {
                throw IllegalArgumentException("backup integrity verification failed: hash mismatch")
            }
        }
        return DecodedBackup(detokSnap, detokEvents, backupMeta, true)
    }

    // Detokenizes fully and parses into entities
    fun importBackup(jsonString: String): BackupData {
        val parsed = Json.parseToJsonElement(jsonString) as JsonObject
        val decoded = decodeBackup(parsed)
        val detokEvents: List<JsonObject> = decoded.events
        val detokSnap: JsonObject = decoded.snapshot

        // Now map detokSnap properties to entities. Stage 3 / T3.1: routed through the shared
        // parse*Entity() helpers (defined above importBackup) instead of duplicating per-field
        // extraction inline -- each helper also stamps `extraJson` with the verbatim original
        // object, which is what makes exportBackup's overlay-based re-emission possible.
        val charObj = detokSnap["char"] as? JsonObject ?: buildJsonObject {}
        val character = parseCharacterEntity(charObj)

        val tasksList = detokSnap["tasks"] as? JsonArray ?: jsonArrayOf()
        val tasks = tasksList.map { item -> parseTaskEntity(item as JsonObject) }

        val rewardsList = detokSnap["rewards"] as? JsonArray ?: jsonArrayOf()
        val rewards = rewardsList.map { item -> parseRewardEntity(item as JsonObject) }

        val deletionsList = detokSnap["deletions"] as? JsonArray ?: jsonArrayOf()
        val deletions = deletionsList.map { item ->
            val obj = item as JsonObject
            val id = (obj["id"] as? JsonPrimitive)?.content ?: ""
            val at = (obj["at"] as? JsonPrimitive)?.longOrNull ?: 0L
            id to at
        }.filter { it.first.isNotEmpty() }

        val lastCron = lastCronFromJson(detokSnap["lastCron"])

        // Lossless passthrough collections
        val tagsList = detokSnap["tags"] as? JsonArray ?: jsonArrayOf()
        val tags = tagsList.map { item -> parseTagEntity(item as JsonObject) }

        val devicesList = detokSnap["devices"] as? JsonArray ?: jsonArrayOf()
        val devices = devicesList.map { item -> parseDeviceEntity(item as JsonObject) }

        val charHistoryList = detokSnap["charHistory"] as? JsonArray ?: jsonArrayOf()
        val charHistory = charHistoryList.map { item -> parseCharHistoryEntity(item as JsonObject) }

        val monthlyBackups = (detokSnap["monthlyBackups"] as? JsonArray)?.toList() ?: emptyList()
        val globalHistory = (detokSnap["history"] as? JsonArray)?.toList() ?: emptyList()

        // Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape"): in the BACKUP shape, `an` lives
        // nested under `S.prefs.an` (spec §1.2 note on `prefs`: "not synced except an.{views,
        // metrics}"), NOT top-level -- that top-level lift is a `syncSubset()`-only (Dropbox
        // state.json) transformation (spec §3's syncSubset table), applied instead in
        // SyncManager's buildLocalSnapshot/mergeSnapshots/applyMergedSnapshotToDatabase.
        val anObj = (detokSnap["prefs"] as? JsonObject)?.get("an") as? JsonObject
        val viewsList = anObj?.get("views") as? JsonArray ?: jsonArrayOf()
        val views = viewsList.mapNotNull { (it as? JsonObject)?.let { o -> parseViewEntity(o) } }
        val metricsList = anObj?.get("metrics") as? JsonArray ?: jsonArrayOf()
        val metrics = metricsList.mapNotNull { it as? JsonObject }

        val events = detokEvents.map { parseEventEntity(it) }

        return BackupData(
            character = character,
            tasks = tasks,
            rewards = rewards,
            events = events,
            deletions = deletions,
            lastCron = lastCron,
            tags = tags,
            devices = devices,
            charHistory = charHistory,
            monthlyBackups = monthlyBackups,
            globalHistory = globalHistory,
            views = views,
            metrics = metrics,
            rawSnapshot = detokSnap
        )
    }

    /**
     * WP-8.3: one event record as it goes into a backup (`events[]`, detokenized form). Order:
     * `ts`, `uid`, `dev`, `kind`, the typed fields, then the opaque `payloadJson` fields (a
     * payload key that is already present keeps its position), and finally `id` (the local store
     * key, like Opti's IndexedDB keyPath `id`; Opti's importData deletes it, app.js:1545) --
     * review finding #2: real Opti exports never re-key an event object, and Opti's own event
     * objects always assign `id` last (fixtures/real_backup.json: `...,uid,imported,id`), so `id`
     * must be written last here too. A payload `id` (e.g. an imported Opti id carried verbatim in
     * `payloadJson`) takes precedence over the local Room id, same value-precedence as before --
     * only the POSITION changed.
     */
    fun buildEventJson(e: EventLogEntity): JsonObject = buildJsonObject {
        put("ts", JsonPrimitive(e.timestamp))
        e.uid?.let { put("uid", JsonPrimitive(it)) }
        e.dev?.let { put("dev", JsonPrimitive(it)) }
        put("kind", JsonPrimitive(e.kind))
        if (e.synthetic) put("synthetic", JsonPrimitive(true))
        e.taskType?.let { put("taskType", JsonPrimitive(it)) }
        e.taskId?.let { put("taskId", JsonPrimitive(it)) }
        e.taskTitle?.let { put("taskTitle", JsonPrimitive(it)) }
        e.streak?.let { put("streak", JsonPrimitive(it)) }
        e.damage?.let { put("dmg", CanonicalJson.jsonNumber(it)) }
        e.direction?.let { put("dir", JsonPrimitive(it)) }
        e.reps?.let { put("reps", JsonPrimitive(it)) }
        e.value?.let { put("value", CanonicalJson.jsonNumber(it)) }
        e.cost?.let { put("cost", CanonicalJson.jsonNumber(it)) }
        var payloadId: JsonElement? = null
        e.payloadJson?.let { payload ->
            try {
                (Json.parseToJsonElement(payload) as? JsonObject)?.forEach { (key, value) ->
                    if (key == "id") payloadId = value else put(key, value)
                }
            } catch (_: Exception) {}
        }
        val idValue = payloadId ?: if (e.id > 0) JsonPrimitive(e.id) else null
        idValue?.let { put("id", it) }
    }

    /**
     * WP-8.3: Opti `_buildFieldMap(snap)` (app.js:7138-7152): key frequency over the whole
     * snapshot, keys in JS `Object.keys` order, stable-sorted by descending count, coded a, b, ...
     */
    fun snapshotFieldMap(snap: JsonElement): Map<String, String> {
        val freq = LinkedHashMap<String, Int>()
        buildFieldMap(snap, freq)
        return CanonicalJson.jsKeyOrder(freq.keys)
            .sortedByDescending { freq.getValue(it) }
            .associateWithIndexed { index -> generateCode(index) }
    }

    // Encodes live entities to detokenized, then tokenized Schema-2 JSON backup
    fun exportBackup(
        character: CharacterEntity,
        tasks: List<TaskEntity>,
        rewards: List<RewardEntity>,
        events: List<EventLogEntity>,
        deletions: List<Pair<String, Long>>,
        lastCron: String?,
        appVersion: String = "v1.0-android",
        tags: List<TagEntity> = emptyList(),
        devices: List<DeviceEntity> = emptyList(),
        charHistory: List<CharHistoryEntity> = emptyList(),
        monthlyBackups: List<JsonElement> = emptyList(),
        globalHistory: List<JsonElement> = emptyList(),
        // Stage 3 / T3.2 (spec §3 "an.views / an.metrics shape"): live view/metric collections.
        // [views] may contain BOTH PWA-synced rows (`source != null`) and native Kotlin-only
        // "quick view" rows (`source == null`, see CustomViewEntity's class doc) -- only the
        // former are built into the emitted `an.views` array (filtered below); the latter are
        // simply not part of the PWA-compatible wire shape. [metrics] stay raw `JsonObject`s
        // (opaque passthrough, no Kotlin feature models metric fields -- see
        // MetricsExtrasEntity's class doc) and are re-emitted verbatim.
        views: List<CustomViewEntity> = emptyList(),
        metrics: List<JsonObject> = emptyList(),
        // Stage 3 / T3.1: verbatim copy of the last-known original top-level backup `S` object
        // (`BackupData.rawSnapshot` from a prior `importBackup`, persisted by the caller -- see
        // `SnapshotExtrasEntity`). Used only to overlay unmodeled top-level fields (`prefs` in
        // full, `__futureField`-style unknowns, `__seq`/`__hlcLast`/`__savedAt`) back into their
        // original position; every key this function itself builds (`char`, `tasks`, `rewards`,
        // ...) always reflects CURRENT live data regardless of what's in here. Null when there is
        // no known original (fresh Kotlin-only install) -- falls back to today's normative-order
        // behavior unchanged.
        snapshotExtras: JsonObject? = null,
        hapticsEnabled: Boolean = true,
        // WP-8.5 (D18): the caller's physical-clock read for the backup file stamp.
        nowPhysical: Long,
    ): String {
        // WP-8.3: this legacy entry point now only maps its arguments onto [FullLocalState] and
        // delegates to [buildBackupFile], so every export has Opti's bytes: `_backup.hashAlgo`
        // written before the hash and inside the pre-image (PX-27), `_gr` (PX-24), no Kotlin-only
        // `char` keys (PX-23), JS number/key/surrogate rules (PX-22).
        //
        // `history` and `monthlyBackups` have no live Room source on this path; when the caller
        // passes none, the values from the last imported `S` ([snapshotExtras]) are kept.
        val originalPrefs = snapshotExtras?.get("prefs") as? JsonObject
        val prefs = overlayJson(originalPrefs, buildJsonObject { put("haptics", JsonPrimitive(hapticsEnabled)) })
        val history = if (globalHistory.isEmpty()) snapshotExtras?.get("history") as? JsonArray else null
        val monthly = if (monthlyBackups.isEmpty()) snapshotExtras?.get("monthlyBackups") as? JsonArray else null
        val wire = LocalWireState(
            char = buildCharWireJson(character),
            tasks = JsonArray(tasks.map { buildTaskJson(it) }),
            rewards = JsonArray(rewards.map { buildRewardJson(it) }),
            tags = JsonArray(tags.map { buildTagJson(it) }),
            devices = JsonArray(devices.map { buildDeviceJson(it) }),
            lastCron = lastCron?.trim()?.toIntOrNull() ?: 0,
            history = history ?: JsonArray(globalHistory),
            charHistory = JsonArray(charHistory.map { buildCharHistoryJson(it) }),
            anViews = JsonArray(views.filter { it.source != null }.map { buildViewJson(it) }),
            anMetrics = JsonArray(metrics),
            pause = LocalWireState.pauseWire(null),
            monthlyBackups = monthly ?: JsonArray(monthlyBackups),
            deletions = JsonArray(deletions.map { d ->
                buildJsonObject {
                    put("id", JsonPrimitive(d.first))
                    put("at", JsonPrimitive(d.second))
                }
            }),
            // This legacy entry point has no SyncPrefsEntity source at all (no such parameter);
            // `prefs` above already carries whatever pause keys the last import/snapshotExtras had,
            // which toSnapshot()'s overlay preserves as-is when pauseRowExists is false.
            pauseRowExists = false
        )
        val state = FullLocalState(wire = wire, prefs = prefs, snapshotExtras = snapshotExtras)
        val env = buildBackupFile(
            state = state,
            events = events,
            sections = null,
            partial = false,
            nowPhysical = nowPhysical,
            deviceId = "",
            appVersion = appVersion
        )
        return CanonicalJson.stringifyPretty(env)
    }

    private inline fun <T, V> Iterable<T>.associateWithIndexed(valueSelector: (Int) -> V): Map<T, V> {
        val result = LinkedHashMap<T, V>()
        var index = 0
        for (element in this) {
            result[element] = valueSelector(index++)
        }
        return result
    }


    private fun jsonArrayOf() = JsonArray(emptyList())
}

data class TokenizedEventsResult(
    val E: List<JsonObject>,
    val K: List<String>,
    val SRC: List<String>,
    val TID: List<String>,
    val TT: List<String>
)

/** WP-8.9 (PX-62): Opti `QUESTA_UNKNOWN_FIELD_CODE` -- the file uses an event field code this build does not know. */
class UnknownFieldCodeException(val fieldCode: String) :
    IllegalArgumentException("Unknown export field code \"$fieldCode\"")
