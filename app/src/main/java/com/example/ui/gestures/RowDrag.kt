package com.example.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Opti's in-card subtask row (`.subitem`, app.js:3098 and `enableSubTouchDrag`, :5395-5460):
 * - a tap toggles the subtask (`onclick="toggleSub(...)"`);
 * - a hold of [delayMs] (`longPressMs()` = 200) without an 8 px move lifts the row (`buzz(15)`,
 *   :5484), then every move reports the finger's y (in this row's coordinates) to [onDragTo], and
 *   the lift drops it ([onDrop], Opti `commitSubOrder`);
 * - a move first is a scroll.
 * The row owns its touch (it consumes the down), so the card never lifts from a subtask
 * (`isSubtaskTouch`, :5211). A press on the row's check box (its own [optiPress]) never lifts the
 * row (`if (e.target.closest('.subbox')) return`, :5397).
 */
fun Modifier.optiRowDrag(
    delayMs: Long = GestureSpec.DRAG_PICKUP_MS,
    slopDp: Float = GestureSpec.SUBTASK_MOVE_CANCEL_DP,
    onTap: () -> Unit,
    onPickup: () -> Unit,
    onDragTo: (y: Float) -> Unit,
    onDrop: () -> Unit,
): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val pickup by rememberUpdatedState(onPickup)
    val dragTo by rememberUpdatedState(onDragTo)
    val drop by rememberUpdatedState(onDrop)
    Modifier
        .semantics { onClick(null) { tap(); true } }
        .pointerInput(delayMs, slopDp) {
            val slop = slopDp.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                down.consume()
                val (end, last) = awaitPressEnd(down, slop, delayMs)
                when (end) {
                    PressEnd.TAP -> { last?.consume(); tap() }
                    PressEnd.CANCEL -> Unit
                    PressEnd.TIMEOUT -> {
                        pickup()
                        // Review 35: a lifted row that never moved past the slop and lifts before
                        // the platform long-press time still toggles, after the drop (Chrome fires
                        // the click after commitSubOrder's touchend).
                        var moved = false
                        var upAt = -1L
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) { upAt = c.uptimeMillis; break }
                                val d = c.position - down.position
                                if (abs(d.x) > slop || abs(d.y) > slop) moved = true
                                dragTo(c.position.y)
                            }
                        } finally {
                            drop()
                        }
                        if (upAt >= 0 && !moved && upAt - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) tap()
                    }
                }
            }
        }
}

/**
 * Opti `moveTouchDrag` for a list of rows (app.js:5423-5429): the row under the finger at [y]
 * (in the list's coordinates) takes the dragged row before it, or after it past its middle.
 * [rows] are (id, top, height) in list order. Returns the new order, or null when nothing moves.
 */
fun reorderUnder(order: List<String>, dragged: String, y: Float, rows: List<Triple<String, Float, Float>>): List<String>? {
    val over = rows.firstOrNull { (id, top, h) -> id != dragged && y >= top && y < top + h } ?: return null
    val (overId, top, h) = over
    val after = (y - top) / h > 0.5f
    val without = order.filter { it != dragged }.toMutableList()
    val at = without.indexOf(overId)
    if (at < 0) return null
    without.add(if (after) at + 1 else at, dragged)
    return if (without == order) null else without
}
