package com.example.data.attachments

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** CR-PWA-003: how many images the user added / removed in one editor session (by attachment id). */
data class AttachmentDelta(val added: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && removed == 0

    companion object {
        val NONE = AttachmentDelta(0, 0)

        /** Opti `attUserDelta(baseArr, editArr)` (app.js, 2296612): set difference by id. */
        fun of(baseIds: Collection<String>, editIds: Collection<String>): AttachmentDelta {
            val b = baseIds.toSet()
            val e = editIds.toSet()
            return AttachmentDelta(added = e.count { it !in b }, removed = b.count { it !in e })
        }
    }
}

object AttachmentActivity {
    /**
     * The `edit` event's `changes` item, Opti saveTask
     * `_ch.push({field:'attachments',added,removed})`; null when nothing changed.
     */
    fun changeItem(d: AttachmentDelta): JsonObject? = if (d.isEmpty) null else buildJsonObject {
        put("field", JsonPrimitive("attachments"))
        put("added", JsonPrimitive(d.added))
        put("removed", JsonPrimitive(d.removed))
    }

    /** The event payload `{"changes":[...]}` (the opaque `payloadJson` of an `edit` event). */
    fun editPayload(changes: List<JsonObject>): String = buildJsonObject {
        put("changes", JsonArray(changes))
    }.toString()
}

/**
 * CR-PWA-003 / RE-KT-009: the user's own image adds/removes while one task editor is open.
 *
 * Kotlin stores an add/remove at once (not on Save), so the delta is counted from the user's own
 * successful [AttachmentRepository.add]/[AttachmentRepository.remove] calls, by id: an add then a
 * remove of the same id nets to 0 (same result as Opti `attUserDelta` start -> end). Images a sync
 * merges in never pass through here, so they never count.
 *
 * [close] ends the session once (Save takes the delta into its `edit` event; a close without
 * Save writes an `edit` event with only this item). Rule for a result that arrives AFTER [close]
 * (adds/removes run on the process scope): it goes to [writeLate] at once as its own delta
 * (+1 or -1), which the caller writes as its own `edit` event.
 */
class AttachmentEditSession(
    val taskId: String,
    private val writeLate: (AttachmentDelta) -> Unit,
) {
    private val added = LinkedHashSet<String>()
    private val removed = LinkedHashSet<String>()
    private var closed = false

    fun onAdded(attachmentId: String) {
        val late = synchronized(this) {
            if (closed) true else { added += attachmentId; false }
        }
        if (late) writeLate(AttachmentDelta(1, 0))
    }

    fun onRemoved(attachmentId: String) {
        val late = synchronized(this) {
            if (closed) true else { if (!added.remove(attachmentId)) removed += attachmentId; false }
        }
        if (late) writeLate(AttachmentDelta(0, 1))
    }

    /** The delta so far, without closing. */
    @Synchronized
    fun current(): AttachmentDelta = AttachmentDelta(added.size, removed.size)

    /** Ends the session and returns its delta; a second call returns [AttachmentDelta.NONE]. */
    @Synchronized
    fun close(): AttachmentDelta {
        if (closed) return AttachmentDelta.NONE
        closed = true
        return AttachmentDelta(added.size, removed.size)
    }
}
