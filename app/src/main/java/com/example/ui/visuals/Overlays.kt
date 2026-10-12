package com.example.ui.visuals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens
import kotlin.math.max
import kotlin.math.min

/** `.btn.primary` text color (index.html:478). */
val PrimaryInk = Color(0xFF1D0F38)

/** `.scrim` (index.html:336): `rgba(10,5,20,.66)`; `.yScrim` (:513) is .72. */
val SheetScrim = Color(0xA80A0514)
val RyaScrim = Color(0xB80A0514)

/**
 * `.btn.primary` (index.html:451-478): accent, `#1d0f38`, radius 9, `padding:10px 8px`,
 * 13 sp bold; `[disabled]` at opacity .4 (the rep sheet's rule, :333).
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, fontSize: Int = 13, padH: Int = 8, padV: Int = 10) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(OptiRootTokens.Accent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = padH.dp, vertical = padV.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = PrimaryInk, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A full-window dialog: Opti's fixed overlays cover the whole viewport. */
@Composable
fun FullWindow(onDismiss: () -> Unit, dismissOnBack: Boolean = true, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBack,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) { content() }
}

/** Opti's death overlay copy (index.html:1080-1083). */
object DeathText {
    const val TITLE = "You died!"
    const val BODY = "You ran out of Health. You lost a level and some gold, and your HP was restored. Be careful out there."
    const val BUTTON = "Continue"
}

/**
 * `.deathOverlay` (index.html:569-573, PX-107): `rgba(40,5,20,.92)` over everything, centered,
 * `text-align:center; padding:30px`, a 340 dp column: a red h2 (24 sp bold, .83 em margins), the
 * `.small` text (11 sp muted, line-height 1.5, 1 em margins) and a `.btn.primary` "Continue".
 * Only the button closes it (no scrim tap).
 */
@Composable
fun DeathOverlay(onContinue: () -> Unit) {
    FullWindow(onDismiss = onContinue) {
        Box(
            Modifier.fillMaxSize().background(OptiSelectorTokens.DeathScrim).padding(30.dp).testTag("death_overlay"),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.widthIn(max = 340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    DeathText.TITLE, color = OptiRootTokens.Red, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 20.dp).testTag("death_title"),
                )
                Text(
                    DeathText.BODY, color = OptiRootTokens.Muted, fontSize = 11.sp, lineHeight = 1.5.em,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 11.dp),
                )
                PrimaryButton(DeathText.BUTTON, onContinue, Modifier.testTag("death_continue"))
            }
        }
    }
}

/** Opti's rep-sheet draft `REP = {id, sign, value}` (app.js:2515-2561). */
data class RepDraft(val sign: Int, val value: Int = 0) {
    /** `repAdjust`: a + sheet never goes below 0, a − sheet never above 0. */
    fun adjust(delta: Int): RepDraft = copy(value = if (sign > 0) max(0, value + delta) else min(0, value + delta))

    /** `repQuick(n)`. */
    fun quick(n: Int): RepDraft = copy(value = n)

    /** `repInput(v)`: `parseInt(v, 10)`, NaN = 0, then clamped to the sheet's sign. */
    fun input(v: String): RepDraft {
        val n = jsParseInt(v) ?: 0
        return copy(value = if (sign > 0) max(0, n) else min(0, n))
    }

    /** `#repConfirm` is disabled while `REP.value === 0`. */
    val canConfirm: Boolean get() = value != 0

    /** `drawRepSheet` quick buttons: ±2 .. ±10, labels "+n" / "−n". */
    val quickOptions: List<Pair<String, Int>>
        get() = (2..10).map { i -> if (sign > 0) ("+$i" to i) else ("−$i" to -i) }

    companion object {
        /** `openRepSheet(id, sign)`: `sign > 0 ? 1 : -1`, value 0. */
        fun open(positive: Boolean) = RepDraft(if (positive) 1 else -1, 0)
    }
}

/** JS `parseInt(v, 10)`: leading blanks, an optional sign, then digits; null = NaN. */
fun jsParseInt(v: String): Int? {
    val m = Regex("^\\s*([+-]?\\d+)").find(v) ?: return null
    return m.groupValues[1].toBigInteger().let {
        when {
            it > Int.MAX_VALUE.toBigInteger() -> Int.MAX_VALUE
            it < Int.MIN_VALUE.toBigInteger() -> Int.MIN_VALUE
            else -> it.toInt()
        }
    }
}

