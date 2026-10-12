package com.example.ui.visuals

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.TagEntity
import com.example.ui.theme.OptiRootTokens

/** WP-8.18: the tag list the cards resolve `task.tags` against (Opti `tagById`, S.tags). */
val LocalTags = staticCompositionLocalOf<List<TagEntity>> { emptyList() }

val CardShape = RoundedCornerShape(OptiDims.CARD_RADIUS.dp)

/**
 * `.railItem` (index.html:183-184): 10 sp bold, `padding:1px 5px`, radius 5, 1 dp border,
 * uppercase, letter-spacing .3 px, tabular numbers.
 */
@Composable
fun RailItem(text: String, style: RailStyle, modifier: Modifier = Modifier, fontSize: Int = OptiDims.RAIL_SP, padH: Int = OptiDims.RAIL_PAD_H, label: String? = null) {
    val shape = RoundedCornerShape(OptiDims.RAIL_RADIUS.dp)
    Text(
        railText(text),
        color = style.fg,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = OptiDims.RAIL_LETTER_SP.sp,
        maxLines = 1,
        softWrap = false,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        // P8 (KT-73): an optional plain-words label so TalkBack does not read the emoji name.
        modifier = modifier
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .background(style.bg, shape)
            .border(1.dp, style.border, shape)
            .padding(horizontal = (padH + 1).dp, vertical = (OptiDims.RAIL_PAD_V + 1).dp), // + the 1 dp border
    )
}

/** The `.railItem.bell` (app.js:3159): accent, no border or background, `padding:0 2px`, 11 sp. */
@Composable
fun BellItem(modifier: Modifier = Modifier) {
    RailItem("🔔", RailStyle(OptiRootTokens.Accent, Color.Transparent, Color.Transparent), modifier.testTag("rail_bell"), fontSize = 11, padH = 2, label = "Reminder set")
}

/**
 * `.rail` (index.html:181-182): a full-height column at the card's end, items right-aligned and
 * centered, `gap:3px`, `padding:4px 8px 4px 4px`.
 */
@Composable
fun Rail(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(
                start = OptiDims.RAIL_PAD_START.dp, top = OptiDims.RAIL_PAD_TOP.dp,
                end = OptiDims.RAIL_PAD_END.dp, bottom = OptiDims.RAIL_PAD_BOTTOM.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(OptiDims.RAIL_GAP.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.End,
    ) { content() }
}

/** `.valdot` (index.html:334): a 10 dp full-height strip in [color]. */
@Composable
fun ValDot(color: Color) {
    Box(Modifier.width(OptiDims.VALDOT_W.dp).fillMaxHeight().background(color).testTag("valdot"))
}

/**
 * `.subFrac` (index.html:192-198): min 32 x 32, `padding:3px`, radius 6, line border, `#2a1947`;
 * full = green with `#0c2a18` numbers. Numbers 12 sp bold, a 16 x 1 dp rule with 2 dp margins.
 */
@Composable
fun SubFrac(done: Int, total: Int, modifier: Modifier = Modifier) {
    val full = total > 0 && done == total
    val shape = RoundedCornerShape(6.dp)
    val ink = if (full) Color(0xFF0C2A18) else OptiRootTokens.Ink
    Column(
        modifier = modifier
            .widthIn(min = 32.dp)
            .heightIn(min = 32.dp)
            .background(if (full) OptiRootTokens.Green else RailDefaultBg, shape)
            .border(1.dp, if (full) OptiRootTokens.Green else OptiRootTokens.Line, shape)
            .padding(4.dp), // padding 3 + the 1 dp border
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink, lineHeight = 1.em)
        Box(Modifier.padding(vertical = 2.dp).width(16.dp).heightIn(min = 1.dp, max = 1.dp).background(if (full) Color(0xFF0C2A18) else OptiRootTokens.Muted))
        Text("$total", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink, lineHeight = 1.em)
    }
}

/** A CSS `#rgb` / `#rrggbb` tag color; null when it does not parse. */
fun tagColor(css: String?): Color? = runCatching {
    val h = css!!.trim().removePrefix("#")
    val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
    require(full.length == 6)
    Color(0xFF000000 or full.toLong(16))
}.getOrNull()

/**
 * Opti `tagChips` (app.js:3272-3274): one `.tagChip` per id that `tagById` finds. Kotlin also
 * accepts a tag stored by name (older Kotlin rows); unknown entries are skipped like Opti's.
 */
fun resolveTags(ids: List<String>, tags: List<TagEntity>): List<TagEntity> = ids.mapNotNull { id ->
    tags.firstOrNull { it.id == id } ?: tags.firstOrNull { it.name.equals(id, ignoreCase = true) }
}

