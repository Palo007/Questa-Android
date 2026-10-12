package com.example.launcher

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.LocalTapQueue
import com.example.data.TapRecord
import com.example.data.TapResult
import com.example.data.boot.BootGate
import com.example.data.local.entities.TaskEntity
import com.example.data.sync.RoundDevice
import com.example.data.sync.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** WP-8.13 card: "6 toast strings are equal" (Bubblewrap QuickLogActivity.kt:21-37, Opti app.js:2456, m-N5). */
class QuickLogToastMapTest {
    @Test fun sixStrings_exact() {
        assertEquals("Logged. Connect Dropbox to sync.", QuickLog.message(TapResult.APPLIED, AuthState.NOT_LINKED, "Water", 1))
        assertEquals("Logged. Reconnect Dropbox to sync.", QuickLog.message(TapResult.APPLIED, AuthState.RECONNECT_REQUIRED, "Water", -1))
        assertEquals("Water +1 logged ✓", QuickLog.message(TapResult.APPLIED, AuthState.LINKED, "Water", 1))
        assertEquals("Water −1 logged ✓", QuickLog.message(TapResult.APPLIED, AuthState.LINKED, "Water", -1))
        assertEquals("Late log saved to history · Water", QuickLog.message(TapResult.EVENT_ONLY, AuthState.LINKED, "Water", 1))
        assertEquals("Could not save the log for Water", QuickLog.message(TapResult.ERROR, AuthState.LINKED, "Water", 1))
    }

    @Test fun allSixResults_mapped() {
        for (a in AuthState.values()) {
            val logged = QuickLog.message(TapResult.APPLIED, a, "T", 1)
            assertEquals(logged, QuickLog.message(TapResult.ALREADY_APPLIED, a, "T", 1))
            assertEquals("a queued tap reads as logged", logged, QuickLog.message(TapResult.GATED, a, "T", 1))
            assertEquals("Could not save the log for T", QuickLog.message(TapResult.UNKNOWN, a, "T", 1))
            assertEquals("Could not save the log for T", QuickLog.message(null, a, "T", 1))
            assertEquals("Late log saved to history · T", QuickLog.message(TapResult.EVENT_ONLY, a, "T", 1))
        }
    }

    @Test fun normalisation_asBubblewrap() {
        assertEquals("Habit", QuickLog.normTitle(null))
        assertEquals("Habit", QuickLog.normTitle("  "))
        assertEquals(1, QuickLog.normDir(0))
        assertEquals(-1, QuickLog.normDir(-1))
        assertEquals(-120, QuickLog.tzOffsetMin(TimeZone.getTimeZone("Europe/Bratislava"), 1_790_000_000_000L))
    }

    @Test fun handleTap_buildsRecord_andThrowIsFailure() = runBlocking {
        var got: TapRecord? = null
        val deps = QuickLogDeps(enqueue = { got = it; TapResult.APPLIED }, authState = { AuthState.LINKED }, io = Dispatchers.Unconfined, newId = { "u1" })
        val msg = QuickLog.handleTap("h1", "Water", -1, 1234L, TimeZone.getTimeZone("UTC"), deps)
        assertEquals("Water −1 logged ✓", msg)
        assertEquals(TapRecord("u1", "h1", -1, 1234L, 0, "android-shortcut"), got)
        val boom = QuickLogDeps(enqueue = { throw IllegalStateException() }, authState = { AuthState.LINKED }, io = Dispatchers.Unconfined)
        assertEquals("Could not save the log for Water", QuickLog.handleTap("h1", "Water", 1, 1L, TimeZone.getDefault(), boom))
        assertEquals(QuickLog.NO_HABIT, QuickLog.handleTap(null, "Water", 1, 1L, TimeZone.getDefault(), boom))
        assertEquals(QuickLog.NO_HABIT, QuickLog.handleTap("", "Water", 1, 1L, TimeZone.getDefault(), boom))
    }

    @Test fun afterTap_seesResultAndAuth() = runBlocking {
        var seen: Pair<TapResult, AuthState>? = null
        val deps = QuickLogDeps({ TapResult.GATED }, { AuthState.RECONNECT_REQUIRED }, { r, a -> seen = r to a }, Dispatchers.Unconfined)
        QuickLog.handleTap("h1", "W", 1, 1L, TimeZone.getDefault(), deps)
        assertEquals(TapResult.GATED to AuthState.RECONNECT_REQUIRED, seen)
    }
}

