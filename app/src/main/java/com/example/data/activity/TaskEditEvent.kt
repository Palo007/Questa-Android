package com.example.data.activity

import com.example.data.RpgEngine
import com.example.data.attachments.AttachmentActivity
import com.example.data.attachments.AttachmentDelta
import com.example.data.local.Converters
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.CanonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * CR-KT-016: the task editor's `edit` event, Opti saveTask (027e371 app.js:6957-6982).
 *
 * `orig` = the task when the editor opened (Opti `_base`), `edit` = the editor's copy (Opti `EDIT`).
 * [changes] item order: title, notes, difficulty (each `{field,from,to}`), checklist
 * `{field,items}` by index, schedule (`JSON.stringify(repeat||[])` differs), reminders (JSON
 * differs), attachments `{field,added,removed}` (CR-PWA-003), counter. The stored event is
 * `{kind:'edit', taskType, taskId, taskTitle}` + [payload] (`changes`, then `counter` when the
 * habit counter changed). No event when [changes] is empty.
 */
object TaskEditEvent {
    private val converters by lazy { Converters() }

    fun changes(
        orig: TaskEntity,
        edit: TaskEntity,
        attachments: AttachmentDelta = AttachmentDelta.NONE,
        counterChanged: Boolean = false,
    ): List<JsonObject> {
        val ch = mutableListOf<JsonObject>()
        if (orig.title != edit.title) ch += fromTo("title", orig.title, edit.title)
        if (orig.notes != edit.notes) ch += fromTo("notes", orig.notes, edit.notes)
        if (orig.difficulty != edit.difficulty) ch += fromTo("difficulty", orig.difficulty, edit.difficulty)
        val oc = orig.checklist
        val ec = edit.checklist
        val items = mutableListOf<JsonObject>()
        for (i in 0 until maxOf(oc.size, ec.size)) {
            val o = oc.getOrNull(i)
            val e = ec.getOrNull(i)
            when {
                o != null && e != null -> when {
                    o.text != e.text -> items += obj("type" to s("changed"), "from" to s(o.text), "to" to s(e.text))
                    o.done != e.done -> items += obj("type" to s("toggled"), "to" to s(e.text), "done" to JsonPrimitive(e.done))
                }
                e != null -> items += obj("type" to s("added"), "to" to s(e.text))
                o != null -> items += obj("type" to s("removed"), "from" to s(o.text))
            }
        }
        if (items.isNotEmpty()) ch += obj("field" to s("checklist"), "items" to JsonArray(items))
        if (repeatJson(orig) != repeatJson(edit)) ch += obj("field" to s("schedule"))
        if (remindersJson(orig) != remindersJson(edit)) ch += obj("field" to s("reminders"))
        AttachmentActivity.changeItem(attachments)?.let { ch += it }
        if (counterChanged) ch += obj("field" to s("counter"))
        return ch
    }

    /** `{"changes":[...]}` plus `"counter":{xp,gold,mp,hp}` when given; null when [changes] is empty. */
    fun payload(changes: List<JsonObject>, counter: RpgEngine.CounterAdjustResult?): String? {
        if (changes.isEmpty()) return null
        return CanonicalJson.stringify(buildJsonObject {
            put("changes", JsonArray(changes))
            counter?.let { c ->
                put("counter", buildJsonObject {
                    put("xp", JsonPrimitive(c.xp))
                    put("gold", CanonicalJson.jsonNumber(c.gold + 0.0))
                    put("mp", JsonPrimitive(c.mp))
                    put("hp", CanonicalJson.jsonNumber(c.hp + 0.0))
                })
            }
        })
    }

    // `JSON.stringify(t.repeat || [])`.
    private fun repeatJson(t: TaskEntity): String =
        CanonicalJson.stringify(JsonArray((t.repeat ?: emptyList()).map { JsonPrimitive(it) }))

    // `JSON.stringify(t.reminders || [])`, on the wire shape (Converters writes the stored key order).
    private fun remindersJson(t: TaskEntity): String {
        val raw = converters.fromReminderSpecList(t.reminders ?: emptyList()) ?: "[]"
        return CanonicalJson.stringify(Json.parseToJsonElement(raw))
    }

    private fun s(v: String) = JsonPrimitive(v)
    private fun obj(vararg kv: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(linkedMapOf(*kv))
    private fun fromTo(field: String, from: String, to: String) = obj("field" to s(field), "from" to s(from), "to" to s(to))
}
