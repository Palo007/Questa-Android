package com.example.ui

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RpgRepository
import com.example.data.attachments.AttachmentDelta
import com.example.data.RpgEngine
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.data.LocalDay
import com.example.data.boot.BootGate
import com.example.data.clock.DayStamp
import com.example.data.sync.SyncDataStore
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncResult
import com.example.ui.gestures.GestureSpec
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class FloatingFeedback(val taskId: String, val message: String, val isPositive: Boolean, val id: String = UUID.randomUUID().toString())

/** §5.0 B11 (r13, T-5): one frozen RYA row (Opti freezes the task objects: title :2716). */
data class RyaItem(val id: String, val title: String)

/** §5.0 B11: the frozen RYA sheet; process-scoped, never persisted (a reload loses the ticks). */
data class RyaSheetState(val items: List<RyaItem>, val ticked: Set<String>, val armDay: DayStamp)

/** §5.0 B12 step 6 (T-4): Opti's toast, app.js:2768 ("daily" / "dailys"). */
fun ryaToastText(credited: Int): String =
    "Credited " + credited + " daily" + (if (credited == 1) "" else "s") + " from yesterday"

/** §5.0 B13: Opti's gated-input toast (app.js:2464, :3078, :5828). */
const val SYNCING_TOAST = "Syncing…"

/** Review 35: the MainViewModel that holds BootGate's sheetOpen bind (identity guard). */
internal object SheetBind { @Volatile var holder: Any? = null }

private fun defaultProcessLifecycle(): Lifecycle? =
    try { ProcessLifecycleOwner.get().lifecycle } catch (e: Throwable) { null }

