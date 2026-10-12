package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.sync.SyncWorker
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KT-62 (fix plan P9): the 15-minute periodic sync runs only when the phone has a network.
 * A plain [Application] is used so QuestaApplication.onCreate does not enqueue anything first;
 * the test calls the [enqueuePeriodicSync] seam directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeriodicSyncRequestTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun init() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    private fun wm() = WorkManager.getInstance(app)

    private fun single() = wm().getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK_NAME).get().single()

    @Test fun enqueue_requiresConnectedNetwork() {
        enqueuePeriodicSync(wm()).result.get()
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** P9 policy rule: always UPDATE. A second process start keeps the same work and its clock. */
    @Test fun enqueueTwice_keepsWorkIdAndNextScheduleTime() {
        enqueuePeriodicSync(wm()).result.get()
        val first = single()
        enqueuePeriodicSync(wm()).result.get()
        val second = single()
        assertEquals(first.id, second.id)
        assertEquals(first.nextScheduleTimeMillis, second.nextScheduleTimeMillis)
        assertEquals(NetworkType.CONNECTED, second.constraints.requiredNetworkType)
    }

    /** An install that already has the old KEEP work (no network rule) gets the rule on next start. */
    @Test fun existingWorkWithoutConstraint_isUpdatedInPlace() {
        wm().enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        ).result.get()
        val old = single()
        assertEquals(NetworkType.NOT_REQUIRED, old.constraints.requiredNetworkType)
        enqueuePeriodicSync(wm()).result.get()
        val now = single()
        assertEquals(old.id, now.id)
        assertEquals(NetworkType.CONNECTED, now.constraints.requiredNetworkType)
    }
}
