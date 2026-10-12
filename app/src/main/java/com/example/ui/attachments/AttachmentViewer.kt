package com.example.ui.attachments

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.R
import com.example.data.attachments.Attachment
import com.example.data.attachments.AttachmentRepository
import com.example.data.attachments.BlobState
import com.example.ui.visuals.FullWindow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * CR-KT-015: the full-screen image viewer (a [FullWindow] dialog, the project's overlay pattern).
 * Black background, a pager over the task's images (swipe only at 1x), pinch zoom 1x..5x with
 * the pan clamped, double tap 1x / 2.5x at the tap point, swipe down at 1x or Back closes.
 */
@Composable
fun AttachmentViewer(
    attachments: List<Attachment>,
    startIndex: Int,
    repo: AttachmentRepository,
    onDismiss: () -> Unit,
) {
    if (attachments.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    FullWindow(onDismiss = onDismiss) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, attachments.size - 1)) { attachments.size }
        val scales = remember { mutableStateMapOf<Int, Float>() }
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        var heightPx by remember { mutableFloatStateOf(1f) }
        val fade = 1f - (drag.value / max(1f, heightPx)).coerceIn(0f, 1f) * 0.6f
        val current = pager.currentPage.coerceIn(0, attachments.size - 1)

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { heightPx = it.height.toFloat() }
                .background(Color.Black.copy(alpha = fade))
                .testTag("attach_viewer")
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !ZoomMath.isZoomed(scales[current] ?: 1f),
                key = { attachments[it].id },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = drag.value },
            ) { page ->
                ZoomablePage(
                    repo = repo,
                    attachment = attachments[page],
                    active = page == pager.currentPage,
                    description = stringResource(R.string.attach_viewer_image, page + 1, attachments.size),
                    onScale = { scales[page] = it },
                    onDrag = { dy -> scope.launch { drag.snapTo(dy) } },
                    onDragEnd = { dy ->
                        if (ZoomMath.shouldDismiss(dy, heightPx)) onDismiss()
                        else scope.launch { drag.animateTo(0f) }
                    },
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("attach_viewer_close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.attach_close), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    AttachmentUiRules.counter(current, attachments.size),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("attach_viewer_counter"),
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun ZoomablePage(
    repo: AttachmentRepository,
    attachment: Attachment,
    active: Boolean,
    description: String,
    onScale: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    val full by remember(repo, attachment.sha) { repo.full(attachment.sha) }.collectAsState(BlobState.Loading)
    val thumb by remember(repo, attachment.sha) { repo.thumb(attachment.sha) }.collectAsState(BlobState.Loading)
    val fullImg = rememberDecoded(full, Attachment.FULL_MAX_EDGE * 2)
    val thumbImg = rememberDecoded(thumb, Attachment.THUMB_MAX_EDGE * 2)
    // The thumb shows while the full image is loading.
    val bitmap = (fullImg as? Decoded.Ok)?.bitmap ?: (thumbImg as? Decoded.Ok)?.bitmap

    var box by remember { mutableStateOf(Size.Zero) }
    var t by remember { mutableStateOf(ZoomMath.Transform(1f, Offset.Zero)) }
    val content = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) }
        ?: Size(attachment.w.toFloat(), attachment.h.toFloat())
    val fitted = ZoomMath.fit(content, box)

    LaunchedEffect(t.scale) { onScale(t.scale) }
    LaunchedEffect(active) { if (!active) t = ZoomMath.Transform(1f, Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it.toSize() }
            .pointerInput(fitted, box) {
                detectTapGestures(onDoubleTap = { p -> t = ZoomMath.doubleTap(t, p, fitted, box) })
            }
            .pointerInput(fitted, box) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var total = Offset.Zero
                    var dismissing = false
                    var dy = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        if (fingers == 0) break
                        val pan = ev.calculatePan()
                        val zoom = ev.calculateZoom()
                        if (dismissing) {
                            dy = max(0f, dy + pan.y)
                            onDrag(dy)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (fingers >= 2 || ZoomMath.isZoomed(t.scale)) {
                            if (zoom != 1f || pan != Offset.Zero) {
                                t = ZoomMath.applyGesture(t, ev.calculateCentroid(useCurrent = false), pan, zoom, fitted, box)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else {
                            // 1x, one finger: horizontal moves stay with the pager.
                            total += pan
                            if (ZoomMath.isDismissDrag(total, slop)) {
                                dismissing = true
                                dy = total.y
                                onDrag(dy)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
                    if (dismissing) onDragEnd(dy)
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = t.scale
                        scaleY = t.scale
                        translationX = t.offset.x
                        translationY = t.offset.y
                    },
            )
            fullImg == Decoded.Failed && thumbImg == Decoded.Failed -> Icon(
                Icons.Outlined.HideImage,
                contentDescription = stringResource(R.string.attach_missing),
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(64.dp),
            )
            else -> {
                val loading = stringResource(R.string.attach_loading)
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(32.dp).semantics { contentDescription = loading },
                )
            }
        }
    }
}
