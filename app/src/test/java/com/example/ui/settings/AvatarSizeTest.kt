package com.example.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** WP-8.15 (PX-90): Opti `uploadFace` (app.js:8419-8428): type first, then at most 1 MB. */
class AvatarSizeTest {
    private val dir: File = Files.createTempDirectory("avatar").toFile()
    private val file = File(File(dir, "avatar"), "face_img")
    private val store = AvatarStore(file)

    @After fun tearDown() { dir.deleteRecursively() }

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun over_1_MB_is_rejected_with_Optis_text() = runBlocking {
        val r = store.set("image/png", 1_048_577L) { ByteArrayInputStream(bytes(10)) }
        assertTrue(r.isFailure)
        assertEquals("That image is 1.0 MB. Please use one under 1 MB.", r.exceptionOrNull()!!.message)
        assertFalse(file.exists())
        assertNull(store.image.value)
        assertEquals("That image is 1.5 MB. Please use one under 1 MB.", AvatarStore.sizeMessage(1_572_864L))
    }

    @Test fun exactly_1_MB_is_accepted_and_stored() = runBlocking {
        val data = bytes(1_048_576)
        val r = store.set("image/jpeg", data.size.toLong()) { ByteArrayInputStream(data) }
        assertTrue(r.isSuccess)
        assertArrayEquals(data, file.readBytes())
        assertEquals(file, store.image.value!!.file)
    }

    @Test fun unknown_size_is_counted_while_reading() = runBlocking {
        val r = store.set("image/gif", null) { ByteArrayInputStream(bytes(2_200_000)) }
        assertEquals("That image is 2.1 MB. Please use one under 1 MB.", r.exceptionOrNull()!!.message)
        assertFalse(file.exists())
    }

    @Test fun wrong_type_is_checked_before_the_size() = runBlocking {
        assertEquals(AvatarStore.TYPE_ERROR, store.set("image/webp", 5_000_000L) { null }.exceptionOrNull()!!.message)
        assertEquals(AvatarStore.TYPE_ERROR, store.set(null, 10L) { null }.exceptionOrNull()!!.message)
        assertEquals("Please choose a PNG, JPEG or GIF image.", AvatarStore.TYPE_ERROR)
    }

    @Test fun unreadable_file() = runBlocking {
        assertEquals("Could not read that file.", store.set("image/png", 10L) { null }.exceptionOrNull()!!.message)
    }

    @Test fun set_twice_bumps_the_version_and_remove_deletes() = runBlocking {
        store.set("image/png", 3L) { ByteArrayInputStream(bytes(3)) }
        val v1 = store.image.value!!.version
        store.set("image/png", 4L) { ByteArrayInputStream(bytes(4)) }
        assertNotEquals(v1, store.image.value!!.version)
        assertEquals(4, file.readBytes().size)
        store.remove()
        assertFalse(file.exists())
        assertNull(store.image.value)
    }

    @Test fun an_existing_file_is_seen_at_start() = runBlocking {
        store.set("image/png", 3L) { ByteArrayInputStream(bytes(3)) }
        assertEquals(file, AvatarStore(file).image.value!!.file)
    }
}
