package com.example.ui.gestures

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OptiRootTokens
import com.example.ui.visuals.OptiDims
import kotlin.math.roundToInt
import org.burnoutcrew.reorderable.ReorderableState

/**
 * CR-KT-011 (Kotlin-only, UI): where a group drag lands. [order] is the list at the drop (the
 * dragged card at its drop slot, every other card at its old slot), [before] the order at pickup.
 * The other selected ids leave their slots and the whole selection takes the dragged card's slot,
 * in its [before] order. Ids the list does not hold are ignored; with only [dragged] selected the
 * result is [order].
 */
internal fun groupDrop(order: List<String>, dragged: String, selected: Set<String>, before: List<String>): List<String> {
    if (dragged !in order) return order
    val members = order.filterTo(HashSet()) { it == dragged || it in selected }
    if (members.size <= 1) return order
    val rank = before.withIndex().associate { (i, id) -> id to i }
    val block = order.filter { it in members }.sortedBy { rank[it] ?: Int.MAX_VALUE }
    return order.flatMap { id ->
        when {
            id == dragged -> block
            id in members -> emptyList()
            else -> listOf(id)
        }
    }
}

/**
 * One list's group drag. [lift] at the pickup of a selected card records the riders (the other
 * selected cards) and the order; [drop] regroups the rows. The riders stay in the list during the
 * drag (the library follows the dragged row by index, see [reorderMove]) and render collapsed.
 */
@Stable
internal class GroupDrag {
    var dragged by mutableStateOf<String?>(null)
        private set
    var riders by mutableStateOf<Set<String>>(emptySet())
        private set
    private var before: List<String> = emptyList()

    fun lift(id: String, selected: Set<String>, order: List<String>) {
        dragged = id
        riders = order.filterTo(HashSet()) { it != id && it in selected }
        before = order
    }

    /** [rows] in the [groupDrop] order; the session ends. */
    fun <T> drop(rows: List<T>, id: (T) -> String): List<T> {
        val d = dragged
        val out = if (d == null || riders.isEmpty()) rows else {
            val byId = rows.associateBy(id)
            groupDrop(rows.map(id), d, riders + d, before).mapNotNull(byId::get)
        }
        cancel()
        return out
    }

    /** Ends the session without a drop; the riders unfold in place. */
    fun cancel() {
        dragged = null
        riders = emptySet()
        before = emptyList()
    }

    /** Index of the first row that takes space, for the card gap (a collapsed rider has none). */
    fun <T> firstShown(rows: List<T>, id: (T) -> String): Int = rows.indexOfFirst { id(it) !in riders }
}

/**
 * Ends a lifted group the library never started: [ReorderStarter] only sends a `StartDrag`, and
 * when the library drops it (or the finger is already up) no `onDragEnd` follows. If no row is
 * dragging a frame after the lift, the session is cancelled so the riders unfold.
 */
@Composable
internal fun GroupDragWatch(group: GroupDrag, state: ReorderableState<*>) {
    val lifted = group.dragged
    LaunchedEffect(lifted) {
        if (lifted == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (state.draggingItemKey == null && group.dragged == lifted) group.cancel()
    }
}

private const val COLLAPSE_MS = 150

/**
 * A rider's slot: it folds to zero height (and draws nothing) while it rides with the lifted
 * card, and unfolds at the drop. The card stays composed, so its own state survives the drag.
 * With no group lifted ([active] false) a row that is not collapsed takes the plain path: no
 * animation state per row. (So a rider snaps open at the drop instead of unfolding.)
 */
internal fun Modifier.groupCollapse(collapsed: Boolean, active: Boolean): Modifier =
    if (!collapsed && !active) this else this.groupCollapseAnimated(collapsed)

private fun Modifier.groupCollapseAnimated(collapsed: Boolean): Modifier = composed {
    val f by animateFloatAsState(if (collapsed) 0f else 1f, tween(COLLAPSE_MS), label = "groupCollapse")
    if (f >= 1f) Modifier else Modifier.clipToBounds().layout { m, c ->
        val p = m.measure(c)
        val h = (p.height * f).roundToInt()
        layout(p.width, h) { if (h > 0) p.place(0, 0) }
    }
}

/**
 * The card gap (`margin-bottom:6px`, OptiDims.CARD_GAP) as padding above every shown card but the
 * first, so a collapsed rider leaves no gap behind (a list `spacedBy` would keep one per item).
 */
internal fun Modifier.cardGapAbove(show: Boolean): Modifier =
    if (show) this.padding(top = OptiDims.CARD_GAP.dp) else this

/** The stacked look of a lifted card that carries [extra] riders: up to two offset card edges. */
internal fun Modifier.groupStack(extra: Int): Modifier = if (extra <= 0) this else this.drawBehind {
    val step = 4.dp.toPx()
    val r = CornerRadius(OptiDims.CARD_RADIUS.dp.toPx())
    for (i in minOf(extra, 2) downTo 1) {
        drawRoundRect(
            color = OptiRootTokens.Accent.copy(alpha = 0.45f / i),
            topLeft = Offset(step * i, step * i),
            size = size,
            cornerRadius = r,
        )
    }
}

/** The "+N" chip on a lifted card that carries [extra] riders (tag `group_badge_<id>`). */
@Composable
internal fun GroupBadge(extra: Int, taskId: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(4.dp)
            .background(OptiRootTokens.Accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            "+$extra", color = Color(0xFF1A1030), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("group_badge_$taskId"),
        )
    }
}
