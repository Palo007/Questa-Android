package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.clock.BarrierState
import com.example.data.clock.StartupBarrier
import com.example.data.local.dao.TaskDao
import com.example.data.sync.SyncWorker
import com.example.reminders.ReminderChecker
import com.example.reminders.ReminderForegroundObserver
import com.example.reminders.ReminderObserver
import com.example.reminders.ReminderScheduler
import com.example.startup.StartupMigrations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

private fun src(rel: String): String =
    listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.isFile }.readText().replace("\r\n", "\n")

/** WP-8.22-D item 1 + 2: the barrier can report a known failure without ever opening. */
class BarrierOutcomeTest {
    @Test fun `awaitOutcome is true once done`() = runTest {
        val b = BarrierState()
        val r = async { b.awaitOutcome() }
        runCurrent()
        assertFalse(r.isCompleted)
        b.markDone()
        assertTrue(r.await())
    }

    @Test fun `awaitOutcome is false once failed, and await stays closed`() = runTest {
        val b = BarrierState()
        val r = async { b.awaitOutcome() }
        runCurrent()
        b.markFailed()
        assertFalse(r.await())
        assertFalse(b.done.value)
        assertNull(withTimeoutOrNull(60_000) { b.await() }) // §5.0: a failure never opens the barrier
    }

    @Test fun `markFailed after markDone is a no-op`() = runTest {
        val b = BarrierState()
        b.markDone()
        b.markFailed()
        assertFalse(b.failed.value)
        assertTrue(b.awaitOutcome())
    }

    @After fun resetGlobals() = StartupMigrations.resetStatusForTest() // review 46 F6

    @Test fun `publishing startup_migration_failed marks the real barrier`() {
        StartupMigrations.publish(StartupMigrations.STATUS_FAILED)
        // done may already be true in this JVM (other tests open it); then markFailed is a no-op.
        assertTrue(StartupBarrier.failed.value || StartupBarrier.done.value)
    }

    @Test fun `the reset seam clears the status and the failed flag`() {
        StartupMigrations.publish(StartupMigrations.STATUS_FAILED)
        StartupMigrations.resetStatusForTest()
        assertNull(StartupMigrations.status.value)
        assertFalse(StartupBarrier.failed.value)
    }
}

/** WP-8.22-D item 1: SyncWorker skips at once when the start-up failed (no 10-minute hang). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncWorkerStartupFailedTest {
    private val original = SyncWorker.awaitStartup
    @After fun restore() { SyncWorker.awaitStartup = original }

    @Test fun `failed start-up returns success without building a round`() = runBlocking {
        var asked = 0
        SyncWorker.awaitStartup = { asked++; false }
        val worker = TestListenableWorkerBuilder<SyncWorker>(ApplicationProvider.getApplicationContext()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, asked)
    }

    @Test fun `doWork checks the start-up outcome before the round, defaulting to awaitOutcome`() {
        val s = src("java/com/example/data/sync/SyncWorker.kt")
        val body = s.substringAfter("override suspend fun doWork()")
        assertTrue(body.indexOf("awaitStartup()") in 0 until body.indexOf("syncManager.sync("))
        assertTrue(s.substringAfter("var awaitStartup").contains("StartupBarrier.awaitOutcome()"))
    }
}

/** WP-8.22-D item 1 + review 46 F1: the dashboard shows the start-up error (the Settings text). */
class StartupFailedDashboardWiringTest {
    private val s = src("java/com/example/ui/DashboardScreen.kt")

    @Test fun `loaded dashboard shows StartupFailedBanner`() {
        assertTrue(s.contains("StartupFailedBanner(settingsUi.startupFailed)"))
    }

    @Test fun `no character plus failed start-up shows the placeholder, else the spinner`() {
        val noChar = s.substringAfter("if (character == null)").substringBefore("OptiHeader(")
        assertTrue(noChar.contains("settingsUi.startupFailed"))
        assertTrue(noChar.contains("StartupFailedPlaceholder("))
        assertTrue(noChar.contains("openSettings("))
        assertTrue(noChar.contains("CircularProgressIndicator("))
    }
}

