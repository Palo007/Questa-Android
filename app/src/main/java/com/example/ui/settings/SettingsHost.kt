package com.example.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.sync.Section
import com.example.ui.gestures.DeviceBuzz
import com.example.ui.gestures.systemHapticFeedbackEnabled
import com.example.ui.theme.OptiRootTokens
import kotlinx.coroutines.launch
import java.io.File

/**
 * WP-8.14: hosts [SettingsScreen] for the Dashboard: the route from [route] (null = closed), the
 * ViewModel's one-shot requests (toasts, the auth URL, the permission prompt, the document
 * pickers), the export Save / Share step, and the first-connect dialog, which can open while
 * Settings is closed (a Sync now from elsewhere, or the OAuth return).
 */
@Composable
/** [onForceCron] (CR-KT-012): the "Simulate midnight rollover" debug button, now in Diagnostics. */
fun SettingsHost(vm: SettingsViewModel, route: SettingsRoute?, onClose: () -> Unit, onForceCron: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportSections by remember { mutableStateOf<Set<Section>?>(null) }
    var pendingSave by remember { mutableStateOf<Set<Section>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onNotificationPermissionResult(granted)
    }
    // WP-8.15 (PX-90): Opti `faceFile` (PNG, JPEG or GIF).
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.setAvatarImage(it) }
    }
    // WP-8.15 (PX-91): the Test vibration buzz is the Dashboard's. WP-8.I (W7): 8.16's DeviceBuzz,
    // Opti `buzz(50)` (VIBRATE one-shot; view haptic fallback). Returns true like navigator.vibrate.
    val view = LocalView.current
    DisposableEffect(vm, view) {
        vm.bindHaptics(
            { DeviceBuzz(context, view) { true }.buzz(50); true },
            systemHapticsOff = !systemHapticFeedbackEnabled(context),
        )
        onDispose { vm.bindHaptics(null) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return@launch
                vm.beginImport(text)
            } catch (e: Exception) {
                Toast.makeText(context, "Import Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val sections = pendingSave
        pendingSave = null
        if (uri == null || sections == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = vm.exportText(sections)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Toast.makeText(context, "Backup Saved/Downloaded Successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // WP-8.14-R1: "Export diagnostics" goes through the same Save dialog as Export.
    val diagSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = vm.diagnosticsExportText() ?: return@launch
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                Toast.makeText(context, "Downloaded!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(vm) {
        launch { vm.diagExportRequests.collect { diagSaveLauncher.launch(it) } }
        launch { vm.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
        launch { vm.closeRequests.collect { onClose() } }
        launch {
            vm.openUrl.collect { uri ->
                try {
                    // WP-8.12-U1: Custom Tab in the app's task (fallback: plain browser).
                    OAuthBrowser.open(context, uri)
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        launch {
            vm.permissionRequests.collect {
                if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.onNotificationPermissionResult(vm.state.value.notificationPermission == "granted")
            }
        }
        launch {
            vm.exactAlarmRequests.collect {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        launch { vm.importRequests.collect { importLauncher.launch("*/*") } }
        launch { vm.avatarRequests.collect { avatarLauncher.launch(AvatarStore.PICKER_TYPES) } }
        launch { vm.exportRequests.collect { exportSections = it } }
    }

    if (route != null) {
        LaunchedEffect(route) { vm.refresh() }
        // Read in the ACTIVITY composition: inside the Dialog window the insets came back 0 on the
        // phone (Android 16), so safeDrawingPadding alone left the version under the nav bar.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Dialog(onDismissRequest = { vm.close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            // targetSdk 36 is edge-to-edge: keep the last row (Reset everything + version) above the nav bar.
            // decorFitsSystemWindows = false lets safeDrawing see the real bars (same fix as DiagnosticsScreen):
            // with the default, Android 15+ drew the window under the nav bar and hid the version.
            Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(bottom = navBottom).padding(12.dp)) {
                SettingsScreen(state, vm, route, onForceCron)
            }
        }
    } else {
        SettingsModalDialogs(state, vm)
    }

    state.firstConnect?.let { p ->
        FirstConnectDialog(p, onChoice = { vm.chooseFirstConnect(it) }, onDismiss = { vm.dismissFirstConnect() })
    }

    exportSections?.let { sections ->
        Dialog(onDismissRequest = { exportSections = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(OptiRootTokens.Bg, RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, OptiRootTokens.Line), RoundedCornerShape(16.dp))
                    .padding(20.dp)
                    .testTag("export_options"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Export Backup", fontSize = 18.sp, color = OptiRootTokens.Ink)
                OptiButton("Download / Save File", Kind.GHOST, Modifier.fillMaxWidth().testTag("export_save")) {
                    exportSections = null
                    pendingSave = sections
                    val name = if (Section.entries.all { it in sections }) "questa-backup" else "questa-partial"
                    saveLauncher.launch("$name-${state.nowMs}.json")
                }
                OptiButton("Share via App", Kind.GHOST, Modifier.fillMaxWidth().testTag("export_share")) {
                    exportSections = null
                    scope.launch {
                        try {
                            val text = vm.exportText(sections)
                            val cacheFile = File(context.cacheDir, "questa_backup.json")
                            cacheFile.writeText(text)
                            val fileUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", cacheFile)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                putExtra(Intent.EXTRA_SUBJECT, "Questa RPG Backup")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share Backup File"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                OptiButton("Cancel", Kind.GHOST, Modifier.fillMaxWidth()) { exportSections = null }
            }
        }
    }
}
