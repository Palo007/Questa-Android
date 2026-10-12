package com.example.data

import com.example.data.attachments.AttachmentJson
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SerializationEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * CR-KT-017: the task editor Save's write-back, Opti saveTask (b7a94cd app.js ~6880-6900).
 * Only the fields the user changed in the sheet are written onto the LIVE row; a sync merge
 * that moved the row while the sheet was open (a peer's counter tap, a subtask) survives.
 *
 * Compared in wire form ([SerializationEngine.buildTaskJson]), key by key, like Opti:
 *  - a key whose [sheet] value differs from [base] (`_eq`: JSON value, absent = null) takes the sheet value;
 *  - every other key keeps the live value;
 *  - a key in [base] that the sheet no longer has is removed;
 *  - when the checklist is written, live subtasks whose id was never in `base.checklist`
 *    (they arrived by sync) are appended: the user cannot have deleted what they never saw.
 * `id` is never written. `attachments` and the local `orderIndex` always stay live (CR-KT-015:
 * the attachment repository owns `attachments`).
 *
 * `_eq` here is JSON value equality (key order inside objects ignored; Opti's JSON.stringify sees
 * order). All three objects come from the same builder, so the orders match for an unchanged value.
 */
object TaskWriteBack {

    private fun eq(a: JsonElement?, b: JsonElement?): Boolean = (a ?: JsonNull) == (b ?: JsonNull)

    private fun idOf(e: JsonElement): JsonElement? = (e as? JsonObject)?.get("id")?.takeIf { it != JsonNull }

    /** The write-back on wire objects: returns the live object with the user's changes applied. */
    fun applyJson(base: JsonObject, sheet: JsonObject, live: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(live)
        for ((k, v) in sheet) {
            if (k == "id" || k == AttachmentJson.KEY) continue
            if (!eq(v, base[k])) out[k] = v
        }
        for (k in base.keys) {
            if (k == "id" || k == AttachmentJson.KEY) continue
            if (!sheet.containsKey(k)) out.remove(k)
        }
        val liveCl = live["checklist"] as? JsonArray
        val outCl = out["checklist"] as? JsonArray
        if (liveCl != null && outCl != null) {
            val seen = outCl.mapNotNull { idOf(it) }.toSet()
            val known = ((base["checklist"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { idOf(it) }.toSet()
            val extra = liveCl.filter { c -> idOf(c)?.let { it !in seen && it !in known } == true }
            if (extra.isNotEmpty()) out["checklist"] = JsonArray(outCl + extra)
        }
        return JsonObject(out)
    }

    /**
     * RE-PWA-018: Opti saveTask's id-based `touchedAt` stamping (b7a94cd app.js:6862-6870), run on
     * the sheet copy BEFORE the write-back. A sheet subtask with an empty id gets a new id; one whose
     * id is not in [base]'s checklist, or whose `text`/`done` differ from that base item, gets
     * `touchedAt = now()` (one call per item, like Opti). Order changes alone stamp nothing. Every
     * other field (extraJson included) is kept. Feeds mergeChecklist (OptiSnapshotMerger).
     */
    fun stampTouched(base: TaskEntity, sheet: TaskEntity, now: () -> Long): TaskEntity {
        val baseById = base.checklist.associateBy { it.id }
        var changed = false
        val cl = sheet.checklist.map { c0 ->
            var c = c0
            if (c.id.isEmpty()) { c = c.copy(id = java.util.UUID.randomUUID().toString()); changed = true }
            val o = baseById[c.id]
            if (o == null || o.text != c.text || o.done != c.done) { c = c.copy(touchedAt = now()); changed = true }
            c
        }
        return if (changed) sheet.copy(checklist = cl) else sheet
    }

    /** [applyJson] on rows: [base] = the task when the editor opened, [sheet] = the editor's copy, [live] = the stored row. */
    fun apply(base: TaskEntity, sheet: TaskEntity, live: TaskEntity): TaskEntity {
        val merged = applyJson(
            SerializationEngine.buildTaskJson(base),
            SerializationEngine.buildTaskJson(sheet),
            SerializationEngine.buildTaskJson(live),
        )
        return SerializationEngine.parseTaskEntity(merged).copy(
            id = live.id,
            orderIndex = live.orderIndex,
            attachments = live.attachments,
        )
    }
}
