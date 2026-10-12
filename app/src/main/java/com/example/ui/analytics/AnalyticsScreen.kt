package com.example.ui.analytics

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entities.EventLogEntity
import com.example.data.local.entities.TaskEntity
import com.example.ui.OptiKeyboard
import com.example.ui.gestures.ModalKeys
import com.example.ui.gestures.ReportModal
import com.example.ui.gestures.swipeNavExclusion
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// =================================================================================================
// WP-8.20 (PX-110): the Analytics tab, drawn as Opti draws it (app.js viewAnalytics /
// refreshAnalytics / anViewsUI / anDetailDashboard / renderEventDetail; index.html:237-311,
// 579-670, 911-941). Sizes are Opti's CSS px as dp; SVG charts keep Opti's viewBox and scale to
// the card width as the SVG does.
// =================================================================================================

private val T = OptiRootTokens
internal val InputBg = OptiSelectorTokens.Nav // #2a1947
private val ChipOnText = Color(0xFF241043)
private val PrimaryText = Color(0xFF1D0F38)

/** A CSS color as Opti writes it (`#rrggbb`, `var(--x)`) to a Compose color. */
fun cssColor(css: String?): Color = when (css) {
    null -> T.Muted
    "var(--muted)" -> T.Muted
    "var(--panel2)" -> T.Panel2
    "var(--accent)" -> T.Accent
    "var(--mp)" -> T.Mp
    "var(--green)" -> T.Green
    "var(--line)" -> T.Line
    else -> runCatching {
        val h = css.trim().removePrefix("#")
        val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
        Color(0xFF000000 or full.toLong(16))
    }.getOrDefault(T.Muted)
}

// ---- tooltips (Opti bindTips / showTip / tipHTML) ----------------------------------------------------
private class TipState { var owner by mutableStateOf<Any?>(null) }
private val LocalTip = staticCompositionLocalOf { TipState() }

/** Positions the tip centred above the datum, below it when there is no room (Opti `showTip`). */
private class TipPosition(val point: Offset, val gapAbove: Int, val gapBelow: Int, val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + point.x
        val y = anchorBounds.top + point.y
        var left = x - popupContentSize.width / 2f
        var top = y - popupContentSize.height - gapAbove
        if (top < margin) top = y + gapBelow
        left = left.coerceIn(margin.toFloat(), max(margin.toFloat(), windowSize.width - popupContentSize.width - margin.toFloat()))
        top = top.coerceIn(margin.toFloat(), max(margin.toFloat(), windowSize.height - popupContentSize.height - margin.toFloat()))
        return IntOffset(left.roundToInt(), top.roundToInt())
    }
}

@Composable
private fun HoverTip(text: String, point: Offset) {
    val d = LocalDensity.current
    val lines = text.split('\n').filter { it.isNotEmpty() }
    if (lines.isEmpty()) return
    Popup(popupPositionProvider = TipPosition(point, with(d) { 18.dp.roundToPx() }, with(d) { 22.dp.roundToPx() }, with(d) { 6.dp.roundToPx() }),
        properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.widthIn(max = 220.dp).shadow(24.dp, RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF241541), Color(0xFF1A0F30))), RoundedCornerShape(10.dp))
                .border(1.dp, T.Accent, RoundedCornerShape(10.dp)).padding(horizontal = 11.dp, vertical = 8.dp)
                .testTag("an_tip")
        ) {
            Text(lines[0], color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, lineHeight = 1.4.em)
            if (lines.size > 1) Text(lines.drop(1).joinToString("\n"), color = T.Ink.copy(alpha = 0.92f), fontSize = 12.sp, lineHeight = 1.4.em)
        }
    }
}

// ---- small building blocks -------------------------------------------------------------------------------
@Composable
private fun AnSection(text: String) =
    Text(text, color = T.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 16.dp, bottom = 6.dp))

@Composable
internal fun AnNote(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) =
    Text(text, color = T.Muted, fontSize = 11.sp, lineHeight = 1.4.em, textAlign = align, modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp))

private val CardShape = RoundedCornerShape(14.dp)

@Composable
internal fun AnCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.background(T.Panel, CardShape).border(1.dp, T.Line, CardShape).padding(horizontal = 12.dp, vertical = 10.dp), content = content)

