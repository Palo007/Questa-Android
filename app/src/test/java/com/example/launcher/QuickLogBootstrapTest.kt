package com.example.launcher

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.data.sync.auth.AuthState
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.OPEN_SETTINGS_DROPBOX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WP-8.13 (PX-79, PX-80): cold-start order and the Room observer (replaces the 6 h worker). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogBootstrapTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val auth = MutableStateFlow(AuthState.NOT_LINKED)
    private val habits = MutableStateFlow(listOf(qh("h1", "H1", 1)))
    private val barrier = CompletableDeferred<Unit>()
    private var pending = false

    private fun deps() = QuickLogBootstrap.Deps(
        habits = habits,
        auth = auth,
        publish = { a, h -> log += "publish:$a:${h?.joinToString(",") { it.id + it.dirs }}" },
        rearm = { log += "rearm" },
        hasPendingTaps = { pending },
        enqueueSync = { log += "sync" },
        awaitBarrier = { barrier.await(); log += "barrier" },
    )

    private suspend fun waitFor(n: Int) = withTimeout(5000) { while (log.size < n) delay(10) }

    @Test fun waitsForBarrier_thenPublish_rearm_observerRepublishesWithinSeconds() = runBlocking {
        val job = QuickLogBootstrap.start(app, CoroutineScope(Dispatchers.Default)) { deps() }
        delay(100)
        assertTrue("nothing before the StartupBarrier opens", log.isEmpty())
        barrier.complete(Unit)
        waitFor(3)
        assertEquals(listOf("barrier", "publish:NOT_LINKED:h1[1]", "rearm"), log.take(3))
        // Observer: an unflag and a link each republish (§6.4 "the menu updates within 2 s").
        habits.value = listOf(qh("h1", "H1"))
        waitFor(4)
        auth.value = AuthState.LINKED
        waitFor(5)
        assertEquals("publish:LINKED:h1[]", log.last())
        job.cancelAndJoin()
        assertTrue("not linked at start: no sync", "sync" !in log)
    }

    @Test fun linkedWithPendingTaps_enqueuesSync() = runBlocking {
        auth.value = AuthState.LINKED
        pending = true
        barrier.complete(Unit)
        val job = QuickLogBootstrap.start(app, CoroutineScope(Dispatchers.Default)) { deps() }
        waitFor(4)
        assertEquals(listOf("barrier", "publish:LINKED:h1[1]", "rearm", "sync"), log.take(4))
        job.cancelAndJoin()
    }

    @Test fun linkedWithoutPendingTaps_noSync_andAFailingStepNeverStopsTheRest() = runBlocking {
        auth.value = AuthState.LINKED
        barrier.complete(Unit)
        val d = deps()
        val failing = QuickLogBootstrap.Deps(d.habits, d.auth, { _, _ -> log += "publish"; error("boom") }, d.rearm, d.hasPendingTaps, d.enqueueSync, d.awaitBarrier)
        val job = QuickLogBootstrap.start(app, CoroutineScope(Dispatchers.Default)) { failing }
        waitFor(3)
        delay(100)
        assertEquals(listOf("barrier", "publish", "rearm"), log.take(3))
        assertTrue("sync" !in log)
        job.cancelAndJoin()
    }

    @Test fun depsThatThrow_neverCrash() = runBlocking {
        QuickLogBootstrap.onAppCreate(app) { error("no app") }
    }
}

/** WP-8.13: the Android Settings > Apps > Questa gear (APPLICATION_PREFERENCES) opens Settings -> Dropbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogSettingsActivityTest {
    @Test fun trampoline_opensMainActivitySettingsDropbox_andFinishes() {
        val a = Robolectric.buildActivity(QuickLogSettingsActivity::class.java, Intent("android.intent.action.APPLICATION_PREFERENCES")).create().get()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertEquals(OPEN_SETTINGS_DROPBOX, next.getStringExtra(EXTRA_OPEN_SETTINGS))
        assertTrue(a.isFinishing)
    }
}
