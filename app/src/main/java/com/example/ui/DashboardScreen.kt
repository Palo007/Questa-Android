package com.example.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.example.data.local.entities.QuickLogMode
import com.example.launcher.QuickLogShortcutEditor
import com.example.ui.gestures.SheetBackHandler
import com.example.data.LocalDay
import com.example.data.boot.BootGate
import com.example.ui.gestures.DragActivity
import com.example.ui.gestures.GestureSpec
import com.example.ui.gestures.LocalBuzz
import com.example.ui.gestures.LocalDragActivity
import com.example.ui.gestures.LocalInputGated
import com.example.ui.gestures.LocalSwipeExclusions
import com.example.ui.gestures.ModalKeys
import com.example.ui.gestures.PasteParse
import com.example.ui.gestures.ReportModal
import com.example.ui.gestures.SwipeExclusions
import com.example.ui.gestures.SyncGateBanner
import com.example.ui.gestures.gatedInput
import com.example.ui.gestures.optiDragPickup
import com.example.ui.gestures.optiPress
import com.example.ui.gestures.optiRowDrag
import com.example.ui.gestures.optiTap
import com.example.ui.gestures.rememberDeviceBuzz
import com.example.ui.gestures.reorderMove
import com.example.ui.gestures.FollowWhenNotDragging
import com.example.ui.gestures.GroupBadge
import com.example.ui.gestures.GroupDrag
import com.example.ui.gestures.GroupDragWatch
import com.example.ui.gestures.cardGapAbove
import com.example.ui.gestures.groupCollapse
import com.example.ui.gestures.groupStack
import com.example.ui.gestures.reorderUnder
import com.example.ui.gestures.swipeNavExclusion
import com.example.ui.gestures.swipeTabs
import com.example.ui.gestures.tabSlideIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import com.example.data.RpgEngine
import com.example.data.local.entities.ChecklistItem
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import com.example.data.local.entities.TagEntity
import com.example.data.local.entities.CustomViewEntity
import com.example.ui.theme.*
import com.example.data.local.entities.ReminderSpec
import com.example.reminders.ReminderEditorRules
import com.example.reminders.SystemReminderClock
import java.util.*
import org.burnoutcrew.reorderable.*
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.unit.em
import com.example.ui.visuals.*
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import java.io.File
import com.example.data.sync.SerializationEngine
import com.example.ui.analytics.AnalyticsScreen
import com.example.ui.analytics.AnalyticsViewModel
import com.example.ui.settings.SettingsHost
import com.example.ui.settings.SettingsRoute
import com.example.ui.settings.SettingsViewModel

import androidx.compose.runtime.CompositionLocalProvider
import com.example.ui.attachments.AttachmentStrip
import com.example.ui.attachments.LocalAttachmentEditSession
import com.example.data.attachments.AttachmentDelta
import com.example.data.attachments.AttachmentEditSession
import com.example.ui.attachments.TaskAttachmentThumb
import androidx.compose.runtime.staticCompositionLocalOf