@Composable
internal fun CardK(text: String, color: Color = T.Muted) =
    Text(text.uppercase(Locale.ROOT), color = color, fontSize = 11.sp, letterSpacing = 0.04.em, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** `.anCards`: a 2-column grid of stat cards, gap 8, margin 8 2. */
@Composable
private fun AnCards(cards: List<AnCardData>, full: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    AnCard(Modifier.weight(1f).fillMaxHeight()) {
                        CardK(c.k, c.kColor?.let { cssColor(it) } ?: T.Muted)
                        Text(c.v, color = T.Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 1.1.em, modifier = Modifier.padding(top = 2.dp))
                        Text(c.sub, color = T.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        full?.invoke()
    }
}

@Composable
private fun FullCard(k: String? = null, content: @Composable ColumnScope.() -> Unit) =
    AnCard(Modifier.fillMaxWidth()) { if (k != null) CardK(k); content() }

@Composable
private fun AnStreakRow(title: String, right: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp).background(T.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, T.Line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = T.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
        Text(right, color = T.Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** `.anList` rows: optional 10dp dot, label, bold value (Opti `anListHTML`). */
@Composable
fun AnList(rows: List<AnRow>, locale: Locale = Locale.getDefault()) {
    if (rows.isEmpty()) { AnNote("No data in this window."); return }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { r -> AnListRow(r.color, r.label, AnalyticsCalc.loc(r.v, locale)) }
    }
}

@Composable
private fun AnListRow(color: String?, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(T.Panel, RoundedCornerShape(8.dp)).border(1.dp, T.Line, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (color != null) Box(Modifier.size(10.dp).background(cssColor(color), CircleShape))
        Text(label, color = T.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, color = T.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** `.anLegendTags`: colored 10dp squares (radius 3) + 11sp muted labels (Opti `anRowLegend`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegendChips(items: List<LegendItem>) {
    if (items.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { li ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("an_legend_tag")) {
                Box(Modifier.size(10.dp).background(cssColor(li.color), RoundedCornerShape(3.dp)))
                Text(li.label, color = T.Muted, fontSize = 11.sp)
            }
        }
    }
}

/** `.anLegend`: Less [5 swatches] More. */
@Composable
fun HeatLegend() {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Less", color = T.Muted, fontSize = 11.sp)
        AnalyticsCalc.HEAT_LEGEND.forEach { Box(Modifier.size(HEAT_CELL).background(cssColor(it), RoundedCornerShape(2.dp))) }
        Text("More", color = T.Muted, fontSize = 11.sp)
    }
}

// ---- charts -------------------------------------------------------------------------------------------------
@Composable
private fun EmptyChart(aspect: Float, text: String) {
    Box(Modifier.fillMaxWidth().aspectRatio(aspect), contentAlignment = Alignment.Center) { Text(text, color = T.Muted, fontSize = 11.sp) }
}

private fun f(s: String) = s.toFloat()

/** Opti `svgBars` (viewBox 300 x 90, `xMidYMax meet`, 0.5 baseline, top-rounded bars, tap = pin tip). */
@Composable
fun BarsChart(labels: List<String>, values: List<Double>, color: Color = T.Accent, locale: Locale = Locale.getDefault()) {
    if (values.isEmpty()) { EmptyChart(300f / 90f, "No data"); return }
    val geom = remember(values) { AnalyticsCalc.barsGeom(values) }
    val tip = LocalTip.current
    val key = remember { Any() }
    var sel by remember { mutableStateOf<Int?>(null) }
    if (tip.owner !== key) sel = null
    var boxW by remember { mutableStateOf(1f) }
    Box(Modifier.fillMaxWidth().aspectRatio(300f / 90f).swipeNavExclusion().onGloballyPositioned { boxW = it.size.width.toFloat() }
        .pointerInput(values) {
            detectTapGestures { o ->
                val i = (o.x / (size.width / 300f) / geom[0].slotW).toInt().coerceIn(0, geom.size - 1)
                if (sel == i) { sel = null; tip.owner = null } else { sel = i; tip.owner = key }
            }
        }.testTag("an_bars")) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / 300f
            drawLine(T.Line, Offset(0f, 89f * s), Offset(size.width, 89f * s), strokeWidth = 0.5f * s)
            geom.forEachIndexed { i, g ->
                val x = f(AnalyticsCalc.fixed1(g.x)); val base = f(AnalyticsCalc.fixed1(g.base)); val yr = f(AnalyticsCalc.fixed1(g.y + g.r))
                val y = f(AnalyticsCalc.fixed1(g.y)); val xr = f(AnalyticsCalc.fixed1(g.x + g.r)); val xe = f(AnalyticsCalc.fixed1(g.x + g.bw - g.r)); val xw = f(AnalyticsCalc.fixed1(g.x + g.bw))
                val p = Path().apply {
                    moveTo(x * s, base * s); lineTo(x * s, yr * s); quadraticBezierTo(x * s, y * s, xr * s, y * s); lineTo(xe * s, y * s)
                    quadraticBezierTo(xw * s, y * s, xw * s, yr * s); lineTo(xw * s, base * s); close()
                }
                val a = if (sel == null || sel == i) 1f else 0.35f
                drawPath(p, color, alpha = a)
                if (sel == i) drawPath(p, Color.White, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        sel?.let { i ->
            val g = geom[i]; val s = boxW / 300f
            HoverTip(AnalyticsCalc.barTip(labels[i], values[i], locale), Offset(((g.slotX + g.slotW / 2) * s).toFloat(), 0f))
        }
    }
}

/** Opti `svgSpark` (viewBox 300 x h, `none`): area at 0.18, 2px round line, tap = ring + tip. */
@Composable
fun SparkChart(chart: SeriesChart) {
    val geom = remember(chart) { AnalyticsCalc.sparkGeom(chart.series.map { it.v }, chart.cumulative, chart.h) }
    if (geom == null) { EmptyChart(300f / chart.h, "No data in this window"); return }
    val color = cssColor(chart.color)
    val tip = LocalTip.current
    val key = remember { Any() }
    var sel by remember { mutableStateOf<Int?>(null) }
    if (tip.owner !== key) sel = null
    var boxW by remember { mutableStateOf(1f) }
    val pts = geom.pts.map { (x, y) -> f(AnalyticsCalc.fixed1(x)) to f(AnalyticsCalc.fixed1(y)) }
    Box(Modifier.fillMaxWidth().aspectRatio(300f / chart.h).swipeNavExclusion().onGloballyPositioned { boxW = it.size.width.toFloat() }
        .pointerInput(chart) {
            if (chart.tips.isNullOrEmpty()) return@pointerInput
            detectTapGestures { o ->
                val s = size.width / 300f
                var best = -1; var bd = Float.MAX_VALUE
                pts.forEachIndexed { i, (x, y) -> val dx = o.x - x * s; val dy = o.y - y * s; val dd = sqrt(dx * dx + dy * dy); if (dd < bd) { bd = dd; best = i } }
                if (best < 0 || bd > 14f * s) { sel = null; tip.owner = null; return@detectTapGestures }
                if (sel == best) { sel = null; tip.owner = null } else { sel = best; tip.owner = key }
            }
        }.testTag("an_spark")) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / 300f
            val h = chart.h.toFloat()
            val area = Path().apply { moveTo(0f, h * s); pts.forEach { (x, y) -> lineTo(x * s, y * s) }; lineTo(300f * s, h * s); close() }
            drawPath(area, color, alpha = 0.18f)
            val line = Path().apply { pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) } }
            drawPath(line, color, style = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            sel?.let { i ->
                val (x, y) = pts[i]
                drawCircle(Color.White, 4f * s, Offset(x * s, y * s))
                drawCircle(T.Accent, 4f * s, Offset(x * s, y * s), style = Stroke(2.dp.toPx()))
            }
        }
        sel?.let { i -> val (x, y) = pts[i]; val s = boxW / 300f; HoverTip(chart.tips!![i], Offset(x * s, y * s - 14f * s)) }
    }
}

/** Opti `svgCompare` (viewBox 300 x 110): one cumulative line per metric in METRIC_COLORS. */
@Composable
fun CompareChart(c: CompareSeries, zone: ZoneId, locale: Locale = Locale.getDefault()) {
    val lines = remember(c) { AnalyticsCalc.compareGeom(c) }
    if (lines == null) { EmptyChart(300f / 110f, "No data in this window"); return }
    val tips = remember(c) { AnalyticsCalc.compareTips(c, zone, locale) }
    val tip = LocalTip.current
    val key = remember { Any() }
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    if (tip.owner !== key) sel = null
    var boxW by remember { mutableStateOf(1f) }
    val pts = lines.map { (_, l) -> l.map { (x, y) -> f(AnalyticsCalc.fixed1(x)) to f(AnalyticsCalc.fixed1(y)) } }
    Box(Modifier.fillMaxWidth().aspectRatio(300f / 110f).swipeNavExclusion().onGloballyPositioned { boxW = it.size.width.toFloat() }
        .pointerInput(c) {
            detectTapGestures { o ->
                val s = size.width / 300f
                var best: Pair<Int, Int>? = null; var bd = Float.MAX_VALUE
                pts.forEachIndexed { mi, l -> l.forEachIndexed { i, (x, y) -> val dx = o.x - x * s; val dy = o.y - y * s; val dd = sqrt(dx * dx + dy * dy); if (dd <= bd) { bd = dd; best = mi to i } } }
                if (best == null || bd > 14f * s) { sel = null; tip.owner = null } else if (sel == best) { sel = null; tip.owner = null } else { sel = best; tip.owner = key }
            }
        }) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.width / 300f
            pts.forEachIndexed { mi, l ->
                val p = Path().apply { l.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) } }
                drawPath(p, cssColor(lines[mi].first), style = Stroke(width = 2f * s, join = StrokeJoin.Round))
            }
            sel?.let { (mi, i) -> val (x, y) = pts[mi][i]; drawCircle(Color.White, 4f * s, Offset(x * s, y * s)); drawCircle(T.Accent, 4f * s, Offset(x * s, y * s), style = Stroke(2.dp.toPx())) }
        }
        sel?.let { (mi, i) -> val (x, y) = pts[mi][i]; val s = boxW / 300f; HoverTip(tips[mi][i], Offset(x * s, y * s - 14f * s)) }
    }
}

