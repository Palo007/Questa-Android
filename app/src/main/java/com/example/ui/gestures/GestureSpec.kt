package com.example.ui.gestures

import kotlin.math.abs

/**
 * WP-8.16 (PX-92..98): Opti's gesture numbers, in one place. Opti is the PWA at 7d5d4f3.
 * A CSS px in a TWA is one dp, so every Opti px threshold is used here as dp.
 */
object GestureSpec {
    /** `DRAG_DELAY_DEFAULT` (app.js:21), returned by `longPressMs()` (:88-94) when no dragDelay pref is set. */
    const val DRAG_PICKUP_MS = 200L

    /** `REP_LONGPRESS_MS` (app.js:2517): hold on a habit +/- button to open the rep sheet. */
    const val REP_LONGPRESS_MS = 350L

    /** Card and rep long-press: a move of more than 10 px on either axis cancels (app.js:5242, :8549). */
    const val MOVE_CANCEL_DP = 10f

    /** Subtask row long-press: the cancel slop is 8 px on either axis (app.js:5433-5436). */
    const val SUBTASK_MOVE_CANCEL_DP = 8f

    /** Swipe navigation (app.js:8470-8471). */
    const val SWIPE_MIN_DP = 60f
    const val SWIPE_RATIO = 1.7f

    /** Tab slide-in after a swipe: `slideInR/L` 0.22 s ease-out, 26 px, opacity .4 -> 1 (index.html:86-89). */
    const val SLIDE_MS = 220
    const val SLIDE_DP = 26f
    const val SLIDE_START_ALPHA = 0.4f

    /** Layer 2 of the boot input gate: `body.bootSyncing .task { opacity:.55 }` (index.html:141). */
    const val GATED_CARD_ALPHA = 0.55f

    /** `buzz(ms)` durations (app.js:2991 and its call sites). */
    const val BUZZ_PICKUP_MS = 15L      // card, subtask and checklist pickup (:5285, :5484, :5594); rep sheet open (:8536)
    const val BUZZ_POSITIVE_MS = 50L    // habit up / log habit (:2486, :2499), complete (:2307), subtask done (:3087), new task (:6227)
    const val BUZZ_NEGATIVE_MS = 100L   // habit down (:2506)

    /** Opti `TABS` (app.js:8442) in Kotlin tab indices: habits, dailies, todos, analytics, rewards. */
    val OPTI_TAB_ORDER: List<Int> = listOf(TAB_HABITS, TAB_DAILIES, TAB_TODOS, TAB_ANALYTICS, TAB_REWARDS)

    /**
     * The swipe decision at `touchend` (app.js:8491-8505). [dx]/[dy] are the travel in dp. Returns the
     * Kotlin tab index to switch to, or null. Swipe left (dx < 0) = next tab. A tab that is not in
     * Opti's `TABS` (the Kotlin-only Activity tab) never swipes (`i < 0`).
     */
    fun swipeTarget(dx: Float, dy: Float, currentTab: Int): Int? {
        if (abs(dx) < SWIPE_MIN_DP) return null
        if (abs(dx) < abs(dy) * SWIPE_RATIO) return null
        val i = OPTI_TAB_ORDER.indexOf(currentTab)
        if (i < 0) return null
        val ni = if (dx < 0) i + 1 else i - 1
        if (ni < 0 || ni >= OPTI_TAB_ORDER.size) return null
        return OPTI_TAB_ORDER[ni]
    }

    /** `switchTab(TABS[ni], dx < 0 ? 1 : -1)`: +1 slides in from the right. */
    fun swipeDir(dx: Float): Int = if (dx < 0) 1 else -1
}

/** The Kotlin bottom-nav indices (DashboardScreen `tabs`). */
const val TAB_HABITS = 0
const val TAB_DAILIES = 1
const val TAB_TODOS = 2
const val TAB_REWARDS = 3
const val TAB_ANALYTICS = 4
const val TAB_LOGS = 5
