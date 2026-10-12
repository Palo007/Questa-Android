package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.CharacterEntity
import com.example.data.local.entities.RewardEntity
import com.example.data.local.entities.TaskEntity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.data.sync.syncDataStore
import com.example.ui.analytics.AnalyticsViewModel
import com.example.ui.CardPrefs
import com.example.ui.DashboardScreen
import com.example.ui.LocalCardPrefs
import com.example.ui.LocalUiStatePrefs
import com.example.ui.UiStatePrefs
import com.example.ui.MainViewModel
import com.example.data.attachments.AttachmentRepositoryImpl
import com.example.ui.attachments.LocalAttachmentRepository
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.SettingsViewModel
import com.example.ui.settings.settingsRouteForExtra
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // WP-5.1 (spec §7 D10): pull the already-constructed repository/syncManager/syncDataStore from
    // QuestaApplication (constructed once per process in its onCreate()) instead of building fresh
    // instances here on every Activity creation/recreation -- a second RpgRepository would have its
    // own private `localWrites` signal that SyncScheduler (owned by QuestaApplication) never
    // observes, silently breaking the debounce trigger for any write made through this second
    // instance. Every other behavior below (seed data, OAuth redirect handling) is unchanged.
    val app = application as com.example.QuestaApplication
    val syncDataStore = app.syncDataStore
    val repository = app.repository

    syncManager = app.syncManager

    // WP-8.22-D item 4: the redirect code already handled by this activity (kept across a rotation).
    handledRedirect = savedInstanceState?.getString(STATE_HANDLED_REDIRECT)

    // Seed data on first launch safely on IO thread. WP-8.21: the seed first waits for the
    // startup migrations (StartupBarrier), so its writes never race step 0-4 (FreshInstallSeedRaceTest).
    // R-4: only a true fresh install is seeded, once (FirstLaunchSeedGate).
    lifecycleScope.launch(Dispatchers.IO) {
      seedFirstLaunch(repository, com.example.startup.FirstLaunchSeedGate.forDir(noBackupFilesDir))
    }

    // Initialize MainViewModel with Factory
    val viewModel: MainViewModel by viewModels {
      MainViewModel.Factory(repository, syncManager, syncDataStore)
    }
    mainViewModel = viewModel
    // WP-8.14: Settings / Analytics ViewModels (factories read the app object).
    val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory }
    this.settingsViewModel = settingsViewModel
    val analyticsViewModel: AnalyticsViewModel by viewModels { AnalyticsViewModel.Factory }

    // Handle OAuth redirect if launched via deep link. WP-8.22-D: after the ViewModels exist (the
    // handling runs in the SettingsViewModel scope) and only once per redirect (item 4).
    takeRedirectOnce(intent, handledRedirect)?.let { handleOAuthRedirect(it) }

    // WP-8.14: EXTRA_OPEN_SETTINGS ("dropbox" = the Sync block), e.g. the 8.13 connect shortcut.
    openSettingsFrom(intent)

    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        // WP-8.14 (PX-88 provider): the card prefs from the DataStore; 8.18's cards read them.
        val cardPrefs by remember { CardPrefs.flow(applicationContext.syncDataStore.data) }
          .collectAsState(initial = CardPrefs())
        val uiStatePrefs by remember { UiStatePrefs.flow(applicationContext.syncDataStore.data) }
          .collectAsState(initial = UiStatePrefs())
        CompositionLocalProvider(
          LocalCardPrefs provides cardPrefs,
          LocalUiStatePrefs provides uiStatePrefs,
          LocalAttachmentRepository provides AttachmentRepositoryImpl.get(applicationContext),
        ) {
          DashboardScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            settingsViewModel = settingsViewModel,
            analyticsViewModel = analyticsViewModel,
          )
        }
      }
    }
  }

  private lateinit var syncManager: com.example.data.sync.SyncManager
  private var mainViewModel: MainViewModel? = null
  private var settingsViewModel: SettingsViewModel? = null
  private var handledRedirect: String? = null

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    takeRedirectOnce(intent, handledRedirect)?.let { handleOAuthRedirect(it) }
    openSettingsFrom(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    handledRedirect?.let { outState.putString(STATE_HANDLED_REDIRECT, it) }
  }

  /** WP-8.14: [EXTRA_OPEN_SETTINGS] -> [MainViewModel.openSettings]; the extra is consumed once. */
  private fun openSettingsFrom(intent: android.content.Intent?) {
    applyOpenSettingsExtra(intent) { mainViewModel?.openSettings(it) }
  }

  private fun handleOAuthRedirect(dataString: String) {
    handledRedirect = dataString
    val settingsVm = settingsViewModel ?: run {
      android.util.Log.w("QuestaAuth", "connect: no SettingsViewModel, redirect dropped")
      return
    }
    // WP-8.22-D item 4: the ViewModel scope survives a rotation, so a login in flight is not cancelled
    // by the recreate (the recreated activity skips the same code, see takeRedirectOnce).
    settingsVm.viewModelScope.launch {
      // WP-8.22-D item 2: wait for the startup migrations (StartupBarrier, no timeout, as every other
      // caller) so the token write never races TokenMigration / AppKeyMigration on a cold start.
      handleRedirectAfterStartup(dataString) { handleOAuthRedirectNow(it, settingsVm) }
    }
  }

  private suspend fun handleOAuthRedirectNow(dataString: String, settingsVm: SettingsViewModel) {
    val forceOps = (application as QuestaApplication).forceOps
    if (!syncManager.handleRedirectUri(dataString)) {
      // WP-8.9 patch 6: a failed login drops a pending "Connect & Force Push" (Opti syncHandleRedirect).
      forceOps.clearPendingForcePush()
      // KT-61 (fix plan P9): a refused account gets a visible message, not silence. A failed
      // exchange or token save gets one too (a missing INTERNET permission was silent before).
      (syncManager.lastConnectRefusal ?: syncManager.lastConnectFailure)?.let {
        android.widget.Toast.makeText(applicationContext, it, android.widget.Toast.LENGTH_LONG).show()
      }
    } else if (com.example.data.sync.auth.DropboxConfig.autoSyncAfterConnect()) {
      // WP-8.9 patch 6: the OAuth-return tail (force push when the intent is set, else the normal round).
      // WP-8.14: NeedsFirstConnectChoice opens the first-connect dialog (PX-111).
      settingsVm.handleRound(forceOps.connectForForcePush())
    } else {
      // WP-8.1 guess 17 (D30): the debug build connects only, no automatic round or upload; a
      // pending force push is dropped too, so no later path can fire it unasked.
      forceOps.clearPendingForcePush()
    }
  }
}