/** WP-8.22-D item 2 + 4: the redirect waits for start-up and is handled once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RedirectOnceTest {
    private val redirect = "questa://oauth?code=abc&state=s1"

    @Test fun `redirect is taken once and removed from the intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(redirect))
        assertEquals(redirect, takeRedirectOnce(intent, null))
        assertNull(intent.data)
        assertNull(takeRedirectOnce(intent, null))
    }

    @Test fun `a rotation re-delivering the used code is skipped`() {
        // First create: handled, then saved in onSaveInstanceState.
        val handled = takeRedirectOnce(Intent(Intent.ACTION_VIEW, Uri.parse(redirect)), null)
        // Recreate: the system hands the launching intent again, the saved code comes back.
        assertNull(takeRedirectOnce(Intent(Intent.ACTION_VIEW, Uri.parse(redirect)), handled))
        // A new login (another code) is still handled.
        val next = "questa://oauth?code=def&state=s2"
        assertEquals(next, takeRedirectOnce(Intent(Intent.ACTION_VIEW, Uri.parse(next)), handled))
    }

    @Test fun `no data, no redirect`() {
        assertNull(takeRedirectOnce(Intent(), null))
        assertNull(takeRedirectOnce(null, null))
    }

    @Test fun `redirect handling waits for the barrier`() = runTest {
        val b = BarrierState()
        val seen = mutableListOf<String>()
        val r = async { handleRedirectAfterStartup(redirect, { b.awaitOutcome() }) { seen += it } }
        runCurrent()
        assertTrue(seen.isEmpty()) // still migrating: no token exchange yet
        b.markDone()
        assertTrue(r.await())
        assertEquals(listOf(redirect), seen)
    }

    @Test fun `failed start-up never runs the redirect (pending force push kept)`() = runTest {
        val seen = mutableListOf<String>()
        assertFalse(handleRedirectAfterStartup(redirect, { false }) { seen += it })
        assertTrue(seen.isEmpty())
    }

    @Test fun `MainActivity wires the once-only redirect in onCreate, onNewIntent and the saved state`() {
        val s = src("java/com/example/MainActivity.kt")
        val onCreate = s.substringAfter("override fun onCreate(").substringBefore("override fun onNewIntent(")
        val onNewIntent = s.substringAfter("override fun onNewIntent(").substringBefore("override fun onSaveInstanceState(")
        val onSave = s.substringAfter("override fun onSaveInstanceState(").substringBefore("private fun ")
        assertTrue(onCreate.contains("STATE_HANDLED_REDIRECT"))
        assertTrue(onCreate.contains("takeRedirectOnce("))
        assertTrue(onNewIntent.contains("takeRedirectOnce("))
        assertTrue(onSave.contains("STATE_HANDLED_REDIRECT"))
        val handler = s.substringAfter("private fun handleOAuthRedirect(").substringBefore("private suspend fun handleOAuthRedirectNow(")
        assertTrue(handler.contains("handledRedirect"))
        assertTrue(handler.contains("viewModelScope"))
        assertTrue(handler.contains("handleRedirectAfterStartup("))
        // The ViewModels exist before the redirect is handled (it runs in the SettingsViewModel scope).
        assertTrue(onCreate.indexOf("SettingsViewModel") < onCreate.indexOf("takeRedirectOnce("))
    }
}

/** Review 41 should-fix: MainActivity stays singleTask and the Custom Tabs <queries> entry is there. */
class ManifestCustomTabTest {
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile })
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test fun `MainActivity is singleTask`() {
        val acts = doc.getElementsByTagName("activity")
        val main = (0 until acts.length).map { acts.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(ns, "launchMode"))
    }

    @Test fun `queries declares the CustomTabsService intent`() {
        val queries = doc.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        val actions = (queries.item(0) as org.w3c.dom.Element).getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
        assertTrue(names.contains("android.support.customtabs.action.CustomTabsService"))
    }
}

/** WP-8.22-D item 3: reminder coroutines in applicationScope log and stop instead of crashing. */
class ReminderObserverCrashTest {
    @Test fun `a DB failure stops the observer without an uncaught exception`() = runTest {
        val dao = mockk<TaskDao>()
        every { dao.observeTasksWithReminders() } returns flow { throw IOException("db open failed") }
        val scheduler = mockk<ReminderScheduler>(relaxed = true)
        val job = ReminderObserver(dao, scheduler).start(this) // runTest fails on an uncaught child exception
        job.join()
        assertFalse(job.isCancelled)
        verify(exactly = 0) { scheduler.rearm(any(), any()) }
    }

    @Test fun `a failed missed-reminder check does not crash`() = runTest {
        val checker = mockk<ReminderChecker>()
        coEvery { checker.checkMissed(any()) } throws IOException("db open failed")
        val obs = ReminderForegroundObserver(checker, this, clock = { 0L })
        obs.onStart(mockk(relaxed = true))
        obs.lastJob!!.join()
        assertFalse(obs.lastJob!!.isCancelled)
    }
}
