package com.example.data.inbox

import com.example.data.TapRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

/**
 * WP-8.8 (PX-59) -- one parsed `/inbox/<id>.json` record, the return value of Opti
 * `inboxParseRecord` (sync.js:547-560): `{v:1, id, kind:"habit", habitId, dir, ts, tzOffsetMin, src}`.
 *
 * [ts] and [tzOffsetMin] stay JS numbers (Double): Opti accepts any finite `ts > 0`, a fractional
 * one included, and keeps a fractional `tzOffsetMin`. [tzOffsetMin] is null when absent or not a
 * number. [src] is the record's non-empty string cut to 40 UTF-16 units, else `"android-shortcut"`.
 */
data class InboxRecord(
    val id: String,
    val habitId: String,
    val dir: Int,
    val ts: Double,
    val tzOffsetMin: Double?,
    val src: String,
) {
    val v: Int get() = 1
    val kind: String get() = "habit"

    /**
     * The tap handed to `RpgRepository.applyHabitTap` (uid `"inbox-$id"`).
     * [DEFERRED_OPEN_QUESTION] a fractional `ts` is floored to a Long (Room stores event
     * timestamps as Long; Bubblewrap only writes integers), and a missing `tzOffsetMin` becomes 0
     * (TapRecord's field is an Int and the apply never reads it).
     */
    fun toTapRecord(): TapRecord = TapRecord(
        id = id,
        habitId = habitId,
        dir = dir,
        ts = floor(ts).toLong(),
        tzOffsetMin = tzOffsetMin?.toInt() ?: 0,
        src = src,
    )

    companion object {
        const val DEFAULT_SRC = "android-shortcut"
    }
}

/** Why a file was not accepted. Opti returns a bare null for every case; the reason is diagnostic only. */
class InvalidInboxRecord(reason: String) : IllegalArgumentException(reason)

/** WP-8.8 (PX-59): Opti `inboxParseRecord` (sync.js:547-560), rule for rule. */
object InboxValidator {
    /** sync.js:550 `/^[A-Za-z0-9-]{8,64}$/`. */
    private val ID_RE = Regex("^[A-Za-z0-9-]{8,64}$")

    private val json = Json

    private fun fail(reason: String): Result<InboxRecord> = Result.failure(InvalidInboxRecord(reason))

    /** A JS number (`typeof x === "number"`): a non-string JSON primitive with a numeric value. */
    private fun num(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.doubleOrNull
    }

    private fun str(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    fun parse(fileName: String, body: String): Result<InboxRecord> {
        val parsed = try { json.parseToJsonElement(body) } catch (e: Exception) { return fail("notJson") }
        // `!o || typeof o !== "object" || o.v !== 1`: an array or a primitive has no `v`.
        val o = parsed as? JsonObject ?: return fail("notObject")
        if (num(o["v"]) != 1.0) return fail("version")
        val id = str(o["id"])
        if (id == null || !ID_RE.matches(id)) return fail("id")
        if (fileName != "$id.json") return fail("fileName")
        if (str(o["kind"]) != "habit") return fail("kind")
        val habitId = str(o["habitId"])
        if (habitId.isNullOrEmpty()) return fail("habitId")
        val dir = when (num(o["dir"])) { 1.0 -> 1; -1.0 -> -1; else -> return fail("dir") }
        val ts = num(o["ts"])
        if (ts == null || !ts.isFinite() || ts <= 0.0) return fail("ts")
        val tz = num(o["tzOffsetMin"])?.takeIf { it.isFinite() }
        val src = str(o["src"])?.takeIf { it.isNotEmpty() }?.take(40) ?: InboxRecord.DEFAULT_SRC
        return Result.success(InboxRecord(id, habitId, dir, ts, tz, src))
    }
}