/** WP-8.22-D item 4: the savedInstanceState key of the redirect already handled. */
internal const val STATE_HANDLED_REDIRECT = "questa.handledRedirect"

/**
 * WP-8.22-D item 4: the OAuth redirect in [intent] to handle now, or null. The data is removed from
 * the intent, and a redirect equal to [alreadyHandled] (restored from savedInstanceState after a
 * rotation re-delivers the launching intent) is skipped: a used code is never exchanged twice, so
 * its failure path can no longer clear a pending force push. PKCE / state checks are unchanged.
 *
 * [DEFERRED_OPEN_QUESTION] (review 46 F4, pre-existing): when the redirect intent is the task root
 * and the task is reopened from Recents after the activity finished (no saved state), the system
 * re-delivers it with FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY and the old code is exchanged again; a
 * failure then clears a pending force push. Not handled here.
 */
internal fun takeRedirectOnce(intent: android.content.Intent?, alreadyHandled: String?): String? {
  val data = intent?.dataString ?: return null
  intent.data = null
  return data.takeIf { it != alreadyHandled }
}

/**
 * WP-8.22-D item 2: runs [handle] once the startup barrier is open. When the startup migrations
 * failed the barrier never opens: nothing runs (no token write, and a pending force push is kept).
 */
internal suspend fun handleRedirectAfterStartup(
  dataString: String,
  awaitStartup: suspend () -> Boolean = { com.example.data.clock.StartupBarrier.awaitOutcome() },
  handle: suspend (String) -> Unit,
): Boolean {
  if (!awaitStartup()) {
    android.util.Log.w("QuestaAuth", "connect: startup barrier closed, redirect dropped")
    return false
  }
  handle(dataString)
  return true
}

/**
 * WP-8.14: reads [EXTRA_OPEN_SETTINGS] from [intent] (onCreate and onNewIntent), removes it so a
 * recreate does not reopen Settings, and hands the route to [open]. No extra: nothing happens.
 */