// ---- heatmap (Opti anHeatmapHTML; .anHeatCell 11x11, radius 2, gap 3) ---------------------------------------
val HEAT_CELL: Dp = 11.dp
val HEAT_GAP: Dp = 3.dp

/** The cells in columns of 7 (Monday on top, Sunday at the bottom), as Opti lays them out. */
fun heatColumns(cells: List<HeatCell>): List<List<HeatCell>> = cells.chunked(7)

@Composable
fun Heatmap(cells: List<HeatCell>) {
    val tip = LocalTip.current
    val key = remember { Any() }
    var sel by remember { mutableStateOf<Long?>(null) }
    if (tip.owner !== key) sel = null
    Row(Modifier.fillMaxWidth().swipeNavExclusion().horizontalScroll(rememberScrollState()).padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 8.dp).testTag("an_heat"),
        horizontalArrangement = Arrangement.spacedBy(HEAT_GAP)) {
        heatColumns(cells).forEach { col ->
            Column(verticalArrangement = Arrangement.spacedBy(HEAT_GAP)) {
                col.forEach { c ->
                    val isSel = sel == c.dayMs
                    Box(
                        Modifier.size(HEAT_CELL).then(if (isSel) Modifier.scale(1.25f) else Modifier)
                            .background(cssColor(c.color), RoundedCornerShape(2.dp))
                            .then(if (isSel) Modifier.border(2.dp, Color.White, RoundedCornerShape(2.dp)) else Modifier)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (isSel) { sel = null; tip.owner = null } else { sel = c.dayMs; tip.owner = key }
                            }.testTag("an_heat_cell")
                    ) {
                        if (isSel) HoverTip(c.tip, Offset(with(LocalDensity.current) { HEAT_CELL.toPx() } / 2f, 0f))
                    }
                }
            }
        }
    }
}

// ---- range slider (Opti .anSlider: 12 tall, track 4 at top 4, 12dp handles, PAD 12) -----------------------------
private val SLIDER_PAD = 12.dp

/**
 * The two-handle window slider. [range] is `toOff..fromOff` in days back from today; [onChange]
 * fires while dragging (Opti repositions live), [onCommit] once on release (Opti save + refresh).
 */
@Composable
fun RangeSlider(range: IntRange, geom: AnalyticsCalc.SliderGeom, onChange: (IntRange) -> Unit, onCommit: () -> Unit = {}, modifier: Modifier = Modifier) {
    val f0 = geom.offToFrac(range.last); val f1 = geom.offToFrac(range.first)
    val lo = min(f0, f1); val hi = max(f0, f1)
    val cur by rememberUpdatedState(range)
    // review 36: the gesture is keyed on geom only, so read the latest callbacks through state
    val latestChange by rememberUpdatedState(onChange)
    val latestCommit by rememberUpdatedState(onCommit)
    Box(modifier.swipeNavExclusion().height(12.dp).padding(horizontal = 4.dp).pointerInput(geom) {
        val pad = SLIDER_PAD.toPx()
        var which = -1
        detectDragGestures(
            onDragStart = { o ->
                val tw = max(1f, size.width - 2 * pad)
                val x0 = pad + geom.offToFrac(cur.last).toFloat() * tw; val x1 = pad + geom.offToFrac(cur.first).toFloat() * tw
                val d0 = abs(o.x - x0); val d1 = abs(o.x - x1)
                which = if (min(d0, d1) > 24.dp.toPx()) -1 else if (d0 < d1 || (d0 == d1 && o.x < x0)) 0 else 1
            },
            onDragEnd = { if (which >= 0) latestCommit(); which = -1 },
            onDragCancel = { if (which >= 0) latestCommit(); which = -1 },
        ) { change, _ ->
            if (which < 0) return@detectDragGestures
            change.consume()
            val fr = ((change.position.x - pad) / max(1f, size.width - 2 * pad)).toDouble()
            val p = AnalyticsCalc.dragTo(AnPrefs(fromOff = cur.last, toOff = cur.first), geom, which, fr)
            latestChange(IntRange(p.toOff ?: 0, p.fromOff ?: 0))
        }
    }.testTag("an_slider")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tw = maxWidth - SLIDER_PAD * 2
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).background(T.Panel2, RoundedCornerShape(2.dp)).border(1.dp, T.Line, RoundedCornerShape(2.dp)))
            Box(Modifier.offset(x = SLIDER_PAD + tw * lo.toFloat(), y = 4.dp).width(tw * (hi - lo).toFloat()).height(4.dp).background(T.Accent, RoundedCornerShape(2.dp)))
            listOf(f0, f1).forEach { fr ->
                Box(Modifier.offset(x = SLIDER_PAD + tw * fr.toFloat() - 6.dp, y = (-1).dp).size(12.dp).shadow(4.dp, CircleShape).background(T.Ink, CircleShape).border(2.dp, T.Accent, CircleShape))
            }
        }
    }
}

