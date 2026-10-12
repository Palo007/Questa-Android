package com.example.ui.settings

import com.example.data.local.entities.DeviceEntity
import com.example.data.sync.ErrorKind
import com.example.data.sync.FirstConnect
import com.example.data.sync.FirstConnectChoice
import com.example.data.sync.ImportMode
import com.example.data.sync.Section
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncResult
import com.example.data.sync.UnknownFieldCodeException
import com.example.data.sync.auth.AuthState
import com.example.data.sync.backup.AutoBackupEnabled
import com.example.data.sync.backup.BkTier
import com.example.startup.StartupMigrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP-8.14: the Settings rows, confirm texts and flows against Opti 7d5d4f3 (app.js:6487-7004,
 * sync.js:3656-3808) and the card's first-connect strings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var backend: FakeSettingsBackend
    private lateinit var vm: SettingsViewModel
    private val toasts = mutableListOf<String>()
    private val urls = mutableListOf<String>()
    private val closes = mutableListOf<Unit>()
    private lateinit var collectors: Job

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeSettingsBackend()
        vm = SettingsViewModel(backend)
        val scope = CoroutineScope(dispatcher)
        collectors = scope.launch {
            launch { vm.toasts.toList(toasts) }
            launch { vm.openUrl.toList(urls) }
            launch { vm.closeRequests.toList(closes) }
        }
    }

    @After
    fun tearDown() {
        collectors.cancel()
        Dispatchers.resetMain()
    }

    private val s get() = vm.state.value

    // ---- rows ----

    @Test
    fun `WP-8_22-B quick-log rows - auth state, waiting to upload and reminders from the backend`() {
        assertEquals(AuthState.NOT_LINKED, s.authState)
        assertEquals(0, s.waitingToUpload)
        backend.waiting = 5
        backend.armed = 2
        backend.remLastChecked = 777L
        backend.authState.value = AuthState.RECONNECT_REQUIRED
        assertEquals(AuthState.RECONNECT_REQUIRED, s.authState)
        assertEquals("a sync-state change re-reads the upload backlog", 5, s.waitingToUpload)
        vm.refresh()
        assertEquals(2, s.remindersScheduled)
        assertEquals(777L, s.remindersLastChecked)
        backend.waiting = 0
        backend.lastSyncAt.value = 42L
        assertEquals("a finished round re-reads the backlog", 0, s.waitingToUpload)
    }

    @Test
    fun `rows map the backend - not connected shows the two connect buttons state`() {
        assertFalse(s.connected)
        assertEquals("Hero", s.charName)
        assertEquals("🐉", s.charFace)
        assertEquals("v1.0", s.appVersion)
        assertEquals("abcdef", s.deviceShort) // Opti devId.slice(0,6)
        assertEquals("Off", s.autoBackupSummary)
        assertFalse(s.clockAhead)
        assertFalse(s.startupFailed)
    }

    @Test
    fun `connected rows - last sync relative time, errors, device name of THIS device`() {
        backend.devices.value = listOf(DeviceEntity("other", "Tablet", 1L), DeviceEntity("abcdef123456", "Phone", 2L))
        backend.lastSyncAt.value = backend.now - 5 * 60_000L
        backend.lastSyncError.value = "sync conflict — retry later"
        backend.lastBackupError.value = "daily backup failed: x"
        backend.connected.value = true
        assertTrue(s.connected)
        assertEquals("Phone", s.deviceName)
        assertEquals("5m ago", SettingsTexts.relativeTime(s.lastSyncAt, s.nowMs))
        assertEquals("sync conflict — retry later", s.lastSyncError)
        assertEquals("daily backup failed: x", s.lastBackupError)
    }

    @Test
    fun `relative time is Opti syncRelativeTime`() {
        val n = 10_000_000L
        assertEquals("never", SettingsTexts.relativeTime(0L, n))
        assertEquals("just now", SettingsTexts.relativeTime(n - 9_000, n))
        assertEquals("42s ago", SettingsTexts.relativeTime(n - 42_000, n))
        assertEquals("3h ago", SettingsTexts.relativeTime(n - 3 * 3_600_000L, n))
        assertEquals("2d ago", SettingsTexts.relativeTime(n - 49 * 3_600_000L, n))
        assertEquals("just now", SettingsTexts.relativeTime(n + 60_000, n)) // Math.max(0, ...)
    }

    @Test
    fun `reconnect required, start-up failure and clock ahead rows`() {
        backend.authState.value = AuthState.RECONNECT_REQUIRED
        backend.startupStatus.value = StartupMigrations.STATUS_FAILED
        backend.skew = 120_001L
        vm.refresh()
        assertTrue(s.reconnectRequired)
        assertTrue(s.startupFailed)
        assertTrue(s.clockAhead)
        backend.skew = 120_000L // Opti: > HLC_RATCHET_TOLERANCE_MS, not >=
        vm.refresh()
        assertFalse(s.clockAhead)
    }

    @Test
    fun `auto-backup summary lists the enabled tiers in Opti order`() {
        backend.tiers = AutoBackupEnabled(fourHour = true, weekly = true, monthly = true)
        vm.refresh()
        assertEquals("4-hourly, Weekly, Monthly", s.autoBackupSummary)
        vm.setTier(BkTier.DAILY, true)
        assertEquals(AutoBackupEnabled(true, true, true, true), backend.tiers)
        assertEquals("4-hourly, Daily, Weekly, Monthly", s.autoBackupSummary)
    }

    @Test
    fun `character name trims and falls back to Adventurer, face to the wizard`() {
        vm.setCharName("   ")
        assertEquals("saveCharacter:Adventurer|🐉", backend.calls.last())
        vm.setCharFace("")
        assertEquals("saveCharacter:Adventurer|🧙", backend.calls.last())
    }

    @Test
    fun `pause, haptics, save button and device name rows call through`() {
        vm.setPaused(true)
        assertTrue(s.paused)
        vm.setHaptics(false)
        vm.setSaveBtnTop(true)
        vm.setDeviceName("My phone")
        assertEquals(listOf("pause:true", "haptics:false", "saveBtnTop:true", "deviceName:My phone"), backend.calls)
    }

    @Test
    fun `migrated auto-backup toast is shown once`() {
        backend.migratedToast = true
        vm.refresh()
        vm.refresh()
        assertEquals(1, toasts.count { it == "Auto-backup upgraded to Monthly tier" })
    }

    // ---- connect ----

    @Test
    fun `plain Connect clears the force-push intent and opens the auth URL`() {
        vm.connect()
        assertEquals(listOf("auth:false"), backend.calls)
        assertEquals(listOf("https://dropbox/auth"), urls)
    }

    @Test
    fun `Connect and Force Push asks first, then marks the intent BEFORE the auth URL`() {
        vm.requestConnectForForcePush()
        assertEquals(ConfirmOp.CONNECT_FORCE_PUSH, s.confirm!!.op)
        assertEquals("Connect and overwrite Dropbox?", s.confirm!!.title)
        assertEquals(
            "This connects to Dropbox and IMMEDIATELY overwrites the remote backup with THIS device's data, skipping the normal merge. Anything already saved in Dropbox will be permanently lost. This cannot be undone.",
            s.confirm!!.text
        )
        assertEquals("Yes", s.confirm!!.yes)
        assertEquals("Cancel", s.confirm!!.no)
        assertTrue(backend.calls.isEmpty())
        vm.confirm(ConfirmOp.CONNECT_FORCE_PUSH)
        assertEquals(listOf("auth:true"), backend.calls)
        assertEquals(listOf("https://dropbox/auth"), urls)
        assertTrue("Connecting to Dropbox…" in toasts)
    }

    @Test
    fun `cancel on a destructive confirm runs nothing`() {
        vm.requestForcePull()
        vm.dismissConfirm()
        assertNull(s.confirm)
        vm.confirm(ConfirmOp.FORCE_PULL) // no pending confirm -> ignored
        assertTrue(backend.calls.isEmpty())
    }

    // ---- destructive confirms (exact Opti texts) ----

    // ---- Dropbox app key ----

    @Test
    fun `app key save closes the dialog, shows the key and toasts`() {
        vm.openAppKeyDialog()
        assertTrue(s.appKeyDialog)
        vm.saveAppKey(" k ")
        assertEquals(listOf("saveAppKey: k "), backend.calls)
        assertFalse(s.appKeyDialog)
        assertEquals("k", s.appKey)
        assertTrue("App key saved" in toasts)
    }

    @Test
    fun `app key change that reset sync says so`() {
        backend.saveAppKeyResult = SaveAppKeyResult.SavedAndReset
        vm.openAppKeyDialog()
        vm.saveAppKey("b")
        assertFalse(s.appKeyDialog)
        assertTrue("App key changed. Dropbox was disconnected and sync was reset." in toasts)
    }

    @Test
    fun `clearing the app key while linked keeps the dialog open and asks to disconnect`() {
        backend.saveAppKeyResult = SaveAppKeyResult.DisconnectFirst
        vm.openAppKeyDialog()
        vm.saveAppKey("")
        assertTrue(s.appKeyDialog)
        assertTrue("Disconnect Dropbox first." in toasts)
    }

    @Test
    fun `app key save before startup finished says to restart`() {
        backend.saveAppKeyResult = SaveAppKeyResult.NotReady
        vm.saveAppKey("k")
        assertTrue("Sync is not ready, restart the app." in toasts)
    }

    @Test
    fun `disconnect confirm text and toast`() {
        vm.requestDisconnect()
        assertEquals("Disconnect Dropbox", s.confirm!!.title)
        assertEquals("Stop syncing this device? Local data is kept; other devices keep their own copy.", s.confirm!!.text)
        vm.confirm(ConfirmOp.DISCONNECT)
        assertEquals(listOf("disconnect"), backend.calls)
        assertTrue("Dropbox disconnected" in toasts)
    }

    @Test
    fun `force push confirm carries the event count and the low-count hint`() {
        backend.events = 1
        vm.requestForcePush()
        assertEquals("Force push — overwrite Dropbox?", s.confirm!!.title)
        assertEquals(
            "This replaces the data in Dropbox with what's on THIS device. Anything saved on other devices that hasn't synced yet will be permanently lost. This cannot be undone. This device has 1 local event. If that looks low, it may not be the most up-to-date device.",
            s.confirm!!.text
        )
        vm.confirm(ConfirmOp.FORCE_PUSH)
        assertEquals(listOf("forcePush"), backend.calls)
        assertTrue("Pushing this device's data to Dropbox…" in toasts)
    }

    @Test
    fun `force push text without a count, and with 50+ events`() {
        assertEquals(SettingsTexts.FORCE_PUSH_TEXT, SettingsTexts.forcePushText(null))
        assertTrue(SettingsTexts.forcePushText(50).endsWith(" This device has 50 local events."))
    }

    @Test
    fun `force pull confirm text`() {
        vm.requestForcePull()
        assertEquals("Force pull — overwrite this device?", s.confirm!!.title)
        assertTrue(s.confirm!!.text.startsWith("This replaces what's on THIS device with whatever is in Dropbox."))
        assertTrue(s.confirm!!.text.contains("\n\nNote: normal sync now protects newer edits on each device"))
        vm.confirm(ConfirmOp.FORCE_PULL)
        assertEquals(listOf("forcePull"), backend.calls)
        assertTrue("Pulling Dropbox's data onto this device…" in toasts)
    }

    @Test
    fun `force push events confirm, blocked count and both outcomes`() {
        backend.blocked = 2
        vm.requestForcePushEvents()
        assertEquals("Force Push Events?", s.confirm!!.title)
        assertEquals(SettingsTexts.EVENTS_PUSH_TEXT + " 2 month files are blocked right now.", s.confirm!!.text)
        vm.confirm(ConfirmOp.FORCE_PUSH_EVENTS)
        assertTrue("Event history re-uploaded to Dropbox." in toasts)

        backend.blocked = 1
        backend.eventsResult = SyncResult.Error(ErrorKind.HTTP, "event force push failed: HTTP 500")
        vm.requestForcePushEvents()
        assertTrue(s.confirm!!.text.endsWith(" 1 month file is blocked right now."))
        vm.confirm(ConfirmOp.FORCE_PUSH_EVENTS)
        assertTrue("Force push failed: HTTP 500" in toasts)
    }

    @Test
    fun `reset confirm, close on success, Opti error alert on failure`() {
        vm.requestReset()
        assertEquals("Reset Everything", s.confirm!!.title)
        assertEquals("Erase ALL progress on this device? This cannot be undone.", s.confirm!!.text)
        vm.confirm(ConfirmOp.RESET)
        assertEquals(listOf("reset"), backend.calls)
        assertEquals(1, closes.size)

        backend.resetError = IllegalStateException("disk full")
        vm.requestReset()
        vm.confirm(ConfirmOp.RESET)
        assertEquals("Reset Error", s.alert!!.title)
        assertEquals("The reset did not finish: disk full\n\nSome data may already have been erased. Check Settings before continuing.", s.alert!!.text)
        assertEquals(1, closes.size)
    }

    // ---- first connect (PX-111) ----

    @Test
    fun `Sync now on NeedsFirstConnectChoice opens the first-connect dialog with the preview`() {
        backend.syncResult = SyncResult.NeedsFirstConnectChoice
        vm.syncNow()
        assertEquals(listOf("sync", "preview"), backend.calls)
        assertEquals(backend.preview, s.firstConnect)
    }

    @Test
    fun `other round results open no dialog, interlock refusal toasts`() {
        vm.handleRound(SyncResult.Ok)
        vm.handleRound(null)
        assertNull(s.firstConnect)
        vm.handleRound(SyncResult.InterlockRefused)
        assertTrue(SyncManager.INTERLOCK_REFUSED_MESSAGE in toasts)
        assertFalse("preview" in backend.calls)
    }

    @Test
    fun `Pull resolves at once`() {
        vm.handleRound(SyncResult.NeedsFirstConnectChoice)
        vm.chooseFirstConnect(FirstConnectChoice.PullFromDropbox)
        assertNull(s.firstConnect)
        assertEquals("resolve:PullFromDropbox", backend.calls.last())
    }

    @Test
    fun `Push asks Replace or Cancel, then resolves`() {
        vm.handleRound(SyncResult.NeedsFirstConnectChoice)
        vm.chooseFirstConnect(FirstConnectChoice.PushThisDevice)
        val c = s.confirm!!
        assertEquals(ConfirmOp.FIRST_CONNECT_PUSH, c.op)
        assertEquals("Replace the Dropbox copy? This cannot be undone from this device.", c.text)
        assertEquals("Replace", c.yes)
        assertEquals("Cancel", c.no)
        assertFalse(backend.calls.any { it.startsWith("resolve") })
        vm.confirm(ConfirmOp.FIRST_CONNECT_PUSH)
        assertEquals("resolve:PushThisDevice", backend.calls.last())
        assertNull(s.firstConnect)
    }

    @Test
    fun `Push is refused in the UI when the device is effectively empty`() {
        backend.preview = backend.preview.copy(localEmpty = true, remoteEmpty = false)
        vm.handleRound(SyncResult.NeedsFirstConnectChoice)
        assertFalse(s.firstConnect!!.pushAllowed)
        vm.chooseFirstConnect(FirstConnectChoice.PushThisDevice)
        assertNull(s.confirm)
        assertTrue(SettingsTexts.FC_DISABLED in toasts)
        assertFalse(backend.calls.any { it.startsWith("resolve") })
    }

    @Test
    fun `engine EMPTY_LOCAL refusal shows the disabled note`() {
        backend.resolveResult = SyncResult.Error(ErrorKind.INTERNAL, FirstConnect.EMPTY_LOCAL)
        vm.handleRound(SyncResult.NeedsFirstConnectChoice)
        vm.chooseFirstConnect(FirstConnectChoice.PushThisDevice)
        vm.confirm(ConfirmOp.FIRST_CONNECT_PUSH)
        assertTrue(SettingsTexts.FC_DISABLED in toasts)
    }

    // ---- first connect pending (review fix 1: background rounds, killed app, debug connect-only) ----

    @Test
    fun `linked with no base shows the persistent choose row, checked on refresh (Settings open)`() {
        backend.connected.value = true
        backend.pending = true
        vm.refresh()
        assertTrue(s.firstConnectPending)
        assertNull(s.firstConnect) // the row only; the dialog opens on the button
        vm.openFirstConnect()
        assertEquals(backend.preview, s.firstConnect)
    }

    @Test
    fun `a resolved first connect clears the row`() {
        backend.connected.value = true
        backend.pending = true
        vm.refresh()
        vm.openFirstConnect()
        backend.pending = false
        vm.chooseFirstConnect(FirstConnectChoice.PullFromDropbox)
        assertFalse(s.firstConnectPending)
    }

    @Test
    fun `not linked never shows the row`() {
        backend.pending = true
        backend.connected.value = false
        vm.refresh()
        assertFalse(s.firstConnectPending)
    }

    @Test
    fun `connecting re-checks the base without a refresh call`() {
        backend.pending = true
        assertFalse(s.firstConnectPending)
        backend.connected.value = true
        assertTrue(s.firstConnectPending)
    }

    // ---- notifications ----

    @Test
    fun `notifications pref defaults off and follows the permission answer`() {
        assertFalse(s.notificationsEnabled)
        vm.onNotificationPermissionResult(true)
        assertTrue(s.notificationsEnabled)
        assertTrue("Permission granted!" in toasts)
        backend.permission = "denied"
        vm.onNotificationPermissionResult(false)
        assertFalse(s.notificationsEnabled)
        assertTrue("Permission: denied" in toasts)
    }

    @Test
    fun `test notification needs the permission`() {
        vm.testNotification()
        assertTrue("Notification permission not granted" in toasts)
        assertFalse("testNotification" in backend.calls)
        backend.permission = "granted"
        vm.testNotification()
        assertTrue("testNotification" in backend.calls)
        assertTrue("Test notification sent" in toasts)
    }

    // ---- import / export pickers ----

    @Test
    fun `import picker lists the file's sections, all ticked, with Opti's note and confirm`() {
        vm.beginImport("{\"char\":{}}")
        val p = s.picker!!
        assertTrue(p.import)
        assertEquals(listOf(Section.CHAR, Section.HABITS, Section.EVENTS), p.rows.map { it.section })
        assertEquals(listOf("Character", "Habits", "Event log"), p.rows.map { it.label })
        assertEquals(4, p.rows[1].count)
        assertEquals(p.rows.map { it.section }.toSet(), p.selected)
        assertTrue(Section.TODOS in p.missing)
        assertEquals("3 parts · Replace. Your event log is only added to, never cleared.", p.note)

        vm.togglePickerSection(Section.EVENTS, false)
        vm.setImportMode(ImportMode.MERGE)
        assertEquals("2 parts · Merge.", s.picker!!.note)
        vm.pickerGo()
        assertEquals("Import", s.confirm!!.title)
        assertEquals("Merge 2 parts from this file into your current data? Nothing will be removed.", s.confirm!!.text)
        vm.confirm(ConfirmOp.IMPORT)
        assertEquals(setOf(Section.CHAR, Section.HABITS), backend.lastImport!!.second)
        assertEquals(ImportMode.MERGE, backend.lastImport!!.third)
        assertEquals("startDay", backend.calls.last()) // Opti runs startDay() after an import
        assertTrue("Imported" in toasts)
        assertEquals("Import complete", s.alert!!.title)
        assertEquals("Restored: Character, Habits.\n\nEverything else on this device was left as it was.", s.alert!!.text)
        assertNull(s.picker)
    }

    @Test
    fun `event import shows Opti's toast, alert and the summary table`() {
        val sum = com.example.data.sync.ImportSummary(5, 2, 3, mapOf("habit" to 1, "daily" to 1, "todo" to 1, "system" to 0))
        backend.importResult = com.example.data.sync.ImportResult(
            listOf(Section.EVENTS), false, ImportMode.MERGE, true, eventsAdded = 4, eventsSkipped = 1, summary = sum,
        )
        vm.beginImport("{\"char\":{}}")
        vm.pickerGo()
        vm.confirm(ConfirmOp.IMPORT)
        assertTrue("Imported · 4 events restored" in toasts)
        assertEquals("Import complete", s.alert!!.title)
        assertEquals("4 events restored to this device.", s.alert!!.text)
        assertEquals(sum, s.alert!!.summary)
    }

    @Test
    fun `a partly failed event import shows Opti's Import incomplete alert`() {
        val sum = com.example.data.sync.ImportSummary(5, 0, 5, mapOf("habit" to 5, "daily" to 0, "todo" to 0, "system" to 0))
        backend.importResult = com.example.data.sync.ImportResult(
            listOf(Section.EVENTS), false, ImportMode.MERGE, true, eventsAdded = 3, eventsSkipped = 0, summary = sum, eventsFailed = 2,
        )
        vm.beginImport("{\"char\":{}}")
        vm.pickerGo()
        vm.confirm(ConfirmOp.IMPORT)
        assertTrue("Imported · 3 events restored" in toasts)
        assertEquals("Import incomplete", s.alert!!.title)
        assertEquals("The sections you picked were restored, but only 3 of 5 events could be written to this device. " +
            "Keep your backup file and free up storage, then import again.", s.alert!!.text)
        assertEquals(sum, s.alert!!.summary)
    }

    @Test
    fun `replace confirm text and nothing picked`() {
        vm.beginImport("{}")
        vm.setPickerAll(false)
        assertEquals("Tick at least one part.", s.picker!!.note)
        vm.pickerGo()
        assertNull(s.confirm)
        vm.togglePickerSection(Section.CHAR, true)
        vm.pickerGo()
        assertEquals("Replace 1 part with the copy in this file? Anything you did not tick stays as it is.", s.confirm!!.text)
    }

    @Test
    fun `import error alerts are Opti's`() {
        backend.previewError = UnknownFieldCodeException("zz")
        vm.beginImport("{}")
        assertEquals("Import Error", s.alert!!.title)
        assertEquals("This backup was made by a newer version of Questa. It uses a field this build does not understand (\"zz\"). Update Questa, then import it again.", s.alert!!.text)

        backend.previewError = IllegalArgumentException("backup integrity verification failed: hash mismatch")
        vm.beginImport("{}")
        assertEquals("This file appears to be corrupted or tampered with (hash mismatch). Import cancelled.", s.alert!!.text)

        backend.previewError = IllegalArgumentException("x")
        vm.beginImport("a,b,c")
        assertEquals("Error", s.alert!!.title)
        assertEquals("That looks like a CSV or Markdown export. Those are for other apps and cannot be imported. Only a backup .json file can be imported.", s.alert!!.text)
        vm.beginImport("{ broken")
        assertEquals("That file does not look like a valid Questa backup.", s.alert!!.text)
        assertNull(s.picker)
    }

    @Test
    fun `import failure after confirm shows Opti's Import Error`() {
        backend.importError = IllegalStateException("boom")
        vm.beginImport("{}")
        vm.pickerGo()
        vm.confirm(ConfirmOp.IMPORT)
        assertEquals("Import Error", s.alert!!.title)
        assertEquals("The import did not finish: boom\n\nSome of your data may already have been replaced. Keep your backup file and try importing it again.", s.alert!!.text)
    }

    @Test
    fun `export picker - everything ticked, notes, Continue emits the sections`() {
        val got = mutableListOf<Set<Section>>()
        val j = CoroutineScope(dispatcher).launch { vm.exportRequests.toList(got) }
        vm.openExportPicker()
        val p = s.picker!!
        assertFalse(p.import)
        assertEquals(Section.entries, p.rows.map { it.section })
        assertNull(p.rows.last().count) // the event count may be pending ("…")
        assertEquals("Full backup · questa-backup-….json", p.note)
        vm.togglePickerSection(Section.EVENTS, false)
        assertEquals("Partial export · questa-partial-….json · restores only the ticked boxes.", s.picker!!.note)
        vm.pickerGo()
        assertEquals(listOf(Section.entries.toSet() - Section.EVENTS), got)
        assertNull(s.picker)
        j.cancel()
    }

    // ---- version tap ----

    @Test
    fun `five version taps within 3 s open Diagnostics, a pause resets the count`() {
        repeat(4) { vm.tapVersion() }
        backend.now += 3_001
        vm.tapVersion()
        assertNull(vm.state.value.diagnostics)
        repeat(4) { vm.tapVersion() }
        assertNotNull(vm.state.value.diagnostics)
    }

    @Test
    fun `Diagnostics export asks for Opti's file name and writes a scrubbed bundle`() {
        backend.diag = DiagnosticsCapture(
            DiagnosticsSnapshot(generatedAt = 1_758_800_000_123L, lastSyncError = "Bearer sl.leak123456", deviceName = "SECRETrefresh99"),
            listOf("SECRETrefresh99"),
        )
        val names = mutableListOf<String>()
        val j = kotlinx.coroutines.CoroutineScope(dispatcher).launch { vm.diagExportRequests.toList(names) }
        repeat(5) { vm.tapVersion() }
        val v = vm.state.value.diagnostics!!
        assertEquals("questa-fulldiag-2025-09-25T11-33-20-123Z.json", v.fileName)
        vm.exportDiagnostics()
        assertEquals(listOf(v.fileName), names)
        val text = vm.diagnosticsExportText()!!
        assertFalse(text.contains("sl.") || text.contains("Bearer") || text.contains("SECRETrefresh99"))
        vm.closeDiagnostics()
        assertNull(vm.state.value.diagnostics)
        assertNull(vm.diagnosticsExportText())
        j.cancel()
    }

    @Test
    fun `Settings close request`() {
        vm.openCategory("interaction")
        vm.close()
        assertNull(s.category)
        assertEquals(1, closes.size)
        assertNotNull(vm)
    }
}
