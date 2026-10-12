package com.example.data.attachments

import com.example.data.sync.SyncResult as RoundResult
import com.example.data.sync.SyncManager
import com.example.data.sync.Wp89TestBase
import com.example.data.sync.merge.FakeStableMerger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CR-KT-015: blobs go up BEFORE /state.json; a blob failure never blocks the state push. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentRoundHookTest : Wp89TestBase() {

    private inner class Recorder(val fail: Boolean) : AttachmentBlobSync {
        val calls = mutableListOf<String>()
        override suspend fun uploadPending(accessToken: String) {
            calls += "upload@stateUploads=${fake.stateUploadAttempts}"
            if (fail) throw RuntimeException("network down")
        }
        override suspend fun onRoundFinished(accessToken: String) {
            calls += "finished@stateUploads=${fake.stateUploadAttempts}"
        }
    }

    private suspend fun round(rec: Recorder): RoundResult? {
        val d = device89("dev-a")
        d.link()
        val m = SyncManager(
            context, d.repository, d.syncDataStore, null, d.tokens,
            merger = FakeStableMerger(), hlcClock = d.hlc, roundSteps = emptyList(), blobSync = rec,
        )
        return m.sync()
    }

    @Test fun blobsUploadBeforeState_thenRetryHookAfterRound() = runBlocking {
        val rec = Recorder(fail = false)
        val r = round(rec)
        assertEquals(RoundResult.Ok, r)
        assertEquals(listOf("upload@stateUploads=0", "finished@stateUploads=1"), rec.calls)
    }

    @Test fun blobFailure_doesNotBlockStatePush() = runBlocking {
        val rec = Recorder(fail = true)
        val r = round(rec)
        assertEquals(RoundResult.Ok, r)
        assertTrue(fake.stateFile != null)
        assertEquals(1, fake.stateUploadAttempts)
    }
}