val LocalFeedbacks = staticCompositionLocalOf<kotlinx.coroutines.flow.SharedFlow<FloatingFeedback>> { error("No feedbacks provided") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    // WP-8.14: Settings and Analytics have their own ViewModels (factories on the app object).
    settingsViewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = SettingsViewModel.Factory),
    analyticsViewModel: AnalyticsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AnalyticsViewModel.Factory),
) {
    val characterState by viewModel.character.collectAsState()
    val tasksState by viewModel.allTasks.collectAsState()
    val rewardsState by viewModel.allRewards.collectAsState()
    val logsState by viewModel.allLogs.collectAsState()
    val levelUpEvent by viewModel.levelUpEvent.collectAsState()
    val deathEvent by viewModel.deathEvent.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val dbTagsState by viewModel.allTags.collectAsStateWithLifecycle(emptyList())
    val rawTasksState by viewModel.rawTasks.collectAsStateWithLifecycle(emptyList())
    val isSaveButtonTop by viewModel.isSaveButtonTop.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    // bulk-select-v1: selection-mode state, process-scoped. The VM exits the mode after every
    // successful bulk action; the UI below only hoists it.
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val habitsScroll by viewModel.habitsScroll.collectAsState()
    val dailiesScroll by viewModel.dailiesScroll.collectAsState()
    val todosScroll by viewModel.todosScroll.collectAsState()
    val rewardsScroll by viewModel.rewardsScroll.collectAsState()

    // bulk-select-v1: the sheet/dialog surfaces owned by this screen.
    var showBulkTagSheet by remember { mutableStateOf(false) }
    var showBulkDiffSheet by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    // WP-8.18 (PX-88): the Appearance prefs (WP-8.14 provider) and the toast queue.
    val cardPrefs = LocalCardPrefs.current
    val toastState = rememberToastState()

    // WP-8.16: Opti buzzes only where `buzz(ms)` is called, with its exact duration (GestureSpec).
    // The ViewModel emits the post-action buzzes; the pickup buzzes (15 ms) are UI-side.
    val buzz = rememberDeviceBuzz(hapticsEnabled)
    LaunchedEffect(viewModel, buzz) { viewModel.buzzes.collect { buzz.buzz(it) } }

    // §5.0 B13: the "syncing…" banner and layer 2 read BootGate.pending (app.js:2789, :5107).
    val inputGated by BootGate.pending.collectAsStateWithLifecycle()
    // §5.0 B8: the swipe is off while a sheet is open (app.js:8480); RYA counts for the swipe only.
    val modalOpen by viewModel.modalOpen.collectAsStateWithLifecycle()
    val ryaShowing by viewModel.rya.collectAsStateWithLifecycle()
    val dragActivity = remember { DragActivity() }
    val swipeExclusions = remember { SwipeExclusions() }

    val selectedTab by viewModel.selectedTab.collectAsState()
    // (dir, serial) of the last swipe; a nav tap is dir 0 (Opti switchTab(tab, 0) does not animate).
    var tabSlide by remember { mutableStateOf(0 to 0) }
    val tabs = listOf("Habits", "Dailies", "Todos", "Rewards", "Analytics", "Activity")
    // CR-KT-012: the Activity tab's range + task filter, set by Analytics and the task editor.
    var activityNav by rememberSaveable(stateSaver = com.example.ui.activity.ActivityNavSaver) { mutableStateOf(com.example.ui.activity.ActivityNav()) }

    // State for task creation / editing
    var showEditSheet by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<TaskEntity?>(null) }


    // State for reward creation dialog
    var showRewardCreator by remember { mutableStateOf(false) }
    // Opti openReward(id): the same sheet edits an existing reward; null = a new one.
    var rewardToEdit by remember { mutableStateOf<RewardEntity?>(null) }

    // State for bulk repetitions logging dialog
    var showBulkRepDialog by remember { mutableStateOf(false) }
    var bulkRepTask by remember { mutableStateOf<TaskEntity?>(null) }
    var bulkRepIsPositive by remember { mutableStateOf(true) }
    var bulkRepCountStr by remember { mutableStateOf("1") }

    // WP-8.16 (§5.0 B8): every Kotlin surface of Opti's #scrim reports to modalOpen. RYA does not.
    val settingsRouteForModal by viewModel.settingsRoute.collectAsStateWithLifecycle()
    val settingsUi by settingsViewModel.state.collectAsStateWithLifecycle()
    // CR-KT-018: lifecycle-aware, so the badge's 5 s poll stops while the app is in the background.
    val syncBadge by settingsViewModel.syncBadge.collectAsStateWithLifecycle()
    ReportModal(ModalKeys.TASK_EDITOR, showEditSheet, viewModel::setModalOpen)
    ReportModal(ModalKeys.REP_SHEET, showBulkRepDialog, viewModel::setModalOpen)
    ReportModal(ModalKeys.REWARD_EDITOR, showRewardCreator, viewModel::setModalOpen)
    ReportModal(ModalKeys.SETTINGS, settingsRouteForModal != null, viewModel::setModalOpen)
    ReportModal(ModalKeys.FIRST_CONNECT, settingsUi.firstConnect != null, viewModel::setModalOpen)
    ReportModal(ModalKeys.BULK_TAGS, showBulkTagSheet, viewModel::setModalOpen)
    ReportModal(ModalKeys.BULK_DIFF, showBulkDiffSheet, viewModel::setModalOpen)
    ReportModal(ModalKeys.BULK_DELETE, showBulkDeleteDialog, viewModel::setModalOpen)

    // PX-96: system Back closes an open sheet before it can leave the app (Opti's scrim tap,
    // app.js:8522: closeSheet(); closeRepSheet()). The sheets are their own windows and close on
    // Back themselves; this covers a Back that reaches the activity while one is still showing.
    SheetBackHandler(open = showEditSheet || showBulkRepDialog || showRewardCreator
        || showBulkTagSheet || showBulkDiffSheet || showBulkDeleteDialog) {
        showEditSheet = false
        showBulkRepDialog = false
        showRewardCreator = false
        showBulkTagSheet = false
        showBulkDiffSheet = false
        showBulkDeleteDialog = false
    }

    // bulk-select-v1: Back exits selection mode. The bulk sheets above swallow their own Back
    // first (they are excluded from `enabled` while open), so this fires only on the bare list.
    BackHandler(enabled = selectionMode && !showBulkTagSheet && !showBulkDiffSheet && !showBulkDeleteDialog) {
        viewModel.exitSelectionMode()
    }

    CompositionLocalProvider(
        LocalFeedbacks provides viewModel.floatingFeedbacks,
        LocalBuzz provides buzz,
        LocalDragActivity provides dragActivity,
        LocalSwipeExclusions provides swipeExclusions,
        LocalInputGated provides inputGated,
        LocalTags provides dbTagsState,
    ) {
      Box(Modifier.fillMaxSize()) {
        Scaffold(
            // PX-92: Opti binds the swipe to the whole document (app.js:8473).
            modifier = modifier.fillMaxSize().swipeTabs(
                currentTab = { selectedTab },
                blocked = { modalOpen || ryaShowing != null },
                onSwipe = { tab, dir -> viewModel.setSelectedTab(tab); tabSlide = dir to tabSlide.second + 1 },
            ),
            containerColor = MaterialTheme.colorScheme.background,

        floatingActionButton = {
            if (selectedTab in 0..3) {
                FloatingActionButton(
                    onClick = {
                        if (selectedTab == 3) {
                            rewardToEdit = null
                            showRewardCreator = true
                        } else {
                            val defaultType = when (selectedTab) {
                                0 -> "habit"
                                1 -> "daily"
                                else -> "todo"
                            }
                            taskToEdit = TaskEntity(
                                id = "",
                                type = defaultType,
                                title = "",
                                difficulty = "easy",
                                // WP-8.23: an unsaved draft (id ""); onSave routes it to createTask, which stamps both.
                                createdAt = 0L,
                                updatedAt = 0L
                            )
                            showEditSheet = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("add_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create New")
                }
            }
        },
        bottomBar = {
            // WP-8.18 (PX-109): Opti `nav` (index.html:483-490, 1067-1073).
            OptiNavBar(
                tabs = tabs,
                selected = selectedTab,
                maxWidth = appWidth(cardPrefs),
                onSelect = { index ->
                    activityNav = com.example.ui.activity.activityNavOnTabSelect(activityNav, index)
                    viewModel.setSelectedTab(index)
                    tabSlide = 0 to tabSlide.second + 1
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val character = characterState
            if (character == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // WP-8.22-D review 46 F1: a fresh install whose start-up failed never loads the
                    // character (the seed waits on the barrier), so the error line and a way into
                    // Settings replace the endless spinner. Start-up OK: the spinner, as before.
                    if (settingsUi.startupFailed) {
                        com.example.ui.gestures.StartupFailedPlaceholder(onOpenSettings = { viewModel.openSettings(SettingsRoute.Root) })
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // WP-8.18 (PX-88): `header,main{max-width:var(--appw)}` centered; --appw is the
                    // Interface width pref (Opti applyWidth, app.js:2018).
                    AppWidthColumn(appWidth(cardPrefs)) {
                        // Opti <header> (renderStats); only the gear opens Settings. The ↻ runs the
                        // guarded manual sync (Settings "Sync now", incl. the Pull/Push choice); G-39.
                        OptiHeader(
                            character = character,
                            onGear = { viewModel.openSettings(SettingsRoute.Root) },
                            onRefresh = { settingsViewModel.syncNow() },
                            paused = settingsUi.paused,
                            gearStale = gearStale(settingsUi.lastExportTs, settingsUi.nowMs),
                            // CR-KT-018: Opti #syncBadge; a tap opens Settings like the gear.
                            syncBadge = syncBadge,
                            onSyncBadgeClick = { viewModel.openSettings(SettingsRoute.Root) },
                        )

                        // `main{padding:0 10px}` (index.html:84)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = OptiDims.MAIN_PAD_H.dp)) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // §5.0 B13: Opti prepends `<div class="syncGate">syncing…</div>` to #view.
                        SyncGateBanner(inputGated)
                        // WP-8.22-D item 1: a failed start-up is said here, not only in Settings.
                        com.example.ui.gestures.StartupFailedBanner(settingsUi.startupFailed)
                        // CR-KT-014: the prune warning when the notification could not be posted (all tabs).
                        com.example.ui.activity.PruneWarningBannerHost(
                            onBackup = { viewModel.openSettings(SettingsRoute.Backup) },
                            covered = modalOpen || ryaShowing != null,
                        )

                        val dailyFilter by viewModel.dailyFilter.collectAsState()
                        val selectedTag by viewModel.selectedTag.collectAsState()
                        val habitSort by viewModel.habitSort.collectAsState()
                        val dailySort by viewModel.dailySort.collectAsState()
                        val todoSort by viewModel.todoSort.collectAsState()
                        val rewardSort by viewModel.rewardSort.collectAsState()
                        
                        var filterOpen by remember { mutableStateOf(false) }
                        var sortOpen by remember { mutableStateOf(false) }
                        
                        // Extract unique tags from both notes and the database; raw tokens may be
                        // tag ids or tag names (see TagFilter.tagFilterLabels).
                        val allTags = remember(rawTasksState, dbTagsState) {
                            tagFilterLabels(rawTasksState.flatMap { it.tags }, dbTagsState)
                        }

                        if (selectedTab in 0..3) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Search Input (Only for Tabs 0, 1, 2). Bulk-select-v1: hidden in
                                    // selection mode, which replaces this row with SelectionContextBar.
                                    if (!selectionMode && selectedTab in 0..2) {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { viewModel.setSearchQuery(it) },
                                            placeholder = { Text("Search tasks...", fontSize = 13.sp) },
                                            keyboardOptions = OptiKeyboard.Text,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Search,
                                                    contentDescription = "Search",
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            },
                                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                                {
                                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                        Icon(
                                                            Icons.Default.Clear,
                                                            contentDescription = "Clear search",
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            } else null,
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedContainerColor = SurfaceDark,
                                                unfocusedContainerColor = SurfaceDark,
                                                focusedBorderColor = PrimaryNeon,
                                                unfocusedBorderColor = BorderLavender.copy(alpha = 0.5f),
                                                focusedPlaceholderColor = TextSecondary,
                                                unfocusedPlaceholderColor = TextSecondary,
                                                focusedLeadingIconColor = PrimaryNeon,
                                                unfocusedLeadingIconColor = TextSecondary,
                                                focusedTrailingIconColor = PrimaryNeon,
                                                unfocusedTrailingIconColor = TextSecondary
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    } else if (!selectionMode) {
                                        // If selectedTab == 3 (Rewards), we can show a title
                                        Text(
                                            "Available Rewards",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryNeon,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    // FILTER BUTTON (Only for Tabs 0, 1, 2)
                                    if (!selectionMode && selectedTab in 0..2) {
                                        val currentFilter = when (selectedTab) {
                                            0 -> viewModel.habitFilter.collectAsState().value
                                            1 -> viewModel.dailyFilter.collectAsState().value
                                            else -> viewModel.todoFilter.collectAsState().value
                                        }
                                        val isFilterActive = currentFilter != "all" && currentFilter != "active" || selectedTag != null
                                        IconButton(
                                            onClick = {
                                                filterOpen = !filterOpen
                                                val habitNow = viewModel.habitFilter.value
                                                val habitNext = habitFilterAfterPanelToggle(filterOpen, selectedTab, habitNow)
                                                if (habitNext != habitNow) viewModel.setHabitFilter(habitNext)
                                            },
                                            modifier = Modifier
                                                .size(52.dp)
                                                .background(if (filterOpen) PrimaryNeon.copy(alpha = 0.2f) else SurfaceDark, RoundedCornerShape(12.dp))
                                                .border(
                                                    1.dp,
                                                    if (filterOpen || isFilterActive) PrimaryNeon else BorderLavender.copy(alpha = 0.5f),
                                                    RoundedCornerShape(12.dp)
                                                )
                                        ) {
                                            Icon(
                                                Icons.Default.FilterList,
                                                contentDescription = "Filter",
                                                tint = if (filterOpen || isFilterActive) PrimaryNeon else TextPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }

                                    // SORT BUTTON
                                    val currentSort = when (selectedTab) {
                                        0 -> viewModel.habitSort.collectAsState().value
                                        1 -> viewModel.dailySort.collectAsState().value
                                        2 -> viewModel.todoSort.collectAsState().value
                                        else -> viewModel.rewardSort.collectAsState().value
                                    }
                                    val isSortActive = currentSort != "manual"
                                    if (!selectionMode || selectedTab == 3) {
                                    IconButton(
                                        onClick = { sortOpen = !sortOpen },
                                        modifier = Modifier
                                            .size(52.dp)
                                            .background(if (sortOpen) PrimaryNeon.copy(alpha = 0.2f) else SurfaceDark, RoundedCornerShape(12.dp))
                                            .border(
                                                1.dp,
                                                if (sortOpen || isSortActive) PrimaryNeon else BorderLavender.copy(alpha = 0.5f),
                                                RoundedCornerShape(12.dp)
                                            )
                                    ) {
                                        Icon(
                                            Icons.Default.Sort,
                                            contentDescription = "Sort",
                                            tint = if (sortOpen || isSortActive) PrimaryNeon else TextPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    }

                                    // bulk-select-v1: the entry is this compact select icon (the header
                                    // has no overflow menu); while active, SelectionContextBar replaces
                                    // the row's other controls.
                                    if (selectionMode && selectedTab in 0..2) {
                                        val currentType = when (selectedTab) { 0 -> "habit"; 1 -> "daily"; else -> "todo" }
                                        val visibleIds = tasksState.filter { it.type == currentType }.map { it.id }
                                        SelectionContextBar(
                                            selectedCount = selectedIds.size,
                                            allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds },
                                            onToggleSelectAll = {
                                                if (visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }) viewModel.selectAll(emptyList())
                                                else viewModel.selectAll(visibleIds)
                                            },
                                            onClose = { viewModel.exitSelectionMode() },
                                            modifier = Modifier.weight(1f),
                                        )
                                    } else if (selectedTab in 0..2) {
                                        IconButton(
                                            onClick = { viewModel.enterSelectionMode() },
                                            modifier = Modifier
                                                .size(52.dp)
                                                .background(SurfaceDark, RoundedCornerShape(12.dp))
                                                .border(1.dp, BorderLavender.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                                .testTag("enter_selection"),
                                        ) {
                                            Icon(
                                                Icons.Default.SelectAll,
                                                contentDescription = "Select tasks",
                                                tint = TextPrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                }

                                // Expanded Filter Bar
                                AnimatedVisibility(
                                    visible = filterOpen && selectedTab in 0..2 && !selectionMode,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            when (selectedTab) {
                                                0 -> { // Habits
                                                    val current = viewModel.habitFilter.collectAsState().value
                                                    val options = listOf(
                                                        "all" to "All",
                                                        "weak" to "Weak",
                                                        "strong" to "Strong",
                                                        "log" to "Log"
                                                    )
                                                    options.forEach { (key, label) ->
                                                        FilterButton(
                                                            label = label,
                                                            selected = current == key,
                                                            onClick = { viewModel.setHabitFilter(key) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                                1 -> { // Dailies
                                                    val current = viewModel.dailyFilter.collectAsState().value
                                                    val options = listOf(
                                                        "all" to "All",
                                                        "due" to "Due Today",
                                                        "notdue" to "Not Due Today"
                                                    )
                                                    options.forEach { (key, label) ->
                                                        FilterButton(
                                                            label = label,
                                                            selected = current == key,
                                                            onClick = { viewModel.setDailyFilter(key) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                                2 -> { // Todos
                                                    val current = viewModel.todoFilter.collectAsState().value
                                                    val options = listOf(
                                                        "active" to "Active Only",
                                                        "all" to "All"
                                                    )
                                                    options.forEach { (key, label) ->
                                                        FilterButton(
                                                            label = label,
                                                            selected = current == key,
                                                            onClick = { viewModel.setTodoFilter(key) },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Tag Filter Bar
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                "Tags:",
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                            
                                            LazyRow(
                                                // A horizontal list owns its own swipe (Opti `.seg`, app.js:8475).
                                                modifier = Modifier.weight(1f).swipeNavExclusion(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (dbTagsState.isNotEmpty()) {
                                                    item {
                                                        val isSelected = selectedTag == "None"
                                                        TagFilterButton(
                                                            label = "None",
                                                            selected = isSelected,
                                                            color = TextSecondary,
                                                            onClick = {
                                                                viewModel.setSelectedTag(if (isSelected) null else "None")
                                                            }
                                                        )
                                                    }
                                                }
                                                items(allTags.toList()) { tag ->
                                                    val dbTag = dbTagsState.find { it.name.equals(tag, ignoreCase = true) }
                                                    val tagColor = dbTag?.color?.let { safeTagColor(it) } ?: PrimaryNeon
                                                    val isSelected = selectedTag == tag
                                                    TagFilterButton(
                                                        label = tag,
                                                        selected = isSelected,
                                                        color = tagColor,
                                                        onClick = {
                                                            viewModel.setSelectedTag(if (isSelected) null else tag)
                                                        }
                                                    )
                                                }
                                            }

                                            if (selectedTag != null) {
                                                Text(
                                                    "clear",
                                                    fontSize = 12.sp,
                                                    color = TextSecondary,
                                                    modifier = Modifier
                                                        .clickable { viewModel.setSelectedTag(null) }
                                                        .padding(horizontal = 4.dp),
                                                    style = TextStyle(textDecoration = TextDecoration.Underline)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Expanded Sort Bar
                                AnimatedVisibility(
                                    visible = sortOpen && selectedTab in 0..3 && !selectionMode,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "Sort:",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )

                                        val currentSort = when (selectedTab) {
                                            0 -> viewModel.habitSort.collectAsState().value
                                            1 -> viewModel.dailySort.collectAsState().value
                                            2 -> viewModel.todoSort.collectAsState().value
                                            else -> viewModel.rewardSort.collectAsState().value
                                        }

                                        // Manual
                                        FilterButton(
                                            label = "Manual",
                                            selected = currentSort == "manual",
                                            onClick = {
                                                when (selectedTab) {
                                                    0 -> viewModel.setHabitSort("manual")
                                                    1 -> viewModel.setDailySort("manual")
                                                    2 -> viewModel.setTodoSort("manual")
                                                    else -> viewModel.setRewardSort("manual")
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Created
                                        val isCreatedActive = currentSort.startsWith("created-")
                                        val createdArrow = if (currentSort == "created-asc") " ↑" else if (currentSort == "created-desc") " ↓" else ""
                                        FilterButton(
                                            label = "Created$createdArrow",
                                            selected = isCreatedActive,
                                            onClick = {
                                                val next = if (currentSort == "created-desc") "created-asc" else "created-desc"
                                                when (selectedTab) {
                                                    0 -> viewModel.setHabitSort(next)
                                                    1 -> viewModel.setDailySort(next)
                                                    2 -> viewModel.setTodoSort(next)
                                                    else -> viewModel.setRewardSort(next)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                        // Updated
                                        val isUpdatedActive = currentSort.startsWith("updated-")
                                        val updatedArrow = if (currentSort == "updated-asc") " ↑" else if (currentSort == "updated-desc") " ↓" else ""
                                        FilterButton(
                                            label = "Updated$updatedArrow",
                                            selected = isUpdatedActive,
                                            onClick = {
                                                val next = if (currentSort == "updated-desc") "updated-asc" else "updated-desc"
                                                when (selectedTab) {
                                                    0 -> viewModel.setHabitSort(next)
                                                    1 -> viewModel.setDailySort(next)
                                                    2 -> viewModel.setTodoSort(next)
                                                    else -> viewModel.setRewardSort(next)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    }
                                }
                            }
                        }

                        // Selected list pane
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .tabSlideIn(tabSlide)
                        ) {
                            when (selectedTab) {
                                0 -> HabitsTabContent(
                                    tasks = tasksState.filter { it.type == "habit" },
                                    onScoreUp = { id ->
                                        viewModel.scoreHabitUp(id)
                                    },
                                    onScoreDown = { id ->
                                        viewModel.scoreHabitDown(id)
                                    },
                                    onLongPressUp = { task ->
                                        buzz.buzz(GestureSpec.BUZZ_PICKUP_MS) // app.js:8536
                                        bulkRepTask = task
                                        bulkRepIsPositive = true
                                        bulkRepCountStr = "1"
                                        showBulkRepDialog = true
                                    },
                                    onLongPressDown = { task ->
                                        buzz.buzz(GestureSpec.BUZZ_PICKUP_MS)
                                        bulkRepTask = task
                                        bulkRepIsPositive = false
                                        bulkRepCountStr = "1"
                                        showBulkRepDialog = true
                                    },
                                    onEdit = { task ->
                                        taskToEdit = task
                                        showEditSheet = true
                                    },
                                    onPersistOrder = { ids -> viewModel.persistTaskOrder(ids) },
                                    onMove = { id, up -> viewModel.reorderTask(id, up) },
                                    sortMode = habitSort,
                                    createdIds = viewModel.createdTaskIds,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onToggleSelect = { id -> viewModel.toggleSelected(id) },
                                    scrollPosition = habitsScroll,
                                    onScrollChanged = { idx, off -> viewModel.setHabitsScroll(idx, off) },
                                )
                                1 -> DailiesTabContent(
                                    tasks = tasksState.filter { it.type == "daily" },
                                    onToggleCheck = { id, checked ->
                                        if (checked) viewModel.completeTask(id, applyDueTodayGate = true) else viewModel.uncompleteTask(id)
                                    },
                                    onEdit = { task ->
                                        taskToEdit = task
                                        showEditSheet = true
                                    },
                                    onToggleSubtask = { t, subId, idx -> viewModel.toggleSubtask(t.id, subId, idx) },
                                    onReorderSubtasks = { t, ids -> viewModel.reorderSubtasks(t.id, ids) },
                                    onPersistOrder = { ids -> viewModel.persistTaskOrder(ids) },
                                    onMove = { id, up -> viewModel.reorderTask(id, up) },
                                    sortMode = dailySort,
                                    createdIds = viewModel.createdTaskIds,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onToggleSelect = { id -> viewModel.toggleSelected(id) },
                                    scrollPosition = dailiesScroll,
                                    onScrollChanged = { idx, off -> viewModel.setDailiesScroll(idx, off) },
                                )
                                2 -> TodosTabContent(
                                    tasks = tasksState.filter { it.type == "todo" },
                                    onToggleCheck = { id, checked ->
                                        if (checked) viewModel.completeTask(id, applyDueTodayGate = true) else viewModel.uncompleteTask(id)
                                    },
                                    onEdit = { task ->
                                        taskToEdit = task
                                        showEditSheet = true
                                    },
                                    onToggleSubtask = { t, subId, idx -> viewModel.toggleSubtask(t.id, subId, idx) },
                                    onReorderSubtasks = { t, ids -> viewModel.reorderSubtasks(t.id, ids) },
                                    onPersistOrder = { ids -> viewModel.persistTaskOrder(ids) },
                                    onMove = { id, up -> viewModel.reorderTask(id, up) },
                                    sortMode = todoSort,
                                    createdIds = viewModel.createdTaskIds,
                                    selectionMode = selectionMode,
                                    selectedIds = selectedIds,
                                    onToggleSelect = { id -> viewModel.toggleSelected(id) },
                                    scrollPosition = todosScroll,
                                    onScrollChanged = { idx, off -> viewModel.setTodosScroll(idx, off) },
                                )
                                3 -> RewardsTabContent(
                                    rewards = rewardsState,
                                    characterGold = character.gold,
                                    onBuy = { id ->
                                        viewModel.buyReward(id)
                                    },
                                    onBuyPotion = { isGreater ->
                                        viewModel.buyPotion(isGreater)
                                    },
                                    onEdit = { reward ->
                                        rewardToEdit = reward
                                        showRewardCreator = true
                                    },
                                    onPersistOrder = { ids -> viewModel.persistRewardOrder(ids) },
                                    onMove = { id, up -> viewModel.reorderReward(id, up) },
                                    sortMode = rewardSort,
                                    createdIds = viewModel.createdRewardIds,
                                    scrollPosition = rewardsScroll,
                                    onScrollChanged = { idx, off -> viewModel.setRewardsScroll(idx, off) },
                                )
                                4 -> AnalyticsScreen(analyticsViewModel,
                                    onModalChange = viewModel::setModalOpen,
                                    onSeeActivity = { range ->
                                        activityNav = activityNav.forRange(range)
                                        viewModel.setSelectedTab(5)
                                        tabSlide = 0 to tabSlide.second + 1
                                    },
                                )
                                // CR-KT-012: the Activity tab. Settings' feed filters stay the only source of truth.
                                5 -> com.example.ui.activity.ActivityScreen(
                                    logs = logsState,
                                    feedFilters = settingsUi.feed,
                                    nav = activityNav,
                                    onNavChange = { activityNav = it },
                                    onToggleDiag = { hide -> settingsViewModel.setHideSyncDiag(hide) },
                                    zone = analyticsViewModel.zone(),
                                    nowMs = analyticsViewModel.nowMs,
                                )
                            }
                        }

                        // bulk-select-v1: the bottom action bar replaces per-task affordances while the
                        // mode is active; it owns no state -- the sheets/dialog below do the work.
                        if (selectionMode && selectedTab in 0..2) {
                            BulkActionBar(
                                selectedCount = selectedIds.size,
                                onTags = { showBulkTagSheet = true },
                                onDifficulty = { showBulkDiffSheet = true },
                                onDelete = { showBulkDeleteDialog = true },
                            )
                        }
                        } // main
                    }
                }
            }

            // WP-8.18: Opti's bulk reps sheet (drawRepSheet, app.js:2562-2600).
            if (showBulkRepDialog) {
                val task = bulkRepTask
                if (task != null) {
                    RepSheet(
                        title = task.title,
                        positive = bulkRepIsPositive,
                        onClose = { showBulkRepDialog = false },
                        onCommit = { n ->
                            // Opti commitReps: closeRepSheet(); addReps(id, n) with the signed n.
                            showBulkRepDialog = false
                            viewModel.addHabitReps(task.id, kotlin.math.abs(n), n > 0)
                        }
                    )
                }
            }

            // RECORD YESTERDAY'S ACTIVITY (RYA) -- WP-8.10, §5.0 B11 "Sheet UI" (Opti #yScrim,
            // drawYesterCheck app.js:2705-2729). Renders ONLY the frozen snapshot `rya`; a row tap
            // toggles a tick and credits nothing; "Start my day" confirms the tapped sheet. It
            // cannot be dismissed: back-press and an outside tap do nothing (:2699, :2721).
            // bulk-select-v1: tag sheet / difficulty sheet / delete dialog. Each confirm funnel goes
            // through the VM's single bulk entry point (which exits the mode on success), and every
            // local state flag closes with it.
            if (showBulkTagSheet) {
                val selectedTagLists = tasksState.filter { it.id in selectedIds }.map { it.tags }
                BulkTagSheet(
                    knownTags = dbTagsState,
                    selectedTagLists = selectedTagLists,
                    onApply = { add, remove ->
                        viewModel.bulkEditTags(add, remove)
                        showBulkTagSheet = false
                    },
                    onDismiss = { showBulkTagSheet = false },
                )
            }
            if (showBulkDiffSheet) {
                BulkDifficultySheet(
                    onPick = { value ->
                        viewModel.bulkSetDifficulty(value)
                        showBulkDiffSheet = false
                    },
                    onDismiss = { showBulkDiffSheet = false },
                )
            }
            if (showBulkDeleteDialog) {
                BulkDeleteDialog(
                    count = selectedIds.size,
                    onConfirm = {
                        viewModel.bulkDeleteSelected()
                        showBulkDeleteDialog = false
                    },
                    onDismiss = { showBulkDeleteDialog = false },
                )
            }

            val rya by viewModel.rya.collectAsState()
            // WP-8.18 (PX-104): the ViewModel's toasts go to Opti's styled #toast host.
            LaunchedEffect(viewModel, toastState) {
                viewModel.toasts.collect { toastState.show(it) }
            }
            rya?.let { sheet ->
                RyaSheet(
                    sheet = sheet,
                    onToggle = { viewModel.toggleRyaTick(it) },
                    onStartDay = { viewModel.confirmRya(sheet) }
                )
            }

            // GAME OVER OVERLAY
            if (character?.needsDeathAcknowledge == true) {
                // WP-8.18 (PX-107): Opti `.deathOverlay` (index.html:569-573, 1080-1083).
                DeathOverlay(onContinue = { viewModel.acknowledgeDeath() })
            }

            // LEVEL UP SPLASH CELEBRATION!
            levelUpEvent?.let { details ->
                Dialog(onDismissRequest = { viewModel.dismissLevelUp() }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .border(BorderStroke(2.dp, OptiRootTokens.Green), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "🎉 LEVEL UP! 🎉",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = OptiRootTokens.Green,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                "✨ LEVEL ${details.oldLvl} ➔ ${details.newLvl} ✨",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryNeon,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                "Your HP is fully restored. Continue your journey and conquer your tasks!",
                                fontSize = 14.sp,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Button(
                                onClick = {
                                    viewModel.dismissLevelUp()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = OptiRootTokens.Green),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dismiss_levelup_button")
                            ) {
                                Text(
                                    "TO ADVENTURE!",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            // TASK CREATOR / MODIFIER BOTTOM SHEET
            if (showEditSheet) {
                // CR-PWA-003 / RE-KT-009: count the user's image adds/removes while this editor
                // is open. Save takes the delta into its `edit` event; any other close (dismiss,
                // Back, History) writes it as an `edit` event with only the attachments item; a
                // result that lands after close is written as its own `edit` event.
                val attTaskId = taskToEdit?.id?.takeIf { it.isNotEmpty() }
                val attSession = remember(attTaskId) {
                    attTaskId?.let { tid -> AttachmentEditSession(tid) { d -> viewModel.logAttachmentEdit(tid, d) } }
                }
                DisposableEffect(attSession) {
                    onDispose { attSession?.let { s -> viewModel.logAttachmentEdit(s.taskId, s.close()) } }
                }
                CompositionLocalProvider(LocalAttachmentEditSession provides attSession) {
                TaskEditSheet(
                    task = taskToEdit,
                    availableTags = dbTagsState,
                    isSaveButtonTop = isSaveButtonTop,
                    onCreateTag = { id, name, color -> viewModel.createTag(name, color, id) },
                    onDeleteTag = { id, name -> viewModel.deleteTag(id, name) },
                    onDismiss = { showEditSheet = false },
                    // CR-KT-012: "History" closes the sheet and opens Activity filtered by this task.
                    onHistory = {
                        taskToEdit?.let { t ->
                            activityNav = activityNav.forTask(t.id, t.title)
                            showEditSheet = false
                            viewModel.setSelectedTab(5)
                            tabSlide = 0 to tabSlide.second + 1
                        }
                    },
                    onDelete = {
                        attSession?.close() // a deleted task gets no image `edit` event
                        taskToEdit?.let { t ->
                            viewModel.deleteTask(t.id)
                        }
                        showEditSheet = false
                    },
                    onSave = { type, title, notes, difficulty, repeat, checklist, tags, resetFreq, editedCUp, editedCDown, reminders, quickLog, up, down ->
                        if (taskToEdit != null && taskToEdit!!.id.isNotEmpty()) {
                            val updatedTask = taskToEdit!!.copy(
                                type = type,
                                title = title,
                                notes = notes,
                                difficulty = difficulty,
                                repeat = repeat,
                                checklist = checklist,
                                tags = tags,
                                resetFreq = EditorTitleRules.resetFreq(type, taskToEdit!!, resetFreq), // CR-KT-017
                                reminders = reminders,
                                quickLog = if (type == "habit") quickLog else taskToEdit!!.quickLog,
                                // Opti app.js:6280-6281: habit "+ Positive" / "− Negative" buttons.
                                up = if (type == "habit") up else taskToEdit!!.up,
                                down = if (type == "habit") down else taskToEdit!!.down,
                            )
                            // WP-8.16 (§4.4): the ViewModel stamps updatedAt from the shared HLC.
                            // CR-KT-016: one save = one `edit` event (fields, images, habit counter).
                            viewModel.saveEditedTask(
                                updatedTask, attSession?.close() ?: AttachmentDelta.NONE,
                                base = taskToEdit,
                                editedCUp = if (type == "habit") editedCUp else null,
                                editedCDown = if (type == "habit") editedCDown else null,
                            )
                        } else {
                            viewModel.createTask(
                                type = type,
                                title = title,
                                notes = notes,
                                difficulty = difficulty,
                                repeat = repeat,
                                checklist = checklist,
                                tags = tags,
                                resetFreq = resetFreq,
                                reminders = reminders,
                                quickLog = quickLog,
                                // Opti openEdit new-task template has up:true,down:true (app.js:6131).
                                up = up ?: true,
                                down = down ?: true
                            )
                        }
                        showEditSheet = false
                    }
                )
                }
            }

            // WP-8.14: Settings (Opti openSettings) replaces the old character customizer dialog;
            // the first-connect dialog can also open here while Settings is closed.
            val settingsRoute by viewModel.settingsRoute.collectAsStateWithLifecycle()
            SettingsHost(settingsViewModel, settingsRoute, onClose = { viewModel.closeSettings() }, onForceCron = { viewModel.forceCron() })

            // CUSTOM REWARD CREATOR DIALOG
            if (showRewardCreator) {
                val editing = rewardToEdit
                RewardCreatorDialog(
                    onDismiss = { showRewardCreator = false; rewardToEdit = null },
                    onCreate = { t, c, n ->
                        if (editing == null) viewModel.createReward(t, c, n)
                        else viewModel.editReward(editing.id, t, c, n)
                    },
                    existing = editing,
                    onDelete = editing?.let { r -> { viewModel.deleteReward(r.id) } },
                )
            }
        }
    }
        // WP-8.18 (PX-104): Opti `#toast`, above the nav (index.html:491).
        QuestaToastHost(toastState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        // Habitica-style +XP/+GP chips, rising and fading at the lower third of the screen.
        RewardBurstHost(viewModel.rewardBursts, Modifier.align(BiasAlignment(0f, 0.35f)))
      }
} // End of Scaffold content
} // End of CompositionLocalProvider

@Composable
fun FilterButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(if (selected) PrimaryNeon else SurfaceDark, RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) PrimaryNeon else BorderLavender.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color(0xFF1A0F2E) else TextSecondary
        )
    }
}

@Composable
fun TagFilterButton(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            // WP-8.18 (PX-106): `.tagBtn` (index.html:231-233): `--panel`, a full `--tc` border,
            // `padding:5px 11px` (+1 dp for the border), 12 sp; `.on` fills `--tc` with `#1a1030`.
            .background(if (selected) color else OptiRootTokens.Panel, RoundedCornerShape(20.dp))
            .border(1.dp, color, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color(0xFF1A1030) else color
        )
    }
}

// ---------------- HABITS TAB ----------------
@Composable
fun HabitsTabContent(
    tasks: List<TaskEntity>,
    onScoreUp: (String) -> Unit,
    onScoreDown: (String) -> Unit,
    onLongPressUp: (TaskEntity) -> Unit,
    onLongPressDown: (TaskEntity) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onPersistOrder: (List<String>) -> Unit,
    sortMode: String = "manual",
    onMove: (id: String, up: Boolean) -> Unit = { _, _ -> },
    createdIds: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    scrollPosition: ScrollPosition = ScrollPosition(),
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var hasRestoredScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollPosition) {
        if (!hasRestoredScroll && (scrollPosition.index > 0 || scrollPosition.offset > 0)) {
            listState.scrollToItem(scrollPosition.index, scrollPosition.offset)
            hasRestoredScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> onScrollChanged(pair.first, pair.second) }
    }
    if (createdIds != null) ScrollToCreated(createdIds, tasks.map { it.id }, listState)
    if (tasks.isEmpty()) {
        EmptyListPlaceholder(
            icon = Icons.Default.Star,
            text = "Create your first Habit! Habits can be scored positive (+) or negative (-) repeatedly throughout the day."
        )
    } else {
        var localData by remember { mutableStateOf(tasks) }
        val drag = LocalDragActivity.current
        val group = remember { GroupDrag() }
        val state = rememberReorderableLazyListState(
            listState = listState,
            onMove = { from, to -> reorderMove(localData, from, to, listState)?.let { localData = it } },
            onDragEnd = { _, _ ->
                drag.dropped()
                localData = group.drop(localData) { it.id }
                onPersistOrder(localData.map { it.id })
            }
        )
        FollowWhenNotDragging(tasks, state) { localData = it }
        GroupDragWatch(group, state)
        val firstShown = group.firstShown(localData) { it.id }

        LazyColumn(
            state = state.listState,
            modifier = Modifier.fillMaxSize().reorderable(state)
        ) {
            itemsIndexed(localData, key = { _, it -> it.id }) { index, item ->
                GroupDragSlot(state, group, item.id, gapAbove = index > firstShown) { cardModifier ->
                    HabitCardItem(
                        modifier = cardModifier
                            .moveActions(sortMode, selectionMode) { up -> onMove(item.id, up) },
                        dragModifier = cardDragPickup(state, sortMode, selectionMode, item.id in selectedIds) {
                            group.lift(item.id, selectedIds, localData.map { it.id })
                        },
                        task = item,
                        onScoreUp = { onScoreUp(item.id) },
                        onScoreDown = { onScoreDown(item.id) },
                        onLongPressUp = { onLongPressUp(item) },
                        onLongPressDown = { onLongPressDown(item) },
                        onEdit = { onEdit(item) },
                        sortMode = sortMode,
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onToggleSelect = { onToggleSelect(item.id) },
                    )
                }
            }
        }
    }
}

/**
 * WP-8.16 (PX-93): the card long-press pickup at Opti's 200 ms (app.js:5223), only in manual sort
 * (Opti `draggable="true"` iff `dragOK`, app.js:3336), with Opti's `buzz(15)` at the lift (:5285).
 * CR-KT-011: in selection mode only a [selected] card lifts, and [onLift] starts its group drag.
 */
@Composable
internal fun cardDragPickup(
    state: ReorderableState<*>,
    sortMode: String,
    selectionActive: Boolean = false,
    selected: Boolean = false,
    onLift: () -> Unit = {},
): Modifier {
    val buzz = LocalBuzz.current
    val drag = LocalDragActivity.current
    return Modifier.optiDragPickup(state, enabled = sortMode == "manual" && (!selectionActive || selected)) {
        onLift()
        drag.pickedUp()
        buzz.buzz(GestureSpec.BUZZ_PICKUP_MS)
    }
}

/**
 * CR-KT-011: the slot of one card in a list that can group-drag: the card gap above it, the
 * rider collapse, and on the lifted card the stacked look and the "+N" badge.
 */
@Composable
private fun LazyItemScope.GroupDragSlot(
    state: ReorderableState<*>,
    group: GroupDrag,
    id: String,
    gapAbove: Boolean,
    card: @Composable (cardModifier: Modifier) -> Unit,
) {
    ReorderableItem(
        state, key = id,
        modifier = Modifier.groupCollapse(id in group.riders, active = group.dragged != null).cardGapAbove(gapAbove),
    ) { isDragging ->
        val extra = if (isDragging && group.dragged == id) group.riders.size else 0
        card(Modifier.groupStack(extra).dragGhost(isDragging))
        if (extra > 0) GroupBadge(extra, id, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * Review 35: the TalkBack "Move up / Move down" actions of a manual-sort card (the drag has no
 * accessibility path of its own). Outside manual sort Opti has no reorder, so no actions.
 */
internal fun Modifier.moveActions(sortMode: String, selectionActive: Boolean = false, onMove: (up: Boolean) -> Unit): Modifier =
    if (sortMode != "manual" || selectionActive) this else this.semantics {
        customActions = listOf(
            CustomAccessibilityAction(MOVE_UP_LABEL) { onMove(true); true },
            CustomAccessibilityAction(MOVE_DOWN_LABEL) { onMove(false); true },
        )
    }

const val MOVE_UP_LABEL = "Move up"
const val MOVE_DOWN_LABEL = "Move down"

@Composable
fun HabitCardItem(
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    task: TaskEntity,
    onScoreUp: () -> Unit,
    onScoreDown: () -> Unit,
    onLongPressUp: () -> Unit,
    onLongPressDown: () -> Unit,
    onEdit: () -> Unit,
    sortMode: String = "manual",
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val feedbacks = LocalFeedbacks.current
    val floatingTexts = remember { mutableStateListOf<FloatingFeedback>() }

    LaunchedEffect(feedbacks) {
        feedbacks.collect { feedback ->
            if (feedback.taskId == task.id) {
                floatingTexts.add(feedback)
            }
        }
    }

    val prefs = LocalCardPrefs.current
    // Opti habitCard: `t.up!==false` / `t.down!==false`, else a `.hbtn.off` button (app.js:3203-3207).
    val upOn = task.up != false
    val downOn = task.down != false

    // WP-8.18: Opti `habitCard` (app.js:3200-3208) with `.task` / `.task.habit` (index.html:126-179).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .gatedInput(LocalInputGated.current)
            .then(dragModifier)
            .optiCardSurface()
            .then(if (selectionMode) Modifier.optiTap(label = "Toggle selection", skipAfterLift = true, onTap = onToggleSelect) else Modifier)
            .testTag("habit_card_${task.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = cardRowMinHeight(prefs)).height(IntrinsicSize.Min)) {
            if (selectionMode) {
                SelectionCheckbox(checked = selected, taskId = task.id)
            }
            ValDot(valColor(task.value))

            // Plus button: tap scores, a 350 ms hold opens the rep sheet (app.js:8524-8561).
            HabitButton(
                glyph = "+",
                on = upOn,
                color = OptiRootTokens.Green,
                borderEnd = true,
                modifier = if (selectionMode) {
                    Modifier
                        .alpha(GestureSpec.GATED_CARD_ALPHA)
                        .optiTap(label = "Toggle selection", onTap = onToggleSelect)
                        .testTag("habit_up_${task.id}")
                } else {
                    Modifier
                        .optiPress(enabled = upOn, onLongPress = onLongPressUp, onTap = onScoreUp)
                        .testTag("habit_up_${task.id}")
                }
            )

            // Body: tap opens the editor (Opti `.body onclick="openEdit"`).
            CardBody(task, prefs, Modifier.weight(1f).optiTap(skipAfterLift = selectionMode, onTap = if (selectionMode) onToggleSelect else onEdit).testTag("habit_body_${task.id}"))

            Rail {
                RailItem(task.difficulty, diffStyle(task.difficulty))
                if (hasReminder(task)) BellItem()
                habitCountLabel(task)?.let { RailItem(it, CountStyle, Modifier.testTag("habit_count_${task.id}")) }
            }

            // CR-KT-015: the task image, at the right of the rail (before the minus button).
            TaskAttachmentThumb(task.id, task.attachments, interactive = !selectionMode)

            // Minus button
            HabitButton(
                glyph = "−",
                on = downOn,
                color = OptiRootTokens.Red,
                borderEnd = !downOn, // `.hbtn.off` is neither .up nor .down: the default `.check` right line
                modifier = if (selectionMode) {
                    Modifier
                        .alpha(GestureSpec.GATED_CARD_ALPHA)
                        .optiTap(label = "Toggle selection", onTap = onToggleSelect)
                        .testTag("habit_down_${task.id}")
                } else {
                    Modifier
                        .optiPress(enabled = downOn, onLongPress = onLongPressDown, onTap = onScoreDown)
                        .testTag("habit_down_${task.id}")
                }
            )
        }

        // Floating feedback overlays
        floatingTexts.forEach { feedback ->
            Box(modifier = Modifier.padding(end = 40.dp, top = 10.dp)) {
                FloatingTextAnim(feedback = feedback, onComplete = { floatingTexts.remove(feedback) })
            }
        }
    }
}

/**
 * `.task` (index.html:126-127): `--panel`, a 1 dp `--line` border, radius 10, clipped; the
 * content sits inside the border (`box-sizing:border-box`).
 */
internal fun Modifier.optiCardSurface(): Modifier = this
    .clip(CardShape)
    .background(OptiRootTokens.Panel)
    .border(OptiDims.CARD_BORDER.dp, OptiRootTokens.Line, CardShape)
    .padding(OptiDims.CARD_BORDER.dp)

@Composable
private fun SelectionCheckbox(checked: Boolean, taskId: String) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = PrimaryNeon,
                uncheckedColor = TextSecondary,
                checkmarkColor = Color(0xFF1A1030),
            ),
            modifier = Modifier.testTag("select_$taskId"),
        )
    }
}

/**
 * `.task.habit .check.hbtn` (index.html:168-171): 44 dp, 18 sp bold, `.up` green with a right
 * line, `.down` red with a left line, `.off` `#4a3568`.
 */
@Composable
private fun HabitButton(glyph: String, on: Boolean, color: Color, borderEnd: Boolean, modifier: Modifier) {
    Box(
        modifier = Modifier
            .width(OptiDims.CHECK_W.dp)
            .fillMaxHeight()
            .then(modifier)
            .drawBehind {
                val x = if (borderEnd) size.width - 0.5.dp.toPx() else 0.5.dp.toPx()
                drawLine(color = OptiRootTokens.Line, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (on) color else OptiSelectorTokens.HabitOff)
    }
}

/**
 * `.task .body` (index.html:172-179, app.js:3144-3152): `padding:5px 8px`, centered; the title
 * (14 sp, 600, line-height 1.25, `word-break:break-word`), then `metaRow`: the tag chips, the
 * notes preview clamped to `notesLines`, and the expanded subtask list.
 */
@Composable
private fun CardBody(
    task: TaskEntity,
    prefs: com.example.ui.CardPrefs,
    modifier: Modifier,
    dim: Boolean = false,
    below: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = OptiDims.BODY_PAD_H.dp, vertical = OptiDims.BODY_PAD_V.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            task.title.ifEmpty { "Untitled" },
            fontSize = OptiDims.TITLE_SP.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (task.done || dim) OptiRootTokens.Muted else OptiRootTokens.Ink,
            lineHeight = OptiDims.TITLE_LH.em,
            textDecoration = if (task.done && task.type != "habit") TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.testTag("card_title")
        )
        TagChipsRow(task.tags)
        notesClamp(task.notes, prefs)?.let { NotesPreview(task.notes, it) }
        below()
    }
}

/**
 * `.task .check` for dailies and todos (index.html:131-145): 44 dp, a right line; daily
 * `rgba(255,255,255,.03)`, done green with the white 18 sp `✓`, else the 18 dp `.ckbox`
 * (a 2 dp muted border, radius 5). `.notdue`: the check at .55, the box at .6.
 */
@Composable
private fun CheckColumn(done: Boolean, daily: Boolean, notDue: Boolean, modifier: Modifier) {
    Box(
        modifier = Modifier
            .width(OptiDims.CHECK_W.dp)
            .fillMaxHeight()
            .background(if (done) OptiRootTokens.Green else if (daily) Color(0x08FFFFFF) else Color.Transparent)
            .then(modifier)
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(color = OptiRootTokens.Line, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.alpha(if (notDue) 0.55f else 1f)) {
            if (done) {
                Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 1.em)
            } else {
                Box(
                    modifier = Modifier
                        .alpha(if (notDue) 0.6f else 1f)
                        .size(18.dp)
                        .border(2.dp, OptiRootTokens.Muted, RoundedCornerShape(5.dp))
                        .testTag("ckbox")
                )
            }
        }
    }
}

/** The rail's `.subFrac` (app.js:3180-3184); a tap expands the subtask list. */
@Composable
private fun SubFracButton(task: TaskEntity, expanded: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val done = task.checklist.count { it.done }
    SubFrac(
        done, task.checklist.size,
        // Opti `.subFrac onclick="toggleExpand"` (app.js:3182); it owns its
        // touch, so the card never lifts from it (app.js:5210).
        Modifier
            .optiPress(enabled = enabled, label = if (expanded) "Collapse sub-tasks" else "Expand sub-tasks", onTap = onToggle)
            .testTag("checklist_badge_${task.id}")
    )
}

// ---------------- DAILIES TAB ----------------
@Composable
fun DailiesTabContent(
    tasks: List<TaskEntity>,
    onToggleCheck: (String, Boolean) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onToggleSubtask: (TaskEntity, String?, Int) -> Unit,
    onReorderSubtasks: (TaskEntity, List<String>) -> Unit,
    onPersistOrder: (List<String>) -> Unit,
    sortMode: String = "manual",
    onMove: (id: String, up: Boolean) -> Unit = { _, _ -> },
    createdIds: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    scrollPosition: ScrollPosition = ScrollPosition(),
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var hasRestoredScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollPosition) {
        if (!hasRestoredScroll && (scrollPosition.index > 0 || scrollPosition.offset > 0)) {
            listState.scrollToItem(scrollPosition.index, scrollPosition.offset)
            hasRestoredScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> onScrollChanged(pair.first, pair.second) }
    }
    if (createdIds != null) ScrollToCreated(createdIds, tasks.map { it.id }, listState)
    if (tasks.isEmpty()) {
        EmptyListPlaceholder(
            icon = Icons.Default.Event,
            text = "Create your first Daily! Dailies scheduled for today must be completed before midnight, otherwise you'll take damage."
        )
    } else {
        var localData by remember { mutableStateOf(tasks) }
        val drag = LocalDragActivity.current
        val group = remember { GroupDrag() }
        val state = rememberReorderableLazyListState(
            listState = listState,
            onMove = { from, to -> reorderMove(localData, from, to, listState)?.let { localData = it } },
            onDragEnd = { _, _ ->
                drag.dropped()
                localData = group.drop(localData) { it.id }
                onPersistOrder(localData.map { it.id })
            }
        )
        FollowWhenNotDragging(tasks, state) { localData = it }
        GroupDragWatch(group, state)
        val firstShown = group.firstShown(localData) { it.id }

        LazyColumn(
            state = state.listState,
            modifier = Modifier.fillMaxSize().reorderable(state)
        ) {
            itemsIndexed(localData, key = { _, it -> it.id }) { index, item ->
                GroupDragSlot(state, group, item.id, gapAbove = index > firstShown) { cardModifier ->
                    DailyCardItem(
                        modifier = cardModifier
                            .moveActions(sortMode, selectionMode) { up -> onMove(item.id, up) },
                        dragModifier = cardDragPickup(state, sortMode, selectionMode, item.id in selectedIds) {
                            group.lift(item.id, selectedIds, localData.map { it.id })
                        },
                        task = item,
                        onToggleCheck = { checked -> onToggleCheck(item.id, checked) },
                        onEdit = { onEdit(item) },
                        onToggleSubtask = { subId, idx -> onToggleSubtask(item, subId, idx) },
                        onReorderSubtasks = { ids -> onReorderSubtasks(item, ids) },
                        sortMode = sortMode,
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onToggleSelect = { onToggleSelect(item.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DailyCardItem(
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    task: TaskEntity,
    onToggleCheck: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onToggleSubtask: (subId: String?, idx: Int) -> Unit = { _, _ -> },
    onReorderSubtasks: (List<String>) -> Unit = {},
    sortMode: String = "manual",
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    // WP-8.16 (clock rule): day math from BootGate.clock/zoneProvider (JS `new Date().getDay()`).
    val dow = LocalDay.getDay(BootGate.clock(), BootGate.zoneProvider())
    val isDue = task.repeat?.getOrNull(dow) != false
    val notDue = !isDue && !task.done
    val cardAlpha = if (notDue) 0.5f else 1f

    // P11: in-card subtask list toggle (badge tap expands/collapses; scoped per-card instance).
    var subtasksExpanded by remember(task.id) { mutableStateOf(false) }

    val feedbacks = LocalFeedbacks.current
    val floatingTexts = remember { mutableStateListOf<FloatingFeedback>() }

    LaunchedEffect(feedbacks) {
        feedbacks.collect { feedback ->
            if (feedback.taskId == task.id) floatingTexts.add(feedback)
        }
    }

    val prefs = LocalCardPrefs.current

    // WP-8.18: Opti `taskCard` (app.js:3194-3199) for a daily; `.task.notdue{opacity:.5}`.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .gatedInput(LocalInputGated.current)
            .then(dragModifier)
            .optiCardSurface()
            .then(if (selectionMode) Modifier.optiTap(label = "Toggle selection", skipAfterLift = true, onTap = onToggleSelect) else Modifier)
            .testTag("daily_card_${task.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = cardRowMinHeight(prefs)).height(IntrinsicSize.Min)) {
            if (selectionMode) {
                SelectionCheckbox(checked = selected, taskId = task.id)
            }
            ValDot(valColor(task.value))

            CheckColumn(
                done = task.done, daily = true, notDue = notDue,
                modifier = if (selectionMode) {
                    Modifier
                        .alpha(GestureSpec.GATED_CARD_ALPHA)
                        .optiTap(label = "Toggle selection", onTap = onToggleSelect)
                        .testTag("check_${task.id}")
                } else {
                    Modifier.optiPress(onTap = { onToggleCheck(!task.done) }).testTag("check_${task.id}")
                }
            )

            // Body: tap opens the editor (Opti `.body onclick="openEdit"`); the sublist is inside it.
            CardBody(task, prefs, Modifier.weight(1f).optiTap(skipAfterLift = selectionMode, onTap = if (selectionMode) onToggleSelect else onEdit).testTag("body_${task.id}"), dim = notDue) {
                if (subtasksExpanded && task.checklist.isNotEmpty()) {
                    Box(Modifier.gatedInput(selectionMode)) {
                        InCardSubtaskList(task = task, onToggle = onToggleSubtask, onReorder = onReorderSubtasks)
                    }
                }
            }

            Rail {
                RailItem(task.difficulty, diffStyle(task.difficulty))
                if (hasReminder(task)) BellItem()
                // Display-only gate (prefs.showStreaks, default on), app.js:3162-3166.
                if (prefs.showStreaks) RailItem("🔥 " + (task.streak ?: 0), StreakStyle, Modifier.testTag("streak_${task.id}"), label = "Streak " + (task.streak ?: 0))
                if (notDue) nextDueWeekday(task.repeat, dow)?.let { RailItem("⏳ $it", NotDueStyle, Modifier.testTag("notdue_${task.id}"), label = "Next due $it") }
                if (task.checklist.isNotEmpty()) SubFracButton(task, subtasksExpanded, enabled = !selectionMode) { subtasksExpanded = !subtasksExpanded }
            }

            // CR-KT-015: the task image, at the right end of the row.
            TaskAttachmentThumb(task.id, task.attachments, interactive = !selectionMode)
        }

        // Floating feedback overlays
        floatingTexts.forEach { feedback ->
            Box(modifier = Modifier.padding(end = 40.dp, top = 10.dp)) {
                FloatingTextAnim(feedback = feedback, onComplete = { floatingTexts.remove(feedback) })
            }
        }
    }
}

/// Shared in-card subtask list (P11): used by DailyCardItem and TodoCardItem — the only two card
// types the edit sheet's "Checklist Sub-Tasks" manager (gated `type == "daily" || type == "todo"`)
// allows checklists on. WP-8.16 (PX-93): Opti's `.subitem` rows (app.js:3098, :5395-5520): a row
// tap toggles, a 200 ms hold lifts the row (no handle), a move reorders it live and the lift
// commits the order. The row owns its touch, so the parent card never lifts from a subtask.
// WP-8.18: Opti's `.sublist` look (index.html:201-208): inside the body, a line on top,
// `margin-top:6px; padding-top:4px; gap:2px`; rows `padding:3px 2px; gap:8px`, the 20 dp
// `.subbox` and the 13 sp `.subtxt`. A lifted row wears `.dragGhost` (no `.lifted`).
@Composable
private fun InCardSubtaskList(
    task: TaskEntity,
    onToggle: (subId: String?, idx: Int) -> Unit,
    onReorder: (List<String>) -> Unit
) {
    val buzz = LocalBuzz.current
    val drag = LocalDragActivity.current
    var order by remember(task.id, task.checklist) { mutableStateOf(task.checklist.map { it.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    val rowBounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val byId = task.checklist.associateBy { it.id }

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .drawBehind { drawLine(OptiRootTokens.Line, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(top = 4.dp)
            .testTag("sublist_${task.id}")
    ) {
        order.forEachIndexed { index, id ->
            val item = byId[id] ?: return@forEachIndexed
            key(item.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { rowBounds[item.id] = it.positionInParent().y to it.size.height.toFloat() }
                        .dragGhost(dragging == item.id, lifted = false)
                        .optiRowDrag(
                            onTap = { onToggle(item.id, task.checklist.indexOfFirst { it.id == item.id }) },
                            onPickup = { dragging = item.id; drag.pickedUp(); buzz.buzz(GestureSpec.BUZZ_PICKUP_MS) },
                            onDragTo = { yInRow ->
                                val top = rowBounds[item.id]?.first ?: return@optiRowDrag
                                val rows = order.mapNotNull { rid -> rowBounds[rid]?.let { (t, h) -> Triple(rid, t, h) } }
                                reorderUnder(order, item.id, top + yInRow, rows)?.let { order = it }
                            },
                            onDrop = {
                                if (dragging == item.id) {
                                    dragging = null
                                    drag.dropped()
                                    onReorder(order)
                                }
                            }
                        )
                        .semantics {
                            val at = order.indexOf(item.id)
                            customActions = listOfNotNull(
                                if (at > 0) CustomAccessibilityAction(MOVE_UP_LABEL) {
                                    order = order.toMutableList().apply { add(at - 1, removeAt(at)) }; onReorder(order); true
                                } else null,
                                if (at in 0 until order.size - 1) CustomAccessibilityAction(MOVE_DOWN_LABEL) {
                                    order = order.toMutableList().apply { add(at + 1, removeAt(at)) }; onReorder(order); true
                                } else null,
                            )
                        }
                        .padding(vertical = 3.dp, horizontal = 2.dp)
                        .testTag("subtask_row_${item.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Opti `.subbox`: a tap toggles at once and never lifts the row (app.js:5397).
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(if (item.done) OptiRootTokens.Green else RailDefaultBg, RoundedCornerShape(5.dp))
                            .border(1.dp, if (item.done) OptiRootTokens.Green else OptiRootTokens.Line, RoundedCornerShape(5.dp))
                            .optiPress(onTap = { onToggle(item.id, index) })
                            .testTag("subtask_checkbox_${item.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.done) Text("✔", fontSize = 13.sp, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        item.text,
                        fontSize = 13.sp,
                        color = if (item.done) OptiRootTokens.Muted else OptiRootTokens.Ink,
                        textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ---------------- TODOS TAB ----------------
@Composable
fun TodosTabContent(
    tasks: List<TaskEntity>,
    onToggleCheck: (String, Boolean) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onToggleSubtask: (TaskEntity, String?, Int) -> Unit,
    onReorderSubtasks: (TaskEntity, List<String>) -> Unit,
    onPersistOrder: (List<String>) -> Unit,
    sortMode: String = "manual",
    onMove: (id: String, up: Boolean) -> Unit = { _, _ -> },
    createdIds: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    scrollPosition: ScrollPosition = ScrollPosition(),
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var hasRestoredScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollPosition) {
        if (!hasRestoredScroll && (scrollPosition.index > 0 || scrollPosition.offset > 0)) {
            listState.scrollToItem(scrollPosition.index, scrollPosition.offset)
            hasRestoredScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> onScrollChanged(pair.first, pair.second) }
    }
    if (createdIds != null) ScrollToCreated(createdIds, tasks.map { it.id }, listState)
    if (tasks.isEmpty()) {
        EmptyListPlaceholder(
            icon = Icons.Default.TaskAlt,
            text = "Create your first Todo! Todos are one-off tasks. Completing them yields gold and experience, with zero penalty if left undone."
        )
    } else {
        var localData by remember { mutableStateOf(tasks) }
        val drag = LocalDragActivity.current
        val group = remember { GroupDrag() }
        val state = rememberReorderableLazyListState(
            listState = listState,
            onMove = { from, to -> reorderMove(localData, from, to, listState)?.let { localData = it } },
            onDragEnd = { _, _ ->
                drag.dropped()
                localData = group.drop(localData) { it.id }
                onPersistOrder(localData.map { it.id })
            }
        )
        FollowWhenNotDragging(tasks, state) { localData = it }
        GroupDragWatch(group, state)
        val firstShown = group.firstShown(localData) { it.id }

        LazyColumn(
            state = state.listState,
            modifier = Modifier.fillMaxSize().reorderable(state)
        ) {
            itemsIndexed(localData, key = { _, it -> it.id }) { index, item ->
                GroupDragSlot(state, group, item.id, gapAbove = index > firstShown) { cardModifier ->
                    TodoCardItem(
                        modifier = cardModifier
                            .moveActions(sortMode, selectionMode) { up -> onMove(item.id, up) },
                        dragModifier = cardDragPickup(state, sortMode, selectionMode, item.id in selectedIds) {
                            group.lift(item.id, selectedIds, localData.map { it.id })
                        },
                        task = item,
                        onToggleCheck = { checked -> onToggleCheck(item.id, checked) },
                        onEdit = { onEdit(item) },
                        onToggleSubtask = { subId, idx -> onToggleSubtask(item, subId, idx) },
                        onReorderSubtasks = { ids -> onReorderSubtasks(item, ids) },
                        sortMode = sortMode,
                        selectionMode = selectionMode,
                        selected = item.id in selectedIds,
                        onToggleSelect = { onToggleSelect(item.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoCardItem(
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    task: TaskEntity,
    onToggleCheck: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onToggleSubtask: (subId: String?, idx: Int) -> Unit = { _, _ -> },
    onReorderSubtasks: (List<String>) -> Unit = {},
    sortMode: String = "manual",
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    val feedbacks = LocalFeedbacks.current
    val floatingTexts = remember { mutableStateListOf<FloatingFeedback>() }

    LaunchedEffect(feedbacks) {
        feedbacks.collect { feedback ->
            if (feedback.taskId == task.id) floatingTexts.add(feedback)
        }
    }

    // P11: in-card subtask list toggle (badge tap expands/collapses; scoped per-card instance).
    var subtasksExpanded by remember(task.id) { mutableStateOf(false) }

    val prefs = LocalCardPrefs.current

    // WP-8.18: Opti `taskCard` (app.js:3194-3199) for a todo.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .gatedInput(LocalInputGated.current)
            .then(dragModifier)
            .optiCardSurface()
            .then(if (selectionMode) Modifier.optiTap(label = "Toggle selection", skipAfterLift = true, onTap = onToggleSelect) else Modifier)
            .testTag("todo_card_${task.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = cardRowMinHeight(prefs)).height(IntrinsicSize.Min)) {
            if (selectionMode) {
                SelectionCheckbox(checked = selected, taskId = task.id)
            }
            ValDot(valColor(task.value))

            CheckColumn(
                done = task.done, daily = false, notDue = false,
                modifier = if (selectionMode) {
                    Modifier
                        .alpha(GestureSpec.GATED_CARD_ALPHA)
                        .optiTap(label = "Toggle selection", onTap = onToggleSelect)
                        .testTag("check_${task.id}")
                } else {
                    Modifier.optiPress(onTap = { onToggleCheck(!task.done) }).testTag("check_${task.id}")
                }
            )

            // Body: tap opens the editor (Opti `.body onclick="openEdit"`); the sublist is inside it.
            CardBody(task, prefs, Modifier.weight(1f).optiTap(skipAfterLift = selectionMode, onTap = if (selectionMode) onToggleSelect else onEdit).testTag("body_${task.id}")) {
                if (subtasksExpanded && task.checklist.isNotEmpty()) {
                    Box(Modifier.gatedInput(selectionMode)) {
                        InCardSubtaskList(task = task, onToggle = onToggleSubtask, onReorder = onReorderSubtasks)
                    }
                }
            }

            Rail {
                RailItem(task.difficulty, diffStyle(task.difficulty))
                if (hasReminder(task)) BellItem()
                if (task.checklist.isNotEmpty()) SubFracButton(task, subtasksExpanded, enabled = !selectionMode) { subtasksExpanded = !subtasksExpanded }
            }

            // CR-KT-015: the task image, at the right end of the row.
            TaskAttachmentThumb(task.id, task.attachments, interactive = !selectionMode)
        }

        // Floating feedback overlays
        floatingTexts.forEach { feedback ->
            Box(modifier = Modifier.padding(end = 40.dp, top = 10.dp)) {
                FloatingTextAnim(feedback = feedback, onComplete = { floatingTexts.remove(feedback) })
            }
        }
    }
}

// ---------------- REWARDS TAB ----------------
@Composable
fun RewardsTabContent(
    rewards: List<RewardEntity>,
    characterGold: Double,
    onBuy: (String) -> Unit,
    onBuyPotion: (Boolean) -> Unit,
    onEdit: (RewardEntity) -> Unit,
    onPersistOrder: (List<String>) -> Unit,
    sortMode: String = "manual",
    onMove: (id: String, up: Boolean) -> Unit = { _, _ -> },
    createdIds: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    scrollPosition: ScrollPosition = ScrollPosition(),
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    var localData by remember { mutableStateOf(rewards) }
    val drag = LocalDragActivity.current
    // The Shop title, the two potions and "Your Rewards" are list items 0-3 before the reward cards.
    val listState = rememberLazyListState()
    var hasRestoredScroll by remember { mutableStateOf(false) }
    LaunchedEffect(scrollPosition) {
        if (!hasRestoredScroll && (scrollPosition.index > 0 || scrollPosition.offset > 0)) {
            listState.scrollToItem(scrollPosition.index, scrollPosition.offset)
            hasRestoredScroll = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { pair -> onScrollChanged(pair.first, pair.second) }
    }
    if (createdIds != null) ScrollToCreated(createdIds, rewards.map { it.id }, listState, indexOffset = 4)
    val state = rememberReorderableLazyListState(
        listState = listState,
        onMove = { from, to -> reorderMove(localData, from, to, listState, headerCount = 4)?.let { localData = it } },
        canDragOver = { over, _ -> over.index >= 4 },
        onDragEnd = { _, _ -> drag.dropped(); onPersistOrder(localData.map { it.id }) }
    )
    FollowWhenNotDragging(rewards, state) { localData = it }

    // WP-8.18: Opti `viewRewards` (app.js:3403-3419): the Shop title and hint, the two
    // `.task.shopitem` cards, then "Your Rewards" with its hint and the reward cards.
    LazyColumn(
        state = state.listState,
        verticalArrangement = Arrangement.spacedBy(OptiDims.CARD_GAP.dp),
        modifier = Modifier.fillMaxSize().reorderable(state)
    ) {
        item {
            ColTitleWithHint("Shop", "Built-in items. Drink a potion to recover HP before a death wipes your gold and level.", top = 8)
        }
        item {
            RewardCardItem(
                reward = RewardEntity(id = "health_potion", title = "Health Potion", notes = "Restore 15 HP.", cost = 25.0, createdAt = 0L, updatedAt = 0L), // WP-8.23: display-only, never stored
                canAfford = characterGold >= 25.0,
                onBuy = { onBuyPotion(false) },
                onEdit = {},
                sortMode = "none",
                shopIcon = "❤️"
            )
        }
        item {
            RewardCardItem(
                reward = RewardEntity(id = "big_potion", title = "Greater Potion", notes = "Fully restore HP.", cost = 60.0, createdAt = 0L, updatedAt = 0L), // WP-8.23: display-only, never stored
                canAfford = characterGold >= 60.0,
                onBuy = { onBuyPotion(true) },
                onEdit = {},
                sortMode = "none",
                shopIcon = "💖"
            )
        }
        item {
            ColTitleWithHint("Your Rewards", "Spend gold on real-life rewards you define yourself.", top = 16)
        }

        if (localData.isEmpty()) {
            item {
                // Opti `.empty` (index.html:511).
                Text(
                    "No rewards yet. Create one to spend your gold on.",
                    fontSize = 13.sp,
                    color = OptiRootTokens.Muted,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(18.dp)
                )
            }
        } else {
            items(localData, key = { it.id }) { reward ->
                ReorderableItem(state, key = reward.id) { isDragging ->
                    RewardCardItem(
                        modifier = Modifier
                            .dragGhost(isDragging)
                            .moveActions(sortMode) { up -> onMove(reward.id, up) },
                        dragModifier = cardDragPickup(state, sortMode),
                        reward = reward,
                        canAfford = characterGold >= reward.cost,
                        onBuy = { onBuy(reward.id) },
                        onEdit = { onEdit(reward) },
                        sortMode = sortMode
                    )
                }
            }
        }
    }
}

/**
 * Opti `.colTitle h2` (index.html:101-104: 15 sp bold, letter-spacing .3, `margin:16px 4px 8px`,
 * 8 on top for the first) plus the `.small` hint (`margin:0 4px 10px`; the list gap adds 6).
 */
@Composable
private fun ColTitleWithHint(title: String, hint: String, top: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = top.dp, bottom = 4.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = OptiRootTokens.Ink)
        Spacer(Modifier.height(8.dp))
        Text(hint, fontSize = 11.sp, color = OptiRootTokens.Muted, lineHeight = 1.5.em)
    }
}

/**
 * Opti's reward card (app.js:3414-3417): `.valdot` gold, `.check.coin` (buy) with the 22 dp coin,
 * the body (tap edits) with the title and `.meta` (`.pill` "N gold", "📝" when there are notes).
 * A [shopIcon] makes it a `.task.shopitem` (app.js:3406-3409): `.valdot` hp, the check on
 * `rgba(247,78,82,.12)` with the icon, and a body tap that also buys; the meta shows the notes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RewardCardItem(
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    reward: RewardEntity,
    canAfford: Boolean,
    onBuy: () -> Unit,
    onEdit: () -> Unit,
    sortMode: String = "manual",
    shopIcon: String? = null
) {
    val prefs = LocalCardPrefs.current
    val buy = { if (canAfford) onBuy() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Opti `.task` covers reward and shop cards too, so layer 2 gates them (index.html:141).
            .gatedInput(LocalInputGated.current)
            .then(dragModifier)
            .optiCardSurface()
            .testTag("reward_card_${reward.id}")
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = cardRowMinHeight(prefs)).height(IntrinsicSize.Min)) {
            ValDot(if (shopIcon != null) OptiRootTokens.Hp else OptiRootTokens.Gold)
            Box(
                modifier = Modifier
                    .width(OptiDims.CHECK_W.dp)
                    .fillMaxHeight()
                    .background(if (shopIcon != null) Color(0x1FF74E52) else Color.Transparent)
                    .optiPress(label = "Buy", onTap = buy)
                    .testTag("buy_reward_button")
                    .drawBehind {
                        val x = size.width - 0.5.dp.toPx()
                        drawLine(color = OptiRootTokens.Line, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    },
                contentAlignment = Alignment.Center
            ) {
                if (shopIcon != null) Text(shopIcon, fontSize = 20.sp) else CoinIcon(22)
            }
            // Body: Opti `.body onclick="openReward"` (app.js:3416); a shop item's body buys.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .optiTap(onTap = if (shopIcon != null) buy else onEdit)
                    .padding(horizontal = OptiDims.BODY_PAD_H.dp, vertical = OptiDims.BODY_PAD_V.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    reward.title,
                    fontSize = OptiDims.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OptiRootTokens.Ink,
                    lineHeight = OptiDims.TITLE_LH.em
                )
                Row(
                    modifier = Modifier.padding(top = OptiDims.META_TOP.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OptiDims.META_GAP.dp)
                ) {
                    Pill(jsNum(reward.cost) + " gold")
                    if (shopIcon != null) Text(reward.notes, fontSize = OptiDims.META_SP.sp, color = OptiRootTokens.Muted)
                    else if (reward.notes.isNotEmpty()) Text("📝", fontSize = OptiDims.META_SP.sp)
                }
            }
        }
    }
}

// ---------------- PLACEHOLDER EMPTY VIEW ----------------
@Composable
fun EmptyListPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = BorderLavender
            )
            Text(
                text,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = TextSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun FloatingTextAnim(feedback: FloatingFeedback, onComplete: () -> Unit) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(feedback.id) {
        launch {
            offsetY.animateTo(
                targetValue = -50f,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 800, delayMillis = 200, easing = LinearEasing)
            )
        }
        onComplete()
    }

        Text(
        text = feedback.message,
        color = if (feedback.isPositive) OptiRootTokens.Green else OptiRootTokens.Hp,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp,
        modifier = Modifier
            .offset(y = offsetY.value.dp)
            .alpha(alpha.value)
    )
}

// ---------------- HABITICA-STYLE REWARD BURSTS (lower third of the screen) ----------------

private fun fmtGold(g: Double): String =
    if (g == kotlin.math.floor(g)) g.toInt().toString() else java.util.Locale.ROOT.let { String.format(it, "%.2f", g) }.trimEnd('0').trimEnd('.')

@Composable
fun BoxScope.RewardBurstHost(bursts: kotlinx.coroutines.flow.SharedFlow<MainViewModel.RewardBurst>, modifier: Modifier = Modifier) {
    val active = remember { mutableStateListOf<MainViewModel.RewardBurst>() }
    LaunchedEffect(bursts) { bursts.collect { active.add(it) } }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        active.forEach { burst ->
            key(burst.id) { RewardBurstAnim(burst) { b -> active.remove(b) } }
        }
    }
}

/** One chip, rising ~64 dp while fading out (Habitica's bottom-screen +XP/+Gold popup). */
@Composable
private fun RewardBurstAnim(burst: MainViewModel.RewardBurst, onComplete: (MainViewModel.RewardBurst) -> Unit) {
    val offsetY = remember { Animatable(12f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(burst.id) {
        launch {
            offsetY.animateTo(-64f, animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing))
        }
        alpha.animateTo(1f, animationSpec = tween(durationMillis = 120, easing = LinearEasing))
        alpha.animateTo(0f, animationSpec = tween(durationMillis = 600, delayMillis = 350, easing = LinearEasing))
        onComplete(burst)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .offset(y = offsetY.value.dp)
            .alpha(alpha.value)
            .testTag("reward_burst")
    ) {
        val textStyle = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp)
        if (burst.text != null) {
            Text(burst.text, style = textStyle, color = OptiRootTokens.Muted)
        } else {
            if (burst.xp > 0) Text("+${burst.xp} XP", style = textStyle, color = OptiRootTokens.Green)
            if (burst.gold > 0.0) Text("+${fmtGold(burst.gold)} GP", style = textStyle, color = OptiRootTokens.Gold)
        }
    }
}

// ---------------- NEW-ITEM AUTO-SCROLL ----------------

/**
 * When a task/reward is created while the list sorts "Created ↓" (newest-first), the card lands
 * at index 0 off-screen if the user was scrolled down; this animates the list up to it.
 * The scroll fires only once the async list update actually surfaces the id, so a Room/Flow
 * round-trip is waited out instead of raced.
 */
@Composable
internal fun ScrollToCreated(
    createdIds: kotlinx.coroutines.flow.SharedFlow<String>,
    ids: List<String>,
    listState: LazyListState,
    indexOffset: Int = 0
) {
    var pendingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(createdIds, listState) {
        createdIds.collect { id -> pendingId = id }
    }
    LaunchedEffect(pendingId, ids) {
        val target = pendingId ?: return@LaunchedEffect
        val idx = ids.indexOf(target)
        if (idx >= 0) {
            listState.animateScrollToItem(idx + indexOffset)
            pendingId = null
        }
    }
}

// ---------------- TASK EDIT SHEET (M3 BOTTOM SHEET SIMULATED VIA BACKWARD COMPATIBLE BOTTOM SHEET DIALOG OR CUSTOM DESIGN) ----------------

/** Snapshot of every editable TaskEditSheet field; structural equality is the dirty check. */
data class EditSheetDraft(
    val title: String,
    val notes: String,
    val type: String,
    val difficulty: String,
    val resetFreq: String,
    val cUp: Int,
    val cDown: Int,
    val quickLog: QuickLogMode?,
    val tags: List<String>,
    val repeatDays: List<Boolean>,
    val checklist: List<ChecklistItem>,
    val reminders: ReminderEditorRules.Draft,
    val up: Boolean? = null,
    val down: Boolean? = null,
)

fun isEditSheetDirty(initial: EditSheetDraft, current: EditSheetDraft): Boolean = initial != current

/** Difficulty buttons for the edit sheet. PWA app.js:6275: `log` is offered only for habits. */
fun difficultyOptions(type: String): List<Pair<String, String>> {
    val base = listOf("trivial" to "Trivial", "easy" to "Easy", "medium" to "Med", "hard" to "Hard")
    return if (type == "habit") base + ("log" to "Log") else base
}

/**
 * Habit filter after the filter panel toggles. PWA app.js:3338-3346 `toggleFilter()`: closing the
 * panel on the habits tab (tab 0) while the filter is `log` resets it to `all`.
 */
fun habitFilterAfterPanelToggle(nowOpen: Boolean, tab: Int, current: String): String =
    if (!nowOpen && tab == 0 && current == "log") "all" else current

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditSheet(
    task: TaskEntity?,
    availableTags: List<TagEntity> = emptyList(),
    isSaveButtonTop: Boolean = true,
    /** (id, name, color): the id comes from [newTagId] so the task can hold it (CR-KT-008). */
    onCreateTag: (String, String, String) -> Unit = { _, _, _ -> },
    newTagId: () -> String = { com.example.ui.newTagId() },
    onDeleteTag: (String, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onDelete: () -> Unit = {},
    /** CR-KT-012: the "History" row (saved tasks only); null hides it. */
    onHistory: (() -> Unit)? = null,
    /** Last two args: habit up / down (null = untouched and on, Opti `t.up!==false`). */
    onSave: (String, String, String, String, List<Boolean>?, List<ChecklistItem>, List<String>, String, Int, Int, List<ReminderSpec>, QuickLogMode?, Boolean?, Boolean?) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // WP-8.16-R1: Opti openEdit focuses #eTitle and selects all of it (app.js:5876-5886).
    var titleField by remember {
        val t = task?.title ?: ""
        mutableStateOf(TextFieldValue(t, TextRange(0, t.length)))
    }
    val title = titleField.text
    val titleFocus = remember { FocusRequester() }
    val subtaskFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    FocusOnOpen(titleFocus)
    var notes by remember { mutableStateOf(task?.notes ?: "") }
    var type by remember { mutableStateOf(task?.type ?: "habit") }
    var difficulty by remember { mutableStateOf(task?.difficulty ?: "easy") }

    var resetFreq by remember { mutableStateOf(task?.resetFreq ?: "daily") }
    var editedCUp by remember { mutableIntStateOf(task?.cUp ?: 0) }
    var editedCDown by remember { mutableIntStateOf(task?.cDown ?: 0) }
    // WP-8.13 patch D-1: the per-habit Android shortcut (Opti app.js:6006-6011).
    var quickLog by remember { mutableStateOf(task?.quickLog) }
    // Opti app.js:6280-6281: habit "+ Positive" / "− Negative". Kept as-is (null stays null)
    // until toggled; a toggle writes `!(x!==false)`. Both may be off (no guard in Opti).
    var habitUp by remember { mutableStateOf(task?.up) }
    var habitDown by remember { mutableStateOf(task?.down) }

    // Tags selection state
    var selectedTags by remember { mutableStateOf(task?.tags ?: emptyList()) }

    // Dailies repetition array (Sun..Sat)
    var repeatDays by remember {
        mutableStateOf(
            task?.repeat ?: listOf(true, true, true, true, true, true, true)
        )
    }

    // WP-8.11: reminder draft (app.js:5863-5873), saved via ReminderEditorRules.save
    var reminderDraft by remember { mutableStateOf(ReminderEditorRules.draftFrom(task?.reminders, SystemReminderClock.nowMs())) }

    // Checklist state
    var checklistItems by remember { mutableStateOf(task?.checklist ?: emptyList()) }
    var newChecklistItemText by remember { mutableStateOf("") }

    val initialDraft = remember {
        EditSheetDraft(
            title = titleField.text,
            notes = notes,
            type = type,
            difficulty = difficulty,
            resetFreq = resetFreq,
            cUp = editedCUp,
            cDown = editedCDown,
            quickLog = quickLog,
            tags = selectedTags,
            repeatDays = repeatDays,
            checklist = checklistItems,
            reminders = reminderDraft,
            up = habitUp,
            down = habitDown,
        )
    }
    val isDirtyNow: () -> Boolean = {
        isEditSheetDirty(
            initialDraft,
            EditSheetDraft(
                title = titleField.text,
                notes = notes,
                type = type,
                difficulty = difficulty,
                resetFreq = resetFreq,
                cUp = editedCUp,
                cDown = editedCDown,
                quickLog = quickLog,
                tags = selectedTags,
                repeatDays = repeatDays,
                checklist = checklistItems,
                reminders = reminderDraft,
                up = habitUp,
                down = habitDown,
            )
        )
    }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // CR-KT-012: the discard guard was opened by "History", so Discard goes there instead of closing.
    var historyAfterDiscard by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !isDirtyNow() },
    )

    BackHandler(enabled = isDirtyNow() && !showDiscardConfirm) { showDiscardConfirm = true }

    // One save path for both Save buttons and Enter (Opti onSheetKeydown -> saveTask, app.js:5961-5973).
    // The keyboard is closed first, as Opti's closeSheet removes the focused input.
    // WP-8.22-A: a blank title saves as "Untitled" and blank subtasks are dropped (app.js:6049, 6057).
    val saveNow: () -> Unit = {
        keyboard?.hide()
        onSave(
            type,
            EditorTitleRules.task(title),
            notes,
            difficulty,
            EditorTitleRules.repeat(type, task, repeatDays), // CR-KT-017: never drops a stored repeat
            EditorTitleRules.checklist(checklistItems),
            tagTokensToIds(selectedTags, availableTags), // CR-KT-008: names -> ids on save
            resetFreq,
            editedCUp,
            editedCDown,
            ReminderEditorRules.save(task?.reminders, reminderDraft, type, if (type == "daily") repeatDays else null, SystemReminderClock.nowMs()),
            quickLog,
            habitUp,
            habitDown
        )
    }
    val addPendingSubtask: () -> Unit = {
        if (newChecklistItemText.isNotEmpty()) {
            checklistItems = checklistItems + ChecklistItem(
                id = UUID.randomUUID().toString(),
                text = newChecklistItemText,
                done = false
            )
            newChecklistItemText = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val typeLabel = when (type) {
                    "habit" -> "Habit"
                    "daily" -> "Daily"
                    "todo" -> "Todo"
                    else -> "Task"
                }
                Text(
                    if (task == null || task.id.isEmpty()) "Create $typeLabel" else "Edit $typeLabel",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryNeon,
                    modifier = Modifier.weight(1f)
                )
                // PX-95: Opti's 📋 paste and ⧉ copy buttons (app.js:5983-5984, :6233-6394).
                val clipboard = LocalClipboardManager.current
                val sheetContext = LocalContext.current
                TextButton(
                    onClick = {
                        val r = PasteParse.fromClipboard(clipboard.getText()?.text)
                        if (r == null) {
                            Toast.makeText(sheetContext, PasteParse.EMPTY_CLIPBOARD_TOAST, Toast.LENGTH_SHORT).show()
                        } else {
                            r.title?.let { titleField = TextFieldValue(it, TextRange(it.length)) }
                            r.checklist?.let { checklistItems = it }
                            r.notes?.let { notes = it }
                            Toast.makeText(sheetContext, r.toast, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("paste_task_button")
                ) { Text("📋", fontSize = 14.sp, color = TextSecondary) }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(PasteParse.copyText(title, notes, checklistItems)))
                        Toast.makeText(sheetContext, PasteParse.COPIED_TOAST, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_task_button")
                ) { Text("⧉", fontSize = 14.sp, color = TextSecondary) }
                if (isSaveButtonTop) {
                    Button(
                        onClick = saveNow,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        modifier = Modifier.testTag("save_task_button")
                    ) {
                        Text("Save Task")
                    }
                }
            }

            // Title Field
            // Opti #eTitle is a one-line <input>; Enter saves & closes (onSheetKeydown).
            OutlinedTextField(
                value = titleField,
                onValueChange = { titleField = it },
                label = { Text("Task Title") },
                singleLine = true,
                keyboardOptions = OptiKeyboard.TextDone,
                keyboardActions = KeyboardActions(onDone = { saveNow() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderLavender,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus)
                    .testTag("task_title_input")
            )

            // Notes Field: Opti #eNotes is a <textarea>, so Enter types a newline.
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes & RPG Context") },
                keyboardOptions = OptiKeyboard.Text,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = BorderLavender,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier.fillMaxWidth().testTag("task_notes_input")
            )

            // CR-KT-015: the "Images" strip (hidden until the data layer provides a repository).
            AttachmentStrip(taskId = task?.id)



            // Difficulty segmented chooser
            Text("Difficulty (Multiplies Rewards & Miss Damage)", fontSize = 13.sp, color = TextSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                difficultyOptions(type).forEach { (diff, label) ->
                    val isSelected = difficulty == diff
                    Button(
                        onClick = { difficulty = diff },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) SecondaryPurple else SurfaceDark,
                            contentColor = if (isSelected) Color.White else TextSecondary
                        ),
                        border = BorderStroke(1.dp, if (isSelected) SecondaryPurple else BorderLavender),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("diff_tab_$diff")
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }

            if (type == "habit") {
                // Opti app.js:6279-6282: "Buttons" row sits between Difficulty and Reset counter.
                Text("Buttons", fontSize = 13.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(true, false).forEach { isUp ->
                        val isOn = if (isUp) habitUp != false else habitDown != false
                        Button(
                            onClick = {
                                if (isUp) {
                                    habitUp = !(habitUp != false)
                                } else {
                                    habitDown = !(habitDown != false)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOn) SecondaryPurple else SurfaceDark,
                                contentColor = if (isOn) Color.White else TextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isOn) SecondaryPurple else BorderLavender),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag(if (isUp) "habit_up_toggle" else "habit_down_toggle")
                        ) {
                            Text(if (isUp) "+ Positive" else "− Negative", fontSize = 11.sp)
                        }
                    }
                }
                Text("+ rewards XP/gold; − costs HP. Enable either or both.", fontSize = 11.sp, color = TextSecondary)

                Text("Reset Frequency", fontSize = 13.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly").forEach { (freq, label) ->
                        val isSelected = resetFreq == freq
                        Button(
                            onClick = { resetFreq = freq },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) SecondaryPurple else SurfaceDark,
                                contentColor = if (isSelected) Color.White else TextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isSelected) SecondaryPurple else BorderLavender),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 11.sp)
                        }
                    }
                }

                Text("Adjust Counters (Compounds Reward/Damage)", fontSize = 13.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Up:", fontSize = 12.sp, color = TextSecondary)
                        IconButton(onClick = { if (editedCUp > 0) editedCUp-- }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease Up", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        Text("$editedCUp", color = TextPrimary, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { editedCUp++ }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase Up", tint = OptiRootTokens.Green, modifier = Modifier.size(16.dp))
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Down:", fontSize = 12.sp, color = TextSecondary)
                        IconButton(onClick = { if (editedCDown > 0) editedCDown-- }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease Down", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        Text("$editedCDown", color = TextPrimary, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { editedCDown++ }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase Down", tint = OptiRootTokens.Hp, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (type == "habit") QuickLogShortcutEditor(quickLog, { quickLog = it }, Modifier.testTag("quicklog_editor"))

            // Repetition Week Selector (Only Dailies)
            if (type == "daily") {
                Text("Repeat Frequency", fontSize = 13.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { index, day ->
                        val isSelected = repeatDays[index]
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) PrimaryNeon else BorderLavender.copy(alpha = 0.2f))
                                .border(1.dp, if (isSelected) PrimaryNeon else BorderLavender, CircleShape)
                                .clickable {
                                    val newDays = repeatDays.toMutableList()
                                    newDays[index] = !isSelected
                                    repeatDays = newDays
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                day,
                                color = if (isSelected) Color.White else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }



            // WP-8.11: reminder editor (app.js:6039 drawReminderEditor)
            ReminderEditor(type = type, repeat = repeatDays, value = reminderDraft, onChange = { reminderDraft = it })

            // Checklist Manager (Dailies & Todos)
            if (type == "daily" || type == "todo") {
                Text("Checklist Sub-Tasks", fontSize = 13.sp, color = TextSecondary)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Drag-to-reorder (P12/2b): small drag handle, immediate pickup (detectReorder),
                    // scoped to this task's in-memory checklist working copy only.
                    val checklistListState = rememberLazyListState()
                    val checklistReorderState = rememberReorderableLazyListState(
                        listState = checklistListState,
                        onMove = { from, to -> reorderMove(checklistItems, from, to, checklistListState)?.let { checklistItems = it } }
                    )

                    LazyColumn(
                        state = checklistReorderState.listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .reorderable(checklistReorderState)
                    ) {
                        itemsIndexed(checklistItems, key = { _, item -> item.id }) { index, item ->
                            ReorderableItem(checklistReorderState, key = item.id) { isDragging ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .shadow(if (isDragging) 4.dp else 0.dp, RoundedCornerShape(6.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.DragIndicator,
                                        contentDescription = "Drag to reorder",
                                        tint = TextSecondary,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .detectReorder(checklistReorderState)
                                            .testTag("drag_handle_subtask_$index")
                                    )
                                    Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null, tint = BorderLavender, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(if (item.done) OptiRootTokens.Green else Color(0xFF2A1947), RoundedCornerShape(5.dp))
                                            .border(1.dp, if (item.done) OptiRootTokens.Green else BorderLavender, RoundedCornerShape(5.dp))
                                            .clickable {
                                                checklistItems = checklistItems.map { if (it.id == item.id) it.copy(done = !it.done) else it }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (item.done) {
                                            Text("✔", fontSize = 13.sp, color = Color.White)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Opti drawChecklist (app.js:6035): each row is a text <input> bound to
                                    // EDIT.checklist[i].text, so a saved subtask stays editable. Enter saves
                                    // the task (onSheetKeydown); blank rows are dropped at save.
                                    BasicTextField(
                                        value = item.text,
                                        onValueChange = { v -> checklistItems = checklistItems.map { if (it.id == item.id) it.copy(text = v) else it } },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 13.sp,
                                            color = if (item.done) TextSecondary else TextPrimary,
                                            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                                        ),
                                        cursorBrush = SolidColor(PrimaryNeon),
                                        keyboardOptions = OptiKeyboard.TextDone,
                                        keyboardActions = KeyboardActions(onDone = { saveNow() }),
                                        modifier = Modifier
                                            .weight(1f)
                                            // Opti's row is a boxed <input>; a thin underline shows it is editable.
                                            .drawBehind {
                                                drawLine(BorderLavender.copy(alpha = 0.5f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                                            }
                                            .padding(vertical = 6.dp)
                                            .testTag("subtask_text_${item.id}")
                                    )

                                    IconButton(
                                        onClick = { checklistItems = checklistItems.filter { it.id != item.id } },
                                        modifier = Modifier.size(28.dp).testTag("delete_subtask_$index")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove sub-task", tint = OptiRootTokens.Hp, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newChecklistItemText,
                            onValueChange = { newChecklistItemText = it },
                            placeholder = { Text("Add checklist sub-task...", fontSize = 12.sp) },
                            // Opti subtask rows are <input>s inside #sheet, so Enter there saves the
                            // task with the typed row (onSheetKeydown). Here the typed row is the
                            // pending text: add it, then save.
                            singleLine = true,
                            keyboardOptions = OptiKeyboard.TextDone,
                            keyboardActions = KeyboardActions(onDone = { addPendingSubtask(); saveNow() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeon,
                                unfocusedBorderColor = BorderLavender,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(subtaskFocus)
                                .testTag("checklist_subtask_input")
                        )
                        IconButton(
                            onClick = {
                                addPendingSubtask()
                                // Opti addSubtask (app.js:5937-5949) focuses the row just added so
                                // typing goes on at once; here that row is this input.
                                runCatching { subtaskFocus.requestFocus() }
                            },
                            modifier = Modifier.testTag("add_subtask_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add sub-task", tint = OptiRootTokens.Green)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tags Selector
            var showNewTagInput by remember { mutableStateOf(false) }
            var newTagName by remember { mutableStateOf("") }
            val tagColors = listOf("#90E0EF", "#FFD166", "#FF9F1C", "#EF476F", "#06D6A0", "#118AB2", "#8338EC")
            var newTagColor by remember { mutableStateOf(tagColors[0]) }
            // Opti addTagToEdit (app.js:3299-3301): addTag trims, refuses empty, reuses a tag whose
            // name matches ignoring case (3259), attaches the tag to the task, and always clears the input.
            val addTagNow: () -> Unit = {
                EditorTitleRules.tagName(newTagName)?.let { name ->
                    val ex = EditorTitleRules.existingTag(name, availableTags)
                    // CR-KT-008: attach the tag ID; a new tag's id is chosen here, before the save.
                    val createdId = if (ex == null) newTagId().also { onCreateTag(it, name, newTagColor) } else ""
                    selectedTags = attachEditorTag(selectedTags, ex, createdId)
                    showNewTagInput = false
                }
                newTagName = ""
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tags", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        text = if (showNewTagInput) "Close" else "+ Add New Tag",
                        color = PrimaryNeon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { showNewTagInput = !showNewTagInput }
                            .padding(4.dp)
                            .testTag("toggle_new_tag_input_button")
                    )
                }

                if (showNewTagInput) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceDark, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderLavender, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newTagName,
                            onValueChange = { newTagName = it },
                            label = { Text("New Tag Name", fontSize = 12.sp) },
                            // Opti #eTagInput: Enter adds the tag and does not save the task (app.js:3313).
                            singleLine = true,
                            keyboardOptions = OptiKeyboard.TextDone,
                            keyboardActions = KeyboardActions(onDone = { addTagNow() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNeon,
                                unfocusedBorderColor = BorderLavender,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("new_tag_name_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                tagColors.forEach { colorHex ->
                                    val isSelected = newTagColor == colorHex
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color(android.graphics.Color.parseColor(colorHex)))
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) Color.White else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .clickable { newTagColor = colorHex }
                                    )
                                }
                            }

                            Button(
                                onClick = { addTagNow() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                                modifier = Modifier.height(32.dp).testTag("create_tag_button")
                            ) {
                                Text("Add Tag", fontSize = 11.sp, color = Color.Black)
                            }
                        }
                    }
                }

                if (availableTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableTags.forEach { tag ->
                            val isSelected = holdsTag(selectedTags, tag)
                            val tagColor = safeTagColor(tag.color)
                            Row(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) tagColor.copy(alpha = 0.3f) else SurfaceDark,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) tagColor else BorderLavender,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        selectedTags = toggleEditorTag(selectedTags, tag)
                                    }
                                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                    .testTag("task_tag_${tag.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tag.name,
                                    fontSize = 12.sp,
                                    color = if (isSelected) TextPrimary else TextSecondary
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete tag",
                                    tint = OptiRootTokens.Hp,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            onDeleteTag(tag.id, tag.name)
                                            selectedTags = removeEditorTag(selectedTags, tag)
                                        }
                                        .testTag("delete_tag_${tag.name}")
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No custom tags created yet.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Delete confirmation dialog
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete this task?") },
                    text = { Text("This cannot be undone.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("confirm_delete_task_button")
                        ) {
                            Text("Delete", color = OptiRootTokens.Hp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                    containerColor = SurfaceDark
                )
            }

            // Unsaved-changes guard: reached from Cancel and system Back when the draft is dirty
            // (sheet drag/scrim are blocked silently by the sheetState gate above).
            if (showDiscardConfirm) {
                AlertDialog(
                    onDismissRequest = { showDiscardConfirm = false; historyAfterDiscard = false },
                    title = { Text("Discard changes?") },
                    text = { Text("You have unsaved changes in this task.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDiscardConfirm = false
                                saveNow()
                                // Review MINOR-2: Save from the History guard still goes to Activity.
                                if (historyAfterDiscard) onHistory?.invoke()
                                historyAfterDiscard = false
                            },
                            modifier = Modifier.testTag("discard_dialog_save_button")
                        ) {
                            Text("Save", color = PrimaryNeon)
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { showDiscardConfirm = false; historyAfterDiscard = false },
                                modifier = Modifier.testTag("discard_dialog_keep_editing_button")
                            ) {
                                Text("Keep Editing")
                            }
                            TextButton(
                                onClick = {
                                    showDiscardConfirm = false
                                    if (historyAfterDiscard) onHistory?.invoke() else onDismiss()
                                    historyAfterDiscard = false
                                },
                                modifier = Modifier.testTag("discard_dialog_discard_button")
                            ) {
                                Text("Discard", color = OptiRootTokens.Hp)
                            }
                        }
                    },
                    containerColor = SurfaceDark
                )
            }

            // CR-KT-012: "History" opens the Activity tab filtered by this task.
            if (onHistory != null && task != null && task.id.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            if (isDirtyNow()) { historyAfterDiscard = true; showDiscardConfirm = true } else onHistory()
                        }
                        .padding(vertical = 10.dp)
                        .testTag("edit_history_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📋 History", color = TextPrimary, modifier = Modifier.weight(1f))
                    Text("→", color = TextSecondary)
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (task != null && task.id.isNotEmpty()) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("delete_task_button")
                    ) {
                        Text("Delete", color = OptiRootTokens.Hp)
                    }
                }

                TextButton(
                    onClick = { if (isDirtyNow()) showDiscardConfirm = true else onDismiss() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", color = TextSecondary)
                }

                if (!isSaveButtonTop) {
                    Button(
                        onClick = saveNow,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_task_button")
                    ) {
                        Text("Save Task")
                    }
                }
            }
        }
    }
}

/** Reward creator and editor (Opti `openReward`, app.js:7171). Extracted in WP-8.16-R1 so its keyboards are testable.
 *  [existing] non-null = edit that reward: fields start from it, the title reads "Edit Reward" and a
 *  Delete button shows when [onDelete] is set (Opti shows Delete only for a reward with an id). */
@Composable
fun RewardCreatorDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Double, String) -> Unit,
    existing: RewardEntity? = null,
    onDelete: (() -> Unit)? = null,
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    // Opti `String(REDIT.cost)`: a whole-number cost shows without ".0".
    var costText by remember(existing?.id) { mutableStateOf(existing?.cost?.let { c -> if (c % 1.0 == 0.0) c.toLong().toString() else c.toString() } ?: "10") }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(BorderStroke(1.dp, BorderLavender), RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (existing != null) "Edit Reward" else "Create Custom Reward",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryNeon
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Reward Name") },
                    // WP-8.16-R1: Opti #rTitle is a one-line <input> (app.js:6421).
                    singleLine = true,
                    keyboardOptions = OptiKeyboard.Text,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = BorderLavender,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reward_title_input")
                )

                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("Gold Cost 🪙") },
                    singleLine = true,
                    keyboardOptions = OptiKeyboard.Decimal,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = BorderLavender,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reward_cost_input")
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    keyboardOptions = OptiKeyboard.Text,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = BorderLavender,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (existing != null && onDelete != null) {
                        TextButton(
                            onClick = { onDelete(); onDismiss() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("delete_reward_button")
                        ) {
                            Text("Delete", color = Color(0xFFF74E52))
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            val cost = EditorTitleRules.rewardCost(costText)
                            // WP-8.22-A: Opti saveReward stores `trim()||'Reward'` (app.js:6431).
                            onCreate(EditorTitleRules.reward(title), cost, notes)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_reward_button")
                    ) {
                        Text(if (existing != null) "Save" else "Create")
                    }
                }
            }
        }
    }
}
