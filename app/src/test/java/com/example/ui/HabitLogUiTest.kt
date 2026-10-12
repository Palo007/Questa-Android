package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Habit "Log" difficulty parity with the PWA.
 *
 * - app.js:6275 offers `log` as a difficulty only when the task is a habit.
 * - app.js:3338-3346 `toggleFilter()` resets the habits filter from `log` to `all` when the
 *   filter panel closes on the habits tab.
 */
class HabitLogUiTest {

    @Test
    fun habitOffersLogDifficultyLast() {
        assertEquals(
            listOf("trivial", "easy", "medium", "hard", "log"),
            difficultyOptions("habit").map { it.first },
        )
        assertEquals("Log", difficultyOptions("habit").last().second)
    }

    @Test
    fun dailyAndTodoDoNotOfferLog() {
        val base = listOf("trivial", "easy", "medium", "hard")
        assertEquals(base, difficultyOptions("daily").map { it.first })
        assertEquals(base, difficultyOptions("todo").map { it.first })
    }

    @Test
    fun closingPanelOnHabitsTabResetsLogFilter() {
        assertEquals("all", habitFilterAfterPanelToggle(nowOpen = false, tab = 0, current = "log"))
    }

    @Test
    fun openingPanelKeepsLogFilter() {
        assertEquals("log", habitFilterAfterPanelToggle(nowOpen = true, tab = 0, current = "log"))
    }

    @Test
    fun closingPanelOnOtherTabKeepsLogFilter() {
        assertEquals("log", habitFilterAfterPanelToggle(nowOpen = false, tab = 1, current = "log"))
        assertEquals("log", habitFilterAfterPanelToggle(nowOpen = false, tab = 2, current = "log"))
    }

    @Test
    fun closingPanelKeepsOtherHabitFilters() {
        listOf("all", "weak", "strong").forEach {
            assertEquals(it, habitFilterAfterPanelToggle(nowOpen = false, tab = 0, current = it))
        }
    }
}
