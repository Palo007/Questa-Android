package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.OptiRootTokens
import com.example.ui.visuals.PrimaryButton
import com.example.ui.visuals.PrimaryInk
import com.example.ui.visuals.RyaScrim

/** The sheet's copy, from Opti `drawYesterCheck` (app.js:2706-2724). */
object RyaText {
    const val ICON = "🌅"
    const val HEAD = "New day — quick check"
    const val TITLE = "$ICON $HEAD"
    const val BUTTON = "Start my day"
    const val FINE = "Anything left unticked applies its miss damage now."
    fun sub(n: Int): String =
        "You had " + n + " " + (if (n == 1) "daily" else "dailies") + " due yesterday that " +
            (if (n == 1) "isn't" else "aren't") +
            " ticked. Tick anything you actually did to keep your 🔥 streak and avoid the HP hit."
    fun note(on: Boolean): String = if (on) "Will restore streak · +XP" else "Leave unticked → counts as missed"
}

/**
 * WP-8.10 (§5.0 B11 "Sheet UI"): the non-dismissable RYA sheet (#yScrim). Titles and the count
 * come from the frozen [sheet] only. WP-8.18 (PX-108): Opti's styling (index.html:512-534):
 * the .72 scrim, `.ySheet` (`--bg`, line border, radius 16, `padding:18px 18px 16px`, max 420 dp
 * wide with 14 dp side margins, max 88 % high, scrolls), `.yHead` / `.ySub` / `.yItem` / `.yBox`
 * / `.yGo` / `.yFine`.
 */
@Composable
fun RyaSheet(sheet: RyaSheetState, onToggle: (String) -> Unit, onStartDay: () -> Unit) {
    // Full window (Opti's fixed #yScrim), never dismissed by Back or an outside tap (B11).
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(RyaScrim), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.88f)
                    .background(OptiRootTokens.Bg, shape)
                    .border(1.dp, OptiRootTokens.Line, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 19.dp, end = 19.dp, top = 19.dp, bottom = 17.dp) // + the 1 dp border
                    .testTag("rya_sheet"),
            ) {
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(RyaText.ICON, fontSize = 22.sp)
                    Text(RyaText.HEAD, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink)
                }
                Text(
                    RyaText.sub(sheet.items.size), color = OptiRootTokens.Muted, fontSize = 13.sp, lineHeight = 1.55.em,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sheet.items.forEach { item ->
                        key(item.id) {
                            RyaItem(item.title, item.id in sheet.ticked, Modifier.testTag("rya_item_${item.id}")) { onToggle(item.id) }
                        }
                    }
                }
                PrimaryButton(RyaText.BUTTON, onStartDay, Modifier.fillMaxWidth().testTag("rya_start_day"))
                Text(
                    RyaText.FINE, color = OptiRootTokens.Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                )
            }
        }
    }
}

/**
 * `.yItem` (index.html:523-532): `--panel`, line border, radius 10, `padding:11px 12px`, gap 11;
 * `.on` = green border on `--panel2`. `.yBox` 24 dp, a 1.5 dp muted border, radius 7, green when
 * on with a `#1d0f38` 15 sp tick. `.yTitle` 14 sp ink, `.yNote` 12 sp muted (green when on).
 */
@Composable
private fun RyaItem(title: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val box = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (on) OptiRootTokens.Panel2 else OptiRootTokens.Panel, shape)
            .border(1.dp, if (on) OptiRootTokens.Green else OptiRootTokens.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp), // + the 1 dp border
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (on) OptiRootTokens.Green else Color.Transparent, box)
                .border(1.5.dp, if (on) OptiRootTokens.Green else OptiRootTokens.Muted, box),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", fontSize = 15.sp, color = PrimaryInk) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = OptiRootTokens.Ink, fontSize = 14.sp)
            Text(RyaText.note(on), color = if (on) OptiRootTokens.Green else OptiRootTokens.Muted, fontSize = 12.sp)
        }
    }
}
