package com.example.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Opti's event kind rules, shared by the feed (ui/) and the import summary (data/). */
object EventKinds {
    /** Opti `DIAGNOSTIC_KINDS` (app.js:1114). */
    val DIAGNOSTIC_KINDS: List<String> = listOf(
        "lifecycle", "storagePersist", "webLocksUnavailable", "clockSkew", "hlcReset", "multiTabClobberAvoided", "quotaError",
    )

    /** Opti `getEventCategory(e)` (app.js:4907-4921); `CONFLICT_CATEGORY` = "conflict". */
    fun category(kind: String?, taskType: String?): String = when {
        kind == "conflictResolved" -> "conflict"
        kind == "habitTap" || kind == "habitReps" || taskType == "habit" -> "habit"
        kind == "import" || kind == "export" || kind == "devicename" -> "system"
        taskType == "daily" || kind == "miss" -> "daily"
        taskType == "todo" -> "todo"
        kind == "subtask" || kind == "complete" -> if (taskType == "todo") "todo" else "daily"
        else -> "system"
    }
}

/** Opti `eventImportSummary(list)` result (app.js:1543-1557). [byCat] always holds the 4 keys in Opti order. */
data class ImportSummary(val total: Int, val diagnostic: Int, val visible: Int, val byCat: Map<String, Int>) {

    /** Opti `eventImportSummaryText(sum)` (app.js:1558-1564). Synced inside the import note: keep byte-exact. */
    fun text(): String =
        "$total stored total · $visible visible in feed · $diagnostic system/diagnostic hidden · " +
            "Habits ${byCat["habit"] ?: 0}, Dailies ${byCat["daily"] ?: 0}, To-dos ${byCat["todo"] ?: 0}, System ${byCat["system"] ?: 0}"

    companion object {
        val CATEGORIES = listOf("habit", "daily", "todo", "system")
        val EMPTY = ImportSummary(0, 0, 0, CATEGORIES.associateWith { 0 })

        /** Counts every embedded event object. A JS array is `typeof 'object'` too, so it counts (as system). */
        fun of(list: List<JsonElement>): ImportSummary {
            var total = 0
            var diag = 0
            val byCat = LinkedHashMap(CATEGORIES.associateWith { 0 })
            for (e in list) {
                if (e !is JsonObject && e !is JsonArray) continue
                total++
                val kind = (e as? JsonObject)?.str("kind")
                if (kind in EventKinds.DIAGNOSTIC_KINDS) { diag++; continue }
                val cat = EventKinds.category(kind, (e as? JsonObject)?.str("taskType"))
                val key = if (cat in byCat) cat else "system"
                byCat[key] = byCat.getValue(key) + 1
            }
            return ImportSummary(total, diag, total - diag, byCat)
        }

        /** The `import` event note when the file embeds events (app.js:8575-8576). */
        fun importNote(added: Int, summary: ImportSummary, skipped: Int, full: Boolean, keys: List<String>, replacing: Boolean): String =
            "Restored $added events (${summary.text()}; $skipped already present, skipped) [" +
                (if (full) "all sections" else keys.joinToString("+")) + "; " + (if (replacing) "replace" else "merge") + "]"

        /** JS `===` against a string literal: only a JSON string matches. */
        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
