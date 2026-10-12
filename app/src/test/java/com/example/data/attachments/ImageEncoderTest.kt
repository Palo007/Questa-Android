package com.example.data.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

/** CR-KT-015: resize, EXIF rotation and EXIF/GPS stripping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEncoderTest {

    @Test fun fit_neverUpscales_keepsAspect() {
        assertEquals(1600 to 1000, ImageMath.fit(3200, 2000, 1600))
        assertEquals(1000 to 1600, ImageMath.fit(2000, 3200, 1600))
        assertEquals(800 to 600, ImageMath.fit(800, 600, 1600))
        assertEquals(256 to 1, ImageMath.fit(5000, 10, 256))
    }

    @Test fun sampleSize_keepsLongEdgeAboveTarget() {
        assertEquals(1, ImageMath.sampleSize(3000, 2000, 1600))
        assertEquals(2, ImageMath.sampleSize(4000, 3000, 1600))
        assertEquals(4, ImageMath.sampleSize(8000, 6000, 1600))
        assertEquals(1, ImageMath.sampleSize(100, 100, 1600))
    }

    @Test fun swapsAxes_forOrientations5to8() {
        assertEquals(listOf(5, 6, 7, 8), (1..8).filter { ImageMath.swapsAxes(it) })
    }

    @Test fun encode_rotatesByExif_resizes_andStripsGps() {
        // Landscape 3200x2000, red top-left quadrant, blue elsewhere; EXIF says "rotate 90" + GPS.
        val src = Bitmap.createBitmap(3200, 2000, Bitmap.Config.ARGB_8888)
        Canvas(src).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, 1600f, 1000f, Paint().apply { color = Color.RED })
        }
        val file = File.createTempFile("src", ".jpg")
        file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,8/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        val enc = AndroidImageEncoder { file.inputStream() }.encode(Uri.parse("content://x/1"))!!
        assertEquals(1000, enc.w)
        assertEquals(1600, enc.h)
        val full = BitmapFactory.decodeByteArray(enc.full, 0, enc.full.size)
        assertEquals(1000, full.width); assertEquals(1600, full.height)
        // Rotated 90 clockwise: the red source top-left is now the top-right corner.
        val tr = full.getPixel(full.width - 20, 20)
        assertTrue("top-right is red, was ${Integer.toHexString(tr)}", Color.red(tr) > 180 && Color.blue(tr) < 80)
        val bl = full.getPixel(20, full.height - 20)
        assertTrue("bottom-left is blue", Color.blue(bl) > 180 && Color.red(bl) < 80)
        val thumb = BitmapFactory.decodeByteArray(enc.thumb, 0, enc.thumb.size)
        assertEquals(160, thumb.width); assertEquals(256, thumb.height)
        // No EXIF carried over: no GPS, no orientation flag.
        val exif = ExifInterface(ByteArrayInputStream(enc.full))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) in setOf(0, ExifInterface.ORIENTATION_NORMAL))
        file.delete()
    }

    @Test fun encode_smallImage_notUpscaled_andJunkIsNull() {
        val src = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val bytes = java.io.ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val enc = AndroidImageEncoder { ByteArrayInputStream(bytes) }.encode(Uri.parse("content://x/2"))!!
        assertEquals(800, enc.w); assertEquals(600, enc.h)
        assertEquals(0xFF.toByte(), enc.full[0]); assertEquals(0xD8.toByte(), enc.full[1]) // JPEG SOI
        assertNull(AndroidImageEncoder { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }.encode(Uri.parse("content://x/3")))
        assertNull(AndroidImageEncoder { null }.encode(Uri.parse("content://x/4")))
    }
}
