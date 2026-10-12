package com.example.data.clock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.data.sync.SyncClocks
import com.example.ui.settings.Diagnostics
import com.example.ui.settings.DiagnosticsCapture
import com.example.ui.settings.DiagnosticsSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.12-T1: the debug-only app clock offset (live scenario 8b without touching the phone clock). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugClockOffsetTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val day = 86_400_000L

    @Before fun setUp() { DebugClockOffset.enabled = true; DebugClockOffset.set(ctx, 0L) }
    @After fun tearDown() { DebugClockOffset.enabled = true; DebugClockOffset.set(ctx, 0L); DebugClockOffset.enabled = BuildConfig.DEBUG_CLOCK_ENABLED }

    @Test fun `debug build enables it, default offset is 0`() {
        assertTrue(BuildConfig.DEBUG_CLOCK_ENABLED)
        assertEquals(0L, DebugClockOffset.offsetMs())
    }

    @Test fun `offset shifts PhysicalClock but not the real transport clock`() {
        DebugClockOffset.set(ctx, day)
        val real = System.currentTimeMillis()
        assertTrue(PhysicalClock.nowMs() - real in (day - 1_000)..(day + 1_000))
        assertTrue(PhysicalClock.realNowMs() - real in -1_000L..1_000L)
        assertTrue(SyncClocks.transport() - real in -1_000L..1_000L)
        for (c in listOf(SyncClocks.mergeNow, SyncClocks.tap, SyncClocks.tombstone, SyncClocks.wrapperSavedAt, SyncClocks.backupLocal)) {
            assertTrue(c() - real in (day - 1_000)..(day + 1_000))
        }
    }

    @Test fun `HLC stamps follow the offset clock`() {
        DebugClockOffset.set(ctx, day)
        val hlc = DefaultHlcClock(seed = 0L, persist = {})
        val real = System.currentTimeMillis()
        assertTrue(hlc.now() - real in (day - 1_000)..(day + 1_000))
    }

    @Test fun `offset is persisted and reloaded after a process restart`() {
        DebugClockOffset.set(ctx, 3 * day)
        DebugClockOffset.resetInMemoryForTest()
        assertEquals(0L, DebugClockOffset.offsetMs())
        DebugClockOffset.load(ctx)
        assertEquals(3 * day, DebugClockOffset.offsetMs())
        DebugClockOffset.set(ctx, 0L)
        DebugClockOffset.resetInMemoryForTest(); DebugClockOffset.load(ctx)
        assertEquals(0L, DebugClockOffset.offsetMs())
    }

    @Test fun `disabled (release) build ignores a stored offset and refuses to set one`() {
        DebugClockOffset.set(ctx, day)
        DebugClockOffset.enabled = false
        assertEquals(0L, DebugClockOffset.offsetMs())
        assertFalse(DebugClockOffset.set(ctx, 2 * day))
        DebugClockOffset.load(ctx)
        assertEquals(0L, DebugClockOffset.offsetMs())
        val real = System.currentTimeMillis()
        assertTrue(PhysicalClock.nowMs() - real in -1_000L..1_000L)
    }

    @Test fun `diagnostics shows clock offsetMs`() {
        val rows = Diagnostics.rows(DiagnosticsCapture(DiagnosticsSnapshot(clockOffsetMs = day), emptyList())).toMap()
        assertEquals(day.toString(), rows["clock.offsetMs"])
    }
}