/** WP-8.13 card: QuickLogActivityToastTest -- headless: a toast, finish, never MainActivity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickLogActivityToastTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val saved = QuickLogRuntime.provider
    private var taps = mutableListOf<TapRecord>()

    @Before fun fake() {
        QuickLogRuntime.provider = { QuickLogDeps({ taps += it; TapResult.APPLIED }, { AuthState.LINKED }, io = Dispatchers.Unconfined) }
    }

    @After fun restore() { QuickLogRuntime.provider = saved }

    private fun launch(i: Intent): android.app.Activity {
        val c = Robolectric.buildActivity(QuickLogActivity::class.java, i).create()
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun tapIntent(id: String?, title: String?, dir: Int) = Intent(QuickLogActivity.ACTION).setClass(app, QuickLogActivity::class.java).apply {
        if (id != null) putExtra(QuickLogActivity.EXTRA_HABIT_ID, id)
        if (title != null) putExtra(QuickLogActivity.EXTRA_TITLE, title)
        putExtra(QuickLogActivity.EXTRA_DIR, dir)
    }

    @Test fun tapPlusOne_toast_finish_noMainActivity() {
        val a = launch(tapIntent("h2", "H2", 1))
        assertEquals("H2 +1 logged ✓", ShadowToast.getTextOfLatestToast())
        assertTrue(a.isFinishing)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, taps.size)
        assertEquals("h2", taps[0].habitId)
        assertEquals("android-shortcut", taps[0].src)
        assertTrue(taps[0].ts > 0)
    }

    @Test fun tapMinusOne_blankTitle_readsHabit() {
        launch(tapIntent("h1", " ", -1))
        assertEquals("Habit −1 logged ✓", ShadowToast.getTextOfLatestToast())
    }

    @Test fun noHabitId_message_noTap() {
        val a = launch(tapIntent(null, "H", 1))
        assertEquals(QuickLog.NO_HABIT, ShadowToast.getTextOfLatestToast())
        assertTrue(a.isFinishing)
        assertTrue(taps.isEmpty())
    }

    /** Review 34 item 1: a config change while the tap is still applying must not queue a second tap. */
    @Test fun recreatedWhileApplying_exactlyOneTap_toastStillShown() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        QuickLogRuntime.provider = {
            QuickLogDeps({ gate.await(); taps += it; TapResult.APPLIED }, { AuthState.LINKED }, io = Dispatchers.Unconfined)
        }
        val c = Robolectric.buildActivity(QuickLogActivity::class.java, tapIntent("h2", "H2", 1)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        c.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the recreated instance finishes at once", c.get().isFinishing)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, taps.size)
        assertEquals("H2 +1 logged ✓", ShadowToast.getTextOfLatestToast())
    }

    @Test fun twoTaps_twoRecords_distinctIds() {
        launch(tapIntent("h1", "H1", -1))
        launch(tapIntent("h1", "H1", -1))
        assertEquals(2, taps.size)
        assertTrue(taps[0].id != taps[1].id)
    }
}

/**
 * WP-8.13 card: "A tap while the gate is closed -> queued, then applied after the gate opens"
 * (§6.4 cold start: PENDING_TAPS has 1 record, cUp +1 exactly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatedTapQueuedTest {
    private val zone: ZoneId = ZoneId.of("Europe/Bratislava")
    private val now = LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var dev: RoundDevice

    @Before fun setUp() = runBlocking {
        dev = RoundDevice(ApplicationProvider.getApplicationContext(), "dev-ql")
        BootGate.resetForTest(CoroutineScope(Dispatchers.Default), { now }, zone)
        dev.repository.insertTask(TaskEntity(id = "h2", type = "habit", title = "H2", difficulty = "easy", updatedAt = 1L, createdAt = com.example.data.clock.PhysicalClock.nowMs()))
    }

    @After fun tearDown() { dev.close(); com.example.data.boot.restoreRealBootClock() }

    @Test fun gateClosed_queuedAndReadsLogged_thenAppliedOnceWhenGateOpens() = runBlocking {
        val q = LocalTapQueue(dev.syncDataStore, dev.repository, zone = { zone }, physicalNow = { now })
        BootGate.bindTapDrain { q.drain(it) }
        BootGate.bootStartDay(configured = true, barrierDone = true)
        assertTrue(BootGate.blocksInput())

        val deps = QuickLogDeps({ q.enqueue(it, now) }, { AuthState.LINKED }, io = Dispatchers.Unconfined)
        val msg = QuickLog.handleTap("h2", "H2", 1, now - 1000, TimeZone.getTimeZone(zone), deps)
        assertEquals("H2 +1 logged ✓", msg)
        assertEquals(1, q.pending().size)
        assertEquals(0, dev.repository.getTaskByIdSync("h2")!!.cUp)

        BootGate.onFirstRoundSettled(null)
        withTimeout(5000) { while (q.pending().isNotEmpty() || dev.repository.getTaskByIdSync("h2")!!.cUp == 0) delay(20) }
        assertEquals(1, dev.repository.getTaskByIdSync("h2")!!.cUp)
        // A second drain never scores it again.
        q.drain(now)
        assertEquals(1, dev.repository.getTaskByIdSync("h2")!!.cUp)
    }
}
