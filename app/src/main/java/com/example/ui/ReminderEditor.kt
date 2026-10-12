package com.example.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reminders.ReminderEditorRules
import com.example.ui.theme.BorderLavender
import com.example.ui.theme.PrimaryNeon
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")

/**
 * WP-8.11: the task-sheet reminder block, Opti `drawReminderEditor` (app.js:5888-5930):
 * "Enable notification reminder" checkbox; when on, a Time picker, plus a Date picker for a
 * to-do, a "Repeat reminder on" day row for a habit, or the daily's repeat days as text.
 * State is the sheet's [ReminderEditorRules.Draft]; [ReminderEditorRules.save] runs on Save.
 */
@Composable
fun ReminderEditor(
    type: String,
    repeat: List<Boolean>?,
    value: ReminderEditorRules.Draft,
    onChange: (ReminderEditorRules.Draft) -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reminder", fontSize = 13.sp, color = TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = value.enabled,
                onCheckedChange = { onChange(value.copy(enabled = it)) },
                colors = CheckboxDefaults.colors(checkedColor = PrimaryNeon),
                modifier = Modifier.testTag("reminderEnabled")
            )
            Text("Enable notification reminder", color = TextPrimary, fontSize = 14.sp)
        }
        if (!value.enabled) return@Column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderLavender, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(60.dp))
                OutlinedButton(
                    modifier = Modifier.testTag("reminderTime"),
                    onClick = {
                        val (h, m) = parseHm(value.time)
                        TimePickerDialog(context, { _, hh, mm ->
                            onChange(value.copy(time = "${pad2(hh)}:${pad2(mm)}"))
                        }, h, m, true).show()
                    }
                ) { Text(value.time.ifEmpty { "09:00" }, color = TextPrimary) }
            }
            when (type) {
                "todo" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Date", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(60.dp))
                    OutlinedButton(
                        modifier = Modifier.testTag("reminderDate"),
                        onClick = {
                            val p = value.date.split("-").mapNotNull { it.toIntOrNull() }
                            if (p.size != 3) return@OutlinedButton
                            DatePickerDialog(context, { _, y, mo, d ->
                                onChange(value.copy(date = "$y-${pad2(mo + 1)}-${pad2(d)}"))
                            }, p[0], p[1] - 1, p[2]).show()
                        }
                    ) { Text(value.date, color = TextPrimary) }
                }
                "habit" -> {
                    Text("Repeat reminder on", fontSize = 12.sp, color = TextSecondary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            val on = value.days.getOrElse(i) { false }
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (on) PrimaryNeon else BorderLavender.copy(alpha = 0.2f))
                                    .border(1.dp, if (on) PrimaryNeon else BorderLavender, CircleShape)
                                    .clickable {
                                        val d = value.days.toMutableList()
                                        while (d.size < 7) d.add(false)
                                        d[i] = !on
                                        onChange(value.copy(days = d))
                                    }
                                    .testTag("reminderDay$i"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (on) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                "daily" -> {
                    // app.js:5922-5925: `(t.repeat||[])`, "Never" when no day is on.
                    val active = (repeat ?: emptyList()).mapIndexedNotNull { i, on -> if (on) DAY_LABELS.getOrNull(i) else null }.joinToString(", ")
                    Text(
                        "Reminder repeats on daily's repeat schedule: ${active.ifEmpty { "Never" }}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

private fun parseHm(s: String): Pair<Int, Int> {
    val p = s.split(":")
    val h = p.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 9
    val m = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    return h to m
}
