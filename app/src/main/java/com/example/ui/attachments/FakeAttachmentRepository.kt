package com.example.ui.attachments

import android.net.Uri
import com.example.data.attachments.AddResult
import com.example.data.attachments.Attachment
import com.example.data.attachments.AttachmentRepository
import com.example.data.attachments.BlobState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * CR-KT-015: an in-memory [AttachmentRepository] for UI tests and previews. Blobs come from
 * [blobs] (sha -> state); an unknown sha is [BlobState.Missing]. [nextAdd] overrides the result
 * of the next [add] (e.g. [AddResult.Failed]).
 */
class FakeAttachmentRepository(
    initial: Map<String, List<Attachment>> = emptyMap(),
    blobs: Map<String, BlobState> = emptyMap(),
) : AttachmentRepository {
    val tasks = MutableStateFlow(initial)
    val blobs = MutableStateFlow(blobs)
    var nextAdd: AddResult? = null
    val added = mutableListOf<Pair<String, Uri>>()
    val removed = mutableListOf<Pair<String, String>>()
    private var seq = 0

    override suspend fun add(taskId: String, source: Uri): AddResult {
        added += taskId to source
        nextAdd?.let { nextAdd = null; return it }
        val list = tasks.value[taskId].orEmpty()
        if (list.size >= Attachment.MAX_PER_TASK) return AddResult.LimitReached
        val n = ++seq
        val a = Attachment(id = "fake-$n", sha = "sha$n", w = 4, h = 3, bytes = 1, addedAt = n.toLong())
        tasks.update { it + (taskId to list + a) }
        return AddResult.Added(a)
    }

    override suspend fun remove(taskId: String, attachmentId: String) {
        removed += taskId to attachmentId
        tasks.update { m -> m + (taskId to m[taskId].orEmpty().filterNot { it.id == attachmentId }) }
    }

    var attachmentsCalls = 0
    val invalidated = mutableListOf<String>()

    override fun attachments(taskId: String): Flow<List<Attachment>> {
        attachmentsCalls++
        return tasks.map { it[taskId].orEmpty() }
    }

    override suspend fun invalidateThumb(sha: String) {
        invalidated += sha
        blobs.update { it + (sha to BlobState.Missing) }
    }

    override fun thumb(sha: String): Flow<BlobState> = blobs.map { it[sha] ?: BlobState.Missing }

    override fun full(sha: String): Flow<BlobState> = blobs.map { it["full:$sha"] ?: it[sha] ?: BlobState.Missing }
}
