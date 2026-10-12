package com.example.ui.settings

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.freshMainDispatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.FirstConnectPreview
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.auth.AuthState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the screen asked for. */
private class RecordingActions : SettingsActions {
    val calls = mutableListOf<String>()
    override fun connect() { calls += "connect" }
    override fun requestConnectForForcePush() { calls += "connectForcePush" }
    override fun syncNow() { calls += "syncNow" }
    override fun requestDisconnect() { calls += "disconnect" }
    override fun requestForcePush() { calls += "forcePush" }
    override fun requestForcePull() { calls += "forcePull" }
    override fun requestForcePushEvents() { calls += "forcePushEvents" }
    override fun openOption(key: String?) { calls += "option:$key" }
    override fun openCategory(key: String?) { calls += "category:$key" }
    override fun requestReset() { calls += "reset" }
    override fun tapVersion() { calls += "version" }
    override fun openExportPicker() { calls += "export" }
    override fun openImportPicker() { calls += "import" }
    override fun close() { calls += "close" }
    override fun openAppKeyDialog() { calls += "appKeyDialog" }
    override fun saveAppKey(key: String) { calls += "saveAppKey:$key" }
}

/** WP-8.14: Settings rows (Opti order and labels) and the reset flow (ResetUiTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsScreenTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    // No Dispatchers.setMain here: viewModelScope runs on Robolectric's main looper, which the
    // compose rule idles (a test Main dispatcher left coroutines resuming after resetMain).
    private val vms = mutableListOf<SettingsViewModel>()

    private fun newVm(b: FakeSettingsBackend = FakeSettingsBackend()) = SettingsViewModel(b).also { vms += it }

    @After fun tearDown() { vms.forEach { it.viewModelScope.cancel() } }

    @Test
    fun `not connected - Connect Dropbox and Connect and Force Push`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(appKey = "k", appVersion = "v1.0"), a) }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onNodeWithText("Appearance").assertIsDisplayed()
        rule.onNodeWithText("Interaction").assertIsDisplayed()
        rule.onNodeWithText("Activity Feed").assertIsDisplayed()
        rule.onNodeWithTag("connect_dropbox").performScrollTo().performClick()
        rule.onNodeWithTag("connect_force_push").performScrollTo().performClick()
        rule.onNodeWithTag("sync_now_button").assertDoesNotExist()
        assertEquals(listOf("connect", "connectForcePush"), a.calls)
    }

    @Test
    fun `no app key - setup row instead of the Connect buttons`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(), a) }
        rule.onNodeWithTag("connect_dropbox").assertDoesNotExist()
        rule.onNodeWithTag("app_key_setup_row").performScrollTo().performClick()
        assertEquals(listOf("appKeyDialog"), a.calls)
    }

    @Test
    fun `app key stored - key row with the warning, Connect shown`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(appKey = "abc123"), a) }
        rule.onNodeWithText("abc123 ›").performScrollTo().performClick()
        rule.onNodeWithText("Changing the key disconnects Dropbox and resets sync cursors.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("connect_dropbox").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("appKeyDialog"), a.calls)
    }

    @Test
    fun `app key dialog - help shows the redirect URI, Save sends the text`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(appKeyDialog = true), a) }
        rule.onNodeWithTag("app_key_help").assertDoesNotExist()
        rule.onNodeWithTag("app_key_help_toggle").performClick()
        rule.onNodeWithTag("app_key_help").assertTextContains(com.example.data.sync.auth.DropboxConfig.REDIRECT_URI, substring = true)
        rule.onNodeWithTag("app_key_help").assertTextContains("App folder", substring = true)
        rule.onNodeWithTag("app_key_field").performTextInput("newkey")
        rule.onNodeWithTag("app_key_save").performClick()
        assertEquals(listOf("saveAppKey:newkey"), a.calls)
    }

    @Test
    fun `connected - status, device name, force row and auto-backup row`() {
        val a = RecordingActions()
        val st = SettingsUiState(appKey = "k", 
            connected = true, lastSyncAt = 1_000L, nowMs = 61_000L, lastSyncError = "boom",
            deviceId = "abcdef99", deviceName = "", autoBackup = AutoBackupEnabled(daily = true)
        )
        rule.setContent { SettingsScreen(st, a) }
        rule.onNodeWithText("Last sync: 1m ago").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("boom").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Device abcdef").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("connect_dropbox").assertDoesNotExist()
        rule.onNodeWithTag("sync_now_button").performScrollTo().performClick()
        rule.onNodeWithTag("disconnect_button").performScrollTo().performClick()
        rule.onNodeWithText("Force push").performScrollTo().performClick()
        rule.onNodeWithText("Force pull").performScrollTo().performClick()
        rule.onNodeWithText("Force Push Events").performScrollTo().performClick()
        rule.onNodeWithText("Daily ›").performScrollTo().performClick()
        assertEquals(listOf("syncNow", "disconnect", "forcePush", "forcePull", "forcePushEvents", "option:autoBackup"), a.calls)
    }

    @Test
    fun `pending first connect - persistent row and Choose button`() {
        var opened = 0
        val a = object : SettingsActions { override fun openFirstConnect() { opened++ } }
        rule.setContent { SettingsScreen(SettingsUiState(appKey = "k", connected = true, firstConnectPending = true), a) }
        rule.onNodeWithText(SettingsTexts.FC_PENDING).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fc_open").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    // ---- WP-8.22-B: Bubblewrap quick-log rows (SettingsActivity.kt:115-121, :145-147) ----

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `sync block - Dropbox status line then Waiting to upload, before the buttons`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(appKey = "k", authState = AuthState.NOT_LINKED, waitingToUpload = 4), a) }
        rule.onNodeWithText("Dropbox: not connected").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Waiting to upload: 4").performScrollTo().assertIsDisplayed()
        assertTrue(top("dropbox_status_row") < top("waiting_upload_row"))
        assertTrue(top("waiting_upload_row") < top("connect_row"))
    }

    @Test
    fun `sync block - login expired and connected texts`() {
        val state = mutableStateOf(SettingsUiState(appKey = "k", authState = AuthState.RECONNECT_REQUIRED, reconnectRequired = true))
        rule.setContent { SettingsScreen(state.value, RecordingActions()) }
        rule.onNodeWithText("Dropbox: login expired. Connect again.").performScrollTo().assertIsDisplayed()
        state.value = SettingsUiState(appKey = "k", authState = AuthState.LINKED, connected = true, waitingToUpload = 0)
        rule.onNodeWithText("Dropbox: connected").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Waiting to upload: 0").performScrollTo().assertIsDisplayed()
        assertTrue(top("waiting_upload_row") < top("dev_name_block"))
    }

    /** Review 44 item 1: pre-8.1 install, DROPBOX_ENABLED but no AUTH_STATE key (read as NOT_LINKED). */
    @Test
    fun `sync block - enabled without saved auth state reads connected, like the Disconnect button`() {
        rule.setContent { SettingsScreen(SettingsUiState(appKey = "k", connected = true, authState = AuthState.NOT_LINKED), RecordingActions()) }
        rule.onNodeWithText("Dropbox: connected").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("disconnect_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `notifications option - Reminders N scheduled and Last checked before Exact alarms`() {
        val now = 1_790_000_000_000L
        val st = SettingsUiState(
            option = "notifications", exactAlarmsAllowed = true, nowMs = now,
            remindersScheduled = 3, remindersLastChecked = now - 5 * 60_000L,
        )
        rule.setContent { SettingsScreen(st, RecordingActions()) }
        rule.onNodeWithText("Reminders: 3 scheduled").assertIsDisplayed()
        rule.onNodeWithText("Last checked: 5 minutes ago").assertIsDisplayed()
        assertTrue(top("reminders_row") < top("reminders_last_checked"))
        assertTrue(top("reminders_last_checked") < top("exact_alarm_status"))
    }

    @Test
    fun `notifications option - Last checked never, rows shown below API 31 too`() {
        rule.setContent { SettingsScreen(SettingsUiState(option = "notifications", exactAlarmsAllowed = null), RecordingActions()) }
        rule.onNodeWithText("Reminders: 0 scheduled").assertIsDisplayed()
        rule.onNodeWithText("Last checked: never").assertIsDisplayed()
    }

    @Test
    fun `backup rows, warnings, reset and version`() {
        val a = RecordingActions()
        rule.setContent {
            SettingsScreen(SettingsUiState(appKey = "k", clockAhead = true, startupFailed = true, reconnectRequired = true, appVersion = "v9"), a)
        }
        rule.onNodeWithText("Last export: None").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SettingsTexts.CLOCK_AHEAD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("startup_failed_row").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("reconnect required").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Export").performScrollTo().performClick()
        rule.onNodeWithText("Import").performScrollTo().performClick()
        rule.onNodeWithText("Reset everything").performScrollTo().performClick()
        rule.onNodeWithText("v9").performScrollTo().performClick()
        assertEquals(listOf("export", "import", "reset", "version"), a.calls)
    }

    @Test
    fun `interaction category - haptics, save button, notifications row`() {
        val a = RecordingActions()
        rule.setContent { SettingsScreen(SettingsUiState(category = "interaction"), a) }
        rule.onNodeWithText("⚙️ Interaction").assertIsDisplayed()
        rule.onNodeWithText("Haptics").assertIsDisplayed()
        rule.onNodeWithText("Save button position").assertIsDisplayed()
        rule.onNodeWithText("Off ›").performClick()
        assertTrue("option:notifications" in a.calls)
    }

    @Test
    fun `notifications option - permission status and request`() {
        val vm = newVm()
        vm.openOption("notifications")
        rule.setContent { SettingsScreen(vm.state.collectAsState().value, vm) }
        rule.onNodeWithText("System Permission: default").assertIsDisplayed()
        rule.onNodeWithText("Request Permission").assertIsDisplayed()
        rule.onNodeWithText("Exact alarms: allowed").assertIsDisplayed()
    }

    @Test
    fun `activity feed category - pause tracking toggle`() {
        val b = FakeSettingsBackend()
        val vm = newVm(b)
        vm.openCategory("activityFeed")
        rule.setContent {
            val s = vm.state.value
            SettingsScreen(s, vm)
        }
        rule.onNodeWithText("Pause tracking").assertIsDisplayed()
        rule.onNodeWithTag("toggle_pause").performClick()
        rule.waitForIdle()
        assertTrue("pause:true" in b.calls)
    }

    /** ResetUiTest: Reset everything -> Opti's confirm -> Yes runs the engine; Cancel does not. */
    @Test
    fun `reset asks Opti's confirm and runs the reset engine only on Yes`() {
        val b = FakeSettingsBackend()
        val vm = newVm(b)
        rule.setContent {
            val s = vm.state.collectAsState().value
            SettingsScreen(s, vm)
        }
        rule.onNodeWithText("Reset everything").performScrollTo().performClick()
        rule.onNodeWithText("Reset Everything").assertIsDisplayed()
        rule.onNodeWithText("Erase ALL progress on this device? This cannot be undone.").assertIsDisplayed()
        rule.onNodeWithTag("confirm_no").performClick()
        rule.waitForIdle()
        assertTrue("reset" !in b.calls)
        rule.onNodeWithText("Reset everything").performScrollTo().performClick()
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.waitForIdle()
        assertTrue("reset" in b.calls)
    }
}

