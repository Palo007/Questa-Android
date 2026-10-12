package com.example.ui.gestures

import org.junit.Assert.assertEquals
import org.junit.Test

/** CR-KT-011 [groupDrop]: the selected tasks land as one block where the lifted card was dropped. */
class GroupDropTest {
    private val before = listOf("a", "b", "c", "d", "e")

    @Test fun `block lands above the neighbor at the top`() {
        // b was dropped at the top; d rides along.
        assertEquals(listOf("b", "d", "a", "c", "e"), groupDrop(listOf("b", "a", "c", "d", "e"), "b", setOf("b", "d"), before))
    }

    @Test fun `block lands at the end`() {
        assertEquals(listOf("a", "c", "e", "b", "d"), groupDrop(listOf("a", "c", "d", "e", "b"), "b", setOf("b", "d"), before))
    }

    @Test fun `block keeps the pickup order when the lower card is the one dragged up`() {
        // d was dropped between a and b: the block is b, d (their order at pickup), not d, b.
        assertEquals(listOf("a", "b", "d", "c", "e"), groupDrop(listOf("a", "d", "b", "c", "e"), "d", setOf("b", "d"), before))
    }

    @Test fun `block lands below the neighbor`() {
        assertEquals(listOf("a", "c", "e", "b", "d"), groupDrop(listOf("a", "b", "c", "e", "d"), "d", setOf("b", "d"), before))
    }

    @Test fun `non-contiguous selection becomes contiguous`() {
        assertEquals(listOf("a", "c", "e", "b", "d"), groupDrop(listOf("c", "a", "b", "d", "e"), "c", setOf("a", "c", "e"), before))
    }

    @Test fun `a lone selected card is a plain move`() {
        val order = listOf("c", "a", "b", "d", "e")
        assertEquals(order, groupDrop(order, "c", setOf("c"), before))
    }

    @Test fun `ids not in the list are ignored`() {
        assertEquals(listOf("a", "b", "d", "c", "e"), groupDrop(listOf("a", "d", "b", "c", "e"), "d", setOf("b", "d", "x"), before + "x"))
        // A dragged id the list does not hold changes nothing.
        assertEquals(before, groupDrop(before, "x", setOf("x", "b"), before))
    }

    @Test fun `a cancelled lift ends the session and the riders unfold`() {
        val g = GroupDrag()
        g.lift("b", setOf("b", "d"), before)
        assertEquals(setOf("d"), g.riders)
        g.cancel()
        assertEquals(null, g.dragged)
        assertEquals(emptySet<String>(), g.riders)
        // A later drop of the same rows is a plain no-op.
        assertEquals(before, g.drop(before) { it })
    }

    @Test fun `a drop in place on a contiguous block is the same order`() {
        assertEquals(before, groupDrop(before, "c", setOf("b", "c", "d"), before))
    }
}
