package com.example.debug

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.clock.DebugClockOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WP-8.12-T1: the adb receiver (debug source set only). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugClockReceiverTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { DebugClockOffset.set(ctx, 0L) }
    @After fun tearDown() { DebugClockOffset.set(ctx, 0L) }

    private fun handle(action: String, offset: Long? = null): Pair<Int, String> {
        val i = Intent(ctx.packageName + action)
        if (offset != null) i.putExtra(DebugClockReceiver.EXTRA_OFFSET_MS, offset)
        return DebugClockReceiver.handle(ctx, i)
    }

    @Test fun `set, read back and clear`() {
        assertEquals(1 to "offsetMs=86400000", handle(DebugClockReceiver.ACTION_SET_SUFFIX, 86_400_000L))
        assertEquals(86_400_000L, DebugClockOffset.offsetMs())
        assertEquals(1 to "offsetMs=86400000", handle(DebugClockReceiver.ACTION_GET_SUFFIX))
        assertEquals(1 to "offsetMs=0", handle(DebugClockReceiver.ACTION_SET_SUFFIX, 0L))
        assertEquals(0L, DebugClockOffset.offsetMs())
    }

    @Test fun `set without the extra is refused and changes nothing`() {
        handle(DebugClockReceiver.ACTION_SET_SUFFIX, 5L)
        val (code, data) = handle(DebugClockReceiver.ACTION_SET_SUFFIX)
        assertEquals(-1, code)
        assertEquals(true, data.startsWith("error"))
        assertEquals(5L, DebugClockOffset.offsetMs())
    }

    /** Review F8: only adb shell / system (holders of DUMP) may send it. */
    @Test fun `receiver is exported but requires the DUMP permission`() {
        val info = ctx.packageManager.getReceiverInfo(android.content.ComponentName(ctx, DebugClockReceiver::class.java), 0)
        assertEquals("android.permission.DUMP", info.permission)
        assertEquals(true, info.exported)
    }

    @Test fun `unknown action is refused`() {
        assertEquals(-1, handle(".SOMETHING_ELSE", 5L).first)
        assertEquals(0L, DebugClockOffset.offsetMs())
    }
}
