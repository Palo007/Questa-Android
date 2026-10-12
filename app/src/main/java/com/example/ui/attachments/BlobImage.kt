package com.example.ui.attachments

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.attachments.AttachmentRepository
import com.example.data.attachments.BlobState
import com.example.ui.theme.OptiRootTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CR-KT-015: image decoding is BitmapFactory with inSampleSize (no image library). Decoding runs
 * on Dispatchers.IO; the result is cached per (file, size) for the composition only.
 */
internal sealed interface Decoded {
    data object Pending : Decoded
    data class Ok(val bitmap: ImageBitmap) : Decoded
    data object Failed : Decoded
}

internal fun decodeSampled(file: File, maxEdgePx: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = ZoomMath.inSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx, maxEdgePx)
    }
    BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

/** Pending while the blob is Loading or decoding; Failed for Missing or an undecodable file. */
@Composable
internal fun rememberDecoded(state: BlobState, maxEdgePx: Int): Decoded {
    val file = (state as? BlobState.Ready)?.file
    val missing = state is BlobState.Missing
    val decoded by produceState<Decoded>(if (missing) Decoded.Failed else Decoded.Pending, file, missing, maxEdgePx) {
        value = when {
            missing -> Decoded.Failed
            file == null -> Decoded.Pending
            else -> withContext(Dispatchers.IO) { decodeSampled(file, maxEdgePx) }?.let { Decoded.Ok(it) } ?: Decoded.Failed
        }
    }
    return decoded
}

/**
 * A square, cropped thumbnail of [sha]. Loading = the plain `--panel2` square (subtle
 * placeholder); Missing or undecodable = the image-off icon.
 */
@Composable
internal fun BlobThumb(
    repo: AttachmentRepository,
    sha: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    contentDescription: String? = null,
) {
    val state by remember(repo, sha) { repo.thumb(sha) }.collectAsState(BlobState.Loading)
    val px = with(LocalDensity.current) { size.roundToPx() }
    val decoded = rememberDecoded(state, px)
    // A local thumb that does not decode (e.g. a truncated download; thumbs are not sha-checked)
    // is deleted and marked Missing, so the next sync round downloads it again.
    val readyFile = (state as? BlobState.Ready)?.file
    LaunchedEffect(decoded, readyFile) {
        if (decoded == Decoded.Failed && readyFile != null) repo.invalidateThumb(sha)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(OptiRootTokens.Panel2),
        contentAlignment = Alignment.Center,
    ) {
        when (decoded) {
            is Decoded.Ok -> Image(
                bitmap = decoded.bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Decoded.Failed -> Icon(
                Icons.Outlined.HideImage,
                contentDescription = stringResource(R.string.attach_missing),
                tint = OptiRootTokens.Muted,
                modifier = Modifier.size(size * 0.5f),
            )
            Decoded.Pending -> Unit
        }
    }
}
