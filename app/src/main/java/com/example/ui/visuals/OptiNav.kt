package com.example.ui.visuals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens

/**
 * Opti's nav buttons (index.html:1067-1073): the emoji icon and the label per Kotlin tab name.
 * The Activity tab (CR-KT-012, was Logs) is Kotlin-only (no Opti button); it takes a clipboard emoji.
 */
fun navItem(tab: String): Pair<String, String> = when (tab) {
    "Habits" -> "🔁" to "Habits"
    "Dailies" -> "📅" to "Dailies"
    "Todos" -> "✅" to "To-Dos"
    "Analytics" -> "📊" to "Analytics"
    "Rewards" -> "🏪" to "Rewards"
    "Activity" -> "📋" to "Activity"
    else -> "•" to tab
}

/**
 * `header,main{margin-left:auto;margin-right:auto;max-width:var(--appw)}` (index.html:26): a
 * column capped at [maxWidth] and centred. `widthIn` must come before `fillMaxWidth`, or the fill
 * pins the width to the parent first.
 */
@Composable
fun AppWidthColumn(maxWidth: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

/** `nav button.on` background: `rgba(189,168,255,0.15)` (index.html:490). */
val NavOnBg = Color(0x26BDA8FF)

/**
 * Opti `nav` (index.html:483-490): `#2a1947`, a line on top, max `--appw` wide and centered;
 * each button shares the width, `padding:9px 0 calc(12px + safe-area)`, a 20 sp icon over an
 * 11 sp label, muted; the active one accent on the soft accent tint.
 */
@Composable
fun OptiNavBar(tabs: List<String>, selected: Int, maxWidth: Dp, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(OptiSelectorTokens.Nav)
            .testTag("bottom_nav_bar"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .drawBehind { drawLine(OptiRootTokens.Line, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
                // The system-bar inset sits under the whole row, so the active tint stays above it.
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val (icon, label) = navItem(tab)
                val on = index == selected
                // The active tab is a rounded pill, not a full-height box.
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) NavOnBg else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(vertical = 5.dp)
                        .testTag("nav_$tab"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(icon, fontSize = 20.sp)
                    Text(label, fontSize = 11.sp, color = if (on) OptiRootTokens.Accent else OptiRootTokens.Muted, maxLines = 1)
                }
            }
        }
    }
}
