package com.example.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import androidx.compose.foundation.lazy.LazyListState
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableState

/**
 * Whether a card or subtask drag is live. The swipe detector reads it: Opti's swipe `touchend`
 * returns when `_tActive` is set (app.js:8493), so a gesture that lifted a card is never a swipe.
 */
class DragActivity {
    var active by mutableStateOf(false)
    /** Bumped at each pickup, so a swipe can tell a drag started during ITS gesture. */
    var pickups by mutableStateOf(0)
        private set
    fun pickedUp() { active = true; pickups++ }
    fun dropped() { active = false }
}

val LocalDragActivity = compositionLocalOf { DragActivity() }

/**
 * Starts a burnoutcrew drag for a pointer. The library only ships `detectReorder` (immediate) and
 * `detectReorderAfterLongPress` (the platform long-press, ~400-500 ms); Opti picks a card up at
 * `longPressMs()` = 200 ms (app.js:5223). The library's own detectors send a `StartDrag` on the
 * state's module-internal `interactions` channel, so this sends the same message.
 */
/**
 * The burnoutcrew `onMove` step for a list whose draggable rows start at list index [headerCount].
 * Returns the moved copy of [rows], or null for a move outside the rows.
 *
 * - It uses the library's INDEXES, not its keys: the library follows the dragged row by index,
 *   and for a move that touches the first visible row it runs `onMove` one frame late. In that
 *   frame the key it reports belongs to the row that WAS at that index, so a key lookup moves the
 *   wrong card (seen on the phone: dragging a card to the top pushed the top card down instead).
 * - When the first visible row takes part, it pins the scroll: Compose keeps the first visible
 *   item's KEY in view, so without the pin the list follows the moved card and jumps.
 */
internal fun <T> reorderMove(
    rows: List<T>, from: ItemPosition, to: ItemPosition, listState: LazyListState, headerCount: Int = 0,
): List<T>? {
    val f = from.index - headerCount
    val t = to.index - headerCount
    if (f !in rows.indices || t !in rows.indices || f == t) return null
    val first = listState.firstVisibleItemIndex
    if (from.index == first || to.index == first) {
        listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
    }
    return rows.toMutableList().apply { add(t, removeAt(f)) }
}

/**
 * Keeps a reorderable list's working copy in step with [source] (the DB rows), but not while a card
 * is lifted. Each drop starts a sync ~5 s later (SyncScheduler debounce) whose merge rewrites the
 * task rows; a reset of the working copy under the finger made the card jump back and forth, and
 * the real order showed only at the drop. A change that lands during a drag applies after the drop.
 */
@Composable
internal fun <T> FollowWhenNotDragging(source: T, state: ReorderableState<*>, apply: (T) -> Unit) {
    val seen = remember { arrayOf<Any?>(source) }
    if (state.draggingItemKey == null && seen[0] != source) {
        seen[0] = source
        apply(source)
    }
}

internal object ReorderStarter {
    private val interactions by lazy {
        ReorderableState::class.java.getMethod("getInteractions\$reorderable")
    }
    private val startDragCtor by lazy {
        Class.forName("org.burnoutcrew.reorderable.StartDrag")
            .declaredConstructors.first { it.parameterTypes.size == 4 }.apply { isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    fun start(state: ReorderableState<*>, id: PointerId): Boolean = try {
        val ch = interactions.invoke(state) as Channel<Any>
        // StartDrag(id: PointerId, offset: Offset? = null): the synthetic default constructor.
        val msg = startDragCtor.newInstance(id.value, null, 2, null)
        ch.trySend(msg).isSuccess
    } catch (e: Exception) {
        false
    }
}

/**
 * Opti's card long-press (`enableTouchDrag`, app.js:5207-5263): the card lifts after [delayMs] if
 * the finger has not moved more than 10 px on either axis (a move first = a scroll,
 * :5242-5243). A press that begins on a card control (a consumed down: `.check`, `.hbtn`,
 * `.subFrac`, `.subitem`, and a card under the boot gate) never lifts the card (`skipCardTimer`,
 * :5210-5216). [onPickup] runs at the lift (Opti `buzz(15)`, :5285).
 *
 * Only for a card that Opti marks `draggable="true"`: manual sort (`dragOK`, app.js:3336).
 */
fun Modifier.optiDragPickup(
    state: ReorderableState<*>,
    enabled: Boolean,
    delayMs: Long = GestureSpec.DRAG_PICKUP_MS,
    slopDp: Float = GestureSpec.MOVE_CANCEL_DP,
    onPickup: () -> Unit = {},
): Modifier = if (!enabled) this else composed {
    val pickup by rememberUpdatedState(onPickup)
    Modifier.pointerInput(state, delayMs, slopDp) {
        val slop = slopDp.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val (end, _) = awaitPressEnd(down, slop, delayMs)
            if (end == PressEnd.TIMEOUT && ReorderStarter.start(state, down.id)) pickup()
        }
    }
}
