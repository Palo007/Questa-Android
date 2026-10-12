package com.example.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.QuickLogMode
import com.example.ui.theme.BorderLavender
import com.example.ui.theme.PrimaryNeon
import com.example.ui.theme.TextSecondary

/**
 * WP-8.13: the per-habit "Android shortcut" control of the Opti habit sheet (app.js:6006-6011):
 * a 4-way segment Off / +1 / −1 / +1 and −1, and the hint below it. Only for habits.
 * Wiring into `TaskEditSheet` is a patch for the DashboardScreen owner (WP-8.13_NOTES.md patch D-1).
 */
object QuickLogEditorText {
    const val LABEL = "Android shortcut"
    const val HINT = "Long-press items in the Android app. Only the buttons this habit has are used. The menu shows about 3 items."
    /** Opti's option order and labels. */
    val OPTIONS: List<Pair<QuickLogMode, String>> = listOf(
        QuickLogMode.OFF to "Off",
        QuickLogMode.UP to "+1",
        QuickLogMode.DOWN to "−1",
        QuickLogMode.BOTH to "+1 and −1",
    )
}

@Composable
fun QuickLogShortcutEditor(value: QuickLogMode?, onChange: (QuickLogMode) -> Unit, modifier: Modifier = Modifier) {
    val current = value ?: QuickLogMode.OFF
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(QuickLogEditorText.LABEL, fontSize = 13.sp, color = TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickLogEditorText.OPTIONS.forEach { (mode, label) ->
                val on = mode == current
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) PrimaryNeon else BorderLavender.copy(alpha = 0.2f))
                        .border(1.dp, if (on) PrimaryNeon else BorderLavender, RoundedCornerShape(8.dp))
                        .clickable { onChange(mode) }
                        .padding(vertical = 8.dp)
                        .testTag("ql_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(QuickLogEditorText.HINT, fontSize = 12.sp, color = TextSecondary)
    }
}
