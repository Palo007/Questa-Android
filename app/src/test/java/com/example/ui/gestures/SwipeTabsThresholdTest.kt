package com.example.ui.gestures

import android.app.Application
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.16 (PX-92): Opti's swipe navigation (app.js:8469-8511). */
class SwipeTabsDecisionTest {
    @Test fun `below 60 dp is not a swipe`() {
        assertNull(GestureSpec.swipeTarget(-59.9f, 0f, TAB_HABITS))
        assertEquals(TAB_DAILIES, GestureSpec.swipeTarget(-60f, 0f, TAB_HABITS))
    }

    @Test fun `dx must beat dy by 1_7`() {
        assertNull(GestureSpec.swipeTarget(-169f, 100f, TAB_HABITS))
        assertEquals(TAB_DAILIES, GestureSpec.swipeTarget(-170f, 100f, TAB_HABITS))
    }

    @Test fun `left is next and right is previous in Opti TABS order`() {
        // habits, dailies, todos, analytics, rewards (app.js:8442).
        assertEquals(TAB_ANALYTICS, GestureSpec.swipeTarget(-80f, 0f, TAB_TODOS))
        assertEquals(TAB_REWARDS, GestureSpec.swipeTarget(-80f, 0f, TAB_ANALYTICS))
        assertEquals(TAB_ANALYTICS, GestureSpec.swipeTarget(80f, 0f, TAB_REWARDS))
        assertEquals(TAB_TODOS, GestureSpec.swipeTarget(80f, 0f, TAB_ANALYTICS))
        assertEquals(1, GestureSpec.swipeDir(-80f))
        assertEquals(-1, GestureSpec.swipeDir(80f))
    }

    @Test fun `no wrap at the ends and no swipe from a tab Opti does not have`() {
        assertNull(GestureSpec.swipeTarget(80f, 0f, TAB_HABITS))
        assertNull(GestureSpec.swipeTarget(-80f, 0f, TAB_REWARDS))
        assertNull(GestureSpec.swipeTarget(-80f, 0f, TAB_LOGS))
        assertNull(GestureSpec.swipeTarget(80f, 0f, TAB_LOGS))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SwipeTabsThresholdTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private var tab by mutableIntStateOf(TAB_HABITS)
    private var blocked by mutableStateOf(false)
    private val swipes = mutableListOf<Pair<Int, Int>>()
    private val drag = DragActivity()

    private fun setUp(extra: @androidx.compose.runtime.Composable () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalDragActivity provides drag) {
                Column(
                    Modifier.size(400.dp).testTag("root").swipeTabs(
                        currentTab = { tab },
                        blocked = { blocked },
                        onSwipe = { t, d -> swipes += t to d; tab = t },
                    )
                ) {
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("plain"))
                    extra()
                }
            }
        }
    }

    private fun swipe(tag: String, dxDp: Float, dyDp: Float = 0f, start: Offset = Offset(200f, 100f)) {
        rule.onNodeWithTag(tag).performTouchInput {
            val s = Offset(start.x.dp.toPx(), start.y.dp.toPx())
            down(s)
            val steps = 10
            for (i in 1..steps) moveTo(s + Offset((dxDp * i / steps).dp.toPx(), (dyDp * i / steps).dp.toPx()))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun `a 61 dp left swipe moves to the next tab with dir +1, 59 dp does nothing`() {
        setUp()
        swipe("root", -59f)
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
        swipe("root", -61f)
        assertEquals(listOf(TAB_DAILIES to 1), swipes)
        swipe("root", 61f)
        assertEquals(TAB_HABITS to -1, swipes.last())
    }

    @Test fun `a mostly vertical swipe does not navigate`() {
        setUp()
        swipe("root", -100f, 70f, start = Offset(250f, 50f))
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
    }

    @Test fun `no swipe while a sheet or RYA is open`() {
        setUp()
        blocked = true
        swipe("root", -120f)
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
    }

    @Test fun `no swipe for a gesture that lifted a card`() {
        setUp()
        rule.onNodeWithTag("root").performTouchInput {
            down(Offset(200f, 100f))
            moveBy(Offset(-40.dp.toPx(), 0f))
        }
        rule.waitForIdle()
        drag.pickedUp(); drag.dropped()
        rule.onNodeWithTag("root").performTouchInput {
            moveBy(Offset(-80.dp.toPx(), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
    }

    @Test fun `a two-finger gesture is not a swipe`() {
        setUp()
        rule.onNodeWithTag("root").performTouchInput {
            down(0, Offset(200f, 100f))
            down(1, Offset(200f, 300f))
            moveBy(0, Offset(-150.dp.toPx(), 0f))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
    }

    @Test fun `a swipe starting in a marked exclusion or on a horizontal control does not navigate`() {
        setUp {
            Box(Modifier.fillMaxWidth().height(60.dp).testTag("excluded").swipeNavExclusion())
            Box(
                Modifier.fillMaxWidth().height(60.dp).testTag("slider")
                    .draggable(rememberDraggableState { }, Orientation.Horizontal)
            )
        }
        swipe("excluded", -150f, start = Offset(200f, 30f))
        swipe("slider", -150f, start = Offset(200f, 30f))
        assertEquals(emptyList<Pair<Int, Int>>(), swipes)
        swipe("plain", -150f)
        assertEquals(listOf(TAB_DAILIES to 1), swipes)
    }
}
