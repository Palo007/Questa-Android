package com.example.ui.gestures

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * WP-8.16 (§5.0 B8): reports one modal surface to `MainViewModel.modalOpen`. Place it next to the
 * surface's `if (open)`; it reports `open` and reports closed when it leaves the composition.
 *
 * The keys are the Kotlin surfaces of Opti's `#scrim` (the day-change listener reads
 * `scrim.classList.contains('show')`, app.js:8637-8638): every sheet Opti opens on `#scrim`.
 * RYA is NOT one (Opti shows it on `#yScrim`, app.js:2699).
 */
@Composable
fun ReportModal(key: String, open: Boolean, sink: (key: String, open: Boolean) -> Unit) {
    val report by rememberUpdatedState(sink)
    DisposableEffect(key, open) {
        report(key, open)
        onDispose { if (open) report(key, false) }
    }
}

/**
 * PX-96: system Back closes the open sheet instead of leaving the app (Opti's scrim tap,
 * app.js:8522: `closeSheet(); closeRepSheet()`). With nothing open, Back falls through.
 */
@Composable
fun SheetBackHandler(open: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = open, onBack = onClose)
}

object ModalKeys {
    const val TASK_EDITOR = "taskEditor"          // openEdit (app.js:5875)
    const val REP_SHEET = "repSheet"              // openRepSheet (app.js:2534)
    const val REWARD_EDITOR = "rewardEditor"      // openReward (app.js:6428)
    const val SETTINGS = "settings"               // openSettings (app.js:6611), the full-screen editor
    const val FIRST_CONNECT = "firstConnect"      // FirstConnectDialog (§5.0 B8)
    const val VIEW_BUILDER = "viewBuilder"        // drawViewBuilder #scrim (app.js:4406), WP-8.20
    const val BULK_TAGS = "bulkTags"              // bulk tag sheet (bulk-select-v1)
    const val BULK_DIFF = "bulkDifficulty"        // bulk difficulty sheet (bulk-select-v1)
    const val BULK_DELETE = "bulkDelete"          // bulk delete dialog (bulk-select-v1)
}
