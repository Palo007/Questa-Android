package com.example.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Test hook: the alpha layer 2 of the boot gate paints a task card with (1f = not gated). */
val GateAlphaKey = SemanticsPropertyKey<Float>("QuestaGateAlpha")
var SemanticsPropertyReceiver.gateAlpha by GateAlphaKey

/** How a press on a control ended, before any action runs. */
internal enum class PressEnd { TAP, CANCEL, TIMEOUT }

/**
 * Waits for [down]'s pointer to lift, to move more than [slopPx] on either axis (Opti's
 * `Math.abs(dx) > 10 || Math.abs(dy) > 10`), or for [timeoutMs] to pass. A lift that another
 * handler already consumed is a cancel.
 */
internal suspend fun AwaitPointerEventScope.awaitPressEnd(
    down: PointerInputChange,
    slopPx: Float,
    timeoutMs: Long,
): Pair<PressEnd, PointerInputChange?> {
    var last: PointerInputChange? = null
    val end = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull PressEnd.CANCEL
            last = c
            if (!c.pressed) return@withTimeoutOrNull if (c.isConsumed) PressEnd.CANCEL else PressEnd.TAP
            val d = c.position - down.position
            if (abs(d.x) > slopPx || abs(d.y) > slopPx) return@withTimeoutOrNull PressEnd.CANCEL
        }
        @Suppress("UNREACHABLE_CODE") PressEnd.CANCEL
    }
    return (end ?: PressEnd.TIMEOUT) to last
}

/**
 * A card control that owns its own touch: Opti's `.check` (the daily/todo check, the habit
 * `.hbtn` +/- buttons, the subtask box, the n/m badge). It consumes the down, so the card
 * long-press never starts from it (`skipCardTimer`, app.js:5210-5216).
 *
 * - [onLongPress] set (the habit +/- buttons, app.js:8524-8561): a hold of [longPressMs] without a
 *   10 px move calls it WHILE the finger is still down, and the lift that follows is swallowed
 *   (`_suppressHabitClick`, :8540 and :2466), so a long-press never also scores.
 * - [onLongPress] null: a lift before the platform long-press time (the user's "Touch & hold
 *   delay", `ViewConfiguration.longPressTimeoutMillis`) within 10 px is a tap.
 * - [enabled] false (Opti `.hbtn.off`): consumes the down and does nothing else.
 *
 * Review 35: the same actions are exposed to accessibility services (click, long click), so
 * TalkBack reaches the same callbacks.
 */
fun Modifier.optiPress(
    enabled: Boolean = true,
    longPressMs: Long = GestureSpec.REP_LONGPRESS_MS,
    label: String? = null,
    longPressLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    Modifier
        .semantics {
            role = Role.Button
            if (!enabled) disabled()
            onClick(label) { if (enabled) tap(); enabled }
            if (onLongPress != null) onLongClick(longPressLabel) { if (enabled) long?.invoke(); enabled }
        }
        .pointerInput(enabled, longPressMs, onLongPress == null) {
            val slop = GestureSpec.MOVE_CANCEL_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                down.consume()
                if (!enabled) return@awaitEachGesture
                val timeout = if (onLongPress != null) longPressMs else viewConfiguration.longPressTimeoutMillis
                val (end, last) = awaitPressEnd(down, slop, timeout)
                when (end) {
                    PressEnd.TAP -> { last?.consume(); tap() }
                    PressEnd.CANCEL -> Unit
                    PressEnd.TIMEOUT -> if (onLongPress != null) {
                        long?.invoke()
                        // Swallow the rest of the gesture, including the trailing lift.
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                }
            }
        }
}

/**
 * Opti's `.body onclick="openEdit(...)"`. It does NOT consume the down (the card pickup, a
 * parent, must still see it). A lift before the platform long-press time within 10 px is a tap,
 * also after a 200 ms card lift that did not move: Chrome fires the click then too, because the
 * page never cancels the touch (review 35, orchestrator ruling). The lifted card drops in place.
 *
 * CR-KT-011: with [skipAfterLift] (the selection-mode toggle) a press during which a card lifted
 * ([DragActivity.pickups] changed, as SwipeTabs reads it) is no tap, so a group lift released in
 * place keeps its selection. The non-selection path (tap -> editor after a still lift) is unchanged.
 */
fun Modifier.optiTap(label: String? = null, skipAfterLift: Boolean = false, onTap: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val drag = LocalDragActivity.current
    Modifier
        .semantics { onClick(label) { tap(); true } }
        .pointerInput(skipAfterLift, drag) {
            val slop = GestureSpec.MOVE_CANCEL_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val pickups = drag.pickups
                val (end, last) = awaitPressEnd(down, slop, viewConfiguration.longPressTimeoutMillis)
                if (end == PressEnd.TAP) {
                    last?.consume()
                    if (!skipAfterLift || drag.pickups == pickups) tap()
                }
            }
        }
}

/**
 * Layer 2 of the boot input gate (§5.0 B13; Opti `body.bootSyncing .task{pointer-events:none;
 * opacity:.55}`, index.html:141, toggled at app.js:5107). While [gated], the card paints at 0.55
 * and no press on it reaches a card handler (tap, long-press, drag pickup): the down is consumed on
 * the Initial pass, before any child sees it. Moves are left alone, so the list still scrolls, as
 * Opti's list does under a `pointer-events:none` card.
 */
fun Modifier.gatedInput(gated: Boolean): Modifier =
    if (!gated) this.semantics { gateAlpha = 1f }
    else this
        .semantics { gateAlpha = GestureSpec.GATED_CARD_ALPHA }
        .alpha(GestureSpec.GATED_CARD_ALPHA)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { if (it.changedToDownIgnoreConsumed()) it.consume() }
                }
            }
        }
