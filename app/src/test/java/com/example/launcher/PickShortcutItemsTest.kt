package com.example.launcher

import com.example.data.inbox.QuickLogHabit
import org.junit.Assert.assertEquals
import org.junit.Test

/** WP-8.13 card: pick vectors, Bubblewrap HabitList.kt:53-67 (caps 0/2/3/4). */
class PickShortcutItemsTest {
    private fun h(id: String, vararg dirs: Int) = QuickLogHabit(id, id.uppercase(), dirs.isNotEmpty(), dirs.toList())
    private fun List<ShortcutItem>.v() = map { it.habit.id + (if (it.dir == 1) "+" else "-") }

    // §6.4: H1 (both), H2 (+1), H3 (-1), H4 (+1).
    private val habits = listOf(h("h1", 1, -1), h("h2", 1), h("h3", -1), h("h4", 1))

    @Test fun cap3_plusBeforeMinus_inOrder() {
        assertEquals(listOf("h1+", "h1-", "h2+"), pickShortcutItems(habits, 3).v())
    }

    @Test fun cap4_plusBeforeMinus_inOrder() {
        assertEquals(listOf("h1+", "h1-", "h2+", "h3-"), pickShortcutItems(habits, 4).v())
    }

    @Test fun cap2_bothItemsOfOneHabitCountTwo() {
        assertEquals(listOf("h1+", "h1-"), pickShortcutItems(habits, 2).v())
    }

    @Test fun cap0_andNegative_empty() {
        assertEquals(emptyList<String>(), pickShortcutItems(habits, 0).v())
        assertEquals(emptyList<String>(), pickShortcutItems(habits, -1).v())
    }

    @Test fun capCutsInsideAHabit() {
        assertEquals(listOf("h2+", "h1+"), pickShortcutItems(listOf(h("h2", 1), h("h1", 1, -1)), 2).v())
    }

    @Test fun skipsHabitsWithNoDirs_dedupesById_minusOrderIgnored() {
        val list = listOf(h("a"), h("b", -1, 1), h("b", 1), h("c", -1))
        assertEquals(listOf("b+", "b-", "c-"), pickShortcutItems(list, 3).v())
    }

    @Test fun titleFallback_trimmedOrId() {
        assertEquals("x", shortcutTitle(QuickLogHabit("x", "   ", true, listOf(1))))
        assertEquals("Run", shortcutTitle(QuickLogHabit("x", "  Run ", true, listOf(1))))
    }
}
