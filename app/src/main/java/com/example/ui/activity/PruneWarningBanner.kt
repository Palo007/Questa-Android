package com.example.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.data.local.DataStorePruneWarnStore
import com.example.data.local.PruneWarnState
import com.example.data.sync.events.EvtUploadPolicy
import com.example.data.local.PruneWarnStore
import com.example.data.local.PruneWarnText
import kotlinx.coroutines.launch

/**
 * CR-KT-014: the in-app prune warning, shown at the top of the dashboard (all tabs) when the
 * notification could not be posted. The first composition stores the warning (the 14-day grace
 * starts when the user can see it: no modal on top, activity RESUMED). After the start date it
 * says the deletion is under way.
 */
@Composable
fun PruneWarningBannerHost(
    onBackup: () -> Unit,
    store: PruneWarnStore = rememberAppPruneWarnStore(),
    now: () -> Long = { com.example.data.clock.PhysicalClock.nowMs() }, // clock-ok:event-prune
    /** True while a modal / sheet covers the dashboard: the banner is not "seen" then. */
    covered: Boolean = false,
) {
    val banner by remember(store) { store.banner() }.collectAsState(initial = null)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scope = rememberCoroutineScope()
    val b = banner ?: return
    val visible = !covered && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(b, visible) {
        if (b.pending != null && visible) try { store.markBannerShown(now()) } catch (e: Exception) {}
    }
    // Queued: show what the warning will say if it is stored now.
    val notice = b.notice ?: b.pending?.let { p ->
        PruneWarnState(pending = p).bannerShown(now(), EvtUploadPolicy.EVENT_AGE_LIMIT_MS).notice()
    } ?: return
    PruneWarningBanner(
        title = PruneWarnText.bannerTitle(notice, now()),
        onBackup = onBackup,
        onDismiss = { scope.launch { try { store.dismissBanner() } catch (e: Exception) {} } },
    )
}

@Composable
private fun rememberAppPruneWarnStore(): PruneWarnStore {
    val ctx = LocalContext.current
    return remember { DataStorePruneWarnStore.forApp(ctx) }
}

@Composable
fun PruneWarningBanner(title: String, onBackup: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("prune_warning_banner"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(PruneWarnText.BODY, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("prune_warning_dismiss")) { Text("Dismiss") }
            TextButton(onClick = onBackup, modifier = Modifier.testTag("prune_warning_backup")) { Text(PruneWarnText.ACTION) }
        }
    }
}
