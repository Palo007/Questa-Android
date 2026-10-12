package com.example.ui.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Opti feed `edit` row summary (app.js renderEventDetail `_summ`, 2296612): one part per
 * `changes` item, joined by ", ". Parts: `title: from→to`, `difficulty: from→to` (when `from` is
 * set), `notes`, `subtasks`, `images +N −M` (CR-PWA-003; U+2212 minus; a zero side is left out),
 * otherwise the raw field name. Empty when the payload has no `changes` array.
 *
 * CR-KT-016: [details] = the `_detail` rows under the summary (Opti 027e371 app.js:5279-5293):
 * `notes: <diff>` when the item has `from` or `to`, and `subtasks:` followed by one `• ` row per
 * item (changed / added / removed as a diff, toggled as `checked|unchecked <text>`). A diff is
 * Opti `_evDiffText`: `new → to` (no from), `from → removed` (no to), else `from → to`.
 */
object EditChangeSummary {
    fun parts(payloadJson: String?): List<String> {
        val changes = payloadJson?.let { p ->
            runCatching { (Json.parseToJsonElement(p) as? JsonObject)?.get("changes") as? JsonArray }.getOrNull()
        } ?: return emptyList()
        return changes.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val field = c.str("field")
            when {
                field == "title" && c.isSet("from") -> "title: ${c.str("from")}→${c.str("to")}"
                field == "difficulty" && c.isSet("from") -> "difficulty: ${c.str("from")}→${c.str("to")}"
                field == "notes" -> "notes"
                field == "checklist" -> "subtasks"
                field == "attachments" -> {
                    val a = c.int("added")
                    val r = c.int("removed")
                    "images" + (if (a != 0) " +$a" else "") + (if (r != 0) " −$r" else "")
                }
                else -> field
            }
        }
    }

    fun text(payloadJson: String?): String = parts(payloadJson).joinToString(", ")

    /** How a detail span is drawn (Opti's inline styles). */
    enum class Style { LABEL, PLAIN, FROM, TO, NEW, REMOVED, CHECK, ITEM }

    data class Span(val text: String, val style: Style)

    /** One detail row; [indent] = a subtask row (Opti `padding-left:8px`). */
    data class DetailLine(val spans: List<Span>, val indent: Boolean = false) {
        val plain: String get() = spans.joinToString("") { it.text }
    }

    fun details(payloadJson: String?): List<DetailLine> {
        val changes = changesOf(payloadJson) ?: return emptyList()
        val out = mutableListOf<DetailLine>()
        changes.forEach { el ->
            val c = el as? JsonObject ?: return@forEach
            when (c.str("field")) {
                "notes" -> if (c.isSet("from") || c.isSet("to")) {
                    out += DetailLine(listOf(Span("notes:", Style.LABEL), Span(" ", Style.PLAIN)) + diff(c.opt("from"), c.opt("to")))
                }
                "checklist" -> {
                    val items = c["items"] as? JsonArray
                    if (items != null && items.isNotEmpty()) {
                        out += DetailLine(listOf(Span("subtasks:", Style.LABEL)))
                        items.forEach { iel ->
                            val it = iel as? JsonObject ?: return@forEach
                            val bullet = Span("• ", Style.PLAIN)
                            when (it.str("type")) {
                                "changed" -> out += DetailLine(listOf(bullet) + diff(it.opt("from"), it.opt("to")), indent = true)
                                "added" -> out += DetailLine(listOf(bullet) + diff(null, it.opt("to")), indent = true)
                                "removed" -> out += DetailLine(listOf(bullet) + diff(it.opt("from"), null), indent = true)
                                "toggled" -> out += DetailLine(listOf(
                                    bullet,
                                    Span(if (it.truthy("done")) "checked" else "unchecked", Style.CHECK),
                                    Span(" ", Style.PLAIN),
                                    Span(it.opt("to") ?: "", Style.ITEM),
                                ), indent = true)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    // Opti `_evDiffText(from, to)` (app.js:5054-5063).
    private fun diff(from: String?, to: String?): List<Span> {
        val hasFrom = !from.isNullOrEmpty()
        val hasTo = !to.isNullOrEmpty()
        val arrow = Span(" → ", Style.PLAIN)
        return when {
            !hasFrom && hasTo -> listOf(Span("new", Style.NEW), arrow, Span(to!!, Style.TO))
            hasFrom && !hasTo -> listOf(Span(from!!, Style.FROM), arrow, Span("removed", Style.REMOVED))
            else -> listOf(Span(from ?: "", Style.FROM), arrow, Span(to ?: "", Style.TO))
        }
    }

    private fun changesOf(payloadJson: String?): JsonArray? = payloadJson?.let { p ->
        runCatching { (Json.parseToJsonElement(p) as? JsonObject)?.get("changes") as? JsonArray }.getOrNull()
    }

    // JS `String(x)` of a present, non-null value; null for absent / null.
    private fun JsonObject.opt(k: String): String? = if (isSet(k)) str(k) else null

    // JS truthiness of a primitive.
    private fun JsonObject.truthy(k: String): Boolean {
        val v = this[k] as? JsonPrimitive ?: return false
        if (v is JsonNull) return false
        if (v.isString) return v.content.isNotEmpty()
        return v.content != "false" && v.content != "0" && v.content != "-0" && v.content != "NaN"
    }

    // JS `c.from != null`: present and not null.
    private fun JsonObject.isSet(k: String) = this[k] != null && this[k] !is JsonNull

    // JS string concatenation of a primitive (`'' + x`); null/undefined print as JS does.
    private fun JsonObject.str(k: String): String = when (val v = this[k]) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    // JS `+c.added || 0`.
    private fun JsonObject.int(k: String): Int =
        ((this[k] as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull } ?: 0.0)
            .takeIf { !it.isNaN() }?.toInt() ?: 0
}