/**
 * `.tagChip` (index.html:220-221): 11 sp, `padding:2px 8px`, radius 20, a 1 dp border and text
 * in `--tc` (fallback `#888` / `#ccc`), transparent, line-height 1.5.
 */
@Composable
fun TagChip(name: String, color: String?, modifier: Modifier = Modifier) {
    val c = tagColor(color)
    val shape = RoundedCornerShape(20.dp)
    Text(
        name,
        color = c ?: Color(0xFFCCCCCC),
        fontSize = 11.sp,
        lineHeight = 1.5.em,
        modifier = modifier.border(1.dp, c ?: Color(0xFF888888), shape).padding(horizontal = 9.dp, vertical = 3.dp), // + border
    )
}

/** `.meta` + `.tagChips` (index.html:175, 219): the chips wrap with a 4 dp gap, `margin-top:3px`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipsRow(ids: List<String>, modifier: Modifier = Modifier) {
    val chips = resolveTags(ids, LocalTags.current)
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(top = OptiDims.META_TOP.dp).testTag("tag_chips"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { chips.forEach { TagChip(it.name, it.color, Modifier.testTag("tag_chip_${it.id}")) } }
}

/**
 * `.task .notes` (index.html:178-179, app.js:3146-3147): 12 sp muted, line-height 1.3,
 * `margin-top:4px`, `white-space:pre-wrap`, clamped to [lines] with an ellipsis.
 */
@Composable
fun NotesPreview(notes: String, lines: Int, modifier: Modifier = Modifier) {
    Text(
        notes,
        fontSize = OptiDims.NOTES_SP.sp,
        color = OptiRootTokens.Muted,
        lineHeight = OptiDims.NOTES_LH.em,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = OptiDims.NOTES_TOP.dp).testTag("card_notes"),
    )
}

/** `.pill` (index.html:335): 10 sp, `padding:1px 6px`, radius 6, `#2a1947`, line border. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text, fontSize = 10.sp, color = OptiRootTokens.Muted,
        modifier = modifier.background(RailDefaultBg, shape).border(1.dp, OptiRootTokens.Line, shape).padding(horizontal = 7.dp, vertical = 2.dp), // + border
    )
}

/**
 * Opti's inline gold coin (`COIN_SVG`, app.js:3190-3193 and the header coin, index.html:1052):
 * a `#ffbe5c` disc with a `#c8862f` rim, an inner ring at 0.7 opacity and a serif "$" in `#7a4d12`.
 */
@Composable
fun CoinIcon(sizeDp: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp.dp).testTag("coin_icon")) {
        val u = size.minDimension / 24f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFFFFBE5C), radius = 10f * u, center = c)
        drawCircle(Color(0xFFC8862F), radius = 10f * u, center = c, style = Stroke(1.5f * u))
        drawCircle(Color(0xFFC8862F).copy(alpha = 0.7f), radius = 6.5f * u, center = c, style = Stroke(1.2f * u))
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(0x7A, 0x4D, 0x12)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 9f * u
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }
        drawContext.canvas.nativeCanvas.drawText("$", 12f * u, 16f * u, paint)
    }
}

/**
 * The drag look (index.html:155-161). The reorder library moves the card itself, so the moving
 * card wears Opti's `.dragGhost.lifted`: scale 1.04, opacity .96, a `0 16px 36px` shadow and a
 * 2 dp accent outline outside the border (radius 10). [lifted] false = `.dragGhost` (scale 1,
 * `0 10px 28px`), which Opti uses for a lifted subtask row. The lift animates in .14 s.
 */
fun Modifier.dragGhost(dragging: Boolean, lifted: Boolean = true): Modifier = this.then(
    if (!dragging) Modifier else Modifier.dragGhostOn(lifted)
)

private fun Modifier.dragGhostOn(lifted: Boolean): Modifier = composed {
    val s by animateFloatAsState(if (lifted) 1.04f else 1f, tween(140), label = "ghostScale")
    this
        .scale(s)
        .shadow(if (lifted) 16.dp else 10.dp, CardShape, clip = false)
        .alpha(0.96f)
        .drawWithContent {
            drawContent()
            val w = 2.dp.toPx()
            drawRoundRect(
                color = OptiRootTokens.Accent,
                topLeft = Offset(-w / 2, -w / 2),
                size = Size(size.width + w, size.height + w),
                cornerRadius = CornerRadius(OptiDims.CARD_RADIUS.dp.toPx() + w / 2),
                style = Stroke(w),
            )
        }
}
