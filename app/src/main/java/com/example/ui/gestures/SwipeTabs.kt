package com.example.ui.gestures

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/**
 * The areas that own their own horizontal gesture, where a swipe never navigates (Opti
 * `inHGesture`: `#anSlider, input[type=range], .anSlider, .seg, .anHeat`, app.js:8475).
 */
class SwipeExclusions {
    internal val rects = mutableStateMapOf<Any, Rect>()
    fun contains(windowPos: Offset): Boolean = rects.values.any { it.contains(windowPos) }
}

val LocalSwipeExclusions = compositionLocalOf { SwipeExclusions() }

/** Marks this element as a horizontal-gesture owner (a slider, a segmented control, a heatmap). */
fun Modifier.swipeNavExclusion(): Modifier = composed {
    val ex = LocalSwipeExclusions.current
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(ex, key) { onDispose { ex.rects.remove(key) } }
    Modifier.onGloballyPositioned { ex.rects[key] = it.boundsInWindow() }
}

/**
 * Opti's swipe navigation (app.js:8469-8511): passive, it never consumes. It watches the whole
 * gesture on the Initial pass and decides at the lift, only for a one-finger gesture that did not
 * start while [blocked] (a sheet, Settings or RYA is open: `.scrim.show, .optScrim.show,
 * .yScrim.show`), did not start in a [SwipeExclusions] area, and did not lift a card
 * ([DragActivity]). Then [GestureSpec.swipeTarget] decides and [onSwipe] gets the target tab and
 * the slide direction (+1 = next).
 *
 * A child that consumes a horizontal move before any vertical travel past the touch slop owns a
 * horizontal gesture of its own (a slider, a horizontal list) and ends the tracking too, so an
 * unmarked horizontal control still never navigates.
 */
fun Modifier.swipeTabs(
    currentTab: () -> Int,
    blocked: () -> Boolean,
    onSwipe: (tab: Int, dir: Int) -> Unit,
): Modifier = composed {
    val drag = LocalDragActivity.current
    val exclusions = LocalSwipeExclusions.current
    val swipe by rememberUpdatedState(onSwipe)
    val tab by rememberUpdatedState(currentTab)
    val isBlocked by rememberUpdatedState(blocked)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Modifier
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(drag, exclusions) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val windowPos = coords[0]?.takeIf { it.isAttached }?.localToWindow(down.position) ?: down.position
                var tracking = !isBlocked() && !exclusions.contains(windowPos) && !drag.active
                val pickups = drag.pickups
                var last = down.position
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.count { it.pressed } > 1) tracking = false // `multi`
                    ev.changes.firstOrNull { it.id == down.id }?.let { last = it.position }
                    val fin = awaitPointerEvent(PointerEventPass.Final)
                    if (tracking && abs(last.y - down.position.y) <= slop && fin.changes.any {
                            it.isConsumed && it.pressed && abs(it.positionChangeIgnoreConsumed().x) > abs(it.positionChangeIgnoreConsumed().y)
                        }) tracking = false
                    if (ev.changes.none { it.pressed }) break
                }
                if (!tracking || drag.active || drag.pickups != pickups) return@awaitEachGesture
                val d = (last - down.position) / density
                val target = GestureSpec.swipeTarget(d.x, d.y, tab()) ?: return@awaitEachGesture
                swipe(target, GestureSpec.swipeDir(d.x))
            }
        }
}

/**
 * The `slideInR` / `slideInL` animation (index.html:86-89) that `switchTab(tab, dir)` adds after a
 * swipe only (a nav tap passes dir 0 and does not animate): 220 ms ease-out from 26 px and
 * opacity .4. [slide] is (dir, serial): a new serial restarts the animation.
 */
@Composable
fun Modifier.tabSlideIn(slide: Pair<Int, Int>): Modifier {
    val p = remember { Animatable(1f) }
    LaunchedEffect(slide) {
        if (slide.first == 0) return@LaunchedEffect
        p.snapTo(0f)
        p.animateTo(1f, tween(GestureSpec.SLIDE_MS, easing = CSS_EASE_OUT))
    }
    val px = with(LocalDensity.current) { GestureSpec.SLIDE_DP.dp().toPx() }
    val dir = slide.first
    return this.graphicsLayer {
        translationX = dir * px * (1f - p.value)
        alpha = GestureSpec.SLIDE_START_ALPHA + (1f - GestureSpec.SLIDE_START_ALPHA) * p.value
    }
}

private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

/** CSS `ease-out`. */
private val CSS_EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
