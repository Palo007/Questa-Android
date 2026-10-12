package com.example.ui.visuals

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens

/** An optional action on a toast (`.toastMsg button`, index.html:495-496), e.g. "Undo". */
data class ToastAction(val label: String, val onClick: () -> Unit)

data class ToastItem(val id: Long, val message: String, val action: ToastAction? = null)

/**
 * WP-8.18 (PX-104): the toast queue. Opti `toast(msg)` (app.js:3015-3019) appends a
 * `.toastMsg` to `#toast` and removes it after 2.4 s; several stack in a column.
 */
@Stable
class ToastState {
    val items = mutableStateListOf<ToastItem>()
    private var next = 0L

    fun show(message: String, action: ToastAction? = null): Long {
        val id = next++
        items.add(ToastItem(id, message, action))
        return id
    }

    fun dismiss(id: Long) { items.removeAll { it.id == id } }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * `#toast` (index.html:491-492): fixed, centered, 84 dp above the bottom, a column with a 6 dp
 * gap; the host itself takes no touches (`pointer-events:none`), only an action button does.
 */
@Composable
fun QuestaToastHost(state: ToastState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(bottom = OptiDims.TOAST_BOTTOM.dp).testTag("toast_host"),
        verticalArrangement = Arrangement.spacedBy(OptiDims.TOAST_GAP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.items.forEach { item ->
            key(item.id) { ToastMsg(item) { state.dismiss(item.id) } }
        }
    }
}

/**
 * `.toastMsg` (index.html:493-494): `#1d0f38`, a 1 dp accent border, radius 20, `padding:7px 14px`,
 * 13 sp bold ink, and the 2.4 s `pop` (fade in from +8, hold, fade out to -6), then removed.
 */
@Composable
private fun ToastMsg(item: ToastItem, onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(item.id) {
        t.animateTo(1f, tween(OptiDims.TOAST_MS.toInt(), easing = LinearEasing))
        onDone()
    }
    val (a, dy) = toastPop(t.value)
    val px = with(LocalDensity.current) { 1.dp.toPx() }
    val shape = RoundedCornerShape(OptiDims.TOAST_RADIUS.dp)
    Row(
        modifier = Modifier
            .graphicsLayer { alpha = a; translationY = dy * px }
            .background(OptiSelectorTokens.ToastBg, shape)
            .border(1.dp, OptiRootTokens.Accent, shape)
            .padding(horizontal = 15.dp, vertical = 8.dp) // 7x14 + the 1 dp border
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("toast_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.message, color = OptiRootTokens.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        item.action?.let { act ->
            // .toastMsg button: margin-left 10, accent, white 800 12 sp, padding 4x12, radius 12.
            val bs = RoundedCornerShape(12.dp)
            Text(
                act.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .background(OptiRootTokens.Accent, bs)
                    .clickable { act.onClick(); onDone() }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("toast_action_${item.id}"),
            )
        }
    }
}
