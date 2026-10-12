package com.example.ui.attachments

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.data.attachments.Attachment
import com.example.data.attachments.AttachmentRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CR-KT-015: the attachment repository for the UI. The app root provides the real one; while it
 * is null (no data layer yet) every attachment UI hides itself.
 */
val LocalAttachmentRepository = staticCompositionLocalOf<AttachmentRepository?> { null }

/** CR-KT-015: pure rules for the card thumbnail and the editor strip. */
object AttachmentUiRules {
    /** The card badge: "+N" for the images after the first one; null for 0 or 1 image. */
    fun extraBadge(count: Int): String? = if (count > 1) "+${count - 1}" else null

    /** How many more images the task can take. */
    fun remaining(count: Int): Int = (Attachment.MAX_PER_TASK - count).coerceAtLeast(0)

    /** The editor shows the "Add image" tile while the task has fewer than the max. */
    fun showAddTile(count: Int): Boolean = remaining(count) > 0

    /** Adding needs a saved task: a new task has no id until its first save. */
    fun canAdd(taskId: String?, count: Int): Boolean = !taskId.isNullOrEmpty() && showAddTile(count)

    /**
     * The gallery picker. `PickMultipleVisualMedia(maxItems)` requires maxItems > 1, so with one
     * slot left the single picker is used. 0 = no picker.
     */
    enum class PickMode { NONE, SINGLE, MULTI }

    fun pickMode(count: Int): PickMode = when (remaining(count)) {
        0 -> PickMode.NONE
        1 -> PickMode.SINGLE
        else -> PickMode.MULTI
    }

    /** The viewer counter, e.g. "2 / 3" (index is 0-based). */
    fun counter(index: Int, count: Int): String = "${index + 1} / $count"
}

/** CR-KT-015: the zoom/pan math of the full-screen viewer (transform origin = the box center). */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f
    /** Swipe-down closes when the drag passes this share of the box height. */
    const val DISMISS_FRACTION = 0.18f

    fun clampScale(s: Float): Float = s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** The size of [content] fitted inside [box] (ContentScale.Fit). */
    fun fit(content: Size, box: Size): Size {
        if (content.width <= 0f || content.height <= 0f || box.width <= 0f || box.height <= 0f) return box
        val k = min(box.width / content.width, box.height / content.height)
        return Size(content.width * k, content.height * k)
    }

    /**
     * Clamps a pan so the scaled image never leaves a gap at an edge it could fill: on each axis
     * the offset is at most half of the overflow of the scaled fitted image over the box.
     */
    fun clampOffset(offset: Offset, scale: Float, fitted: Size, box: Size): Offset {
        val maxX = max(0f, (fitted.width * scale - box.width) / 2f)
        val maxY = max(0f, (fitted.height * scale - box.height) / 2f)
        // "+ 0f" turns a -0.0 from coerceIn(-0, 0) into 0.0.
        return Offset(offset.x.coerceIn(-maxX, maxX) + 0f, offset.y.coerceIn(-maxY, maxY) + 0f)
    }

    data class Transform(val scale: Float, val offset: Offset)

    /**
     * One pinch/pan step: zoom by [zoom] about [centroid] (box coordinates) and move by [pan]; the
     * image point under the centroid stays under it. Result is clamped.
     */
    fun applyGesture(current: Transform, centroid: Offset, pan: Offset, zoom: Float, fitted: Size, box: Size): Transform {
        val newScale = clampScale(current.scale * zoom)
        val z = newScale / current.scale
        val c = Offset(box.width / 2f, box.height / 2f)
        val rel = centroid - c
        val raw = (current.offset - rel) * z + rel + pan
        return Transform(newScale, clampOffset(raw, newScale, fitted, box))
    }

    /** Double tap: back to 1x when zoomed, else 2.5x with the tapped point kept in place. */
    fun doubleTap(current: Transform, tap: Offset, fitted: Size, box: Size): Transform {
        if (current.scale > MIN_SCALE + 0.01f) return Transform(MIN_SCALE, Offset.Zero)
        val c = Offset(box.width / 2f, box.height / 2f)
        val raw = (tap - c) * (1f - DOUBLE_TAP_SCALE)
        return Transform(DOUBLE_TAP_SCALE, clampOffset(raw, DOUBLE_TAP_SCALE, fitted, box))
    }

    fun isZoomed(scale: Float): Boolean = scale > MIN_SCALE + 0.01f

    fun shouldDismiss(dragY: Float, boxHeight: Float): Boolean = dragY > boxHeight * DISMISS_FRACTION

    /** A one-finger move at 1x becomes the swipe-down when it is mostly vertical and downward. */
    fun isDismissDrag(total: Offset, slop: Float): Boolean = total.y > slop && abs(total.y) > abs(total.x)

    /** BitmapFactory inSampleSize: the largest power of 2 that keeps both edges >= the request. */
    fun inSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return 1
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }
}
