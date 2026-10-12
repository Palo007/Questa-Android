package com.example.ui.gestures

import com.example.data.local.entities.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WP-8.16 (PX-95, I7): Opti `applyPaste` / `copyEditTask` (app.js:6233-6394). */
class PasteParseTest {
    private var n = 0
    private val ids: () -> String = { "id${++n}" }

    @Test fun `one line is the title only`() {
        val r = PasteParse.parse("Buy milk", ids)
        assertEquals("Buy milk", r.title)
        assertNull(r.checklist)
        assertNull(r.notes)
        assertEquals("Title pasted", r.toast)
    }

    @Test fun `the copy format round-trips`() {
        val cl = listOf(ChecklistItem("a", "eggs", true), ChecklistItem("b", "bread", false))
        val text = PasteParse.copyText("Shop", "at noon\nsecond line", cl)
        assertEquals("Shop\n\nChecklist:\n- [x] eggs\n- [ ] bread\n\nNotes:\nat noon\nsecond line", text)
        val r = PasteParse.parse(text, ids)
        assertEquals("Shop", r.title)
        assertEquals(listOf("eggs" to true, "bread" to false), r.checklist!!.map { it.text to it.done })
        assertEquals("at noon\nsecond line", r.notes)
        assertEquals("Task pasted", r.toast)
    }

    @Test fun `title lines join with a space and marker-free lines under Checklist are open items`() {
        val r = PasteParse.parse("Big\ntitle\nChecklist:\nplain item\n\n- [X] upper x\n-[]none", ids)
        assertEquals("Big title", r.title)
        assertEquals(listOf("plain item" to false, "upper x" to true, "none" to false), r.checklist!!.map { it.text to it.done })
        assertNull(r.notes)
    }

    @Test fun `without a marker the first dash line starts the checklist and keeps a bare dash text`() {
        val r = PasteParse.parse("Title\n- [ ] one\n- two\nNotes:\nn1\nn2", ids)
        assertEquals("Title", r.title)
        // Opti keeps `- two` as the item text (only the bracket form is stripped).
        assertEquals(listOf("one" to false, "- two" to false), r.checklist!!.map { it.text to it.done })
        assertEquals("n1\nn2", r.notes)
    }

    @Test fun `a dash on the first line is no checklist and nothing but the title changes`() {
        val r = PasteParse.parse("- a\nb", ids)
        assertEquals("- a b", r.title)
        assertNull(r.checklist)
    }

    @Test fun `CRLF line breaks and JS whitespace trim`() {
        val r = PasteParse.parse("T\r\nChecklist:\r\n- [ ] x ", ids)
        assertEquals("T", r.title)
        assertEquals(listOf("x"), r.checklist!!.map { it.text })
    }

    @Test fun `an empty or blank clipboard is Clipboard is empty`() {
        assertNull(PasteParse.fromClipboard(null))
        assertNull(PasteParse.fromClipboard("  \n "))
        assertEquals("Title pasted", PasteParse.fromClipboard("  hi  ")!!.toast)
        assertEquals("hi", PasteParse.fromClipboard("  hi  ")!!.title)
    }

    @Test fun `copy skips blank subtasks and blank notes`() {
        assertEquals("T", PasteParse.copyText("T", "  ", listOf(ChecklistItem("a", " ", false))))
    }
}
