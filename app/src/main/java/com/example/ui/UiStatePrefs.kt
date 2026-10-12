package com.example.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class ScrollPosition(val index: Int = 0, val offset: Int = 0)

data class UiStatePrefs(
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val dailyFilter: String = "all",
    val habitFilter: String = "all",
    val todoFilter: String = "active",
    val habitSort: String = "manual",
    val dailySort: String = "manual",
    val todoSort: String = "manual",
    val rewardSort: String = "manual",
    val selectedTab: Int = 0,
    val habitsScroll: ScrollPosition = ScrollPosition(),
    val dailiesScroll: ScrollPosition = ScrollPosition(),
    val todosScroll: ScrollPosition = ScrollPosition(),
    val rewardsScroll: ScrollPosition = ScrollPosition(),
    val analyticsScroll: ScrollPosition = ScrollPosition(),
    val logsScroll: ScrollPosition = ScrollPosition(),
) {
    companion object {
        fun from(p: Preferences): UiStatePrefs = UiStatePrefs(
            searchQuery = p[SyncKeys.UI_SEARCH_QUERY] ?: "",
            selectedTag = p[SyncKeys.UI_SELECTED_TAG],
            dailyFilter = p[SyncKeys.UI_DAILY_FILTER] ?: "all",
            habitFilter = p[SyncKeys.UI_HABIT_FILTER] ?: "all",
            todoFilter = p[SyncKeys.UI_TODO_FILTER] ?: "active",
            habitSort = p[SyncKeys.UI_HABIT_SORT] ?: "manual",
            dailySort = p[SyncKeys.UI_DAILY_SORT] ?: "manual",
            todoSort = p[SyncKeys.UI_TODO_SORT] ?: "manual",
            rewardSort = p[SyncKeys.UI_REWARD_SORT] ?: "manual",
            selectedTab = p[SyncKeys.UI_SELECTED_TAB] ?: 0,
            habitsScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_HABITS_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_HABITS_OFFSET] ?: 0,
            ),
            dailiesScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_DAILIES_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_DAILIES_OFFSET] ?: 0,
            ),
            todosScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_TODOS_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_TODOS_OFFSET] ?: 0,
            ),
            rewardsScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_REWARDS_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_REWARDS_OFFSET] ?: 0,
            ),
            analyticsScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_ANALYTICS_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_ANALYTICS_OFFSET] ?: 0,
            ),
            logsScroll = ScrollPosition(
                index = p[SyncKeys.UI_SCROLL_LOGS_INDEX] ?: 0,
                offset = p[SyncKeys.UI_SCROLL_LOGS_OFFSET] ?: 0,
            ),
        )

        fun flow(data: Flow<Preferences>): Flow<UiStatePrefs> = data.map { from(it) }.distinctUntilChanged()
    }
}

val LocalUiStatePrefs: ProvidableCompositionLocal<UiStatePrefs> = staticCompositionLocalOf { UiStatePrefs() }