// ---- buttons and inputs ------------------------------------------------------------------------------------------
@Composable
internal fun AnChip(text: String, on: Boolean, dashed: Boolean = false, onDoubleTap: (() -> Unit)? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    val tap = onClick
    // P8 (KT-70): semantics only. A chip is a tab with a selected state ("+ add" is a button),
    // and TalkBack can activate it (the touch path stays the pointerInput above).
    Text(text, color = if (on) ChipOnText else T.Muted, fontSize = 10.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, lineHeight = 1.2.em, maxLines = 1,
        modifier = modifier.background(if (on) T.Accent else T.Panel2, shape).border(1.dp, if (on) T.Accent else T.Line, shape)
            .pointerInput(onDoubleTap, onClick) { detectTapGestures(onDoubleTap = onDoubleTap?.let { { _ -> it() } }, onLongPress = onDoubleTap?.let { { _ -> it() } }, onTap = { onClick() }) }
            .semantics {
                role = if (dashed) Role.Button else Role.Tab
                if (!dashed) selected = on
                this.onClick(label = null, action = { tap(); true })
                if (onDoubleTap != null) this.onLongClick(label = "Edit", action = { onDoubleTap(); true })
            }
            .padding(horizontal = 5.dp, vertical = 2.dp))
}

@Composable
internal fun AnMini(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, color: Color = T.Muted, dashed: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.alpha(if (enabled) 1f else 0.3f).background(T.Panel, shape).border(1.dp, T.Line, shape)
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 11.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

private enum class BtnKind { PRIMARY, GHOST, DANGER }

@Composable
private fun AnBtn(text: String, kind: BtnKind, modifier: Modifier = Modifier, fontSize: Int = 13, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val (bg, fg) = when (kind) { BtnKind.PRIMARY -> T.Accent to PrimaryText; BtnKind.GHOST -> T.Panel to T.Ink; BtnKind.DANGER -> T.Red to Color.White }
    Box(modifier.defaultMinSize(minHeight = 40.dp).background(bg, shape).then(if (kind == BtnKind.GHOST) Modifier.border(1.dp, T.Line, shape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AnInput(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, bg: Color = T.Panel, radius: Dp = 8.dp,
                    number: Boolean = false, tag: String? = null, textAlign: TextAlign = TextAlign.Start) {
    val shape = RoundedCornerShape(radius)
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = T.Ink, fontSize = 14.sp, textAlign = textAlign), cursorBrush = SolidColor(T.Accent),
        // WP-8.16-R1: Opti's text inputs get Blink's default autocapitalize "sentences".
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else OptiKeyboard.Text,
        modifier = modifier.background(bg, shape).border(1.dp, T.Line, shape).padding(horizontal = 10.dp, vertical = 9.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = T.Muted, fontSize = 14.sp, textAlign = textAlign, modifier = Modifier.fillMaxWidth()); inner() } })
}

@Composable
private fun FieldLabel(text: String) = Text(text, color = T.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

/** `.seg` buttons: 36 tall, panel, 1px line, radius 8, 12sp 600; `.on` = panel2 + accent border, bold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Seg(options: List<Pair<String, String>>, isOn: (String) -> Boolean, onPick: (String) -> Unit, extra: (@Composable () -> Unit)? = null) {
    FlowRow(Modifier.fillMaxWidth().swipeNavExclusion().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            val on = isOn(value); val shape = RoundedCornerShape(8.dp)
            Box(Modifier.weight(1f).widthIn(min = 50.dp).height(36.dp).background(if (on) T.Panel2 else T.Panel, shape).border(1.dp, if (on) T.Accent else T.Line, shape)
                .clickable(role = Role.Tab) { onPick(value) }.semantics { selected = on }.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(label, color = T.Ink, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        extra?.invoke()
    }
}

/** `.tagChip` (outline in the tag color; `.on` filled). */
@Composable
private fun TagChip(name: String, color: String, on: Boolean, onClick: () -> Unit) {
    val c = cssColor(color); val shape = RoundedCornerShape(20.dp)
    Text(name, color = if (on) Color(0xFF1A1030) else c, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, lineHeight = 1.5.em,
        modifier = Modifier.background(if (on) c else Color.Transparent, shape).border(1.dp, c, shape).clickable(role = Role.Checkbox, onClick = onClick).semantics { toggleableState = ToggleableState(on) }.padding(horizontal = 8.dp, vertical = 2.dp))
}

// ---- views -------------------------------------------------------------------------------------------------------
/** Draws one Opti `renderView` result. */
@Composable
fun ViewBodyContent(body: ViewBody, locale: Locale = Locale.getDefault()) {
    when (body) {
        is ViewBody.Cards -> AnCards(body.cards)
        is ViewBody.Note -> AnNote(body.text)
        is ViewBody.TagSummary -> {
            FullCard { BarsChart(body.bars.map { it.label }, body.bars.map { it.v }, T.Accent, locale) }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                body.rows.forEach { r -> AnListRow(r.color, r.name, "${r.count} items · ${r.comp} done · ${r.created} new") }
            }
        }
        is ViewBody.ListRows -> AnList(body.rows, locale)
        is ViewBody.Bars -> {
            FullCard { BarsChart(body.rows.map { it.label }, body.rows.map { it.v }, T.Accent, locale) }
            LegendChips(AnalyticsCalc.rowLegend(body.rows))
        }
        is ViewBody.Line -> FullCard { SparkChart(body.chart) }
        is ViewBody.Heatmap -> { Heatmap(body.cells); HeatLegend() }
    }
}

@Composable
private fun ViewsSection(vm: AnalyticsViewModel, d: AnData, p: AnPrefs, from: Long, to: Long, now: Long, zone: ZoneId,
                         onExport: () -> Unit, onImport: () -> Unit, onNew: () -> Unit, onEdit: (String) -> Unit) {
    val views = d.views
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("📐 Custom views (${views.size}/20)", color = T.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactBtn("📥 Export", "an_export", onExport)
            CompactBtn("📤 Import", "an_import", onImport)
        }
    }
    if (views.isEmpty()) AnNote("No view sections yet. Tap “+ Add view section” to build one.")
    views.forEachIndexed { i, v ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp).testTag("an_view_section")) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(v.name, color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(AnalyticsCalc.viewMetaLabel(v, d.metrics, p), color = T.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                AnMini("↑", enabled = i > 0) { vm.moveView(v.id, -1) }
                AnMini("↓", enabled = i < views.size - 1) { vm.moveView(v.id, 1) }
                AnMini("edit") { onEdit(v.id) }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val body = remember(v, d, p, from, to) { AnalyticsCalc.renderView(v, d, p, from, to, now, zone) }
                ViewBodyContent(body)
            }
        }
        if (i < views.size - 1) DashedLine()
    }
    if (views.size < 20) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).background(T.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, T.Line, RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onNew).padding(10.dp).testTag("an_add_view"), contentAlignment = Alignment.Center) {
            Text("+ Add view section", color = T.Accent, fontSize = 12.sp)
        }
    } else AnNote("Maximum of 20 view sections reached.")
}

