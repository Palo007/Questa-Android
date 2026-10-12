package com.example.data.inbox

import com.example.data.local.entities.QuickLogMode
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.CanonicalJson
import com.example.reminders.ReminderItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One `/inbox-meta/habits.json` entry: `{id, title, quickLog, dirs}` (sync.js:668-669). */
data class QuickLogHabit(val id: String, val title: String, val quickLog: Boolean, val dirs: List<Int>)

/** WP-8.8 (PX-60): Opti `_inboxHash` (sync.js:662-665): FNV-1a 32 over UTF-16 units, `hex:length`. */
object InboxHash {
    fun of(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193 // Math.imul: 32-bit wrap-around multiply
        }
        return h.toUInt().toString(16) + ":" + s.length
    }
}

/**
 * WP-8.8 (PX-60): the habit list Bubblewrap reads for its launcher shortcuts
 * (sync.js:666-676 `syncInboxWriteMeta`; app.js:2410-2421 `quickLogMode`/`quickLogDirs`).
 */
object QuickLogMeta {
    /** app.js:2416 `quickLogDirs`: +1 for 'up'/'both' unless `t.up === false`, -1 for 'down'/'both' unless `t.down === false`. */
    fun dirs(t: TaskEntity): List<Int> {
        val m = t.quickLog ?: QuickLogMode.OFF
        val out = ArrayList<Int>(2)
        if ((m == QuickLogMode.UP || m == QuickLogMode.BOTH) && t.up != false) out.add(1)
        if ((m == QuickLogMode.DOWN || m == QuickLogMode.BOTH) && t.down != false) out.add(-1)
        return out
    }

    /** sync.js:668-669: habits with an id, in `S.tasks` order; `quickLog` = at least one dir. */
    fun habits(tasks: List<TaskEntity>): List<QuickLogHabit> =
        tasks.filter { it.type == "habit" && it.id.isNotEmpty() }
            .map { t -> dirs(t).let { d -> QuickLogHabit(t.id, t.title, d.isNotEmpty(), d) } }

    /** `JSON.stringify(habits)`: the hashed string. */
    fun habitsJson(habits: List<QuickLogHabit>): String = CanonicalJson.stringify(JsonArray(habits.map { h ->
        buildJsonObject {
            put("id", JsonPrimitive(h.id))
            put("title", JsonPrimitive(h.title))
            put("quickLog", JsonPrimitive(h.quickLog))
            put("dirs", JsonArray(h.dirs.map { JsonPrimitive(it) }))
        }
    }))

    /** The upload body `JSON.stringify({v:1, updatedAt, habits})` (sync.js:672). */
    fun body(habits: List<QuickLogHabit>, updatedAt: Long): String =
        "{\"v\":1,\"updatedAt\":$updatedAt,\"habits\":${habitsJson(habits)}}"
}

/** WP-8.8 (PX-60): the `/inbox-meta/reminders.json` bytes (sync.js:685-714); items come from WP-8.11's `ReminderEngine.items`. */
object InboxReminderMeta {
    /** `JSON.stringify(items)`: keys in the Opti literal order `key,taskId,type,title,body,time,date,days`. */
    fun itemsJson(items: List<ReminderItem>): String = CanonicalJson.stringify(JsonArray(items.map { i ->
        JsonObject(linkedMapOf(
            "key" to JsonPrimitive(i.key),
            "taskId" to JsonPrimitive(i.taskId),
            "type" to JsonPrimitive(i.type),
            "title" to JsonPrimitive(i.title),
            "body" to JsonPrimitive(i.body),
            "time" to JsonPrimitive(i.time),
            "date" to (i.date?.let { JsonPrimitive(it) } ?: JsonNull),
            "days" to (i.days?.let { d -> JsonArray(d.map { JsonPrimitive(it) }) } ?: JsonNull),
        ))
    }))

    fun body(items: List<ReminderItem>, updatedAt: Long): String =
        "{\"v\":1,\"updatedAt\":$updatedAt,\"items\":${itemsJson(items)}}"
}
