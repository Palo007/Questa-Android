package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.attachments.Attachment
import com.example.data.attachments.AttachmentJson
import com.example.data.local.entities.CharAbsEntry
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.GrantedReward
import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.ReminderSpec
import com.example.data.local.entities.TaskAbsEntry
import com.example.data.local.entities.TaskHistoryPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class Converters {
    // review r1 major 6: `coerceInputValues = true` so a decode that hits a null/wrong-shape
    // value for a field with a Kotlin default (e.g. a malformed ReminderSpec.lastFiredKey) falls
    // back to that default instead of throwing and losing the whole surrounding list/object.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // CR-KT-015: task attachments, stored in the wire item shape (AttachmentJson).
    @TypeConverter
    fun fromAttachments(value: List<Attachment>?): String =
        AttachmentJson.listToJson(value ?: emptyList()).toString()

    @TypeConverter
    fun toAttachments(value: String?): List<Attachment> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            AttachmentJson.listFromJson(Json.parseToJsonElement(value))
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromChecklist(value: List<ChecklistItem>?): String {
        return json.encodeToString(value ?: emptyList())
    }

    @TypeConverter
    fun toChecklist(value: String?): List<ChecklistItem> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromRepeatList(value: List<Boolean>?): String? {
        if (value == null) return null
        return json.encodeToString(value)
    }

    @TypeConverter
    fun toRepeatList(value: String?): List<Boolean>? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromGrantedReward(value: GrantedReward?): String? {
        if (value == null) return null
        return json.encodeToString(value)
    }

    @TypeConverter
    fun toGrantedReward(value: String?): GrantedReward? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            null
        }
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return json.encodeToString(value ?: emptyList())
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromTaskHistoryList(value: List<TaskHistoryPoint>?): String {
        return json.encodeToString(value ?: emptyList())
    }

    @TypeConverter
    fun toTaskHistoryList(value: String?): List<TaskHistoryPoint> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // WP-8.2 (schema v7): DayStamp is a plain Int typealias (com.example.data.clock.DayStamp), so
    // it needs no converter of its own for scalar columns -- only for SyncPrefsEntity.pausedDays,
    // which is a List<DayStamp> == List<Int>.
    @TypeConverter
    fun fromDayStampList(value: List<Int>?): String? {
        if (value == null) return null
        return json.encodeToString(value)
    }

    @TypeConverter
    fun toDayStampList(value: String?): List<Int>? {
        if (value.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            null
        }
    }

    // WP-8.2 (schema v7): TaskEntity.quickLog (D9 typed backfill target).
    @TypeConverter
    fun fromQuickLogMode(value: QuickLogMode?): String? = value?.name

    @TypeConverter
    fun toQuickLogMode(value: String?): QuickLogMode? {
        if (value.isNullOrEmpty()) return null
        return try {
            QuickLogMode.valueOf(value)
        } catch (e: Exception) {
            null
        }
    }

    // WP-8.2 (schema v7): TaskEntity.reminders (D9 typed backfill target). review r2 major 1:
    // `extraJson` is @Transient (never auto-serialized) -- each element is re-built by overlaying
    // the CURRENT typed field values onto the element's own stored original (SerializationEngine's
    // established T3.1 overlay pattern), so unmodeled/unknown keys pass through unmoved and no
    // "extraJson" key is ever written, instead of `json.encodeToString` (which would only emit the
    // known fields, silently dropping every unknown key an original element carried).
    @TypeConverter
    fun fromReminderSpecList(value: List<ReminderSpec>?): String? {
        if (value == null) return null
        // P4 / KT-38: an opaque entry (ReminderSpec.raw) is written back verbatim at its index.
        val elements: List<kotlinx.serialization.json.JsonElement> = value.map { spec ->
            spec.raw?.let { parseRawElement(it) } ?: encodeReminder(spec)
        }
        return kotlinx.serialization.json.JsonArray(elements).toString()
    }

    private fun parseRawElement(raw: String): kotlinx.serialization.json.JsonElement? = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        null
    }

    /** P4 / KT-38: an element that does not decode, kept as an opaque, never-enabled entry. */
    private fun opaqueReminder(element: kotlinx.serialization.json.JsonElement): ReminderSpec {
        val obj = element as? JsonObject
        fun str(key: String): String? =
            (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return ReminderSpec(
            id = str("id"),
            time = str("time"),
            enabled = false,
            lastFiredKey = str("lastFiredKey") ?: "",
            raw = element.toString()
        )
    }

    private fun encodeReminder(spec: ReminderSpec): JsonObject {
        val original = com.example.data.sync.SerializationEngine.parseExtraJson(spec.extraJson)
        // WP-8.11: fresh key order = Opti's editor object literal (app.js:6061-6082):
        // id, enabled, kind, time, lastFiredKey, then date (once) or days (weekly/daily), so a
        // reminder made on this device serializes byte-identical to one made in Opti.
        val fresh = kotlinx.serialization.json.buildJsonObject {
            spec.id?.let { put("id", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("enabled", kotlinx.serialization.json.JsonPrimitive(spec.enabled))
            put("kind", kotlinx.serialization.json.JsonPrimitive(spec.kind))
            spec.time?.let { put("time", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("lastFiredKey", kotlinx.serialization.json.JsonPrimitive(spec.lastFiredKey))
            spec.date?.let { put("date", kotlinx.serialization.json.JsonPrimitive(it)) }
            spec.days?.let { d -> put("days", kotlinx.serialization.json.JsonArray(d.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
        }
        val merged = com.example.data.sync.SerializationEngine.overlayJson(original, fresh)
        // WP-8.2 follow-up (fixed in WP-8.11): a modeled nullable field set to null must clear
        // the stored value, not let the overlay keep the original's. A decode only yields null
        // for an absent key or a JSON null, so an original `null` is kept byte-stable.
        val cleared = listOf(
            "id" to spec.id, "time" to spec.time, "date" to spec.date, "days" to spec.days
        ).filter { (k, v) -> v == null && merged[k] != null && merged[k] !is kotlinx.serialization.json.JsonNull }
            .map { it.first }.toSet()
        return if (cleared.isEmpty()) merged
        else kotlinx.serialization.json.JsonObject(merged.filterKeys { it !in cleared })
    }

    // review r1 major 6 / r2 major 1: decode each element on its own (one malformed reminder must
    // not null out the whole list), keep unknown sub-fields via the per-element extraJson overlay
    // (T3.1 convention), and never let the DECODER itself see a stray "extraJson" key from a
    // previous encode (it is @Transient, so `decodeFromJsonElement` already ignores it).
    @TypeConverter
    fun toReminderSpecList(value: String?): List<ReminderSpec>? {
        if (value.isNullOrEmpty()) return null
        val arr = try {
            json.parseToJsonElement(value) as? JsonArray ?: return null
        } catch (e: Exception) {
            return null
        }
        // P4 / KT-38 (DEC-3): an element that does not decode is KEPT as an opaque entry (Opti
        // normalizeTaskReminders, app.js:2069-2072, never touches an array's elements), so the
        // list keeps Opti's length and raw indices and the element is uploaded unchanged.
        return arr.map { element ->
            try {
                val spec = json.decodeFromJsonElement<ReminderSpec>(element)
                val obj = element as? JsonObject
                spec.copy(extraJson = obj?.let { com.example.data.sync.SerializationEngine.encodeExtraJson(it) })
            } catch (e: Exception) {
                opaqueReminder(element)
            }
        }
    }

    // WP-8.2 (schema v7, review r1 blockers 3/4, review r2 major 2): CharacterEntity.abs /
    // TaskEntity.cAbs. `numOf` is Opti's `_num` (sync.js:1672: `Number(v) || 0`). WP-8.22-E: it
    // delegates to the shared JS `Number()` port (`Js.num`), so the STRING "NaN", "abc", "5f",
    // "true" all give 0, " 5 " gives 5, "0x10" gives 16 and "Infinity" stays Infinity, exactly as
    // JS. Each device entry is decoded on its own: `_absEntry`/`_cAbsEntry` (sync.js:1705-1712,
    // :1865-1872) return null only for a falsy or non-`typeof "object"` entry, so a scalar entry
    // is OMITTED from the map, but an ARRAY entry is an object to JS and decodes as all zeros.
    // A decoded xp/gold/mp CAN be +Infinity (`Number("Infinity")`, `1e999`), as in Opti's memory.
    // Room cannot store it as JSON, so [storableCharAbs] writes 0 in its place: that is what Opti
    // itself reads back after a reload (JSON.stringify writes Infinity as null, `_num(null)` is 0).
    // The wire keeps the original bytes for an unchanged map (SerializationEngine compares through
    // [storableCharAbs]) and writes null for a changed one (CanonicalJson.jsonNumber), as Opti.
    // [DEFERRED_OPEN_QUESTION] the typed Long/Int fields (ua, cUp, cDown) cannot hold Infinity or a
    // fraction the way the JS number can (`toLong()`/`toInt()` saturate/truncate, so an Infinity ua
    // goes out as Long.MAX_VALUE on a changed map, where JS writes null); kept as WP-8.2 shipped it.
    private fun numOf(element: kotlinx.serialization.json.JsonElement?): Double =
        com.example.data.sync.merge.Js.num(element)

    private fun absEntryOrNull(el: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement? =
        if (com.example.data.sync.merge.Js.isObjectLike(el)) el else null

    private fun field(e: kotlinx.serialization.json.JsonElement, key: String) =
        com.example.data.sync.merge.Js.get(e, key)

    @TypeConverter
    fun fromCharAbsMap(value: Map<String, CharAbsEntry>?): String? {
        if (value == null) return null
        return json.encodeToString(storableCharAbs(value))
    }

    /** WP-8.22-E review 43 F1: [value] as Room stores it -- a non-finite xp/gold/mp becomes 0. */
    fun storableCharAbs(value: Map<String, CharAbsEntry>): Map<String, CharAbsEntry> =
        value.mapValues { (_, e) -> e.copy(xp = finiteOrZero(e.xp), gold = finiteOrZero(e.gold), mp = finiteOrZero(e.mp)) }

    private fun finiteOrZero(d: Double): Double = if (d.isFinite()) d else 0.0

    @TypeConverter
    fun toCharAbsMap(value: String?): Map<String, CharAbsEntry>? {
        if (value.isNullOrEmpty()) return null
        val obj = try {
            json.parseToJsonElement(value) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }
        val result = mutableMapOf<String, CharAbsEntry>()
        for ((dev, el) in obj) {
            val e = absEntryOrNull(el) ?: continue
            result[dev] = CharAbsEntry(
                ua = numOf(field(e, "ua")).toLong(),
                xp = maxOf(0.0, numOf(field(e, "xp"))),
                gold = maxOf(0.0, numOf(field(e, "gold"))),
                mp = maxOf(0.0, numOf(field(e, "mp")))
            )
        }
        return result
    }

    @TypeConverter
    fun fromTaskAbsMap(value: Map<String, TaskAbsEntry>?): String? {
        if (value == null) return null
        return json.encodeToString(value)
    }

    @TypeConverter
    fun toTaskAbsMap(value: String?): Map<String, TaskAbsEntry>? {
        if (value.isNullOrEmpty()) return null
        val obj = try {
            json.parseToJsonElement(value) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }
        val result = mutableMapOf<String, TaskAbsEntry>()
        for ((dev, el) in obj) {
            val e = absEntryOrNull(el) ?: continue
            result[dev] = TaskAbsEntry(
                ua = numOf(field(e, "ua")).toLong(),
                cUp = maxOf(0.0, numOf(field(e, "cUp"))).toInt(),
                cDown = maxOf(0.0, numOf(field(e, "cDown"))).toInt()
            )
        }
        return result
    }
}
