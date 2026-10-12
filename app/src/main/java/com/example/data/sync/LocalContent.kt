package com.example.data.sync

import com.example.data.RpgRepository
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import kotlinx.coroutines.flow.first

/**
 * WP-8.9 review fix 1 (orchestrator ruling, a guess) -- is this device "effectively empty" for
 * the first-connect Push guard? Opti starts with no tasks (app.js:115), so its guard is
 * `tasks == 0 && rewards == 0 && untouched(char)`. Kotlin seeds 4 starter tasks and 2 starter
 * rewards once, on a true fresh install only (`seedFirstLaunch`, MainActivity.kt; R-4: never on an
 * upgrade, a restore or after Reset -- those stay truly empty, as Opti's). So an untouched SEEDED
 * state must count as empty too, or a fresh device could push its starter tasks over a real
 * Dropbox state; a truly empty state is empty as well.
 *
 * Empty = ALL of:
 *  - the event log has no user event ([USER_EVENT_KINDS]);
 *  - every task is an unmodified seed ([SEED_TASKS], matched on every user-owned field; the
 *    cron-owned fields value / streak / missedOn / doneDay / cResetOn / cAbs are ignored, and a
 *    history point may exist only if it records no completion and no score);
 *  - every reward is an unmodified seed ([SEED_REWARDS]); no tags; no PWA views, no metrics;
 *  - every charHistory row and the char itself are at defaults (sync.js:2222 `untouched`).
 * A deleted seed (fewer seeds) is still empty. Devices and tombstones do not count as content.
 */
class LocalContent(private val repository: RpgRepository) {

    companion object {
        /** The event kinds only a user action writes (complete / edit / taps / purchases). */
        val USER_EVENT_KINDS = setOf("complete", "uncomplete", "edit", "habitReps", "habitTap", "purchase", "potion")

        /**
         * The starter tasks of `seedFirstLaunch` (MainActivity.kt), id-less (the seed uses random
         * UUIDs). `LocalContentSeedTest` runs the real seed function, so a drift is caught.
         */
        val SEED_TASKS: List<TaskEntity> = listOf(
            TaskEntity(id = "", type = "habit", title = "Practice Coding", notes = "Gain experience by sharpening your steel.", difficulty = "medium", createdAt = 0L, updatedAt = 0L),
            TaskEntity(id = "", type = "habit", title = "Eat Junk Food", notes = "Penalty for giving in to dark desires.", difficulty = "easy", createdAt = 0L, updatedAt = 0L),
            TaskEntity(id = "", type = "daily", title = "Morning Gym Routine", notes = "Maintain your physical power stat.", difficulty = "hard",
                repeat = listOf(true, true, true, true, true, false, false), createdAt = 0L, updatedAt = 0L),
            TaskEntity(id = "", type = "todo", title = "Slay Stage 2 Code Review", notes = "Complete the native Kotlin migration UI layer.", difficulty = "hard", createdAt = 0L, updatedAt = 0L),
        )

        val SEED_REWARDS: List<RewardEntity> = listOf(
            RewardEntity(id = "", title = "Play 1 hour of Elden Ring", cost = 15.0, notes = "A fitting reward for a productive warrior.", createdAt = 0L, updatedAt = 0L),
            RewardEntity(id = "", title = "Order premium coffee", cost = 8.0, notes = "Boost your mana pool with caffeine.", createdAt = 0L, updatedAt = 0L),
        )

        /** The task with its identity, clock and cron-owned fields blanked. */
        private fun userShape(t: TaskEntity): TaskEntity = t.copy(
            id = "", createdAt = 0L, updatedAt = 0L, orderIndex = 0L,
            value = 0.0, streak = 0, missedOn = null, doneDay = null, cResetOn = null, cAbs = null,
            history = emptyList(),
        )

        fun isSeedTask(t: TaskEntity): Boolean {
            val historyClean = t.history.none { h ->
                h.completed == true || (h.scoredUp ?: 0) > 0 || (h.scoredDown ?: 0) > 0 || (h.reps ?: 0) > 0 || h.scored == true
            }
            return historyClean && SEED_TASKS.any { userShape(it) == userShape(t) }
        }

        fun isSeedReward(r: RewardEntity): Boolean =
            SEED_REWARDS.any { it == r.copy(id = "", createdAt = 0L, updatedAt = 0L, orderIndex = it.orderIndex) }
    }

    suspend fun isEffectivelyEmpty(): Boolean {
        if (repository.allLogs.first().any { it.kind in USER_EVENT_KINDS && !it.synthetic }) return false
        val tasks = repository.getAllTasksSync()
        if (!tasks.all { isSeedTask(it) }) return false
        // Each seed at most once (a duplicated seed is a user copy).
        if (tasks.groupBy { userShape(it) }.values.any { it.size > 1 }) return false
        val rewards = repository.getAllRewardsSync()
        if (!rewards.all { isSeedReward(it) } || rewards.groupBy { it.title }.values.any { it.size > 1 }) return false
        if (repository.getAllTagsSync().isNotEmpty()) return false
        if (repository.getAllCustomViewsSync().any { it.source != null }) return false
        if (SerializationEngine.parseJsonArray(repository.getMetricsExtrasJson()).isNotEmpty()) return false
        if (repository.getAllCharHistorySync().any { it.lvl > 1 || it.xp > 0 || it.gold > 0.0 || it.maxHp > 50.0 }) return false
        val char = SerializationEngine.buildCharWireJson(repository.getCharacterSync())
        return FirstConnect.untouched(char)
    }
}
