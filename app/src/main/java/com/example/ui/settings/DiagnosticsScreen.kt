package com.example.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File

private val DiagBg = Color(0xFF000000)
private val DiagPanel = Color(0xFF111111)
private val DiagInk = Color(0xFF00FF00)
private val DiagLine = Color(0xFF444444)

/**
 * WP-8.14-R1: the native stand-in for Opti's debug overlay (`showSyncDebugOverlay`, app.js:654-765:
 * black full-screen, green 11px monospace, bordered #111 boxes, the recent-errors block newest
 * first, a button row). Read-only. The recovery controls of that overlay (backfill file input,
 * republish imported history) are not ported.
 *
 * Native additions: the text is selectable (long-press), "Copy all" puts the scrubbed JSON on the
 * clipboard, "Share" sends it as a file. The button row is on top and the dialog does not fit the
 * system windows itself (decorFitsSystemWindows = false) so safeDrawing gets the real bar sizes:
 * with the default, Android 15+ drew the window under the navigation bar and cut off the bottom.
 */
@Composable
internal fun DiagnosticsDialog(v: DiagnosticsView, actions: SettingsActions, onForceCron: () -> Unit = {}, onRepairHistory: () -> Unit = {}) {
    val context = LocalContext.current
    Dialog(onDismissRequest = { actions.closeDiagnostics() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier.fillMaxSize().background(DiagBg).windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp)
                .testTag("diagnostics_screen"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OptiFlexRow(Modifier.fillMaxWidth()) {
                OptiButton("Copy all", Kind.GHOST, Modifier.testTag("diag_copy")) { copyDiagnostics(context, v.exportText) }
                OptiButton("Share", Kind.GHOST, Modifier.testTag("diag_share")) { shareDiagnostics(context, v) }
                OptiButton("Save file", Kind.GHOST, Modifier.testTag("diag_export")) { actions.exportDiagnostics() }
                OptiButton("Close", Kind.GHOST, Modifier.testTag("diag_close")) { actions.closeDiagnostics() }
            }
            // CR-KT-012: moved here from the old Logs tab (debug only: runs the midnight rollover now).
            OptiButton("Simulate midnight rollover", Kind.GHOST, Modifier.testTag("force_cron_button")) { onForceCron() }
            // CR-KT-022: manual re-run of the one-shot completion-history backfill (idempotent;
            // the boot hook normally has it covered -- this is the "after a sync" fallback path).
            OptiButton("Restore completion history", Kind.GHOST, Modifier.testTag("restore_history_button")) { onRepairHistory() }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().background(DiagPanel).border(BorderStroke(1.dp, DiagLine))
                        .verticalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Mono("Diagnostics", bold = true)
                    v.rows.forEach { (k, value) -> Mono("$k: $value", Modifier.testTag("diag_row_$k")) }
                    Mono("")
                    Mono("Recent errors (newest first): " + v.ring.size, bold = true, modifier = Modifier.testTag("diag_ring_title"))
                    if (v.ring.isEmpty()) Mono("none")
                    v.ring.forEachIndexed { i, line -> Mono(line, Modifier.testTag("diag_ring_$i")) }
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text, color = DiagInk, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier,
    )
}


private fun copyDiagnostics(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Questa diagnostics", text))
    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
}

private fun shareDiagnostics(context: Context, v: DiagnosticsView) {
    try {
        val file = File(context.cacheDir, v.fileName)
        file.writeText(v.exportText)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Questa diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share diagnostics"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
