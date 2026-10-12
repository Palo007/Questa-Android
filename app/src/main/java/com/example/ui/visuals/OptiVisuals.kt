package com.example.ui.visuals

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.local.entities.TaskEntity
import com.example.ui.CardPrefs
import com.example.ui.theme.OptiRootTokens
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * WP-8.18 (PX-88, 100, 102, 104..109): the pure values behind the Opti component visuals.
 * Source: Opti `index.html` / `app.js` at `7d5d4f3`. One CSS px is one dp and one CSS font px is
 * one sp (the plan's rule: the G13 capture runs at DPR 2.625 = the device density).
 */
object OptiDims {
    // .task (index.html:126-127) and applyCardThick (app.js:2019)
    const val CARD_RADIUS = 10
    const val CARD_GAP = 6 // margin-bottom:6px
    const val CARD_BASE_MIN_H = 32 // --card-min-h = 32 + cardThick
    const val CARD_BORDER = 1
    const val VALDOT_W = 10 // .valdot
    const val CHECK_W = 44 // .task .check
    const val BODY_PAD_H = 8 // .task .body padding:5px 8px
    const val BODY_PAD_V = 5
    const val TITLE_SP = 14 // .ttl
    const val TITLE_LH = 1.25f
    const val META_SP = 11 // .meta
    const val META_TOP = 3
    const val META_GAP = 8
    const val NOTES_SP = 12 // .notes
    const val NOTES_LH = 1.3f
    const val NOTES_TOP = 4

    // .rail / .railItem (index.html:181-184)
    const val RAIL_PAD_TOP = 4
    const val RAIL_PAD_END = 8
    const val RAIL_PAD_BOTTOM = 4
    const val RAIL_PAD_START = 4
    const val RAIL_GAP = 3
    const val RAIL_SP = 10
    const val RAIL_PAD_H = 5
    const val RAIL_PAD_V = 1
    const val RAIL_RADIUS = 5
    const val RAIL_LETTER_SP = 0.3f

    // .bar (index.html:70-81)
    const val BAR_H = 14
    const val BAR_RADIUS = 6
    const val BARS_TOP = 6
    const val BARS_GAP = 4
    const val BAR_LABEL_SP = 10
    const val SHINE_MS = 3200
    const val BAR_FILL_MS = 350

    // header (index.html:33-38, 60-66)
    const val HEADER_PAD_TOP = 8
    const val HEADER_PAD_H = 12
    const val HEADER_PAD_BOTTOM = 6
    const val AVATAR = 46
    const val AVATAR_RADIUS = 10
    const val AVATAR_SP = 24
    const val AVATAR_GAP = 10
    const val LOWHP_PULSE_MS = 1400

    // main (index.html:84)
    const val MAIN_PAD_H = 10

    // #toast / .toastMsg (index.html:491-498)
    const val TOAST_BOTTOM = 84
    const val TOAST_GAP = 6
    const val TOAST_MS = 2400L
    const val TOAST_RADIUS = 20
}

/** Opti `applyWidth` (app.js:2018): `--appw = (S.prefs.width || 480) px`. */
fun appWidth(p: CardPrefs): Dp = (if (p.widthPx > 0) p.widthPx else CardPrefs.DEFAULT_WIDTH_PX).dp

/** Opti `applyCardThick` (app.js:2019): the card min height, `32 + (cardThick || 0)` px. */
fun cardMinHeight(p: CardPrefs): Dp = (OptiDims.CARD_BASE_MIN_H + p.cardThick.coerceIn(0, 60)).dp

/** The card row's min height inside the 1 dp border (`box-sizing:border-box`). */
fun cardRowMinHeight(p: CardPrefs): Dp = cardMinHeight(p) - (2 * OptiDims.CARD_BORDER).dp

/**
 * Opti `metaRow` (app.js:3146-3147): the notes preview shows when `t.notes && notesLines > 0`
 * and clamps to `Number(notesLines)` lines. Null = no preview.
 */
fun notesClamp(notes: String, p: CardPrefs): Int? =
    if (notes.isNotEmpty() && p.notesLines > 0) p.notesLines else null

/** Opti `valColor` (app.js:2211-2219): the value dot color. */
fun valColor(v: Double): Color = when {
    v < -16 -> OptiRootTokens.DarkRed
    v < -9 -> OptiRootTokens.Red
    v < -1 -> OptiRootTokens.Orange
    v < 1 -> OptiRootTokens.Yellow
    v < 6 -> OptiRootTokens.Green
    v < 12 -> OptiRootTokens.LBlue
    else -> OptiRootTokens.Blue
}

/** One `.railItem` look: text, border and background colors. */
data class RailStyle(val fg: Color, val border: Color, val bg: Color)

/** `.railItem` default: `border:1px solid var(--line); background:#2a1947` (index.html:183-184). */
val RailDefaultBg = Color(0xFF2A1947)

/** `.railItem.diff-*` (index.html:187-191). An unknown difficulty keeps the plain `.railItem`. */
fun diffStyle(difficulty: String): RailStyle = when (difficulty) {
    "trivial" -> RailStyle(OptiRootTokens.Muted, OptiRootTokens.Line, Color(0x08FFFFFF))
    "easy" -> RailStyle(OptiRootTokens.Green, Color(0x4048B677), Color(0x1448B677))
    "medium" -> RailStyle(OptiRootTokens.Orange, Color(0x40F9A03F), Color(0x14F9A03F))
    "hard" -> RailStyle(OptiRootTokens.Red, Color(0x40F74E52), Color(0x14F74E52))
    "log" -> RailStyle(OptiRootTokens.LBlue, Color(0x4074B6E0), Color(0x1474B6E0))
    else -> RailStyle(OptiRootTokens.Ink, OptiRootTokens.Line, RailDefaultBg)
}

val StreakStyle = RailStyle(OptiRootTokens.Gold, OptiRootTokens.Gold, RailDefaultBg) // .railItem.streak
val CountStyle = RailStyle(OptiRootTokens.Accent, OptiRootTokens.Accent, RailDefaultBg) // .railItem.cnt
val NotDueStyle = RailStyle(OptiRootTokens.Muted, OptiRootTokens.Line, Color(0x08FFFFFF)) // .railItem.notdue

/** `text-transform:uppercase` of a `.railItem`. */
fun railText(s: String): String = s.uppercase()

/** Opti `rail()` habit counter (app.js:3172-3175); null when both directions are off. */
fun habitCountLabel(t: TaskEntity): String? {
    val up = t.up != false
    val down = t.down != false
    return when {
        up && down -> "+" + t.cUp + "|−" + t.cDown
        up -> "+" + t.cUp
        down -> "−" + t.cDown
        else -> null
    }
}

/** Opti `rail()` bell: `t.reminders && t.reminders[0] && t.reminders[0].enabled` (app.js:3157). */
fun hasReminder(t: TaskEntity): Boolean = t.reminders?.firstOrNull()?.enabled == true

private val DOW_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** Opti `nextDueWeekday` (app.js:103-108); [today] is JS `getDay()` (0 = Sunday). */
fun nextDueWeekday(repeat: List<Boolean>?, today: Int): String? {
    if (repeat == null) return null
    for (i in 1 until 7) {
        val d = (today + i) % 7
        if (repeat.getOrNull(d) == true) return DOW_LABELS[d]
    }
    return "Never"
}

/** Opti `isDailyDueOn` (app.js:100): `!t.repeat || !!t.repeat[dow]`. */
fun isDueOn(repeat: List<Boolean>?, dow: Int): Boolean = repeat == null || repeat.getOrNull(dow) == true

/** A JS `String(number)` for the values the HUD prints (integers without ".0"). */
fun jsNum(v: Double): String =
    if (v == floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

/** Opti `renderStats` HP label (app.js:3121): `Math.ceil(Math.max(0,hp)) + ' / ' + maxHp + ' HP'`. */
fun hpLabel(hp: Double, maxHp: Double): String = ceil(max(0.0, hp)).toLong().toString() + " / " + jsNum(maxHp) + " HP"

/** Opti `renderStats` XP label (app.js:3124): `Math.floor(xp) + ' / ' + need + ' XP'`. */
fun xpLabel(xp: Double, need: Double): String = floor(xp).toLong().toString() + " / " + jsNum(need) + " XP"

/** Opti `#statGold`: `Math.floor(c.gold)` (app.js:3119). */
fun goldLabel(gold: Double): String = floor(gold).toLong().toString()

/** Opti `clamp(v / max * 100, 0, 100)` as a 0..1 fraction; a non-positive max is 0. */
fun barFraction(v: Double, max: Double): Float = if (max <= 0.0) 0f else min(1.0, max(0.0, v / max)).toFloat()

/** Opti `body.lowhp`: `c.hp / c.maxHp <= 0.3` (app.js:3125). */
fun isLowHp(hp: Double, maxHp: Double): Boolean = maxHp > 0 && hp / maxHp <= 0.3

/**
 * `@keyframes pop` of `.toastMsg` over its 2.4 s (index.html:497-498): (alpha, dy in dp) at
 * [t] in 0..1. Opacity: fade in 0 -> 10 %, hold to 80 %, fade out. Transform: +8 -> 0 by 10 %,
 * then a 6 dp drift up over 10 -> 100 % (80 % sets no transform).
 */
fun toastPop(t: Float): Pair<Float, Float> {
    if (t <= 0f) return 0f to 8f
    if (t >= 1f) return 0f to -6f
    // opacity keyframes: 0 / 10 / 80 / 100 %
    val alpha = when {
        t < 0.1f -> CssEase.transform(t / 0.1f)
        t <= 0.8f -> 1f
        else -> 1f - CssEase.transform((t - 0.8f) / 0.2f)
    }
    // transform keyframes: 0 / 10 / 100 % only (80 % sets no transform), so it drifts from 10 %.
    val dy = if (t < 0.1f) 8f * (1f - CssEase.transform(t / 0.1f)) else 0f - 6f * CssEase.transform((t - 0.1f) / 0.9f) // 0f - x: no -0.0
    return alpha to dy
}

/** Opti `checkExportStaleness` (app.js:6620-6628): no export yet, or the last one > 7 days ago. */
fun gearStale(lastExportTs: Long, nowMs: Long): Boolean = lastExportTs <= 0L || nowMs - lastExportTs > 7 * 86_400_000L

/** CSS `ease` and `ease-in-out`: a keyframe animation applies them per keyframe interval. */
val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * `@keyframes shine` (index.html:79): the sweep's translateX as a multiple of the fill width at
 * [t] in 0..1 of the 3.2 s cycle. 0..60 % rests at -100 %; 60..100 % runs to 220 % with the
 * animation's `ease-in-out` (CSS applies it per keyframe interval).
 */
fun shineOffset(t: Float): Float =
    if (t <= 0.6f) -1f else -1f + 3.2f * CssEaseInOut.transform(((t - 0.6f) / 0.4f).coerceIn(0f, 1f))