/**
 * Opti `drawRepSheet` (app.js:2562-2600, index.html:319-341) inside `#scrim .sheet`: the bottom
 * sheet (`--bg`, radius 16 on top, a line top border, `padding:16px 16px 26px`) over the .66 scrim;
 * at 520 dp and wider Opti centers it with all corners rounded (index.html:30-31). A scrim tap
 * closes it (app.js:8522). Confirm with a non-zero value calls [onCommit] with the signed count.
 */
@Composable
fun RepSheet(title: String, positive: Boolean, onClose: () -> Unit, onCommit: (Int) -> Unit) {
    var draft by remember(title, positive) { mutableStateOf(RepDraft.open(positive)) }
    var text by remember(title, positive) { mutableStateOf("0") }
    fun set(d: RepDraft) { draft = d; text = d.value.toString() }
    FullWindow(onDismiss = onClose) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(SheetScrim)
                .pointerInput(onClose) { detectTapGestures { onClose() } }
                .testTag("rep_scrim"),
        ) {
            val wide = maxWidth >= 520.dp
            val shape = if (wide) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            Column(
                Modifier
                    .align(if (wide) Alignment.Center else Alignment.BottomCenter)
                    .then(if (wide) Modifier.padding(bottom = 24.dp) else Modifier)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.92f)
                    .background(OptiRootTokens.Bg, shape)
                    .drawWithContent {
                        drawContent()
                        if (!wide) drawRect(OptiRootTokens.Line, Offset(0f, 0f), Size(size.width, 1.dp.toPx()))
                    }
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = if (wide) 16.dp else 17.dp, bottom = 26.dp) // + border-top
                    .testTag("rep_sheet"),
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Log reps", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink, modifier = Modifier.padding(bottom = 2.dp))
                        Text(title, fontSize = 11.sp, color = OptiRootTokens.Muted, lineHeight = 1.5.em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rep_title"))
                    }
                    PrimaryButton(
                        "Confirm",
                        onClick = { val n = draft.value; if (n == 0) onClose() else onCommit(n) },
                        enabled = draft.canConfirm,
                        padH = 16, padV = 0,
                        modifier = Modifier.height(32.dp).testTag("confirm_bulk_reps_button"),
                    )
                }
                // .repRow (margin-top 10 inline, 6 0 14 in CSS; the inline style wins for the top)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepSide("−", "remove one") { set(draft.adjust(-1)) }
                    val ishape = RoundedCornerShape(8.dp)
                    BasicTextField(
                        value = text,
                        onValueChange = { v -> text = v; draft = draft.input(v) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(color = OptiRootTokens.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                        cursorBrush = SolidColor(OptiRootTokens.Ink),
                        modifier = Modifier
                            .weight(1f)
                            .background(OptiRootTokens.Bg, ishape)
                            .border(1.dp, OptiRootTokens.Line, ishape)
                            .padding(9.dp) // 8 + the 1 dp border
                            .testTag("bulk_reps_input"),
                    )
                    RepSide("+", "add one") { set(draft.adjust(1)) }
                }
                // .repQuickRow: 3 columns, gap 8, margin-bottom 16
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.quickOptions.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (label, v) -> RepQuick(label, draft.value == v, Modifier.weight(1f)) { set(draft.quick(v)) } }
                        }
                    }
                }
            }
        }
    }
}

/** `.repSide` (index.html:321-323): 46 dp wide, line border, `--panel2`, radius 8, 22 sp bold. */
@Composable
private fun RepSide(glyph: String, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(OptiRootTokens.Panel2, shape)
            .border(1.dp, OptiRootTokens.Line, shape)
            .clickable(onClickLabel = label, onClick = onClick)
            .testTag("rep_side_$glyph"),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink) }
}

/**
 * `.repQuick` (index.html:327-330): line border, `--panel`, radius 8, `padding:12px 0`, 16 sp bold;
 * `.on` = accent with `#1a0f2e` text.
 */
@Composable
private fun RepQuick(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(if (on) OptiRootTokens.Accent else OptiRootTokens.Panel, shape)
            .border(1.dp, if (on) OptiRootTokens.Accent else OptiRootTokens.Line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp) // 12 + the 1 dp border
            .testTag("rep_quick_$label"),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) Color(0xFF1A0F2E) else OptiRootTokens.Ink) }
}
