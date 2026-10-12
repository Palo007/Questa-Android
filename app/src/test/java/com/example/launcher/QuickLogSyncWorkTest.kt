package com.example.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review 34 item 2: taps enqueue with APPEND_OR_REPLACE (Bubblewrap InboxUploadWorker.kt:85), so a
 * tap made while a round is pending or running gets its own round after it (KEEP would drop it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogSyncWorkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun init() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @Test fun secondTapWhileTheFirstRoundWaits_isAppended_notDropped() {
        QuickLog.enqueueSync(app) // no network: stays ENQUEUED
        QuickLog.enqueueSync(app)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(QuickLog.SYNC_WORK_NAME).get()
        assertEquals(2, infos.size)
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), infos.map { it.state }.toSet())
    }
}