class MainViewModel(
    private val repository: RpgRepository,
    val syncManager: SyncManager,
    val syncDataStore: SyncDataStore,
    // WP-8.10 (§5.0 B11 caller 2): the process lifecycle for the ON_START observer (tests inject one).
    private val processLifecycle: Lifecycle? = defaultProcessLifecycle()
) : ViewModel() {

    private var lastLevel: Int? = null

    data class LevelUpDetails(val oldLvl: Int, val newLvl: Int)

    private val _levelUpEvent = MutableStateFlow<LevelUpDetails?>(null)
    val levelUpEvent: StateFlow<LevelUpDetails?> = _levelUpEvent.asStateFlow()

    private val _deathEvent = MutableStateFlow(false)
    val deathEvent: StateFlow<Boolean> = _deathEvent.asStateFlow()

    private val _floatingFeedbacks = MutableSharedFlow<FloatingFeedback>(extraBufferCapacity = 10)
    val floatingFeedbacks = _floatingFeedbacks.asSharedFlow()

    /** Habitica-style screen-level gain chip (rendered by RewardBurstHost in the lower third).
     *  [text] carries a neutral notice (e.g. rep-sheet "+3 reps") that grants no xp/gold. */
    data class RewardBurst(val xp: Int, val gold: Double, val text: String? = null, val id: String = UUID.randomUUID().toString())

    private val _rewardBursts = MutableSharedFlow<RewardBurst>(extraBufferCapacity = 10)
    val rewardBursts = _rewardBursts.asSharedFlow()

    /** Ids of just-created tasks/rewards; the lists auto-scroll to them on "Created ↓" (newest-first). */
    private val _createdTaskIds = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val createdTaskIds = _createdTaskIds.asSharedFlow()
    private val _createdRewardIds = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val createdRewardIds = _createdRewardIds.asSharedFlow()

    val searchQuery = MutableStateFlow("")
    val selectedTag = MutableStateFlow<String?>(null)
    val dailyFilter = MutableStateFlow("all") // "all", "due", "notdue"
    val habitFilter = MutableStateFlow("all") // "all", "weak", "strong", "log"
    val todoFilter = MutableStateFlow("active") // "active", "all")
    
    val habitSort = MutableStateFlow("manual") // "manual", "created-desc", "created-asc", "updated-desc", "updated-asc"
    val dailySort = MutableStateFlow("manual")
    val todoSort = MutableStateFlow("manual")
    val rewardSort = MutableStateFlow("manual")

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _habitsScroll = MutableStateFlow(ScrollPosition())
    val habitsScroll: StateFlow<ScrollPosition> = _habitsScroll.asStateFlow()

    private val _dailiesScroll = MutableStateFlow(ScrollPosition())
    val dailiesScroll: StateFlow<ScrollPosition> = _dailiesScroll.asStateFlow()

    private val _todosScroll = MutableStateFlow(ScrollPosition())
    val todosScroll: StateFlow<ScrollPosition> = _todosScroll.asStateFlow()

    private val _rewardsScroll = MutableStateFlow(ScrollPosition())
    val rewardsScroll: StateFlow<ScrollPosition> = _rewardsScroll.asStateFlow()

    private val _analyticsScroll = MutableStateFlow(ScrollPosition())
    val analyticsScroll: StateFlow<ScrollPosition> = _analyticsScroll.asStateFlow()

    private val _logsScroll = MutableStateFlow(ScrollPosition())
    val logsScroll: StateFlow<ScrollPosition> = _logsScroll.asStateFlow()

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
        viewModelScope.launch { syncDataStore.saveUiSelectedTab(index) }
    }

    fun setSearchQuery(value: String) {
        searchQuery.value = value
        viewModelScope.launch { syncDataStore.saveUiSearchQuery(value) }
    }

    fun setSelectedTag(value: String?) {
        selectedTag.value = value
        viewModelScope.launch { syncDataStore.saveUiSelectedTag(value) }
    }

    fun setDailyFilter(value: String) {
        dailyFilter.value = value
        viewModelScope.launch { syncDataStore.saveUiDailyFilter(value) }
    }

    fun setHabitFilter(value: String) {
        habitFilter.value = value
        viewModelScope.launch { syncDataStore.saveUiHabitFilter(value) }
    }

    fun setTodoFilter(value: String) {
        todoFilter.value = value
        viewModelScope.launch { syncDataStore.saveUiTodoFilter(value) }
    }

    fun setHabitSort(value: String) {
        habitSort.value = value
        viewModelScope.launch { syncDataStore.saveUiHabitSort(value) }
    }

    fun setDailySort(value: String) {
        dailySort.value = value
        viewModelScope.launch { syncDataStore.saveUiDailySort(value) }
    }

    fun setTodoSort(value: String) {
        todoSort.value = value
        viewModelScope.launch { syncDataStore.saveUiTodoSort(value) }
    }

    fun setRewardSort(value: String) {
        rewardSort.value = value
        viewModelScope.launch { syncDataStore.saveUiRewardSort(value) }
    }

    fun setHabitsScroll(index: Int, offset: Int) {
        _habitsScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiHabitsScroll(index, offset) }
    }

    fun setDailiesScroll(index: Int, offset: Int) {
        _dailiesScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiDailiesScroll(index, offset) }
    }

    fun setTodosScroll(index: Int, offset: Int) {
        _todosScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiTodosScroll(index, offset) }
    }

    fun setRewardsScroll(index: Int, offset: Int) {
        _rewardsScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiRewardsScroll(index, offset) }
    }

    fun setAnalyticsScroll(index: Int, offset: Int) {
        _analyticsScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiAnalyticsScroll(index, offset) }
    }

    fun setLogsScroll(index: Int, offset: Int) {
        _logsScroll.value = ScrollPosition(index, offset)
        viewModelScope.launch { syncDataStore.saveUiLogsScroll(index, offset) }
    }

    val character: StateFlow<CharacterEntity?> = repository.character
        .onEach { char ->
            if (char != null) {
                val previous = lastLevel
                if (previous != null && char.lvl > previous) {
                    _levelUpEvent.value = LevelUpDetails(oldLvl = previous, newLvl = char.lvl)
                } else if (previous != null && char.lvl < previous) {
                    _deathEvent.value = true
                }
                lastLevel = char.lvl
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun dismissLevelUp() {
        _levelUpEvent.value = null
    }

    fun acknowledgeDeath() {
        viewModelScope.launch {
            val char = repository.getCharacterSync()
            repository.insertCharacter(char.copy(needsDeathAcknowledge = false))
        }
    }

    private fun createdMs(task: TaskEntity): Long {
        return task.createdAt
    }

    private fun updatedMs(task: TaskEntity): Long {
        return task.updatedAt
    }

    private fun sortTasks(tasks: List<TaskEntity>, sortKey: String): List<TaskEntity> {
        if (sortKey == "manual") return tasks
        val sorted = tasks.toMutableList()
        when (sortKey) {
            "created-desc" -> sorted.sortByDescending { createdMs(it) }
            "created-asc" -> sorted.sortBy { createdMs(it) }
            "updated-desc" -> sorted.sortByDescending { updatedMs(it) }
            "updated-asc" -> sorted.sortBy { updatedMs(it) }
        }
        return sorted
    }

    val allTasks: StateFlow<List<TaskEntity>> = combine(
        repository.allTasks,
        searchQuery,
        selectedTag,
        habitFilter,
        dailyFilter,
        todoFilter,
        habitSort,
        dailySort,
        todoSort,
        repository.allTags
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val rawTasks = array[0] as List<TaskEntity>
        val query = array[1] as String
        val tag = array[2] as String?
        val hFilter = array[3] as String
        val dFilter = array[4] as String
        val tFilter = array[5] as String
        val hSort = array[6] as String
        val dSort = array[7] as String
        val tSort = array[8] as String
        @Suppress("UNCHECKED_CAST")
        val tagRows = array[9] as List<TagEntity>
        val nameById = tagNameIndex(tagRows)

        val searchTagged = rawTasks.filter { task ->
            val matchesQuery = query.isBlank() || task.title.contains(query, ignoreCase = true) || task.notes.contains(query, ignoreCase = true)
            val matchesTag = taskMatchesTag(task, tag, nameById)
            matchesQuery && matchesTag
        }

        val processedHabits = searchTagged.filter { it.type == "habit" }.let { habits ->
            val filtered = when (hFilter) {
                "log" -> habits.filter { it.difficulty == "log" }
                "weak" -> habits.filter { it.difficulty != "log" && it.value < 1.0 }
                "strong" -> habits.filter { it.difficulty != "log" && it.value >= 1.0 }
                else -> habits.filter { it.difficulty != "log" || (it.cUp + it.cDown) == 0 }
            }
            sortTasks(filtered, hSort)
        }

        val processedDailies = searchTagged.filter { it.type == "daily" }.let { dailies ->
            val dow = todayDow()
            val filtered = when (dFilter) {
                "due" -> dailies.filter { task ->
                    val isDue = task.repeat?.getOrNull(dow) != false
                    isDue && !task.done
                }
                "notdue" -> dailies.filter { task ->
                    val isDue = task.repeat?.getOrNull(dow) != false
                    task.done || !isDue
                }
                else -> dailies
            }
            sortTasks(filtered, dSort)
        }

        val processedTodos = searchTagged.filter { it.type == "todo" }.let { todos ->
            val filtered = when (tFilter) {
                "active" -> todos.filter { !it.done }
                else -> todos
            }
            sortTasks(filtered, tSort)
        }

        processedHabits + processedDailies + processedTodos
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val rawTasks: StateFlow<List<TaskEntity>> = repository.allTasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ================================================================ WP-8.10: RYA (§5.0 B11, B12)
    // There is no cron collector in init and no Flow feeds RYA (D25). The day is decided by
    // RpgRepository.startDay, run by BootGate (B3, caller 1) or by the ON_START caller 2 below.

    private val _rya = MutableStateFlow<RyaSheetState?>(null)
    /** B11: the frozen RYA sheet; null = hidden. The ONLY thing that shows RYA. */
    val rya: StateFlow<RyaSheetState?> = _rya.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot toasts (the "Credited N …" RYA toast). */
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // ================================================================ WP-8.16 (§5.0 B8, B13)

    private val openModals = LinkedHashSet<String>()
    private val _modalOpen = MutableStateFlow(false)
    /**
     * B8: true while a Kotlin surface of Opti's `#scrim` is showing (the task editor, the rep
     * sheet, the reward editor, Settings, FirstConnectDialog; see `ModalKeys`). RYA is NOT one.
     * Read synchronously by BootGate's `sheetOpen()` (the W7 bind in [init]).
     */
    val modalOpen: StateFlow<Boolean> = _modalOpen.asStateFlow()

    /** DashboardScreen reports each modal surface here (`ReportModal`). */
    fun setModalOpen(key: String, open: Boolean) {
        synchronized(openModals) {
            if (open) openModals += key else openModals -= key
            _modalOpen.value = openModals.isNotEmpty()
        }
    }

    private val _buzzes = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    /** Opti `buzz(ms)` after an action succeeded (the durations in `GestureSpec`). */
    val buzzes: SharedFlow<Long> = _buzzes.asSharedFlow()

    /** B13: the first statement of every gated card action (Opti app.js:2464, :3078, :5828). */
    private fun gated(): Boolean {
        if (!BootGate.blocksInput()) return false
        _toasts.tryEmit(SYNCING_TOAST)
        return true
    }

    /** B11 (r13, minor 5): this instance's opener reference, compared by identity. */
    internal val opener: suspend (Long) -> Unit = { nowMs -> openRyaLocked(nowMs) }

    private fun dayStampOf(ms: Long): DayStamp = LocalDay.dayStamp(ms, BootGate.zoneProvider())

    /** JS `new Date().getDay()` (0 = Sunday) in the BootGate clock and zone (review minor). */
    internal fun todayDow(): Int = LocalDay.getDay(BootGate.clock(), BootGate.zoneProvider())

    /**
     * B11: the ONE opener body; the caller holds [BootGate.dayMutex]. A sheet for the current arm
     * stays frozen; else `startDay` (the one read) decides, and a non-empty list opens the sheet.
     * An older-arm sheet with an empty current list is left for caller 2(a) to close (r14, T-10f).
     */
    private suspend fun openRyaLocked(nowMs: Long) {
        val armDay = BootGate.loadDayStamp.value
        if (_rya.value?.armDay == armDay) return
        val missed = repository.startDay(nowMs)
        if (missed.isNotEmpty()) {
            _rya.value = RyaSheetState(missed.map { RyaItem(it.id, it.title) }, emptySet(), armDay)
        }
    }

    /**
     * B11 caller 2 (the ON_START foreground). Everything is re-read after the lock: (0) only the
     * bound instance; (a) an older-arm sheet closes (a re-arm; ticks lost); (b) open when the gate
     * is open, the rollover entered today and no sheet is showing.
     */
    suspend fun openRyaIfNeeded(@Suppress("UNUSED_PARAMETER") nowMs: Long) {
        BootGate.dayMutex.withLock {
            val now = BootGate.clock()
            if (repository.ryaOpener !== opener) return
            val cur = _rya.value
            if (cur != null && cur.armDay != BootGate.loadDayStamp.value) _rya.value = null
            if (!BootGate.pending.value && BootGate.rolloverDay.value == dayStampOf(now) && _rya.value == null) {
                openRyaLocked(now)
            }
        }
    }

    /** app.js:2701-2704 `toggleYesterTick`: edits `ticked` only; credits nothing. */
    fun toggleRyaTick(id: String) {
        _rya.update { s -> s?.copy(ticked = if (id in s.ticked) s.ticked - id else s.ticked + id) }
    }

    /**
     * B12: port of `commitYesterCheck` (app.js:2730-2769), under [BootGate.dayMutex] and
     * NonCancellable. A no-op unless [tapped] is the showing sheet (r14, T-8). One commit-time
     * clock; credits (each its own committed transaction) → runCron once → close → toast.
     */
    fun confirmRya(tapped: RyaSheetState): Job = viewModelScope.launch {
        BootGate.dayMutex.withLock {
            withContext(NonCancellable) {
                val s = _rya.value
                if (s == null || s !== tapped) return@withContext
                val nowMs = BootGate.clock()
                var credited = 0
                for (item in s.items) {
                    if (item.id !in s.ticked) continue
                    val live = repository.taskOnce(item.id) ?: continue
                    if (repository.creditYesterday(live.id, nowMs)) credited++
                }
                repository.runCron(nowMs)
                _rya.value = null
                if (credited > 0) _toasts.tryEmit(ryaToastText(credited))
            }
        }
    }

    private val ryaStartObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            viewModelScope.launch { openRyaIfNeeded(BootGate.clock()) }
        }
    }

    init {
        repository.ryaOpener = opener
        try { processLifecycle?.addObserver(ryaStartObserver) } catch (e: Exception) { }
        BootGate.bindSheetOpen { modalOpen.value }
        SheetBind.holder = this

        viewModelScope.launch {
            syncDataStore.uiSearchQuery.collect { searchQuery.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiSelectedTag.collect { selectedTag.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiDailyFilter.collect { dailyFilter.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiHabitFilter.collect { habitFilter.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiTodoFilter.collect { todoFilter.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiHabitSort.collect { habitSort.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiDailySort.collect { dailySort.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiTodoSort.collect { todoSort.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiRewardSort.collect { rewardSort.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiSelectedTab.collect { _selectedTab.value = it }
        }
        viewModelScope.launch {
            syncDataStore.uiHabitsScrollIndex.collect { idx ->
                _habitsScroll.value = _habitsScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiHabitsScrollOffset.collect { off ->
                _habitsScroll.value = _habitsScroll.value.copy(offset = off)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiDailiesScrollIndex.collect { idx ->
                _dailiesScroll.value = _dailiesScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiDailiesScrollOffset.collect { off ->
                _dailiesScroll.value = _dailiesScroll.value.copy(offset = off)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiTodosScrollIndex.collect { idx ->
                _todosScroll.value = _todosScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiTodosScrollOffset.collect { off ->
                _todosScroll.value = _todosScroll.value.copy(offset = off)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiRewardsScrollIndex.collect { idx ->
                _rewardsScroll.value = _rewardsScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiRewardsScrollOffset.collect { off ->
                _rewardsScroll.value = _rewardsScroll.value.copy(offset = off)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiAnalyticsScrollIndex.collect { idx ->
                _analyticsScroll.value = _analyticsScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiAnalyticsScrollOffset.collect { off ->
                _analyticsScroll.value = _analyticsScroll.value.copy(offset = off)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiLogsScrollIndex.collect { idx ->
                _logsScroll.value = _logsScroll.value.copy(index = idx)
            }
        }
        viewModelScope.launch {
            syncDataStore.uiLogsScrollOffset.collect { off ->
                _logsScroll.value = _logsScroll.value.copy(offset = off)
            }
        }
    }

    override fun onCleared() {
        try { processLifecycle?.removeObserver(ryaStartObserver) } catch (e: Exception) { }
        if (repository.ryaOpener === opener) repository.ryaOpener = null
        // Review 35: only the ViewModel that holds the bind may clear it (as ryaOpener).
        synchronized(SheetBind) {
            if (SheetBind.holder === this) { BootGate.bindSheetOpen { false }; SheetBind.holder = null }
        }
        super.onCleared()
    }

    /** WP-8.10 (PX-65): the live pause flag, and Opti `setPause` (the Settings row is 8.14's). */
    fun setPaused(paused: Boolean) {
        viewModelScope.launch { repository.setPause(paused) }
    }

    // WP-8.14: the Settings sheet route (null = closed). MainActivity opens it from the
    // EXTRA_OPEN_SETTINGS intent extra; the gear button opens Root.
    private val _settingsRoute = MutableStateFlow<com.example.ui.settings.SettingsRoute?>(null)
    val settingsRoute: StateFlow<com.example.ui.settings.SettingsRoute?> = _settingsRoute.asStateFlow()

    fun openSettings(route: com.example.ui.settings.SettingsRoute = com.example.ui.settings.SettingsRoute.Root) {
        _settingsRoute.value = route
    }

    fun closeSettings() {
        _settingsRoute.value = null
    }

    val allRewards: StateFlow<List<RewardEntity>> = repository.allRewards
        .combine(rewardSort) { rewards, sortKey ->
            if (sortKey == "manual") rewards
            else {
                val sorted = rewards.toMutableList()
                when (sortKey) {
                    "created-desc" -> sorted.sortByDescending { it.createdAt }
                    "created-asc" -> sorted.sortBy { it.createdAt }
                    "updated-desc" -> sorted.sortByDescending { it.createdAt }
                    "updated-asc" -> sorted.sortBy { it.createdAt }
                }
                sorted
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allLogs: StateFlow<List<EventLogEntity>> = repository.allLogs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Dropbox synchronization state
    val isDropboxEnabled: StateFlow<Boolean> = syncDataStore.isDropboxEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val isSaveButtonTop: StateFlow<Boolean> = syncDataStore.isSaveButtonTop
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun setSaveButtonTop(isTop: Boolean) {
        viewModelScope.launch {
            syncDataStore.setSaveButtonTop(isTop)
        }
    }

    val hapticsEnabled: StateFlow<Boolean> = syncDataStore.hapticsEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            syncDataStore.setHapticsEnabled(enabled)
        }
    }

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun triggerSync() {
        viewModelScope.launch {
            _syncStatus.value = "Syncing..."
            // WP-8.10 (WP-8.6 hand-off): the round API, not the pre-8.6 syncNow() fold.
            _syncStatus.value = when (val result = syncManager.sync()) {
                SyncResult.Ok, SyncResult.NothingToPush -> "Sync Successful!"
                SyncResult.InterlockRefused -> "Sync Failed: ${SyncManager.INTERLOCK_REFUSED_MESSAGE}"
                SyncResult.NeedsFirstConnectChoice -> "Sync Failed: ${SyncManager.FIRST_CONNECT_MESSAGE}"
                is SyncResult.Error -> "Sync Failed: ${result.msg}"
                null -> "Sync Failed: A sync round is already running"
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
    }

    /** WP-8.1: the full Opti `syncDisconnect` (tokens, keep set, revoke); keeps the base (D8). */
    fun unlinkDropbox() {
        viewModelScope.launch {
            syncManager.disconnect()
            _syncStatus.value = "Dropbox unlinked."
        }
    }

    // Interaction handlers
    fun completeTask(
        taskId: String,
        applyDueTodayGate: Boolean = false
    ) {
        if (gated()) return
        viewModelScope.launch {
            val task = allTasks.value.find { it.id == taskId } ?: return@launch
            val todayDow = if (applyDueTodayGate) {
                todayDow()
            } else {
                -1
            }
            
            val r = RpgEngine.completionReward(task.difficulty, task.value)
            val success = repository.completeTask(
                taskId = taskId,
                todayDow = todayDow
            )
            
            if (success) {
                _buzzes.tryEmit(GestureSpec.BUZZ_POSITIVE_MS) // Opti completeTask buzz(50), app.js:2307
                _floatingFeedbacks.tryEmit(FloatingFeedback(taskId, "+${r.xp} XP", true))
                _rewardBursts.tryEmit(RewardBurst(r.xp, r.gold))
            } else if (applyDueTodayGate && task.type == "daily" && !task.done) {
                // PWA blocked-tap toast parity: "Not due today"
                _floatingFeedbacks.tryEmit(FloatingFeedback(taskId, "Not due today", false))
            }
        }
    }

    fun uncompleteTask(taskId: String) {
        if (gated()) return
        viewModelScope.launch {
            repository.uncompleteTask(taskId)
        }
    }

    // Stage 3 / T3.5 follow-up (Phase 0c): reorder core. Tasks share ONE global `orderIndex`
    // sequence across habit/daily/todo (interleaved), so a reorder confined to one tab must only
    // touch that type's slots: load the full collection in canonical `orderIndex ASC` order (the
    // DAO's own sort -- see TaskDao.getAllTasks), extract the affected type's sublist, splice the
    // caller's desired new relative order for that type back into the SAME positions that type
    // occupied (every other-type row keeps its existing slot/relative order untouched), then
    // reassign contiguous `orderIndex` = 0..n-1 across the rebuilt full list. Only rows whose
    // `orderIndex` actually changes are persisted, and only `orderIndex` is ever copied -- this
    // must never touch `createdAt`/`updatedAt` (that was the bug: the old implementation swapped
    // `createdAt` between adjacent rows, which the DAO doesn't even sort by primarily, and bumped
    // `updatedAt` on a pure reorder, violating the sync contract for a no-op-to-remote change).
    // Reused by both the adjacent-move `reorderTask` below AND `persistTaskOrder` (the latter is
    // already the live drag-and-drop persistence path wired from DashboardScreen's per-tab
    // onDragEnd -> onPersistOrder), so a future Phase 1 drag `onMove` can call this same core with
    // an arbitrary resulting order.
    private suspend fun reindexTasksForType(type: String, newOrderIdsForType: List<String>) {
        val all = repository.getAllTasksSync() // canonical orderIndex ASC global sequence
        val targetSet = newOrderIdsForType.toSet()
        val positions = all.indices.filter { all[it].type == type && all[it].id in targetSet }
        if (positions.size != newOrderIdsForType.size) return // id set must match this type's current members 1:1

        val byId = all.associateBy { it.id }
        val reordered = newOrderIdsForType.map { id -> byId[id] ?: return }

        val rebuilt = all.toMutableList()
        positions.forEachIndexed { i, pos -> rebuilt[pos] = reordered[i] }

        // ONE transaction = ONE Room emission: row-by-row writes redrew the list with each half-done
        // order (duplicate orderIndex), so the cards near a drop jumped before settling.
        repository.inTransaction {
            rebuilt.forEachIndexed { i, task ->
                if (task.orderIndex != i.toLong()) {
                    repository.updateTask(task.copy(orderIndex = i.toLong()))
                }
            }
        }
    }

    // Reward counterpart of reindexTasksForType: rewards are a single global collection (no
    // per-type split), so this simply reassigns contiguous orderIndex = 0..n-1 to realize
    // [newOrderIds] as the new order. createdAt/updatedAt untouched; only changed rows persisted.
    private suspend fun reindexRewards(newOrderIds: List<String>) {
        val all = repository.allRewards.first() // canonical orderIndex ASC sequence
        if (all.size != newOrderIds.size) return

        val byId = all.associateBy { it.id }
        val reordered = newOrderIds.map { id -> byId[id] ?: return }

        repository.inTransaction { // one Room emission, as in reindexTasksForType
            reordered.forEachIndexed { i, reward ->
                if (reward.orderIndex != i.toLong()) {
                    repository.insertReward(reward.copy(orderIndex = i.toLong()))
                }
            }
        }
    }

    fun reorderTask(taskId: String, moveUp: Boolean) {
        viewModelScope.launch {
            val all = repository.getAllTasksSync()
            val task = all.find { it.id == taskId } ?: return@launch
            val sameType = all.filter { it.type == task.type }
            val index = sameType.indexOfFirst { it.id == taskId }
            val targetIndex = if (moveUp) index - 1 else index + 1
            if (index < 0 || targetIndex < 0 || targetIndex >= sameType.size) return@launch

            val newOrder = sameType.map { it.id }.toMutableList()
            newOrder[index] = sameType[targetIndex].id
            newOrder[targetIndex] = sameType[index].id
            reindexTasksForType(task.type, newOrder)
        }
    }

    fun persistTaskOrder(orderedIds: List<String>) {
        viewModelScope.launch {
            if (orderedIds.isEmpty()) return@launch
            val all = repository.getAllTasksSync()
            val type = all.find { it.id == orderedIds.first() }?.type ?: return@launch
            reindexTasksForType(type, orderedIds)
        }
    }

    fun persistRewardOrder(orderedIds: List<String>) {
        viewModelScope.launch {
            if (orderedIds.isEmpty()) return@launch
            reindexRewards(orderedIds)
        }
    }

    fun scoreHabitUp(taskId: String) {
        if (gated()) return
        viewModelScope.launch {
            val task = allTasks.value.find { it.id == taskId } ?: return@launch
            val r = RpgEngine.completionReward(task.difficulty, task.value)
            repository.scoreHabitUp(taskId)
            _buzzes.tryEmit(GestureSpec.BUZZ_POSITIVE_MS) // app.js:2486 (log), :2499 (up)
            _floatingFeedbacks.tryEmit(FloatingFeedback(taskId, "+${r.xp} XP", true))
            _rewardBursts.tryEmit(RewardBurst(r.xp, r.gold))
        }
    }

    fun scoreHabitDown(taskId: String) {
        if (gated()) return
        viewModelScope.launch {
            val task = allTasks.value.find { it.id == taskId } ?: return@launch
            val dmg = RpgEngine.missDamage(task.difficulty, task.value)
            repository.scoreHabitDown(taskId)
            // app.js:2486: a log habit's minus is a tally (buzz 50); :2506 a real minus buzzes 100.
            _buzzes.tryEmit(if (task.difficulty == "log") GestureSpec.BUZZ_POSITIVE_MS else GestureSpec.BUZZ_NEGATIVE_MS)
            _floatingFeedbacks.tryEmit(FloatingFeedback(taskId, "-$dmg HP", false))
        }
    }

    /**
     * Opti `toggleSub` (app.js:3077-3091): B13 guard first; flips the subtask, stamps its
     * `touchedAt` and the task's `updatedAt` from the shared HLC, logs a `subtask` event and buzzes
     * 50 when it is now done. [subId] first, [idx] as Opti's fallback.
     */
    fun toggleSubtask(taskId: String, subId: String?, idx: Int) {
        if (gated()) return
        viewModelScope.launch { subtaskLock.withLock {
            val t = repository.getTaskByIdSync(taskId) ?: return@launch
            val cl = t.checklist
            var at = if (subId != null) cl.indexOfFirst { it.id == subId } else -1
            if (at < 0 && idx in cl.indices) at = idx
            if (at < 0) return@launch
            val c = cl[at]
            val cId = c.id.ifEmpty { UUID.randomUUID().toString() }
            val touched = repository.hlcClock.now()
            val flipped = c.copy(id = cId, done = !c.done, touchedAt = touched)
            val stamp = repository.hlcClock.now()
            repository.updateTask(t.copy(checklist = cl.toMutableList().also { it[at] = flipped }, updatedAt = stamp))
            if (flipped.done) _buzzes.tryEmit(GestureSpec.BUZZ_POSITIVE_MS)
            repository.insertLog(EventLogEntity(
                timestamp = repository.physicalNow(),
                kind = "subtask",
                taskId = t.id,
                taskTitle = t.title,
                taskType = t.type,
                payloadJson = com.example.data.sync.CanonicalJson.canonicalJsonString(kotlinx.serialization.json.JsonObject(mapOf(
                    "subId" to kotlinx.serialization.json.JsonPrimitive(cId),
                    "subText" to kotlinx.serialization.json.JsonPrimitive(c.text),
                    "done" to kotlinx.serialization.json.JsonPrimitive(flipped.done),
                )))
            ))
        } }
    }

    /** The drop and the tap after a still lift write the same task one after the other (review 35). */
    private val subtaskLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Opti `commitSubOrder` (app.js:5500-5520): the dropped order, by subtask id, and
     * `updatedAt = now()` from the shared HLC (Opti stamps even when the order did not change).
     */
    fun reorderSubtasks(taskId: String, orderedIds: List<String>) {
        viewModelScope.launch { subtaskLock.withLock {
            val t = repository.getTaskByIdSync(taskId) ?: return@launch
            val byId = t.checklist.associateBy { it.id }
            val next = orderedIds.mapNotNull { byId[it] } + t.checklist.filter { it.id !in orderedIds }
            repository.updateTask(t.copy(checklist = next, updatedAt = repository.hlcClock.now()))
        } }
    }

    /**
     * The editor's save: the task as edited, stamped from the shared HLC (Opti `t.updatedAt = now()`).
     * CR-KT-016: one `edit` event (Opti saveTask) with every changed field, the user's image delta
     * (CR-PWA-003) and, for a habit, the counter edited in the sheet. [base] = the task when the
     * editor opened (null = the stored row); [editedCUp]/[editedCDown] = the sheet's counters.
     */
    fun saveEditedTask(
        task: TaskEntity,
        attachmentDelta: AttachmentDelta = AttachmentDelta.NONE,
        base: TaskEntity? = null,
        editedCUp: Int? = null,
        editedCDown: Int? = null,
    ) {
        viewModelScope.launch {
            // CR-KT-008: known tag names become ids on this user save; unknown tokens stay.
            val tags = tagTokensToIds(task.tags, repository.getAllTagsSync())
            val saved = task.copy(tags = tags, updatedAt = repository.hlcClock.now())
            repository.saveTaskEdit(base, saved, attachmentDelta, editedCUp, editedCDown)
        }
    }

    /**
     * CR-PWA-003 / RE-KT-009: an image delta outside Save (editor closed without Save, or an
     * add/remove that finished after the editor closed): its own `edit` event, current task row.
     */
    fun logAttachmentEdit(taskId: String, delta: AttachmentDelta) {
        if (delta.isEmpty) return
        viewModelScope.launch {
            val t = repository.getTaskByIdSync(taskId) ?: return@launch
            repository.logAttachmentEdit(t, delta)
        }
    }

    fun addHabitReps(taskId: String, reps: Int, isPositive: Boolean) {
        viewModelScope.launch {
            val task = repository.allTasks.first().find { it.id == taskId } ?: return@launch
            // WP-8.10 (§4.4): the HLC stamp (Opti `t.updatedAt = now()`), not the wall clock.
            // [DEFERRED_OPEN_QUESTION] Opti bulk reps do not touch cUp/cDown (app.js REP sheet); WP-8.16.
            val stamp = repository.hlcClock.now()
            val updatedTask = if (isPositive) {
                task.copy(
                    cUp = task.cUp + reps,
                    updatedAt = stamp
                )
            } else {
                task.copy(
                    cDown = task.cDown + reps,
                    updatedAt = stamp
                )
            }
            repository.updateTask(updatedTask)
            repository.insertLog(EventLogEntity(
                timestamp = repository.physicalNow(),
                kind = "habitReps",
                direction = if (isPositive) 1 else -1,
                taskId = task.id,
                taskTitle = task.title,
                reps = reps,
                value = task.value
            ))
            // WP-8.16: Opti addReps toasts, with no floating text (app.js:2531).
            _toasts.tryEmit((if (isPositive) "+" else "-") + reps + " reps · no reward")
            _rewardBursts.tryEmit(RewardBurst(0, 0.0, text = (if (isPositive) "+" else "-") + reps + " reps"))
        }
    }

    fun reorderReward(rewardId: String, moveUp: Boolean) {
        viewModelScope.launch {
            val all = repository.allRewards.first()
            val index = all.indexOfFirst { it.id == rewardId }
            val targetIndex = if (moveUp) index - 1 else index + 1
            if (index < 0 || targetIndex < 0 || targetIndex >= all.size) return@launch

            val newOrder = all.map { it.id }.toMutableList()
            newOrder[index] = all[targetIndex].id
            newOrder[targetIndex] = all[index].id
            reindexRewards(newOrder)
        }
    }

    fun buyReward(rewardId: String) {
        viewModelScope.launch {
            repository.buyReward(rewardId)
        }
    }

    fun buyPotion(isGreater: Boolean) {
        // PX-70 (G14): Opti SHOP_ITEMS (app.js:6466-6480): the Greater Potion restores FULL HP, the
        // gold is rounded, and the event is a 'purchase'. One engine path (RpgRepository.buyPotion).
        viewModelScope.launch {
            repository.buyPotion(if (isGreater) "bigpotion" else "potion")
        }
    }

    fun updateCharacter(char: CharacterEntity) {
        viewModelScope.launch {
            repository.insertCharacter(char)
        }
    }

    val allTags: StateFlow<List<TagEntity>> = repository.allTags
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // WP-8.14: the custom views (Analytics) moved to AnalyticsViewModel.

    /** [id] is chosen by the caller (the editor attaches it to the task before saving, CR-KT-008). */
    fun createTag(name: String, color: String, id: String = newTagId()) {
        viewModelScope.launch {
            // Stage 3 / T3.5 (D12, opus-review finding #3): assign the append-style ordinal a
            // genuinely new tag needs to sort after every existing one (PWA `S.tags.push`).
            val tag = TagEntity(
                id = id,
                name = name,
                color = color,
                createdAt = repository.physicalNow(),
                updatedAt = repository.hlcClock.now(),
                orderIndex = repository.nextTagOrderIndex()
            )
            repository.insertTag(tag)
        }
    }

    fun deleteTag(tagId: String, tagName: String) {
        viewModelScope.launch {
            repository.deleteTagById(tagId)
            val tasks = repository.getAllTasksSync()
            tasks.forEach { task ->
                if (task.tags.contains(tagName) || task.tags.contains(tagId)) {
                    val updatedTags = task.tags.filter { it != tagName && it != tagId }
                    repository.updateTask(task.copy(tags = updatedTags))
                }
            }
        }
    }

    fun createTask(
        type: String,
        title: String,
        notes: String = "",
        difficulty: String = "easy",
        repeat: List<Boolean>? = EditorTitleRules.NEW_TASK_REPEAT, // CR-KT-017: Opti template, every type
        checklist: List<ChecklistItem> = emptyList(),
        tags: List<String> = emptyList(),
        resetFreq: String = "daily",
        reminders: List<com.example.data.local.entities.ReminderSpec>? = null, // WP-8.11
        quickLog: com.example.data.local.entities.QuickLogMode? = null, // WP-8.13 D-1
        up: Boolean? = null, // habit "+ Positive"; null = on (Opti app.js:6280)
        down: Boolean? = null // habit "− Negative"; null = on (Opti app.js:6281)
    ) {
        viewModelScope.launch {
            // Stage 3 / T3.5 (D12, opus-review finding #3): assign the prepend-style ordinal a
            // genuinely new task needs to sort before every existing one (PWA `S.tasks.unshift`).
            val task = TaskEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                title = title,
                notes = notes,
                difficulty = difficulty,
                repeat = repeat,
                checklist = checklist,
                tags = tagTokensToIds(tags, repository.getAllTagsSync()), // CR-KT-008: ids, not names
                value = 0.0,
                done = false,
                streak = 0,
                resetFreq = if (type == "habit") resetFreq else null,
                reminders = reminders,
                quickLog = if (type == "habit") quickLog else null,
                up = up,
                down = down,
                createdAt = repository.physicalNow(),
                updatedAt = repository.hlcClock.now(),
                orderIndex = repository.nextTaskOrderIndex()
            )
            repository.insertTask(task)
            repository.logTaskCreate(task) // CR-KT-016: Opti saveTask `logEvent({kind:'create', ...})`
            _createdTaskIds.tryEmit(task.id)
            _buzzes.tryEmit(GestureSpec.BUZZ_POSITIVE_MS) // Opti saveTask new task: buzz(50), app.js:6227
        }
    }

    fun updateTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(task)
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repository.deleteTaskById(taskId)
            syncDataStore.recordDeletion(taskId)
        }
    }

    // ============================================================ Bulk Selection Mode (`bulk-select-v1`)
    // Process-scoped selection state for the dashboard's bulk-action bar (Worker 2 owns the UI).
    // The VM never derives the selection itself: [selectAll] takes the currently-VISIBLE
    // (filtered) ids from the caller (see TagFilter.taskMatchesTag for how the visible list is
    // derived), so a "select all" never widens to a DB-wide select. Selection is never persisted
    // (no DataStore/Room write on any of these paths); a successful bulk action auto-exits.

    private val _selectionMode = MutableStateFlow(false)
    /**
     * True while the dashboard is in bulk-selection mode (the bulk-action bar is showing).
     * Process-scoped only; never persisted across process death.
     */
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * The currently selected task ids. Set only via [toggleSelected]/[selectAll]; cleared by
     * [exitSelectionMode] and after every successful bulk action. Process-scoped only.
     */
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /** Enters bulk-selection mode with an empty selection. Performs no DB writes. */
    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    /** Exits bulk-selection mode and clears the selection. Performs no DB writes. */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    /** Flips [id]'s membership in the selection. Performs no DB writes. */
    fun toggleSelected(id: String) {
        _selectedIds.update { cur -> if (id in cur) cur - id else cur + id }
    }

    /**
     * Replaces the selection with [ids] -- the caller passes the currently-VISIBLE (filtered)
     * task ids, so this never performs a DB-wide select. Performs no DB writes.
     */
    fun selectAll(ids: List<String>) {
        _selectedIds.value = ids.toSet()
    }

    /**
     * Applies the tag diff ([add]/[remove] are tag IDS, CR-KT-008) to every selected task
     * ([RpgRepository.bulkUpdateTags]) and exits
     * selection mode on success. A failed batch keeps the selection so the user can retry.
     */
    fun bulkEditTags(add: Set<String>, remove: Set<String>) {
        viewModelScope.launch {
            repository.bulkUpdateTags(_selectedIds.value.toList(), add, remove)
            exitSelectionMode()
        }
    }

    /**
     * Overwrites difficulty on every selected task ([RpgRepository.bulkSetDifficulty], which
     * `require`s one of trivial/easy/medium/hard) and exits selection mode on success. A failed
     * batch keeps the selection so the user can retry.
     */
    fun bulkSetDifficulty(difficulty: String) {
        viewModelScope.launch {
            repository.bulkSetDifficulty(_selectedIds.value.toList(), difficulty)
            exitSelectionMode()
        }
    }

    /**
     * Tombstones every selected task ([RpgRepository.bulkDelete]), records each id in the local
     * deletions (as [deleteTask] does per id) and exits selection mode on success. A failed batch
     * keeps the selection so the user can retry.
     */
    fun bulkDeleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            repository.bulkDelete(ids)
            ids.forEach { syncDataStore.recordDeletion(it) }
            exitSelectionMode()
        }
    }

    fun createReward(title: String, cost: Double, notes: String = "") {
        viewModelScope.launch {
            // Stage 3 / T3.5 (D12, opus-review finding #3): assign the append-style ordinal a
            // genuinely new reward needs to sort after every existing one (PWA `S.rewards.push`).
            val reward = RewardEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                cost = cost,
                notes = notes,
                createdAt = repository.physicalNow(),
                updatedAt = repository.hlcClock.now(),
                orderIndex = repository.nextRewardOrderIndex()
            )
            repository.insertReward(reward)
            _createdRewardIds.tryEmit(reward.id)
        }
    }

    /** Opti saveReward for an existing reward (app.js:7195). Reads the row fresh, so a reward
     *  deleted by a sync while the sheet was open is not brought back (Opti's -1 guard). */
    fun editReward(id: String, title: String, cost: Double, notes: String) {
        viewModelScope.launch {
            val cur = repository.getAllRewardsSync().firstOrNull { it.id == id }
            if (cur == null) { _toasts.tryEmit("That reward no longer exists"); return@launch }
            repository.insertReward(cur.copy(title = title, cost = cost, notes = notes, updatedAt = repository.hlcClock.now()))
        }
    }

    fun updateReward(reward: RewardEntity) {
        viewModelScope.launch {
            repository.insertReward(reward)
        }
    }

    fun deleteReward(rewardId: String) {
        viewModelScope.launch {
            repository.deleteRewardById(rewardId)
            syncDataStore.recordDeletion(rewardId)
        }
    }

    /**
     * The History tab's manual rollover. WP-8.10: it runs the one startDay path under
     * [BootGate.dayMutex] (so it can open RYA and never bypasses the :2922 guard); on a day
     * already crossed it is a no-op, as Opti's runCron.
     */
    fun forceCron() {
        viewModelScope.launch {
            BootGate.dayMutex.withLock { openRyaLocked(BootGate.clock()) }
        }
    }

    // Class factory
    class Factory(
        private val repository: RpgRepository,
        private val syncManager: SyncManager,
        private val syncDataStore: SyncDataStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository, syncManager, syncDataStore) as T
        }
    }
}