@Composable
private fun DashedLine() = Canvas(Modifier.fillMaxWidth().height(1.dp)) {
    drawLine(T.Line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
}

@Composable
private fun CompactBtn(text: String, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.height(24.dp).background(Color.White.copy(alpha = 0.03f), shape).border(1.dp, T.Line, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center) { Text(text, color = T.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.em) }
}

// ---- view builder sheet (Opti drawViewBuilder) --------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewBuilder(vm: AnalyticsViewModel, draft: ViewDraft, onDraft: (ViewDraft) -> Unit, d: AnData, p: AnPrefs, from: Long, to: Long, now: Long, zone: ZoneId,
                        onEditMetric: (String?) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 26.dp)) {
        Text(if (draft.id != null) "Edit view" else "New view", color = T.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        FieldLabel("Name")
        AnInput(draft.name, { onDraft(draft.copy(name = it)) }, "e.g. Weekly completions", Modifier.fillMaxWidth(), tag = "an_view_name")
        FieldLabel("Data")
        Seg(AnalyticsCalc.V_SOURCES, { draft.source == it }, { onDraft(vm.draftSet(draft, "source", it)) })
        if (draft.source == "metric") {
            FieldLabel("Reps metric")
            Seg(d.metrics.map { it.name to it.id }, { draft.metricId == it }, { onDraft(vm.draftSet(draft, "metricId", it)) }) {
                val shape = RoundedCornerShape(8.dp)
                Box(Modifier.widthIn(min = 50.dp).height(36.dp).background(T.Panel, shape).border(1.dp, T.Line, shape).clickable { onEditMetric(null) }.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center) { Text("+ new metric", color = T.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
            if (draft.metricId != null) Box(Modifier.padding(top = 8.dp, bottom = 2.dp)) { AnMini("Edit this metric") { onEditMetric(draft.metricId) } }
            else if (d.metrics.isEmpty()) Text("No reps metrics yet — tap “+ new metric” to create one.", color = T.Muted, fontSize = 11.sp, lineHeight = 1.4.em, modifier = Modifier.padding(top = 8.dp))
        }
        if (draft.source !in AnalyticsCalc.V_SPECIAL) {
            FieldLabel("Group by")
            Seg(AnalyticsCalc.V_GROUPS, { draft.group == it }, { onDraft(vm.draftSet(draft, "group", it)) })
            FieldLabel("Chart")
            Seg(AnalyticsCalc.V_CHARTS, { draft.chart == it }, { onDraft(vm.draftSet(draft, "chart", it)) })
            if (draft.source != "metric") {
                FieldLabel("Only these types (optional)")
                Seg(AnalyticsCalc.V_TYPES, { it in draft.types }, { ty -> onDraft(draft.copy(types = if (ty in draft.types) draft.types - ty else draft.types + ty)) })
                if (d.tags.isNotEmpty()) {
                    FieldLabel("Only these tags (optional)")
                    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.tags.forEach { g -> TagChip(g.name, g.color, g.id in draft.tags) { onDraft(draft.copy(tags = if (g.id in draft.tags) draft.tags - g.id else draft.tags + g.id)) } }
                    }
                }
            }
        } else {
            Text("This view shows a fixed summary layout and ignores grouping, chart and filters.", color = T.Muted, fontSize = 11.sp, lineHeight = 1.4.em, modifier = Modifier.padding(top = 8.dp))
        }
        FieldLabel("Preview")
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 300.dp).background(T.Panel, RoundedCornerShape(8.dp)).border(1.dp, T.Line, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            val body = remember(draft, d, p, from, to) { AnalyticsCalc.renderView(draft.asView(), d, p, from, to, now, zone) }
            ViewBodyContent(body)
        }
        // phone width (< 480): two per row, 11sp, danger full-width last (index.html:1016-1036)
        FlowRow(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            if (draft.id != null) AnBtn("Clone", BtnKind.GHOST, Modifier.weight(1f), 11) { vm.cloneView(draft); onClose() }
            AnBtn("Cancel", BtnKind.GHOST, Modifier.weight(1f), 11) { onClose() }
            AnBtn("Save", BtnKind.PRIMARY, Modifier.weight(1f).testTag("an_view_save"), 11) { if (vm.saveView(draft)) onClose() }
            if (draft.id != null) AnBtn("Delete", BtnKind.DANGER, Modifier.fillMaxWidth(), 11) { vm.deleteView(draft.id); onClose() }
        }
    }
}

// ---- metric editor (Opti drawMetricEditor) --------------------------------------------------------------------------
@Composable
private fun MetricEditor(vm: AnalyticsViewModel, draft: MetricDraft, onDraft: (MetricDraft) -> Unit, tasks: List<TaskEntity>, onSave: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    AnCard(Modifier.fillMaxWidth().testTag("an_metric_editor")) {
        Column(Modifier.padding(2.dp)) {
            Text(if (draft.mid != null) "Edit metric" else "New metric", color = T.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            MLabel("Name", Modifier.padding(top = 10.dp))
            AnInput(draft.name, { onDraft(draft.copy(name = it)) }, "e.g. Zdvihy", Modifier.fillMaxWidth(), bg = InputBg, radius = 9.dp, tag = "an_metric_name")
            MLabel("How habits are matched", Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(InputBg).border(1.dp, T.Line, RoundedCornerShape(9.dp)).height(IntrinsicSize.Min)) {
                listOf(false to "By keyword", true to "Pick exact habits").forEachIndexed { i, (ex, label) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(T.Line))
                    val on = draft.exact == ex
                    Box(Modifier.weight(1f).background(if (on) T.Accent else Color.Transparent).clickable(role = Role.Tab) { if (draft.exact != ex) onDraft(draft.copy(exact = ex)) }.semantics { selected = on }.padding(horizontal = 8.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center) { Text(label, color = if (on) ChipOnText else T.Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold) }
                }
            }
            if (!draft.exact) {
                MLabel("Habit title contains", Modifier.padding(top = 10.dp))
                AnInput(draft.keyword, { onDraft(draft.copy(keyword = it)) }, "e.g. zdvih", Modifier.fillMaxWidth(), bg = InputBg, radius = 9.dp, tag = "an_metric_kw")
                MHint("Counts the number in each matching habit title × your + taps.")
            } else {
                MHint("Pick the habits to include. Each habit’s reps-per-tap defaults to the number in its title — set a value to override.")
                Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MLabel("Selected")
                    Text(draft.habits.size.toString(), color = T.Ink, fontSize = 11.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.background(T.Panel2, RoundedCornerShape(999.dp)).border(1.dp, T.Line, RoundedCornerShape(999.dp)).widthIn(min = 20.dp).padding(horizontal = 7.dp, vertical = 1.dp))
                }
                if (draft.habits.isEmpty()) MEmpty("No habits selected yet — add some below.")
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.habits.forEach { (hid, reps) ->
                        val t = tasks.firstOrNull { it.id == hid }
                        val titleNum = t?.let { (it.repsPerTap?.takeIf { n -> n != 0 } ?: AnalyticsCalc.repsPerTap(it.title)).toString() } ?: ""
                        Row(Modifier.fillMaxWidth().background(InputBg, RoundedCornerShape(10.dp)).border(1.dp, T.Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(22.dp).background(T.Panel, CircleShape).border(1.dp, T.Line, CircleShape).clickable(role = Role.Button) { onDraft(draft.copy(habits = draft.habits.filterNot { it.first == hid })) }.semantics { contentDescription = "Remove habit" },
                                contentAlignment = Alignment.Center) { Text("×", color = T.Muted, fontSize = 15.sp, lineHeight = 1.em) }
                            Text(t?.title ?: hid, color = T.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            AnInput(reps, { v -> onDraft(draft.copy(habits = draft.habits.map { if (it.first == hid) hid to v else it })) }, titleNum, Modifier.width(62.dp), number = true, textAlign = TextAlign.End)
                            Text("/tap", color = T.Muted, fontSize = 11.sp)
                        }
                    }
                }
                MLabel("Add habits", Modifier.padding(top = 12.dp, bottom = 6.dp))
                var filter by remember { mutableStateOf("") }
                AnInput(filter, { filter = it }, "Search habits…", Modifier.fillMaxWidth().padding(bottom = 6.dp), bg = InputBg, radius = 9.dp)
                val chosen = draft.habits.map { it.first }.toSet()
                val fl = filter.trim().lowercase(Locale.ROOT)
                val habits = tasks.filter { it.type == "habit" && it.id !in chosen && (fl.isEmpty() || it.title.lowercase(Locale.ROOT).contains(fl)) }
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).background(InputBg, RoundedCornerShape(10.dp)).border(1.dp, T.Line, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState())) {
                    if (habits.isEmpty()) MEmpty(if (fl.isNotEmpty()) "No matching habits." else "All habits added.")
                    habits.take(50).forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(T.Line))
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onDraft(draft.copy(habits = draft.habits + (t.id to ""))) }.padding(horizontal = 11.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.title, color = T.Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("+ add", color = T.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnBtn("Save", BtnKind.PRIMARY, Modifier.weight(1f).testTag("an_metric_save")) { onSave() }
                AnBtn("Cancel", BtnKind.GHOST, Modifier.weight(1f)) { onCancel() }
                if (draft.mid != null) AnBtn("Delete", BtnKind.DANGER, Modifier.weight(1f)) { onDelete() }
            }
        }
    }
}

