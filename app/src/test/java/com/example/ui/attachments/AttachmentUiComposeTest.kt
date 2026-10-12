package com.example.ui.attachments

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.data.attachments.Attachment
import com.example.data.attachments.BlobState
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.example.ui.freshMainDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentUiComposeTest {
    @get:Rule
    val rule = run { freshMainDispatcher(); createComposeRule() }

    private fun att(n: Int) = Attachment(id = "a$n", sha = "s$n", w = 4, h = 3, bytes = 1, addedAt = n.toLong())

    @Test fun `no repository hides the thumbnail and the strip`() {
        rule.setContent {
            Row { TaskAttachmentThumb("t1", listOf(att(1))) }
            AttachmentStrip("t1")
        }
        rule.onNodeWithTag("attach_thumb_t1").assertDoesNotExist()
        rule.onNodeWithTag("attach_strip").assertDoesNotExist()
    }

    @Test fun `card thumb shows the plus N badge and opens the viewer`() {
        val repo = FakeAttachmentRepository()
        val list = listOf(att(1), att(2), att(3))
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { Row { TaskAttachmentThumb("t1", list) } }
        }
        rule.onNodeWithTag("attach_badge_t1").assertExists()
        rule.onNodeWithText("+2").assertExists()
        rule.onNodeWithTag("attach_thumb_t1").performClick()
        rule.onNodeWithTag("attach_viewer_counter").assertExists()
        rule.onNodeWithText("1 / 3").assertExists()
        rule.onNodeWithTag("attach_viewer_close").performClick()
        rule.onNodeWithTag("attach_viewer").assertDoesNotExist()
    }

    @Test fun `single image has no badge`() {
        val repo = FakeAttachmentRepository()
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { Row { TaskAttachmentThumb("t1", listOf(att(1))) } }
        }
        rule.onNodeWithTag("attach_thumb_t1").assertExists()
        rule.onNodeWithTag("attach_badge_t1").assertDoesNotExist()
        // The card uses the list it already has: no per-card task query (review MINOR 5).
        assertEquals(0, repo.attachmentsCalls)
    }

    @Test fun `empty list emits no card thumb`() {
        val repo = FakeAttachmentRepository()
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { Row { TaskAttachmentThumb("t1", emptyList()) } }
        }
        rule.onNodeWithTag("attach_thumb_t1").assertDoesNotExist()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `undecodable local thumb is invalidated for a re-download`() {
        val bad = File.createTempFile("bad", "_t.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)) }
        val repo = FakeAttachmentRepository(blobs = mapOf("s1" to BlobState.Ready(bad)))
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { BlobThumb(repo, "s1", 40.dp) }
        }
        rule.waitUntil(5_000) { repo.invalidated.isNotEmpty() }
        assertEquals(listOf("s1"), repo.invalidated)
        bad.delete()
    }

    @Test fun `new task disables the add tile with a hint`() {
        val repo = FakeAttachmentRepository()
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { AttachmentStrip(null) }
        }
        rule.onNodeWithTag("attach_add_tile").assertIsNotEnabled()
        rule.onNodeWithText("Save the task first").assertExists()
    }

    @Test fun `five images hide the add tile`() {
        val repo = FakeAttachmentRepository(mapOf("t1" to (1..5).map(::att)))
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { AttachmentStrip("t1") }
        }
        rule.onNodeWithTag("attach_strip_thumb_a5").assertExists()
        rule.onNodeWithTag("attach_add_tile").assertDoesNotExist()
    }

    @Test fun `add tile opens the gallery and camera choice`() {
        val repo = FakeAttachmentRepository(mapOf("t1" to listOf(att(1))))
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { AttachmentStrip("t1") }
        }
        rule.onNodeWithTag("attach_add_tile").performClick()
        rule.onNodeWithText("Choose from gallery").assertExists()
        rule.onNodeWithText("Take photo").assertExists()
    }

    @Test fun `long press asks then removes`() {
        val repo = FakeAttachmentRepository(mapOf("t1" to listOf(att(1), att(2))))
        rule.setContent {
            CompositionLocalProvider(LocalAttachmentRepository provides repo) { AttachmentStrip("t1") }
        }
        rule.onNodeWithTag("attach_strip_thumb_a2").performTouchInput { longClick() }
        rule.onNodeWithText("Remove image?").assertExists()
        rule.onNodeWithTag("attach_confirm_remove").performClick()
        rule.waitUntil(2_000) { repo.removed.isNotEmpty() }
        assertEquals(listOf("t1" to "a2"), repo.removed)
        rule.waitForIdle()
        rule.onNodeWithTag("attach_strip_thumb_a2").assertDoesNotExist()
    }
}
