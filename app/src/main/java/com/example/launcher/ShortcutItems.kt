package com.example.launcher

import com.example.data.inbox.QuickLogHabit

/**
 * WP-8.13 (PX-77): one shortcut-worthy (habit, direction) pair, in publish order.
 * Port of Bubblewrap `quicklog/HabitList.kt` `ShortcutItem`.
 */
data class ShortcutItem(val habit: QuickLogHabit, val dir: Int)

/**
 * Bubblewrap `HabitList.kt:53-67` `pickShortcutItems`: for each habit in the web app's order, its
 * directions (+1 before -1), skipping habits with no directions, deduped by habit id, capped at
 * [cap] ITEMS (a habit with two items counts two toward the cap).
 *
 * The dirs are the Bubblewrap `parseDirs` result (only 1/-1, first-seen, no duplicates); the
 * Kotlin input comes from [com.example.data.inbox.QuickLogMeta.dirs], which already has that shape.
 */
fun pickShortcutItems(habits: List<QuickLogHabit>, cap: Int): List<ShortcutItem> {
    if (cap <= 0) return emptyList()
    val seen = HashSet<String>()
    val out = ArrayList<ShortcutItem>()
    for (h in habits) {
        if (h.dirs.isEmpty()) continue
        if (!seen.add(h.id)) continue
        for (dir in listOf(1, -1)) {
            if (dir !in h.dirs) continue
            if (out.size >= cap) return out
            out.add(ShortcutItem(h, dir))
        }
    }
    return out
}

/** Bubblewrap `parseHabitList` (HabitList.kt:24): the title is trimmed, and an empty title falls back to the id. */
internal fun shortcutTitle(h: QuickLogHabit): String = h.title.trim().ifEmpty { h.id }
