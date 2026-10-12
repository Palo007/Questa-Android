package com.example.ui.gestures

import com.example.data.local.entities.ChecklistItem
import java.util.UUID

/**
 * WP-8.16 (PX-95): the task editor's paste and copy buttons. Port of Opti `applyPaste`
 * (app.js:6324-6394), `pasteEditTask` (:6264-6323) and `copyEditTask` (:6233-6257).
 */
object PasteParse {

    /** What a paste changes. A null field is left as it is (Opti only assigns non-empty parts). */
    data class Result(
        val title: String?,
        val checklist: List<ChecklistItem>?,
        val notes: String?,
        /** The toast Opti shows: "Title pasted" or "Task pasted". */
        val toast: String,
    )

    // JS `\s` (and String.prototype.trim) whitespace, which is wider than Java's ASCII `\s`.
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val ITEM = Regex("^[$WS]*-[$WS]*\\[([ xX]?)\\][$WS]*(.*)$")
    private val ITEM_START = Regex("^[$WS]*-[$WS]*\\[([ xX]?)\\]")
    private val DASH_START = Regex("^[$WS]*-[$WS]")
    private val LINE_BREAK = Regex("\\r?\\n")
    private val TRIM = Regex("^[$WS]+|[$WS]+$")

    /** JS `String.prototype.trim`. */
    fun jsTrim(s: String): String = s.replace(TRIM, "")

    /**
     * `applyPaste(text)`. [text] is the clipboard text AFTER `pasteEditTask`'s `.trim()`; an empty
     * text never reaches here (Opti toasts "Clipboard is empty" instead, see [pasteToast]).
     */
    fun parse(text: String, newId: () -> String = { UUID.randomUUID().toString() }): Result {
        val lines = text.split(LINE_BREAK)
        if (lines.size == 1) return Result(title = text, checklist = null, notes = null, toast = "Title pasted")

        var titleLines = mutableListOf<String>()
        val items = mutableListOf<ChecklistItem>()
        var notesLines = mutableListOf<String>()
        var mode = "title"
        var hasChecklistMarker = false

        fun item(line: String, trimmed: String) {
            val m = ITEM.find(line)
            if (m != null) {
                items += ChecklistItem(id = newId(), text = jsTrim(m.groupValues[2]), done = m.groupValues[1].lowercase() == "x")
            } else if (trimmed != "") {
                items += ChecklistItem(id = newId(), text = trimmed, done = false)
            }
        }

        for (line in lines) {
            val trimmed = jsTrim(line)
            if (trimmed == "Checklist:") { hasChecklistMarker = true; mode = "checklist"; continue }
            else if (trimmed == "Notes:") { mode = "notes"; continue }
            when (mode) {
                "title" -> titleLines += line
                "checklist" -> item(line, trimmed)
                "notes" -> notesLines += line
            }
        }
        if (!hasChecklistMarker && items.isEmpty()) {
            val first = lines.indexOfFirst { ITEM_START.containsMatchIn(it) || DASH_START.containsMatchIn(it) }
            if (first > 0) {
                titleLines = lines.subList(0, first).toMutableList()
                for (i in first until lines.size) {
                    val l = lines[i]
                    val t = jsTrim(l)
                    if (t == "Notes:") { notesLines = lines.subList(i + 1, lines.size).toMutableList(); break }
                    item(l, t)
                }
            }
        }
        val parsedTitle = jsTrim(titleLines.joinToString(" "))
        val parsedNotes = jsTrim(notesLines.joinToString("\n"))
        return Result(
            title = parsedTitle.ifEmpty { null },
            checklist = items.toList().ifEmpty { null },
            notes = parsedNotes.ifEmpty { null },
            toast = "Task pasted",
        )
    }

    /**
     * `pasteEditTask` on Android: the clipboard is readable in the foreground, so Opti's
     * blocked-clipboard textarea fallback never applies. Returns the parse, or null with the
     * "Clipboard is empty" toast when the trimmed clip is empty.
     */
    fun fromClipboard(clip: String?, newId: () -> String = { UUID.randomUUID().toString() }): Result? {
        val text = jsTrim(clip ?: "")
        return if (text.isEmpty()) null else parse(text, newId)
    }

    const val EMPTY_CLIPBOARD_TOAST = "Clipboard is empty"
    const val COPIED_TOAST = "Copied"

    /** `copyEditTask`: the text the copy button puts on the clipboard. */
    fun copyText(title: String, notes: String, checklist: List<ChecklistItem>): String {
        val cl = checklist.filter { jsTrim(it.text).isNotEmpty() }
        var text = title
        if (cl.isNotEmpty()) text += "\n\nChecklist:\n" + cl.joinToString("\n") { "- [" + (if (it.done) "x" else " ") + "] " + it.text }
        if (jsTrim(notes).isNotEmpty()) text += "\n\nNotes:\n" + notes
        return text
    }
}
