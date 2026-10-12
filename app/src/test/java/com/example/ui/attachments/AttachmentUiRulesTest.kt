package com.example.ui.attachments

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.ui.attachments.AttachmentUiRules.PickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentUiRulesTest {

    @Test fun `badge is null for 0 and 1 image`() {
        assertNull(AttachmentUiRules.extraBadge(0))
        assertNull(AttachmentUiRules.extraBadge(1))
    }

    @Test fun `badge counts the images after the first`() {
        assertEquals("+1", AttachmentUiRules.extraBadge(2))
        assertEquals("+4", AttachmentUiRules.extraBadge(5))
    }

    @Test fun `add tile shows below the max only`() {
        (0..4).forEach { assertTrue("count $it", AttachmentUiRules.showAddTile(it)) }
        assertFalse(AttachmentUiRules.showAddTile(5))
        assertFalse(AttachmentUiRules.showAddTile(6))
    }

    @Test fun `remaining never goes negative`() {
        assertEquals(5, AttachmentUiRules.remaining(0))
        assertEquals(1, AttachmentUiRules.remaining(4))
        assertEquals(0, AttachmentUiRules.remaining(7))
    }

    @Test fun `adding needs a saved task id`() {
        assertFalse(AttachmentUiRules.canAdd(null, 0))
        assertFalse(AttachmentUiRules.canAdd("", 0))
        assertTrue(AttachmentUiRules.canAdd("t1", 4))
        assertFalse(AttachmentUiRules.canAdd("t1", 5))
    }

    @Test fun `pick mode uses the single picker for the last slot`() {
        assertEquals(PickMode.MULTI, AttachmentUiRules.pickMode(0))
        assertEquals(PickMode.MULTI, AttachmentUiRules.pickMode(3))
        assertEquals(PickMode.SINGLE, AttachmentUiRules.pickMode(4))
        assertEquals(PickMode.NONE, AttachmentUiRules.pickMode(5))
    }

    @Test fun `counter is one based`() {
        assertEquals("2 / 3", AttachmentUiRules.counter(1, 3))
    }
}

class ZoomMathTest {
    private val box = Size(1000f, 2000f)
    private val fitted = Size(1000f, 750f) // a 4:3 image fitted to the width
    private val eps = 0.001f

    @Test fun `scale clamps to 1 to 5`() {
        assertEquals(1f, ZoomMath.clampScale(0.3f), eps)
        assertEquals(5f, ZoomMath.clampScale(9f), eps)
        assertEquals(2f, ZoomMath.clampScale(2f), eps)
    }

    @Test fun `fit keeps the aspect inside the box`() {
        val f = ZoomMath.fit(Size(1600f, 1200f), box)
        assertEquals(1000f, f.width, eps)
        assertEquals(750f, f.height, eps)
        val tall = ZoomMath.fit(Size(100f, 1000f), box)
        assertEquals(200f, tall.width, eps)
        assertEquals(2000f, tall.height, eps)
    }

    @Test fun `fit of an empty size is the box`() {
        assertEquals(box, ZoomMath.fit(Size.Zero, box))
    }

    @Test fun `at 1x the offset is always zero`() {
        assertEquals(Offset.Zero, ZoomMath.clampOffset(Offset(300f, -400f), 1f, fitted, box))
    }

    @Test fun `offset clamps to half the overflow per axis`() {
        // 2x: 2000 x 1500 in a 1000 x 2000 box: x may move +-500, y not at all (1500 < 2000).
        val o = ZoomMath.clampOffset(Offset(900f, 900f), 2f, fitted, box)
        assertEquals(500f, o.x, eps)
        assertEquals(0f, o.y, eps)
        // 4x: 4000 x 3000: x +-1500, y +-500.
        val o4 = ZoomMath.clampOffset(Offset(-9000f, -9000f), 4f, fitted, box)
        assertEquals(-1500f, o4.x, eps)
        assertEquals(-500f, o4.y, eps)
    }

    @Test fun `pinch about the center does not pan`() {
        val c = Offset(500f, 1000f)
        val t = ZoomMath.applyGesture(ZoomMath.Transform(1f, Offset.Zero), c, Offset.Zero, 2f, fitted, box)
        assertEquals(2f, t.scale, eps)
        assertEquals(Offset.Zero, t.offset)
    }

    @Test fun `pinch keeps the point under the fingers`() {
        val centroid = Offset(700f, 1000f)
        val t = ZoomMath.applyGesture(ZoomMath.Transform(1f, Offset.Zero), centroid, Offset.Zero, 2f, fitted, box)
        // Point 200 px right of the center moves to 400 px at 2x, so offset -200 keeps it in place.
        assertEquals(-200f, t.offset.x, eps)
    }

    @Test fun `pinch past 5x stops at 5x`() {
        val t = ZoomMath.applyGesture(ZoomMath.Transform(4f, Offset.Zero), Offset(500f, 1000f), Offset.Zero, 3f, fitted, box)
        assertEquals(5f, t.scale, eps)
    }

    @Test fun `pan is clamped`() {
        val t = ZoomMath.applyGesture(ZoomMath.Transform(2f, Offset.Zero), Offset(500f, 1000f), Offset(5000f, 5000f), 1f, fitted, box)
        assertEquals(500f, t.offset.x, eps)
        assertEquals(0f, t.offset.y, eps)
    }

    @Test fun `double tap zooms in at the tap point then back to 1x`() {
        val tap = Offset(700f, 1000f)
        val zin = ZoomMath.doubleTap(ZoomMath.Transform(1f, Offset.Zero), tap, fitted, box)
        assertEquals(2.5f, zin.scale, eps)
        assertEquals(-300f, zin.offset.x, eps) // (700-500) * (1-2.5)
        val zout = ZoomMath.doubleTap(zin, tap, fitted, box)
        assertEquals(1f, zout.scale, eps)
        assertEquals(Offset.Zero, zout.offset)
    }

    @Test fun `double tap near an edge is clamped`() {
        val zin = ZoomMath.doubleTap(ZoomMath.Transform(1f, Offset.Zero), Offset(1000f, 0f), fitted, box)
        // 2.5x: 2500 wide -> x within +-750; 1875 tall < 2000 -> y 0.
        assertEquals(-750f, zin.offset.x, eps)
        assertEquals(0f, zin.offset.y, eps)
    }

    @Test fun `swipe down dismiss threshold`() {
        assertFalse(ZoomMath.shouldDismiss(300f, 2000f))
        assertTrue(ZoomMath.shouldDismiss(400f, 2000f))
    }

    @Test fun `dismiss drag must be mostly vertical and downward`() {
        assertTrue(ZoomMath.isDismissDrag(Offset(5f, 30f), 10f))
        assertFalse(ZoomMath.isDismissDrag(Offset(40f, 30f), 10f))
        assertFalse(ZoomMath.isDismissDrag(Offset(0f, -30f), 10f))
        assertFalse(ZoomMath.isDismissDrag(Offset(0f, 5f), 10f))
    }

    @Test fun `in sample size is the largest power of two that keeps the request`() {
        assertEquals(1, ZoomMath.inSampleSize(256, 256, 200, 200))
        assertEquals(2, ZoomMath.inSampleSize(256, 256, 120, 120))
        assertEquals(2, ZoomMath.inSampleSize(256, 256, 100, 100))
        assertEquals(8, ZoomMath.inSampleSize(4000, 3000, 300, 300))
        assertEquals(1, ZoomMath.inSampleSize(0, 0, 100, 100))
    }
}
