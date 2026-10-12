package com.example.ui.gestures

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TextSecondary

/** §5.0 B13 layer 2: true while `BootGate.pending`; every task card reads it (`gatedInput`). */
val LocalInputGated = compositionLocalOf { false }

/**
 * §5.0 B13: Opti `_bootGateBanner()` (app.js:2789): `<div class="syncGate">syncing…</div>` at the
 * top of the view while the gate is pending (`.syncGate{padding:6px 11px;font-size:11px;
 * color:var(--muted);text-align:center;letter-spacing:.04em}`, index.html:142).
 */
@Composable
fun SyncGateBanner(pending: Boolean) {
    if (!pending) return
    Text(
        SYNC_GATE_BANNER,
        fontSize = 11.sp,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        letterSpacing = 0.04.em,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp).testTag("sync_gate_banner"),
    )
}

/**
 * WP-8.22-D item 1: when the startup migrations failed (`startup_migration_failed`) the barrier
 * stays closed, so the dashboard would stay empty with no word why. Shows the Settings Sync-block
 * error text (WP-8.14 `startup_failed_row`) at the top of the view too. Hidden otherwise, so the
 * Opti-visible dashboard is unchanged.
 */
@Composable
fun StartupFailedBanner(failed: Boolean) {
    if (!failed) return
    Text(
        com.example.ui.settings.SettingsTexts.STARTUP_FAILED,
        fontSize = 12.sp,
        color = com.example.ui.theme.OptiRootTokens.Red,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp).testTag("startup_failed_banner"),
    )
}

/**
 * WP-8.22-D review 46 F1: shown instead of the loading spinner when the start-up failed before the
 * character loaded (the header and its gear are not drawn then): the error line and a Settings button.
 */
@Composable
fun StartupFailedPlaceholder(onOpenSettings: () -> Unit) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        StartupFailedBanner(true)
        androidx.compose.material3.TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("startup_failed_settings")) {
            Text("Settings")
        }
    }
}

const val SYNC_GATE_BANNER = "syncing…"
