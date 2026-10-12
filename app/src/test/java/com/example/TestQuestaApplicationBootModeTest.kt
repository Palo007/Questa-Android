package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.clock.StartupBarrier
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Test isolation: the default app boots quietly; the real boot is an explicit opt-in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestQuestaApplicationQuietBootTest {
    @Test fun defaultApp_buildsFields_butRunsNoStartupWork() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>() as QuestaApplication
        assertNotNull(app.syncManager)
        assertNotNull(app.sectionImporter)
        val job = app.startupMigrationsJob!!
        assertTrue("StartupMigrations must not run in a quiet boot", job.isCancelled)
        assertTrue("quiet boot opens the barrier itself", StartupBarrier.done.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BootingTestQuestaApplication::class)
class TestQuestaApplicationRealBootTest {
    @Test fun optIn_runsStartupMigrations() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>() as QuestaApplication
        val job = app.startupMigrationsJob!!
        withTimeout(30_000) { job.join() }
        assertFalse("the opted-in boot runs StartupMigrations to the end", job.isCancelled)
        assertTrue(StartupBarrier.done.value)
    }
}