internal fun applyOpenSettingsExtra(intent: android.content.Intent?, open: (com.example.ui.settings.SettingsRoute) -> Unit) {
  val route = settingsRouteForExtra(intent?.getStringExtra(EXTRA_OPEN_SETTINGS)) ?: return
  intent?.removeExtra(EXTRA_OPEN_SETTINGS)
  open(route)
}

/**
 * First-launch seed (character always; starter tasks and rewards on a fresh install only). WP-8.21
 * (r6 m-R5-3): the seed awaits [StartupBarrier] first, so its writes never race step 0-4 and the
 * D21 class is captured before it runs. If the migrations fail, the barrier stays closed.
 *
 * R-4 (8.12 s10): the starter content is written ONLY when [gate] says this is an unseeded FRESH
 * install (never an upgrade, a restored copy, after a Reset or after the user emptied a list; Opti
 * `freshState()` never re-adds deleted content), and only when BOTH lists are empty -- all or
 * nothing, never a per-table top-up. The gate is closed right after the decision.
 */
internal suspend fun seedFirstLaunch(
  repository: com.example.data.RpgRepository,
  gate: com.example.startup.SeedGate,
  // 8.I (W3 close): switched in the same commit that launches StartupMigrations.run from
  // QuestaApplication.onCreate, together with SyncLock's DEFAULT_AWAIT_BARRIER (review 26 C2).
  awaitBarrier: suspend () -> Unit = { com.example.data.clock.StartupBarrier.await() }
) {
  awaitBarrier()
  // Create character if missing
  repository.getCharacterSync()

  if (!gate.shouldSeed()) return
  if (repository.allTasks.first().isEmpty() && repository.allRewards.first().isEmpty()) {
    // WP-8.23: the stamps a user-created row gets (MainViewModel.createTask): createdAt from the
    // physical clock seam, updatedAt from the shared HLC.
    val createdAt = repository.physicalNow()
    val updatedAt = repository.hlcClock.now()
    starterTasks(createdAt, updatedAt).forEach { repository.insertTask(it) }
    starterRewards(createdAt, updatedAt).forEach { repository.insertReward(it) }
  }
  gate.markSeeded()
}

/** The starter tasks (mirrored id-less by `LocalContent.SEED_TASKS`; LocalContentSeedTest catches drift). */
private fun starterTasks(createdAt: Long, updatedAt: Long): List<TaskEntity> = listOf(
  TaskEntity(
    id = UUID.randomUUID().toString(),
    type = "habit",
    title = "Practice Coding",
    notes = "Gain experience by sharpening your steel.",
    difficulty = "medium",
    createdAt = createdAt,
    updatedAt = updatedAt
  ),
  TaskEntity(
    id = UUID.randomUUID().toString(),
    type = "habit",
    title = "Eat Junk Food",
    notes = "Penalty for giving in to dark desires.",
    difficulty = "easy",
    createdAt = createdAt,
    updatedAt = updatedAt
  ),
  TaskEntity(
    id = UUID.randomUUID().toString(),
    type = "daily",
    title = "Morning Gym Routine",
    notes = "Maintain your physical power stat.",
    difficulty = "hard",
    repeat = listOf(true, true, true, true, true, false, false), // Mon-Fri
    createdAt = createdAt,
    updatedAt = updatedAt
  ),
  TaskEntity(
    id = UUID.randomUUID().toString(),
    type = "todo",
    title = "Slay Stage 2 Code Review",
    notes = "Complete the native Kotlin migration UI layer.",
    difficulty = "hard",
    createdAt = createdAt,
    updatedAt = updatedAt
  )
)

/** The starter rewards (mirrored id-less by `LocalContent.SEED_REWARDS`). */
private fun starterRewards(createdAt: Long, updatedAt: Long): List<RewardEntity> = listOf(
  RewardEntity(
    id = UUID.randomUUID().toString(),
    title = "Play 1 hour of Elden Ring",
    cost = 15.0,
    notes = "A fitting reward for a productive warrior.",
    createdAt = createdAt,
    updatedAt = updatedAt
  ),
  RewardEntity(
    id = UUID.randomUUID().toString(),
    title = "Order premium coffee",
    cost = 8.0,
    notes = "Boost your mana pool with caffeine.",
    createdAt = createdAt,
    updatedAt = updatedAt
  )
)
