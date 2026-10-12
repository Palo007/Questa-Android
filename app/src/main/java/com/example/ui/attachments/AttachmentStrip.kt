package com.example.ui.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.attachments.AddResult
import com.example.data.attachments.Attachment
import com.example.data.attachments.AttachmentEditSession
import com.example.data.attachments.AttachmentRepository
import com.example.ui.theme.OptiRootTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/**
 * CR-PWA-003: the open task editor's image session; the strip reports the user's own successful
 * adds/removes to it for the Activity `edit` event. null = not counted (no editor session).
 */
val LocalAttachmentEditSession = androidx.compose.runtime.staticCompositionLocalOf<AttachmentEditSession?> { null }

/** The editor thumbnail edge (CR-KT-015). */
const val STRIP_THUMB_DP = 72

/**
 * CR-KT-015: the task editor's "Images" strip: 72 dp thumbnails in a row (tap = viewer,
 * long-press = "Remove image?"), then an "Add image" tile while the task has fewer than
 * [Attachment.MAX_PER_TASK]. The tile offers the gallery (photo picker, images only) or the
 * camera (a cache file shared through the app's FileProvider). A new task has no id until it is
 * saved, so the tile is disabled with "Save the task first". Emits nothing without a repository.
 *
 * Adds run on the process scope, so closing the editor does not cancel an image being stored.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentStrip(taskId: String?, modifier: Modifier = Modifier) {
    val repo = LocalAttachmentRepository.current ?: return
    val context = LocalContext.current
    val session = LocalAttachmentEditSession.current
    val id = taskId?.takeIf { it.isNotEmpty() }
    val list by remember(repo, id) { id?.let { repo.attachments(it) } ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    var adding by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<Attachment?>(null) }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    val msgLimit = stringResource(R.string.attach_limit_reached)
    val msgFailed = stringResource(R.string.attach_failed)
    val msgNoCamera = stringResource(R.string.attach_no_camera)
    val count = list.size + adding
    val remaining = AttachmentUiRules.remaining(count)
    val currentRemaining by rememberUpdatedState(remaining)

    fun addAll(uris: List<Uri>, cleanup: () -> Unit = {}) {
        if (id == null || uris.isEmpty()) { cleanup(); return }
        launchAdds(context.applicationContext, repo, id, uris, msgLimit, msgFailed, session,
            onStart = { adding += uris.size }, onEach = { adding = (adding - 1).coerceAtLeast(0) }, cleanup = cleanup)
    }

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addAll(listOf(uri))
    }
    val multiContract = remember(remaining) { ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, remaining)) }
    val pickMany = rememberLauncherForActivityResult(multiContract) { uris ->
        // Some pickers ignore the limit; the repository also answers LimitReached.
        addAll(uris.take(maxOf(1, currentRemaining)))
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto?.let(::File)
        pendingPhoto = null
        if (ok && file != null && file.length() > 0) addAll(listOf(photoUri(context, file))) { file.delete() }
        else file?.delete()
    }

    Column(modifier.fillMaxWidth().testTag("attach_strip")) {
        Text(stringResource(R.string.attach_images), fontSize = 13.sp, color = OptiRootTokens.Muted)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            list.forEachIndexed { i, a ->
                val desc = stringResource(R.string.attach_open, i + 1, list.size)
                val removeLabel = stringResource(R.string.attach_remove)
                Box(
                    Modifier
                        .size(STRIP_THUMB_DP.dp)
                        .combinedClickable(
                            role = Role.Image,
                            onClickLabel = desc,
                            onLongClickLabel = removeLabel,
                            onLongClick = { removeTarget = a },
                            onClick = { viewerAt = i },
                        )
                        .semantics { contentDescription = desc }
                        .testTag("attach_strip_thumb_${a.id}")
                ) {
                    BlobThumb(repo, a.sha, STRIP_THUMB_DP.dp, corner = 8.dp)
                }
            }
            repeat(adding) {
                val desc = stringResource(R.string.attach_adding)
                Box(
                    Modifier.size(STRIP_THUMB_DP.dp).border(1.dp, OptiRootTokens.Line, RoundedCornerShape(8.dp))
                        .semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = OptiRootTokens.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
            }
            if (AttachmentUiRules.showAddTile(count)) {
                Box {
                    AddTile(enabled = id != null) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_choose_gallery)) },
                            leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                            modifier = Modifier.testTag("attach_menu_gallery"),
                            onClick = {
                                menuOpen = false
                                val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                when (AttachmentUiRules.pickMode(count)) {
                                    AttachmentUiRules.PickMode.MULTI -> pickMany.launch(req)
                                    AttachmentUiRules.PickMode.SINGLE -> pickOne.launch(req)
                                    AttachmentUiRules.PickMode.NONE -> Unit
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_take_photo)) },
                            leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                            modifier = Modifier.testTag("attach_menu_camera"),
                            onClick = {
                                menuOpen = false
                                val file = newCaptureFile(context)
                                if (file == null) {
                                    Toast.makeText(context, msgFailed, Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingPhoto = file.path
                                    try {
                                        takePhoto.launch(photoUri(context, file))
                                    } catch (e: ActivityNotFoundException) {
                                        pendingPhoto = null
                                        file.delete()
                                        Toast.makeText(context, msgNoCamera, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        if (id == null) {
            Text(
                stringResource(R.string.attach_save_first),
                fontSize = 12.sp,
                color = OptiRootTokens.Muted,
                modifier = Modifier.testTag("attach_save_first"),
            )
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.attach_remove_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        if (id != null) ProcessLifecycleOwner.get().lifecycleScope.launch {
                            repo.remove(id, target.id)
                            session?.onRemoved(target.id) // CR-PWA-003 Activity count
                        }
                    },
                    modifier = Modifier.testTag("attach_confirm_remove"),
                ) { Text(stringResource(R.string.attach_remove), color = OptiRootTokens.Hp) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.attach_cancel), color = OptiRootTokens.Muted)
                }
            },
            containerColor = OptiRootTokens.Panel,
            titleContentColor = OptiRootTokens.Ink,
        )
    }

    viewerAt?.let { start ->
        AttachmentViewer(list, start, repo, onDismiss = { viewerAt = null })
    }
}

@Composable
private fun AddTile(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.attach_add)
    Box(
        Modifier
            .size(STRIP_THUMB_DP.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(1.dp, OptiRootTokens.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("attach_add_tile"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = OptiRootTokens.Accent, modifier = Modifier.size(28.dp))
    }
}

private fun launchAdds(
    appContext: Context,
    repo: AttachmentRepository,
    taskId: String,
    uris: List<Uri>,
    msgLimit: String,
    msgFailed: String,
    session: AttachmentEditSession?,
    onStart: () -> Unit,
    onEach: () -> Unit,
    cleanup: () -> Unit,
) {
    onStart()
    ProcessLifecycleOwner.get().lifecycleScope.launch {
        var limit = false
        var failed = false
        try {
            for (u in uris) {
                val r = try {
                    repo.add(taskId, u)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AddResult.Failed(e.message ?: e.javaClass.simpleName)
                }
                when (r) {
                    is AddResult.Added -> session?.onAdded(r.attachment.id) // CR-PWA-003 Activity count
                    AddResult.LimitReached -> limit = true
                    is AddResult.Failed -> failed = true
                }
                onEach()
            }
        } finally {
            cleanup()
        }
        if (limit) Toast.makeText(appContext, msgLimit, Toast.LENGTH_SHORT).show()
        if (failed) Toast.makeText(appContext, msgFailed, Toast.LENGTH_SHORT).show()
    }
}

/** The camera target: a new file in `cache/attach_capture/` (covered by `@xml/file_paths`). */
private fun newCaptureFile(context: Context): File? = try {
    val dir = File(context.cacheDir, "attach_capture").apply { mkdirs() }
    File.createTempFile("photo_", ".jpg", dir)
} catch (e: Exception) {
    null
}

private fun photoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