@Composable
private fun MLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(Locale.ROOT), color = T.Muted, fontSize = 11.sp, letterSpacing = 0.04.em, modifier = modifier.padding(bottom = 5.dp))

@Composable
private fun MHint(text: String) = Text(text, color = T.Muted, fontSize = 11.sp, lineHeight = 1.4.em, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun MEmpty(text: String) = Text(text, color = T.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp))

// ---- detail dashboard (Opti anDetailDashboard) -----------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailDashboard(vm: AnalyticsViewModel, d: AnData, p: AnPrefs, from: Long, to: Long, now: Long, zone: ZoneId, inlineMetric: MetricDraft?, onInlineMetric: (MetricDraft?) -> Unit) {
    val locale = Locale.getDefault()
    val days = max(1L, AnalyticsCalc.jsRound((to - from).toDouble() / AnalyticsCalc.DAY))
    AnSection("Reps metric")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val active = AnalyticsCalc.activeMetric(d.metrics, p)
        d.metrics.forEach { m ->
            AnChip(m.name, m.id == (p.activeMetric ?: active?.id), onDoubleTap = { onInlineMetric(vm.metricDraft(m.id)) }) { onInlineMetric(null); vm.selectMetric(m.id) }
        }
        AnChip("+ add", false, dashed = true) { onInlineMetric(MetricDraft()) }
    }
    inlineMetric?.let { md ->
        MetricEditor(vm, md, { onInlineMetric(it) }, d.tasks,
            onSave = { if (vm.saveMetric(md)) onInlineMetric(null) }, onCancel = { onInlineMetric(null) },
            onDelete = { md.mid?.let { vm.deleteMetric(it) }; onInlineMetric(null) })
    }
    val m = AnalyticsCalc.activeMetric(d.metrics, p)
    val kw = if (m == null) "" else if (m.exact) m.name else m.keyword
    val mname = m?.name ?: "(no metric)"
    val model = remember(d, p, from, to) {
        object {
            val reps = AnalyticsCalc.cumulativeReps(d.events, m, from, to)
            val series = AnalyticsCalc.fillDays(AnalyticsCalc.repsSeries(d.events, m, from, to, zone), from, to, zone)
            val mile = AnalyticsCalc.milestones(d.events, m, now, zone)
            val bdMode = if (days > 120) "month" else "week"
            val bd = AnalyticsCalc.fillPeriods(AnalyticsCalc.breakdown(d.events, m, from, to, bdMode, zone), from, to, bdMode, zone)
            val yr = AnalyticsCalc.rollup(d.events, m, "year", now, zone)
            val totals = d.metrics.map { mm -> mm to AnalyticsCalc.cumulativeReps(d.events, mm, from, to) }
            val cmp = AnalyticsCalc.fillCompare(AnalyticsCalc.compareSeries(d.events, d.metrics, from, to, zone), from, to, zone)
            val adh = AnalyticsCalc.adherence(d.events, from, to, zone)
            val inten = AnalyticsCalc.intensity(d.events, from, to, zone)
            val streaks = AnalyticsCalc.streaks(d.tasks)
        }
    }
    val reps = model.reps
    AnSection("$mname — totals over time")
    var run = 0.0
    val cumTips = model.series.map { run += it.v; "📅 " + AnalyticsCalc.fmtDate(it.d, zone) + "\n∑ " + AnalyticsCalc.loc(run, locale) + " total" }
    AnCards(listOf(
        AnCardData("In window", AnalyticsCalc.loc(reps.total, locale), "${reps.taps} taps · ${days}d"),
        AnCardData("Per-day avg", AnalyticsCalc.loc(AnalyticsCalc.jsRound(reps.total / days).toDouble(), locale), "in this window"),
    )) { FullCard("Cumulative over window") { SparkChart(SeriesChart(model.series, true, "var(--accent)", 70, cumTips)) } }
    if (reps.matched > 0) AnNote("Matched habits: " + reps.byTask.entries.joinToString(", ") { it.key + " (" + AnalyticsCalc.jsNum(it.value) + ")" } + ".")
    else AnNote("No habits contain \"$kw\" in this window. Edit the metric (double-tap its chip) or pick a keyword that appears in a habit title.")
    if (reps.missingTapPts > 0) AnNote("Note: ${reps.missingTapPts} older imported point(s) predate Habitica logging individual + taps, so those reps are not counted. Newer activity is exact.")

    val mile = model.mile
    AnSection("$mname — lifetime milestones (since ${mile.firstDate?.let { AnalyticsCalc.fmtDate(it, zone) } ?: "—"})")
    AnCards(listOf(
        AnCardData("All-time reps", AnalyticsCalc.loc(mile.total, locale), "${mile.taps} taps"),
        AnCardData("Biggest day", AnalyticsCalc.loc(mile.biggestV, locale), mile.biggestDay?.let { AnalyticsCalc.fmtDate(it.d, zone) } ?: "—"),
        AnCardData("Activity days", AnalyticsCalc.loc(mile.activityDays.toDouble(), locale), "days you did it"),
        AnCardData("Exact-rep days", AnalyticsCalc.loc(mile.repDays.toDouble(), locale), "of ${mile.activityDays} active"),
    ))
    if (mile.activityDays > mile.repDays) AnNote("${mile.activityDays - mile.repDays} active day(s) have no exact tap count in the Habitica export (exact-only policy: they count as activity but contribute 0 reps, so totals are a verified floor, never estimated).")

    AnSection("$mname — reps per day")
    FullCard("Repetitions done each day") {
        SparkChart(SeriesChart(model.series, false, "var(--mp)", 60, model.series.map { "📅 " + AnalyticsCalc.fmtDate(it.d, zone) + "\n🔁 " + AnalyticsCalc.loc(it.v, locale) + " reps" }))
    }
    AnSection("$mname — by ${model.bdMode}")
    FullCard { BarsChart(model.bd.map { it.label }, model.bd.map { it.v }, T.Accent, locale) }

    AnSection("$mname — per-year totals")
    if (model.yr.isEmpty()) AnNote("No reps recorded yet.")
    else AnCards(model.yr.map { AnCardData(it.label, AnalyticsCalc.loc(it.v, locale), "reps") })

    AnSection("All metrics — comparison")
    FullCard("Cumulative (all metrics, this window)") { CompareChart(model.cmp, zone, locale) }
    if (model.totals.isNotEmpty()) AnCards(model.totals.mapIndexed { i, (mm, r) -> AnCardData(mm.name, AnalyticsCalc.loc(r.total, locale), "${r.taps} taps", AnalyticsCalc.METRIC_COLORS[i % AnalyticsCalc.METRIC_COLORS.size]) })

    val adh = model.adh
    val avgPct = if (adh.isNotEmpty()) AnalyticsCalc.jsRound(adh.sumOf { it.pct } / adh.size * 100) else 0L
    AnSection("Daily adherence")
    AnCards(listOf(
        AnCardData("Avg completion", "$avgPct%", "${adh.size} tracked days"),
        AnCardData("Days logged", adh.size.toString(), "with due dailies"),
    )) {
        FullCard("Completion % over window") {
            SparkChart(SeriesChart(adh.map { DayVal(it.d, it.pct * 100) }, false, "var(--green)", 70,
                adh.map { "📅 " + AnalyticsCalc.fmtDate(it.d, zone) + "\n✅ " + AnalyticsCalc.jsRound(it.pct * 100) + "% complete\n☑️ " + it.done + " of " + it.tot + " dailies" }))
        }
    }
    AnSection("Activity heatmap")
    val maxI = max(1.0, model.inten.values.maxOrNull() ?: 0.0)
    Heatmap(AnalyticsCalc.heatmapCells(from, to, model.inten, maxI, zone))
    HeatLegend()
    AnSection("Streak leaderboard")
    if (model.streaks.isNotEmpty()) model.streaks.take(12).forEach { (t, s) -> AnStreakRow(t, "🔥 $s") } else AnNote("No dailies yet.")
    AnNote("History reflects dated value/tap snapshots from Habitica (engagement events), not a per-calendar-day completion grid. Counts shown are from logged + taps and completion flags.")
    Lifecycle(d, from, to, zone)
}

