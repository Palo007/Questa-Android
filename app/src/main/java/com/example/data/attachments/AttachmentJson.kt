package com.example.data.attachments

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * CR-KT-015: the wire form of [Attachment] (task key `attachments`), shared by the Room
 * converter and SerializationEngine. Item key order: id, sha, mime, w, h, bytes, addedAt
 * (fixture shared/fixtures/CR-KT-015/task_attachments.json).
 */
object AttachmentJson {
    const val KEY = "attachments"

    fun toJson(a: Attachment): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(a.id))
        put("sha", JsonPrimitive(a.sha))
        put("mime", JsonPrimitive(a.mime))
        put("w", JsonPrimitive(a.w))
        put("h", JsonPrimitive(a.h))
        put("bytes", JsonPrimitive(a.bytes))
        put("addedAt", JsonPrimitive(a.addedAt))
    }

    fun listToJson(list: List<Attachment>): JsonArray = JsonArray(list.map { toJson(it) })

    private val SHA = Regex("[0-9a-f]{64}")

    /**
     * The only sha this app reads, stores or sends: lower-case hex sha256, the PWA's
     * `/^[0-9a-f]{64}$/` (sync.js syncUploadPendingAttachments). Anything else would name a
     * file path, so it never reaches the file system or Dropbox.
     */
    fun isValidSha(sha: String): Boolean = SHA.matches(sha)

    /**
     * Wire items this app cannot use but must not delete: they have an `id` (so the PWA merge
     * keeps them, sync.js mergeAttachments `clean`) yet [fromJson] rejects them (bad or no sha).
     */
    fun foreignItems(e: JsonElement?): List<JsonObject> =
        (e as? JsonArray).orEmpty().mapNotNull { x ->
            (x as? JsonObject)?.takeIf { o -> o["id"].let { it != null && it !is JsonNull } && fromJson(o) == null }
        }

    /**
     * The `attachments` value to write for [list], given the [original] wire array: null (omit
     * the key) when there is nothing; the live list; or, when [original] holds foreign items,
     * the live list plus those items verbatim, ordered like the merge (addedAt, then id).
     */
    fun wireValue(list: List<Attachment>, original: JsonElement?): JsonArray? {
        val foreign = foreignItems(original)
        if (foreign.isEmpty()) return list.takeIf { it.isNotEmpty() }?.let { listToJson(it) }
        val all: List<JsonObject> = list.map { toJson(it) } + foreign
        return JsonArray(all.sortedWith(compareBy<JsonObject>({ sortNum(it["addedAt"]) }, { idText(it) })))
    }

    private fun sortNum(e: JsonElement?): Double {
        val p = e as? JsonPrimitive ?: return 0.0
        if (p is JsonNull) return 0.0
        val d = if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull
        return if (d == null || d.isNaN()) 0.0 else d
    }

    private fun idText(o: JsonObject): String = (o["id"] as? JsonPrimitive)?.content ?: o["id"].toString()

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(o: JsonObject, k: String): Long? {
        val p = o[k] as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }

    /** One item, or null when it has no string `id` or no valid `sha` ([isValidSha]); such an item is not usable. */
    fun fromJson(e: JsonElement?): Attachment? {
        val o = e as? JsonObject ?: return null
        val id = str(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        val sha = str(o, "sha")?.takeIf { isValidSha(it) } ?: return null
        return Attachment(
            id = id,
            sha = sha,
            mime = str(o, "mime") ?: Attachment.MIME_JPEG,
            w = (num(o, "w") ?: 0L).toInt(),
            h = (num(o, "h") ?: 0L).toInt(),
            bytes = num(o, "bytes") ?: 0L,
            addedAt = num(o, "addedAt") ?: 0L,
        )
    }

    /** Missing key, null, a non-array or `[]` = empty. Unusable items are skipped (see [wireValue]). */
    fun listFromJson(e: JsonElement?): List<Attachment> =
        (e as? JsonArray)?.mapNotNull { fromJson(it) } ?: emptyList()
}
