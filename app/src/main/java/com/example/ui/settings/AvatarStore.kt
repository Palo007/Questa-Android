package com.example.ui.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/** One stored avatar image; [version] changes on every set, so an image loader can re-read [file]. */
data class AvatarImage(val file: File, val version: Long)

/** A refused avatar file; [message] is Opti's alert text (title "Error"). */
class AvatarRejectedException(message: String) : Exception(message)

/**
 * WP-8.15 (PX-90): Opti `uploadFace` / `removeFace` (app.js:8419-8428). The image lives only in
 * the app's files (never on the sync wire, §4.3). A PNG, JPEG or GIF of at most 1 MB
 * (1 048 576 bytes, Opti `f.size > 1048576`) is accepted; the type is checked first.
 */
class AvatarStore(private val file: File) {
    private var nextVersion = 1L
    private val _image = MutableStateFlow(if (file.isFile) AvatarImage(file, 0L) else null)
    val image: StateFlow<AvatarImage?> = _image.asStateFlow()

    /**
     * Stores the image read from [open]. [size] is the provider's size, or null when unknown (then
     * the bytes are counted). Fails with [AvatarRejectedException] carrying Opti's text.
     */
    suspend fun set(mime: String?, size: Long?, open: () -> InputStream?): Result<Unit> = withContext(Dispatchers.IO) {
        rejectReason(mime, size)?.let { return@withContext Result.failure(AvatarRejectedException(it)) }
        val bytes = try {
            open()?.use { readCapped(it) } ?: return@withContext Result.failure(AvatarRejectedException(READ_ERROR))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(AvatarRejectedException(READ_ERROR))
        }
        if (bytes.second > MAX_BYTES) return@withContext Result.failure(AvatarRejectedException(sizeMessage(bytes.second)))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes.first)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
            }
        } catch (e: Exception) {
            return@withContext Result.failure(AvatarRejectedException(READ_ERROR))
        }
        synchronized(this@AvatarStore) { _image.value = AvatarImage(file, nextVersion++) }
        Result.success(Unit)
    }

    /** Opti `removeFace()`. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        file.delete()
        _image.value = null
    }

    /** The first [MAX_BYTES] + 1 bytes and the total byte count. */
    private fun readCapped(input: InputStream): Pair<ByteArray, Long> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (total <= MAX_BYTES) out.write(buf, 0, minOf(n.toLong(), MAX_BYTES + 1 - total).toInt())
            total += n
        }
        return out.toByteArray() to total
    }

    companion object {
        const val MAX_BYTES = 1_048_576L
        const val TYPE_ERROR = "Please choose a PNG, JPEG or GIF image."
        const val READ_ERROR = "Could not read that file."
        const val SET_TOAST = "Avatar image set"
        const val REMOVED_TOAST = "Image removed"
        private val MIME = Regex("^image/(jpeg|png|gif)$")

        /** The picker's types (Opti `accept="image/jpeg,image/png,image/gif"`). */
        val PICKER_TYPES = arrayOf("image/jpeg", "image/png", "image/gif")

        /** Opti `(f.size / 1048576).toFixed(1)`. */
        fun sizeMessage(size: Long): String =
            "That image is " + String.format(Locale.US, "%.1f", size / 1048576.0) + " MB. Please use one under 1 MB."

        /** The type check, then the size check (a null size is checked after reading). */
        fun rejectReason(mime: String?, size: Long?): String? = when {
            mime == null || !MIME.matches(mime) -> TYPE_ERROR
            size != null && size > MAX_BYTES -> sizeMessage(size)
            else -> null
        }

        @Volatile private var app: AvatarStore? = null

        /** The app's one store (`files/avatar/face_img`); the HUD (WP-8.18) reads [image]. */
        fun forApp(context: Context): AvatarStore = app ?: synchronized(this) {
            app ?: AvatarStore(File(File(context.applicationContext.filesDir, "avatar"), "face_img")).also { app = it }
        }
    }
}
