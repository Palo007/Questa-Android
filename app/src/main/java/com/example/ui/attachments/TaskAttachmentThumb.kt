package com.example.ui.attachments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.attachments.Attachment
import com.example.ui.gestures.optiPress

/** The card thumbnail edge (CR-KT-015). */
const val CARD_THUMB_DP = 40

/**
 * CR-KT-015: the task card's image, at the right end of the row: the first image as a 40 dp
 * rounded square, "+N" for more. A tap opens the viewer; the press is consumed so the card's own
 * tap, long-press pickup and check never fire from it. [attachments] is the card task's own list. With [interactive] false (selection mode)
 * it is display-only and the card's selection tap still works through it. Emits nothing when
 * there is no repository or the task has no image.
 */
@Composable
fun TaskAttachmentThumb(taskId: String, attachments: List<Attachment>, interactive: Boolean = true) {
    val repo = LocalAttachmentRepository.current ?: return
    // The card's already-loaded task list: no per-card Room query (the editor strip observes).
    val list = attachments
    if (list.isEmpty()) return
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    val badge = AttachmentUiRules.extraBadge(list.size)
    val desc = if (badge != null) stringResource(R.string.attach_thumb_more, list.size - 1)
    else stringResource(R.string.attach_thumb)

    Box(
        modifier = Modifier.fillMaxHeight().padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CARD_THUMB_DP.dp)
                .then(if (interactive) Modifier.optiPress(label = desc) { viewerAt = 0 } else Modifier)
                .semantics { contentDescription = desc }
                .testTag("attach_thumb_$taskId"),
        ) {
            BlobThumb(repo, list.first().sha, CARD_THUMB_DP.dp)
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 3.dp)
                        .testTag("attach_badge_$taskId"),
                )
            }
        }
    }

    viewerAt?.let { start ->
        AttachmentViewer(list, start, repo, onDismiss = { viewerAt = null })
    }
}