/** Opti `anLifecycleHTML`. */
@Composable
private fun Lifecycle(d: AnData, from: Long, to: Long, zone: ZoneId) {
    fun inWin(ms: Long) = ms != 0L && ms >= from && ms <= to
    AnSection("🗂️ Items created & updated")
    val cards = listOf("habit" to "Habits", "daily" to "Dailies", "todo" to "To-Dos").map { (ty, label) ->
        val items = d.tasks.filter { it.type == ty }
        val c = items.count { inWin(AnalyticsCalc.createdMs(it)) }
        val u = items.count { val um = AnalyticsCalc.updatedMs(it); um != AnalyticsCalc.createdMs(it) && inWin(um) }
        AnCardData(label, items.size.toString(), "$c new · $u upd")
    } + d.rewards.let { rs ->
        val c = rs.count { inWin(AnalyticsCalc.createdMs(it)) }
        val u = rs.count { val um = AnalyticsCalc.updatedMs(it); um != AnalyticsCalc.createdMs(it) && inWin(um) }
        AnCardData("Rewards", rs.size.toString(), "$c new · $u upd")
    }
    AnCards(cards)
    data class It(val title: String, val type: String, val c: Long, val u: Long)
    val all = d.tasks.map { It(it.title, it.type, AnalyticsCalc.createdMs(it), AnalyticsCalc.updatedMs(it)) } +
        d.rewards.map { It(it.title, "reward", AnalyticsCalc.createdMs(it), AnalyticsCalc.updatedMs(it)) }
    val recentC = all.filter { it.c != 0L }.sortedByDescending { it.c }.take(8)
    val recentU = all.filter { it.u != 0L && it.u != it.c }.sortedByDescending { it.u }.take(8)
    fun icon(ty: String) = when (ty) { "habit" -> "🔁"; "daily" -> "📅"; "todo" -> "✅"; "reward" -> "🎁"; else -> "•" }
    AnSection("Recently created")
    if (recentC.isEmpty()) AnNote("No dated items yet.") else recentC.forEach { AnDateRow(icon(it.type) + " " + it.title, AnalyticsCalc.fmtDate(it.c, zone)) }
    AnSection("Recently updated")
    if (recentU.isEmpty()) AnNote("No edits recorded yet (updates are tracked from now on).") else recentU.forEach { AnDateRow(icon(it.type) + " " + it.title, AnalyticsCalc.fmtDate(it.u, zone)) }
    AnNote("“Updated” = you opened an item and saved an edit. The new/upd counts respect the date range above; the recent lists are all-time.")
}

@Composable
private fun AnDateRow(title: String, right: String) = AnStreakRow(title, right)

