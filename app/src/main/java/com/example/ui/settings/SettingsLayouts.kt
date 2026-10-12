package com.example.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * WP-8.14-R1: the CSS flexbox rules Opti's Settings rows rely on (`display:flex; flex-wrap:wrap;
 * gap; flex: 1 1 <basis>; align-items: stretch`), as a pure line solver.
 *
 * Each item starts at `max(basis, min)`, where `min` is the item's min-content width (for a
 * button: its longest word plus padding). Items go on a line while they fit; a line's free space
 * is shared equally (`flex-grow: 1`). Without wrap, an overflowing line shrinks the items that are
 * above their `min`, never below it. So a label always gets at least its longest word: no word
 * is ever broken inside.
 */
internal object FlexWrap {
    data class Slot(val index: Int, val width: Int)

    fun lines(avail: Int, mins: List<Int>, basisPx: Int, gap: Int, wrap: Boolean): List<List<Slot>> {
        if (mins.isEmpty()) return emptyList()
        val base = mins.map { maxOf(it, basisPx) }
        val out = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        var used = 0
        for (i in base.indices) {
            val need = if (cur.isEmpty()) base[i] else used + gap + base[i]
            if (wrap && cur.isNotEmpty() && need > avail) {
                out += cur
                cur = mutableListOf(i)
                used = base[i]
            } else {
                cur += i
                used = need
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out.map { distribute(it, base, mins, avail, gap) }
    }

    private fun distribute(idx: List<Int>, base: List<Int>, mins: List<Int>, avail: Int, gap: Int): List<Slot> {
        val free = avail - gap * (idx.size - 1)
        val w = IntArray(idx.size) { base[idx[it]] }
        val extra = free - w.sum()
        if (extra >= 0) {
            val each = extra / idx.size
            var rem = extra - each * idx.size
            for (k in w.indices) {
                w[k] += each + if (rem > 0) 1 else 0
                if (rem > 0) rem--
            }
        } else {
            // flex-shrink, floored at each item's min-content width.
            var over = -extra
            val slack = IntArray(idx.size) { w[it] - mins[idx[it]] }
            val total = slack.sum()
            if (total > 0) {
                val cut = minOf(over, total)
                for (k in w.indices) {
                    val c = (cut.toLong() * slack[k] / total).toInt()
                    w[k] -= c
                    slack[k] -= c
                    over -= c
                }
                var k = 0
                while (over > 0 && slack.any { it > 0 }) {
                    if (slack[k] > 0) { w[k]--; slack[k]--; over-- }
                    k = (k + 1) % w.size
                }
            }
            if (over > 0) {
                // The mins alone do not fit (cannot happen in the Settings rows at 360+ dp): scale.
                val sum = w.sum().coerceAtLeast(1)
                for (k in w.indices) w[k] = (w[k].toLong() * free / sum).toInt()
            }
        }
        return idx.mapIndexed { k, i -> Slot(i, w[k]) }
    }
}

private fun Measurable.minContentWidth(): Int = minIntrinsicWidth(Constraints.Infinity)

/**
 * A wrapping flex row (Opti `.settingsRow` / `.catBtnRow` / `.seg`). [basis] is the CSS
 * flex-basis as a share of the row (0.5 = `calc(50% - gap/2)`, 0 = `flex: 1`), [minItemWidth] the
 * CSS `min-width`. Every item on one line gets that line's height (`align-items: stretch`).
 * [minWidthOverride] replaces an item's min-content width (an input that may shrink).
 */
@Composable
internal fun OptiFlexRow(
    modifier: Modifier = Modifier,
    basis: Float = 0f,
    gap: Dp = 8.dp,
    rowGap: Dp = gap,
    minItemWidth: Dp = 0.dp,
    wrap: Boolean = true,
    minWidthOverride: Map<Int, Dp> = emptyMap(),
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val avail = if (constraints.hasBoundedWidth) constraints.maxWidth else measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val gapPx = gap.roundToPx()
        val rowGapPx = rowGap.roundToPx()
        val minPx = minItemWidth.roundToPx()
        val mins = measurables.mapIndexed { i, m -> minWidthOverride[i]?.roundToPx() ?: maxOf(minPx, m.minContentWidth()) }
        val basisPx = if (basis <= 0f) 0 else ((avail + gapPx) * basis).toInt() - gapPx
        val lines = FlexWrap.lines(avail, mins, basisPx, gapPx, wrap)
        val placed = lines.map { line ->
            val h = line.maxOf { measurables[it.index].maxIntrinsicHeight(it.width) }
            line.map { s -> s to measurables[s.index].measure(Constraints.fixed(s.width, h)) } to h
        }
        val total = placed.sumOf { it.second } + rowGapPx * (placed.size - 1).coerceAtLeast(0)
        layout(avail, total.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            placed.forEach { (row, h) ->
                var x = 0
                row.forEach { (s, p) ->
                    p.place(x, y)
                    x += s.width + gapPx
                }
                y += h + rowGapPx
            }
        }
    }
}

/**
 * Opti's Sync block (`.devNameWrap`, app.js:6536-6553, index.html:555-564), children in order:
 * the sync status (left), the "Device name" label (centred), the "Device xxxxxx" label (centred
 * over Disconnect), then the row: Sync now, the device-name field, Disconnect.
 *
 * The header is Opti's grid `minmax(0,1fr) auto minmax(0,1fr)`; the row is `display:flex; gap:8px;
 * align-items:stretch` with each item `flex: 1 1 33%` (no wrap; the field may shrink, a button
 * keeps its label's width). The device label sits 2 dp above Disconnect (Opti `bottom: calc(100% + 2px)`).
 */
@Composable
internal fun DeviceNameLayout(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    headerGap: Dp = 6.dp,
    fieldMinWidth: Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { m, constraints ->
        require(m.size == 6) { "DeviceNameLayout needs 6 children" }
        val w = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val mins = listOf(m[3].minContentWidth(), fieldMinWidth.roundToPx(), m[5].minContentWidth())
        val basisPx = ((w + gapPx) / 3f).toInt() - gapPx
        val row = FlexWrap.lines(w, mins, basisPx, gapPx, wrap = false).single()
        val rowH = row.maxOf { m[it.index + 3].maxIntrinsicHeight(it.width) }
        val btns = row.map { m[it.index + 3].measure(Constraints.fixed(it.width, rowH)) }

        val loose = Constraints(maxWidth = w)
        val nameLbl = m[1].measure(loose)
        val devLbl = m[2].measure(Constraints(maxWidth = row[2].width + gapPx))
        val statusW = ((w - nameLbl.width) / 2 - 4.dp.roundToPx()).coerceAtLeast(0)
        val status = m[0].measure(Constraints(maxWidth = statusW))
        val headerH = maxOf(status.height, nameLbl.height, devLbl.height)
        val rowY = headerH + headerGap.roundToPx()
        layout(w, rowY + rowH) {
            status.place(0, 0)
            nameLbl.place((w - nameLbl.width) / 2, 0)
            val discX = row[0].width + row[1].width + 2 * gapPx
            devLbl.place(discX + (row[2].width - devLbl.width) / 2, rowY - 2.dp.roundToPx() - devLbl.height)
            var x = 0
            btns.forEachIndexed { k, p ->
                p.place(x, rowY)
                x += row[k].width + gapPx
            }
        }
    }
}
