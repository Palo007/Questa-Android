package com.example.data.attachments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** CR-KT-015: the stored form of one picked image. [full] and [thumb] are JPEG bytes without EXIF. */
class EncodedImage(val full: ByteArray, val thumb: ByteArray, val w: Int, val h: Int)

/** Turns a picked image into [EncodedImage]; null when the source cannot be read as an image. */
fun interface ImageEncoder {
    fun encode(source: Uri): EncodedImage?
}

/** Pure size / orientation math (JVM-testable). */
object ImageMath {
    /** Scales (w, h) so the long edge is at most [maxEdge]; never upscales; each side >= 1. */
    fun fit(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val long = max(w, h)
        if (long <= maxEdge) return w to h
        val s = maxEdge.toDouble() / long
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /** Largest power-of-two decode sample that still leaves the long edge >= [maxEdge]. */
    fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        val long = max(w, h)
        var s = 1
        while (long / (s * 2) >= maxEdge) s *= 2
        return s
    }

    /** EXIF orientations 5..8 swap width and height. */
    fun swapsAxes(orientation: Int): Boolean = orientation in 5..8

    /** The matrix that turns stored pixels upright for an EXIF [orientation] (1..8). */
    fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> Unit
        }
    }
}

/**
 * Decodes with a power-of-two sample, applies the EXIF orientation (platform ExifInterface,
 * InputStream constructor = API 24, our minSdk), scales the long edge to
 * [Attachment.FULL_MAX_EDGE] and re-encodes JPEG q[Attachment.FULL_QUALITY]. A Bitmap re-encode
 * writes no EXIF, so GPS and every other tag are dropped. The thumbnail is made from the full image.
 */
class AndroidImageEncoder(private val open: (Uri) -> InputStream?) : ImageEncoder {
    constructor(resolver: ContentResolver) : this({ uri -> resolver.openInputStream(uri) })

    override fun encode(source: Uri): EncodedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds: decodeStream returns null by design; only the stream may be missing.
        (open(source) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = try {
            open(source)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, Attachment.FULL_MAX_EDGE)
        }
        val decoded = open(source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val full = upright(decoded, orientation, Attachment.FULL_MAX_EDGE)
        if (full !== decoded) decoded.recycle()
        val fullBytes = jpeg(full, Attachment.FULL_QUALITY)
        val (tw, th) = ImageMath.fit(full.width, full.height, Attachment.THUMB_MAX_EDGE)
        val thumb = Bitmap.createScaledBitmap(full, tw, th, true)
        val thumbBytes = jpeg(thumb, Attachment.THUMB_QUALITY)
        val w = full.width
        val h = full.height
        if (thumb !== full) thumb.recycle()
        full.recycle()
        return EncodedImage(fullBytes, thumbBytes, w, h)
    }

    private fun upright(src: Bitmap, orientation: Int, maxEdge: Int): Bitmap {
        val m = ImageMath.orientationMatrix(orientation)
        val (sw, sh) = if (ImageMath.swapsAxes(orientation)) src.height to src.width else src.width to src.height
        val (tw, th) = ImageMath.fit(sw, sh, maxEdge)
        if (tw != sw || th != sh) m.postScale(tw.toFloat() / sw, th.toFloat() / sh)
        if (m.isIdentity) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        // createBitmap may round a side by one pixel; pin the exact target size.
        return if (out.width == tw && out.height == th) out
        else Bitmap.createScaledBitmap(out, tw, th, true).also { if (it !== out) out.recycle() }
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, quality, o); o.toByteArray() }
}