// ---- collapsible `<details class="anDetails">` -----------------------------------------------------------------------
@Composable
private fun AnDetails(summary: String, open: Boolean, onToggle: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.Line))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 4.dp, vertical = 8.dp).testTag(tag)) {
            Text(if (open) "▾ " else "▸ ", color = T.Accent, fontSize = 13.sp)
            Text(summary, color = T.Muted, fontSize = 13.sp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 6.dp), content = content)
    }
}

// ---- the tab -------------------------------------------------------------------------------------------------------
/** WP-8.20 (PX-110): the Analytics tab entry point (Opti `viewAnalytics` + `refreshAnalytics`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(vm: AnalyticsViewModel, onModalChange: (key: String, open: Boolean) -> Unit = { _, _ -> },
    /** CR-KT-012: "See activity for this period →" with this window as an Activity range. */
    onSeeActivity: (com.example.ui.activity.ActivityRange) -> Unit = {},
) {
    val d by vm.data.collectAsStateWithLifecycle()
    val p by vm.prefs.collectAsStateWithLifecycle()
    val msg by vm.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = vm.zone()
    val now = remember(d, p) { vm.nowMs() }
    LaunchedEffect(Unit) { vm.reloadMetrics() }
    LaunchedEffect(d.events) { vm.normalize() }
    LaunchedEffect(msg) { msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeMessage() } }

    // live slider range while dragging (Opti repositions on move, re-renders on release)
    var live by remember { mutableStateOf<AnPrefs?>(null) }
    val shown = live ?: p
    val (from, to) = remember(d, p) { vm.window(p, d) }
    val (feedFrom, feedTo) = remember(d, p) { vm.window(p, d, noFloor = true) }
    val geom = remember(d, now) { vm.sliderGeom(d) }
    val (lFrom, lTo) = vm.window(shown, d)

    var draft by remember { mutableStateOf<ViewDraft?>(null) }
    var builderMetric by remember { mutableStateOf<MetricDraft?>(null) }
    var inlineMetric by remember { mutableStateOf<MetricDraft?>(null) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    // WP-8.I (W7, 8.16 hand-off): the view builder is Opti's #scrim (app.js:4406), so it is a modal.
    ReportModal(ModalKeys.VIEW_BUILDER, draft != null, onModalChange)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportViewsJson().toByteArray()) } }
                .onSuccess { Toast.makeText(context, "Views exported (${vm.viewsCount()})", Toast.LENGTH_SHORT).show() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            val r = text?.let { vm.importViewsJson(it) }
            if (r == null) importError = true
            else Toast.makeText(context, "Imported ${r.first} view" + (if (r.first == 1) "" else "s") + (if (r.second > 0) " · ${r.second} skipped (max 20)" else ""), Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalTip provides remember { TipState() }) {
        Column(Modifier.fillMaxSize().testTag("analytics_screen")) {
            // .anStickyHeader
            Column(Modifier.fillMaxWidth().shadow(16.dp).background(OptiSelectorTokens.PageBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📊 Analytics", color = T.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.swipeNavExclusion(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        AnalyticsCalc.SNAP_CHIPS.forEach { (label, snap) -> AnChip(label, shown.snap == snap) { live = null; vm.selectSnap(snap) } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AnalyticsCalc.fmtDate(lFrom, zone) + " → " + AnalyticsCalc.fmtDate(lTo, zone), color = T.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = 1.2.em,
                        modifier = Modifier.background(Color(0x0DFFD200), RoundedCornerShape(6.dp)).border(1.dp, Color(0x26FFD200), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("an_range_lbl"))
                    RangeSlider(IntRange(shown.toOff ?: 0, shown.fromOff ?: geom.totalDays), geom,
                        onChange = { r -> live = shown.copy(fromOff = r.last, toOff = r.first, snap = null) },
                        onCommit = { live?.let { vm.commitRange(it) }; live = null }, modifier = Modifier.weight(1f))
                }
            }
            val tip = LocalTip.current
            LazyColumn(Modifier.fillMaxSize().testTag("an_list").pointerInput(Unit) { detectTapGestures { tip.owner = null } }, contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 30.dp)) {
                item {
                    ViewsSection(vm, d, p, from, to, now, zone,
                        onExport = { exportLauncher.launch("questa-views.json") },
                        onImport = { importLauncher.launch(arrayOf("application/json")) },
                        onNew = { vm.newViewDraft()?.let { draft = it } },
                        onEdit = { id -> vm.editDraft(id)?.let { draft = it } })
                }
                item {
                    AnDetails("🔎 Full activity detail (reps, adherence, streaks, event log)", detailsOpen, { detailsOpen = !detailsOpen }, "an_details") {
                        DetailDashboard(vm, d, p, from, to, now, zone, inlineMetric) { inlineMetric = it }
                    }
                }
                item {
                    // CR-KT-012: the event feed moved to the Activity tab; this row opens it on this window.
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(T.Line))
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(role = Role.Button) { onSeeActivity(com.example.ui.activity.analyticsActivityRange(p.snap, p.toOff, feedFrom, feedTo)) }
                            .padding(horizontal = 4.dp, vertical = 8.dp).testTag("an_see_activity")) {
                            Text("📋 ", color = T.Accent, fontSize = 13.sp)
                            Text("See activity for this period →", color = T.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        draft?.let { dr ->
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { draft = null; builderMetric = null }, sheetState = sheet, containerColor = T.Bg, contentColor = T.Ink,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), scrimColor = Color(0xA80A0514), dragHandle = null) {
                val bm = builderMetric
                if (bm != null) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        MetricEditor(vm, bm, { builderMetric = it }, d.tasks,
                            onSave = { if (vm.saveMetric(bm, makeActive = false) { id -> draft = draft?.copy(source = "metric", metricId = id, metricKey = true) }) builderMetric = null },
                            onCancel = { builderMetric = null },
                            onDelete = {
                                bm.mid?.let { mid -> vm.deleteMetric(mid) { first -> if (draft?.metricId == mid) draft = draft?.copy(metricId = first) } }
                                builderMetric = null
                            })
                    }
                } else {
                    ViewBuilder(vm, dr, { draft = it }, d, p, from, to, now, zone, onEditMetric = { mid -> builderMetric = vm.metricDraft(mid) }, onClose = { draft = null })
                }
            }
        }
        if (importError) {
            AlertDialog(onDismissRequest = { importError = false }, containerColor = T.Bg,
                title = { Text("Error", color = T.Ink) }, text = { Text("That file does not look like Questa views.", color = T.Ink) },
                confirmButton = { TextButton(onClick = { importError = false }) { Text("OK", color = T.Accent) } })
        }
    }
}
