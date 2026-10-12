package com.example.ui.gestures

import androidx.compose.foundation.lazy.LazyListState
import org.burnoutcrew.reorderable.ItemPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [reorderMove]: the library's indexes decide the move, never its (possibly stale) keys. */
class ReorderMoveTest {
    private val rows = listOf("a", "b", "c", "d")

    @Test
    fun `moves by index even when the reported key is stale`() {
        // The library reports index 3 -> 0 with the keys of the rows that were there one frame ago.
        val out = reorderMove(rows, ItemPosition(3, "a"), ItemPosition(0, "b"), LazyListState())
        assertEquals(listOf("d", "a", "b", "c"), out)
    }

    @Test
    fun `header rows shift the indexes and are never a target`() {
        val out = reorderMove(rows, ItemPosition(6, "c"), ItemPosition(4, "a"), LazyListState(), headerCount = 4)
        assertEquals(listOf("c", "a", "b", "d"), out)
        assertNull(reorderMove(rows, ItemPosition(4, "a"), ItemPosition(3, "hdr"), LazyListState(), headerCount = 4))
    }

    @Test
    fun `same or out-of-range index is no move`() {
        assertNull(reorderMove(rows, ItemPosition(1, "b"), ItemPosition(1, "b"), LazyListState()))
        assertNull(reorderMove(rows, ItemPosition(1, "b"), ItemPosition(9, "x"), LazyListState()))
    }
}
