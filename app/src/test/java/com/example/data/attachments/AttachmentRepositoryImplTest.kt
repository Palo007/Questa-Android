package com.example.data.attachments

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RpgRepository
import com.example.data.clock.DefaultHlcClock
import com.example.data.local.AppDatabase
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.SyncDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import kotlin.random.Random

/** CR-KT-015: AttachmentRepositoryImpl over an in-memory Room database and a fake blob remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: RpgRepository
    private lateinit var root: File
    private lateinit var dsFile: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A fake Dropbox: path -> bytes, plus a call log. */
    private inner class FakeRemote : BlobRemote {
        val files = Collections.synchronizedMap(LinkedHashMap<String, ByteArray>())
        val log = Collections.synchronizedList(mutableListOf<String>())
        var failUploads = false
        var failDownloads = false
        override suspend fun upload(accessToken: String, path: String, bytes: ByteArray): Boolean {
            log += "up $path"
            if (failUploads) return false
            files.putIfAbsent(path, bytes) // mode add: an existing file is a conflict = success
            return true
        }
        override suspend fun download(accessToken: String, path: String): BlobFetch {
            log += "down $path"
            if (failDownloads) return BlobFetch.Failed
            return files[path]?.let { BlobFetch.Ok(it) } ?: BlobFetch.NotFound
        }
    }

    private val remote = FakeRemote()
    private var encoded: EncodedImage? = EncodedImage(byteArrayOf(1, 2, 3, -1), byteArrayOf(9, 9), 1600, 1200)
    private var token: String? = "tok"

    private fun impl(encoder: ImageEncoder = ImageEncoder { encoded }) = AttachmentRepositoryImpl(
        root, repo, db.attachmentUploadDao(), encoder, remote, { token }, scope, Dispatchers.IO, Random(7),
    )

    /** A well-formed sha that no local add produced (thumbs are not sha-checked). */
    private val otherSha = "ab".repeat(32)

    private val uri: Uri = Uri.parse("content://test/img")
    private val fullSha = AttachmentRepositoryImpl.sha256Hex(byteArrayOf(1, 2, 3, -1))

    @Before fun setUp() = runBlocking {
        dsFile = File.createTempFile("att_ds", ".preferences_pb").also { it.delete() }
        val ds = SyncDataStore(context, PreferenceDataStoreFactory.create(produceFile = { dsFile }))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = RpgRepository(
            db.characterDao(), db.taskDao(), db.rewardDao(), db.eventLogDao(), db.tagDao(), db.deviceDao(),
            db.charHistoryDao(), db.tombstoneDao(), db.customViewDao(), db.snapshotExtrasDao(),
            db.metricsExtrasDao(), ds, db, DefaultHlcClock(0L) { },
        )
        root = File(context.cacheDir, "att-test-" + System.nanoTime())
        repo.insertTask(TaskEntity(id = "t1", type = "todo", title = "Bike", createdAt = 1, updatedAt = 5))
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
        dsFile.delete()
    }

    @Test fun add_storesFiles_appendsItem_bumpsUpdatedAt_queuesUpload() = runBlocking {
        val r = impl().add("t1", uri)
        assertTrue(r is AddResult.Added)
        val a = (r as AddResult.Added).attachment
        assertEquals(fullSha, a.sha)
        assertEquals(1600, a.w); assertEquals(1200, a.h); assertEquals(4L, a.bytes)
        assertTrue("PWA uid: base36 time + 5 chars", Regex("^[0-9a-z]{6,}$").matches(a.id))
        assertEquals(a.addedAt.toString(36), a.id.dropLast(5))
        assertTrue(File(root, "$fullSha.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3, -1)))
        assertTrue(File(root, "${fullSha}_t.jpg").exists())
        val t = repo.getTaskByIdSync("t1")!!
        assertEquals(listOf(a), t.attachments)
        assertTrue("updatedAt bumped from the HLC", t.updatedAt > 5)
        assertEquals(listOf(fullSha), db.attachmentUploadDao().pending().map { it.sha })
        assertEquals(listOf(a), impl().attachments("t1").first())
    }

    @Test fun add_limit_unknownTask_unreadable() = runBlocking {
        val repoImpl = impl()
        repeat(Attachment.MAX_PER_TASK) { assertTrue(repoImpl.add("t1", uri) is AddResult.Added) }
        assertEquals(AddResult.LimitReached, repoImpl.add("t1", uri))
        assertEquals(Attachment.MAX_PER_TASK, repo.getTaskByIdSync("t1")!!.attachments.size)
        assertTrue(repoImpl.add("nope", uri) is AddResult.Failed)
        encoded = null
        repo.insertTask(TaskEntity(id = "t2", type = "todo", title = "x", createdAt = 1, updatedAt = 1))
        assertTrue(repoImpl.add("t2", uri) is AddResult.Failed)
    }

    @Test fun remove_dropsItem_bumpsUpdatedAt_keepsFile() = runBlocking {
        val repoImpl = impl()
        val a = (repoImpl.add("t1", uri) as AddResult.Added).attachment
        val before = repo.getTaskByIdSync("t1")!!.updatedAt
        repoImpl.remove("t1", a.id)
        val t = repo.getTaskByIdSync("t1")!!
        assertTrue(t.attachments.isEmpty())
        assertTrue(t.updatedAt > before)
        assertTrue("[DEFERRED] orphan cleanup: file kept", File(root, "$fullSha.jpg").exists())
        repoImpl.remove("t1", "unknown") // no-op
        assertEquals(t.updatedAt, repo.getTaskByIdSync("t1")!!.updatedAt)
    }

    // ---- UI saves of a stale whole-task snapshot keep the stored attachments (CR-KT-015 fix) ----

    @Test fun uiSave_ofSnapshotTakenBeforeAdd_keepsTheAddedAttachment() = runBlocking {
        val snapshot = repo.getTaskByIdSync("t1")!! // the editor opens
        val a = (impl().add("t1", uri) as AddResult.Added).attachment
        repo.updateTask(snapshot.copy(title = "Bike ride", updatedAt = repo.hlcClock.now())) // editor Save
        val t = repo.getTaskByIdSync("t1")!!
        assertEquals("Bike ride", t.title)
        assertEquals(listOf(a), t.attachments)
    }

    @Test fun uiSave_ofSnapshotTakenBeforeRemove_doesNotBringTheAttachmentBack() = runBlocking {
        val repoImpl = impl()
        val a = (repoImpl.add("t1", uri) as AddResult.Added).attachment
        val snapshot = repo.getTaskByIdSync("t1")!! // the editor opens, holding [a]
        assertEquals(listOf(a), snapshot.attachments)
        repoImpl.remove("t1", a.id)
        repo.updateTask(snapshot.copy(title = "Bike ride", updatedAt = repo.hlcClock.now()))
        val t = repo.getTaskByIdSync("t1")!!
        assertEquals("Bike ride", t.title)
        assertTrue(t.attachments.isEmpty())
    }

    @Test fun uiSave_cannotSetAttachments_andUnknownTaskIsANoOp() = runBlocking {
        val bogus = Attachment(id = "x", sha = "0".repeat(64), w = 1, h = 1, bytes = 1, addedAt = 1)
        repo.updateTask(repo.getTaskByIdSync("t1")!!.copy(attachments = listOf(bogus)))
        assertTrue(repo.getTaskByIdSync("t1")!!.attachments.isEmpty())
        repo.updateTask(TaskEntity(id = "ghost", type = "todo", title = "g", createdAt = 1, updatedAt = 1))
        assertEquals(null, repo.getTaskByIdSync("ghost"))
    }

    @Test fun concurrentAddsAndStaleUiSaves_loseNoAttachment() = runBlocking {
        val repoImpl = impl()
        val snapshot = repo.getTaskByIdSync("t1")!!
        val jobs = (1..Attachment.MAX_PER_TASK).flatMap { i ->
            listOf(
                scope.async { repoImpl.add("t1", uri) },
                scope.async { repo.updateTask(snapshot.copy(title = "save $i", updatedAt = repo.hlcClock.now())); null },
            )
        }
        val added = jobs.awaitAll().filterIsInstance<AddResult.Added>().map { it.attachment }
        // Same image bytes in every add: parallel writes of one sha never fail an add (unique temp
        // file per write, an existing content-addressed file is kept), and every add stays on the row.
        assertEquals(Attachment.MAX_PER_TASK, added.size)
        assertEquals(added.map { it.id }.toSet(), repo.getTaskByIdSync("t1")!!.attachments.map { it.id }.toSet())
    }

    @Test fun uploadPending_sendsFullThenThumb_andDequeues_failureKeepsQueue() = runBlocking {
        val repoImpl = impl()
        repoImpl.add("t1", uri)
        remote.failUploads = true
        repoImpl.uploadPending("tok")
        assertEquals(1, db.attachmentUploadDao().pending().size)
        remote.failUploads = false
        remote.log.clear()
        repoImpl.uploadPending("tok")
        assertEquals(listOf("up /attachments/$fullSha.jpg", "up /attachments/${fullSha}_t.jpg"), remote.log)
        assertTrue(db.attachmentUploadDao().pending().isEmpty())
        // Already there (409 path conflict) counts as success.
        db.attachmentUploadDao().enqueue(com.example.data.local.entities.AttachmentUploadEntity(fullSha, 1))
        repoImpl.uploadPending("tok")
        assertTrue(db.attachmentUploadDao().pending().isEmpty())
    }

    @Test fun thumb_local_isReady() = runBlocking {
        val repoImpl = impl()
        repoImpl.add("t1", uri)
        val s = repoImpl.thumb(fullSha).first()
        assertTrue(s is BlobState.Ready)
    }

    @Test fun thumb_remoteOnly_downloads() = runBlocking {
        remote.files[Attachment.thumbPath(otherSha)] = byteArrayOf(5, 6)
        val s = withTimeout(5000) { impl().thumb(otherSha).first { it is BlobState.Ready } } as BlobState.Ready
        assertTrue(s.file.readBytes().contentEquals(byteArrayOf(5, 6)))
    }

    @Test fun missing_staysMissing_untilNextRound_thenHeals() = runBlocking {
        val repoImpl = impl()
        assertEquals(BlobState.Missing, withTimeout(5000) { repoImpl.thumb(otherSha).first { it is BlobState.Missing } })
        // A new observer does not retry before the next round.
        val downloadsBefore = remote.log.count { it.startsWith("down") }
        assertEquals(BlobState.Missing, repoImpl.thumb(otherSha).first())
        assertEquals(downloadsBefore, remote.log.count { it.startsWith("down") })
        // Another device uploads it; the next round retries.
        remote.files[Attachment.thumbPath(otherSha)] = byteArrayOf(1)
        repoImpl.onRoundFinished("tok")
        assertTrue(withTimeout(5000) { repoImpl.thumb(otherSha).first { it is BlobState.Ready } } is BlobState.Ready)
    }

    @Test fun full_withWrongSha_isNotStored() = runBlocking {
        val sha = "0".repeat(64)
        remote.files[Attachment.fullPath(sha)] = byteArrayOf(1, 2)
        assertEquals(BlobState.Missing, withTimeout(5000) { impl().full(sha).first { it is BlobState.Missing } })
        assertFalse(File(root, "$sha.jpg").exists())
    }

    @Test fun noToken_isMissing() = runBlocking {
        token = null
        assertEquals(BlobState.Missing, withTimeout(5000) { impl().thumb(otherSha).first { it is BlobState.Missing } })
    }

    @Test fun pwaUid_shape() {
        val id = AttachmentRepositoryImpl.pwaUid(1791460100000L, Random(1))
        assertEquals(1791460100000L.toString(36), id.dropLast(5))
        assertEquals(5, id.length - 1791460100000L.toString(36).length)
    }

    // ---- review fixes ---------------------------------------------------------------------------

    @Test fun invalidSha_isMissing_withoutFileOrNetworkAccess() = runBlocking {
        val repoImpl = impl()
        for (bad in listOf("../../evil", "abc", "A".repeat(64), "a".repeat(63) + "/")) {
            remote.files[Attachment.thumbPath(bad)] = byteArrayOf(1)
            remote.files[Attachment.fullPath(bad)] = byteArrayOf(1)
            assertEquals(bad, BlobState.Missing, withTimeout(5000) { repoImpl.thumb(bad).first() })
            assertEquals(bad, BlobState.Missing, withTimeout(5000) { repoImpl.full(bad).first() })
            repoImpl.invalidateThumb(bad)
        }
        repoImpl.onRoundFinished("tok")
        db.attachmentUploadDao().enqueue(com.example.data.local.entities.AttachmentUploadEntity("../../evil", 1))
        repoImpl.uploadPending("tok")
        assertTrue("an invalid queue row can never upload: dropped", db.attachmentUploadDao().pending().isEmpty())
        assertTrue(remote.log.toString(), remote.log.isEmpty())
        assertFalse(root.exists() && root.walk().any { it.isFile })
    }

    @Test fun writeAtomic_keepsAnExistingFile_andParallelWritesAllSucceed() = runBlocking {
        val repoImpl = impl()
        val target = File(root, "$fullSha.jpg")
        repoImpl.writeAtomic(target, byteArrayOf(1))
        repoImpl.writeAtomic(target, byteArrayOf(2)) // content-addressed: the first copy stays
        assertTrue(target.readBytes().contentEquals(byteArrayOf(1)))
        val fresh = File(root, "${otherSha}.jpg")
        (1..32).map { scope.async { repoImpl.writeAtomic(fresh, byteArrayOf(7, 7)) } }.awaitAll()
        assertTrue(fresh.readBytes().contentEquals(byteArrayOf(7, 7)))
        assertEquals("no temp files left", listOf("$fullSha.jpg", "$otherSha.jpg").sorted(), root.list()!!.sorted())
    }

    @Test fun uploadPending_missingLocalFile_keepsTheRow_andGoesOn() = runBlocking {
        val repoImpl = impl()
        db.attachmentUploadDao().enqueue(com.example.data.local.entities.AttachmentUploadEntity(otherSha, 0))
        repoImpl.add("t1", uri)
        repoImpl.uploadPending("tok")
        assertEquals(listOf(otherSha), db.attachmentUploadDao().pending().map { it.sha })
        assertEquals(listOf("up /attachments/$fullSha.jpg", "up /attachments/${fullSha}_t.jpg"), remote.log)
    }

    @Test fun roundThatSeesMissingBeforeInFlightIsCleared_stillDownloads() = runBlocking {
        val repoImpl = impl()
        val st = repoImpl.stateOf(repoImpl.thumbFile(otherSha))
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        // Unconfined: the collector runs inside the `st.value = Missing` write, i.e. at the
        // earliest moment a round can observe Missing.
        val watcher = launch(Dispatchers.Unconfined) {
            st.collect {
                if (it is BlobState.Missing && fired.compareAndSet(false, true)) {
                    remote.files[Attachment.thumbPath(otherSha)] = byteArrayOf(3)
                    repoImpl.onRoundFinished("tok")
                }
            }
        }
        repoImpl.thumb(otherSha).first() // starts the first (failing) download
        val s = withTimeout(5000) { st.first { it is BlobState.Ready } }
        assertTrue(s is BlobState.Ready)
        watcher.cancel()
    }

    @Test fun invalidateThumb_deletesTheFile_marksMissing_nextRoundHeals() = runBlocking {
        val repoImpl = impl()
        val bad = File(root, "${otherSha}_t.jpg")
        repoImpl.writeAtomic(bad, byteArrayOf(0)) // truncated / undecodable
        assertTrue(repoImpl.thumb(otherSha).first() is BlobState.Ready)
        repoImpl.invalidateThumb(otherSha)
        assertFalse(bad.exists())
        assertEquals(BlobState.Missing, withTimeout(5000) { repoImpl.thumb(otherSha).first() })
        remote.files[Attachment.thumbPath(otherSha)] = byteArrayOf(4, 4)
        repoImpl.onRoundFinished("tok")
        val s = withTimeout(5000) { repoImpl.thumb(otherSha).first { it is BlobState.Ready } } as BlobState.Ready
        assertTrue(s.file.readBytes().contentEquals(byteArrayOf(4, 4)))
    }

    @Test fun add_outOfMemoryInEncode_isFailed_notACrash() = runBlocking {
        val r = impl(ImageEncoder { throw OutOfMemoryError("big") }).add("t1", uri)
        assertTrue(r is AddResult.Failed)
        assertTrue(repo.getTaskByIdSync("t1")!!.attachments.isEmpty())
    }
}