/** WP-8.14 (PX-111): the first-connect dialog strings and the empty-local guard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstConnectDialogTest {

    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private val p = FirstConnectPreview(
        localTasks = 3, localRewards = 1, localLevel = 2, localEmpty = false,
        remoteTasks = 10, remoteRewards = 2, remoteLevel = 5, remoteEmpty = false, remoteSavedAt = 1_700_000_000_000L
    )

    @Test
    fun `strings are the card's, with the preview numbers`() {
        val picked = mutableListOf<FirstConnectChoice>()
        rule.setContent { FirstConnectDialog(p, onChoice = { picked += it }) }
        rule.onNodeWithText("First sync with this Dropbox").assertIsDisplayed()
        rule.onNodeWithText("Pull from Dropbox (recommended) — replaces this device's data with the Dropbox copy (10 tasks, level 5).").assertIsDisplayed()
        val saved = localDateTime(p.remoteSavedAt)
        rule.onNodeWithText(
            "Push this device — replaces the Dropbox copy (10 tasks, level 5, saved $saved) with this device's data (3 tasks, level 2). Other devices will receive this device's data."
        ).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("fc_disabled_note").assertDoesNotExist()
        rule.onNodeWithTag("fc_pull").performClick()
        rule.onNodeWithTag("fc_push").performClick()
        assertEquals(listOf(FirstConnectChoice.PullFromDropbox, FirstConnectChoice.PushThisDevice), picked)
    }

    @Test
    fun `an effectively empty device cannot push`() {
        val empty = p.copy(localEmpty = true)
        val picked = mutableListOf<FirstConnectChoice>()
        rule.setContent { FirstConnectDialog(empty, onChoice = { picked += it }) }
        rule.onNodeWithTag("fc_push").assertIsNotEnabled()
        rule.onNodeWithText("This device has no data. Pushing would erase the Dropbox copy. Choose Pull.").assertIsDisplayed()
        rule.onNodeWithTag("fc_push").performClick()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `WP-8_14-R1 Diagnostics screen shows the rows and the ring, Export and Close`() {
        val calls = mutableListOf<String>()
        val a = object : SettingsActions {
            override fun exportDiagnostics() { calls += "export" }
            override fun closeDiagnostics() { calls += "close" }
        }
        val v = DiagnosticsView(listOf("sync.authState" to "LINKED", "room.version" to "7"), listOf("{\"t\":2}", "{\"t\":1}"), "questa-fulldiag-x.json")
        rule.setContent { SettingsScreen(SettingsUiState(appVersion = "v1.0", diagnostics = v), a) }
        rule.onNodeWithTag("diagnostics_screen").assertIsDisplayed()
        rule.onNodeWithText("sync.authState: LINKED").assertExists()
        rule.onNodeWithText("Recent errors (newest first): 2").assertExists()
        rule.onNodeWithTag("diag_export").performClick()
        rule.onNodeWithTag("diag_close").performClick()
        assertEquals(listOf("export", "close"), calls)
    }

    @Test
    fun `Diagnostics Copy all puts the scrubbed JSON on the clipboard`() {
        val v = DiagnosticsView(listOf("room.version" to "7"), emptyList(), "questa-fulldiag-x.json", exportText = "{\"manifest\":1}")
        rule.setContent { SettingsScreen(SettingsUiState(appVersion = "v1.0", diagnostics = v), object : SettingsActions {}) }
        rule.onNodeWithTag("diag_copy").assertIsDisplayed().performClick()
        rule.onNodeWithTag("diag_share").assertIsDisplayed()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("{\"manifest\":1}", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
