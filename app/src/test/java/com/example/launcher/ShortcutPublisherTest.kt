package com.example.launcher

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.data.inbox.QuickLogHabit
import com.example.data.sync.auth.AuthState
import com.example.ui.settings.EXTRA_OPEN_SETTINGS
import com.example.ui.settings.SettingsRoute
import com.example.ui.settings.settingsRouteForExtra
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

internal class MemSig : ShortcutPublisher.SigStore {
    var value: String? = null
    override suspend fun get() = value
    override suspend fun set(value: String) { this.value = value }
}

internal fun qh(id: String, title: String, vararg dirs: Int) = QuickLogHabit(id, title, dirs.isNotEmpty(), dirs.toList())

/** §6.4: H1 (both), H2 (+1), H3 (-1), H4 (+1). */
internal val SCRIPT_HABITS = listOf(qh("h1", "H1", 1, -1), qh("h2", "H2", 1), qh("h3", "H3", -1), qh("h4", "H4", 1))

internal abstract class PublisherTestBase {
    protected val ctx: Context = ApplicationProvider.getApplicationContext()
    protected val sig = MemSig()

    @Before fun clearShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(ctx)
    }

    protected fun publish(auth: AuthState, habits: List<QuickLogHabit>?) = runBlocking {
        ShortcutPublisher.publish(ctx, auth, habits, sig)
    }

    protected fun dynamic(): List<ShortcutInfoCompat> =
        ShortcutManagerCompat.getDynamicShortcuts(ctx).sortedBy { it.rank }

    protected fun menu(): List<String> = dynamic().map { "${it.rank}:${it.id}=${it.shortLabel}" }
}

/** WP-8.13 card: "All 3 states are published exactly" (D6; Bubblewrap ShortcutPublisher.kt:33-40, 63-93). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class ShortcutPublisherStatesTest : PublisherTestBase() {

    @Test fun notLinked_onlyConnect_longLabel_opensSettingsDropbox() {
        publish(AuthState.NOT_LINKED, SCRIPT_HABITS)
        assertEquals(listOf("0:ql-connect=Connect Dropbox"), menu())
        val s = dynamic().single()
        assertEquals("Connect Dropbox for quick log", s.longLabel)
        val i = s.intent
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(MainActivity::class.java.name, i.component!!.className)
        assertEquals(SettingsRoute.Dropbox, settingsRouteForExtra(i.getStringExtra(EXTRA_OPEN_SETTINGS)))
    }

    @Test fun linked_upToThreeHabitItems_ranks0to2_intentsTargetQuickLog() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        assertEquals(listOf("0:ql-h-h1=+1 H1", "1:ql-h-h1-down=−1 H1", "2:ql-h-h2=+1 H2"), menu())
        val down = dynamic()[1]
        assertEquals("−1 H1", down.longLabel)
        val i = down.intent
        assertEquals(QuickLogActivity.ACTION, i.action)
        assertEquals(QuickLogActivity::class.java.name, i.component!!.className)
        assertEquals("h1", i.getStringExtra(QuickLogActivity.EXTRA_HABIT_ID))
        assertEquals("H1", i.getStringExtra(QuickLogActivity.EXTRA_TITLE))
        assertEquals(-1, i.getIntExtra(QuickLogActivity.EXTRA_DIR, 0))
    }

    @Test fun reconnectRequired_reconnectAtRank0_plusTwoHabits() {
        publish(AuthState.RECONNECT_REQUIRED, SCRIPT_HABITS)
        assertEquals(listOf("0:ql-connect=Reconnect Dropbox", "1:ql-h-h1=+1 H1", "2:ql-h-h1-down=−1 H1"), menu())
        assertEquals("Reconnect Dropbox for quick log", dynamic()[0].longLabel)
    }

    @Test fun stateChanges_removeStaleItems() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        publish(AuthState.NOT_LINKED, SCRIPT_HABITS)
        assertEquals(listOf("0:ql-connect=Connect Dropbox"), menu())
        publish(AuthState.LINKED, listOf(qh("h4", "H4", 1)))
        assertEquals(listOf("0:ql-h-h4=+1 H4"), menu())
    }

    @Test fun linked_noFlaggedHabits_emptyMenu() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        publish(AuthState.LINKED, listOf(qh("h1", "H1")))
        assertEquals(emptyList<String>(), menu())
    }

    @Test fun fortyCharTitle_cutTo25() {
        val t = "A".repeat(40)
        publish(AuthState.LINKED, listOf(qh("h1", t, 1)))
        val s = dynamic().single()
        assertEquals(("+1 $t").take(25), s.shortLabel)
        assertEquals(25, s.shortLabel.length)
        assertEquals(25, s.longLabel!!.length)
    }

    @Test fun blankTitle_usesId() {
        publish(AuthState.LINKED, listOf(qh("hx", "  ", 1)))
        assertEquals(listOf("0:ql-h-hx=+1 hx"), menu())
    }

    @Test fun foreignShortcutsAreNeverTouched() {
        val other = ShortcutInfoCompat.Builder(ctx, "other").setShortLabel("Other").setIntent(Intent(Intent.ACTION_VIEW).setClass(ctx, MainActivity::class.java)).build()
        ShortcutManagerCompat.addDynamicShortcuts(ctx, listOf(other))
        publish(AuthState.LINKED, SCRIPT_HABITS)
        publish(AuthState.NOT_LINKED, SCRIPT_HABITS)
        assertTrue(dynamic().any { it.id == "other" })
    }

    @Test fun desired_isPure_andSignatureFormat() {
        val d = ShortcutPublisher.desired(AuthState.LINKED, SCRIPT_HABITS)
        assertEquals("ql-h-h1=+1 H1|ql-h-h1-down=−1 H1|ql-h-h2=+1 H2", ShortcutPublisher.signature(d))
        assertEquals(1, ShortcutPublisher.desired(AuthState.NOT_LINKED, null).size)
        assertEquals(1, ShortcutPublisher.desired(AuthState.RECONNECT_REQUIRED, null).size)
    }
}

/** WP-8.13 card: "The signature skip gives 0 add calls" (Bubblewrap ShortcutPublisher.kt:42-45). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class SignatureSkipTest : PublisherTestBase() {
    @After fun unmock() = unmockkStatic(ShortcutManagerCompat::class)

    @Test fun samePublishTwice_zeroAddCalls_andSignatureStored() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        assertEquals("ql-h-h1=+1 H1|ql-h-h1-down=−1 H1|ql-h-h2=+1 H2", sig.value)
        mockkStatic(ShortcutManagerCompat::class)
        repeat(15) { publish(AuthState.LINKED, SCRIPT_HABITS) }
        verify(exactly = 0) { ShortcutManagerCompat.addDynamicShortcuts(any(), any()) }
        verify(exactly = 0) { ShortcutManagerCompat.removeDynamicShortcuts(any(), any()) }
    }

    @Test fun labelChange_republishes_once() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        mockkStatic(ShortcutManagerCompat::class)
        val renamed = listOf(qh("h1", "Water", 1, -1)) + SCRIPT_HABITS.drop(1)
        publish(AuthState.LINKED, renamed)
        publish(AuthState.LINKED, renamed)
        verify(exactly = 1) { ShortcutManagerCompat.addDynamicShortcuts(any(), any()) }
        unmockkStatic(ShortcutManagerCompat::class)
        assertEquals("+1 Water", dynamic()[0].shortLabel)
    }

    @Test fun sameSignature_butLauncherLostTheItems_republishes() {
        publish(AuthState.LINKED, SCRIPT_HABITS)
        ShortcutManagerCompat.removeAllDynamicShortcuts(ctx)
        publish(AuthState.LINKED, SCRIPT_HABITS)
        assertEquals(3, dynamic().size)
    }

    @Test fun addRefused_signatureNotStored_nextPublishRetries() {
        mockkStatic(ShortcutManagerCompat::class)
        every { ShortcutManagerCompat.addDynamicShortcuts(any(), any()) } returns false
        publish(AuthState.LINKED, SCRIPT_HABITS)
        assertNull(sig.value)
        unmockkStatic(ShortcutManagerCompat::class)
        publish(AuthState.LINKED, SCRIPT_HABITS)
        assertEquals(3, dynamic().size)
    }
}

/** WP-8.13 card: pinned items of deleted habits are disabled (Bubblewrap ShortcutPublisher.kt:51-59). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class OrphanDisableTest : PublisherTestBase() {
    @After fun unmock() = unmockkStatic(ShortcutManagerCompat::class)

    private fun pinned(vararg ids: String) = ids.map {
        ShortcutInfoCompat.Builder(ctx, it).setShortLabel(it).setIntent(Intent(QuickLogActivity.ACTION).setClass(ctx, QuickLogActivity::class.java)).build()
    }

    private fun stubPinned(vararg ids: String) {
        mockkStatic(ShortcutManagerCompat::class)
        every { ShortcutManagerCompat.getShortcuts(any(), ShortcutManagerCompat.FLAG_MATCH_PINNED) } returns pinned(*ids)
        every { ShortcutManagerCompat.disableShortcuts(any(), any(), any<CharSequence>()) } returns Unit
    }

    @Test fun linked_deletedHabit_pinnedItemDisabled_withMessage() {
        stubPinned("ql-h-h2", "ql-h-gone", "ql-h-gone-down", "ql-connect", "other")
        // H2 unflagged (still a habit): kept; "gone" is not a habit any more: disabled.
        publish(AuthState.LINKED, listOf(qh("h1", "H1", 1), qh("h2", "H2")))
        verify(exactly = 1) {
            ShortcutManagerCompat.disableShortcuts(any(), listOf("ql-h-gone", "ql-h-gone-down"), "Habit no longer in Questa")
        }
    }

    @Test fun notLinkedOrReconnect_neverDisables() {
        stubPinned("ql-h-gone")
        publish(AuthState.NOT_LINKED, emptyList())
        publish(AuthState.RECONNECT_REQUIRED, emptyList())
        verify(exactly = 0) { ShortcutManagerCompat.disableShortcuts(any(), any(), any<CharSequence>()) }
    }

    @Test fun nullHabitList_neverDisables() {
        stubPinned("ql-h-gone")
        publish(AuthState.LINKED, null)
        verify(exactly = 0) { ShortcutManagerCompat.disableShortcuts(any(), any(), any<CharSequence>()) }
    }
}

/** WP-8.13 card / §6.4 step 1: API 24 has no ShortcutManager: no crash, no items. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class Api24NoCrashTest : PublisherTestBase() {
    private var saved = 0

    @Before fun toApi24() {
        saved = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 24)
    }

    @After fun restore() = ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", saved)

    @Test fun publishEveryState_noCrash_noItems() {
        for (a in AuthState.values()) publish(a, SCRIPT_HABITS)
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(ctx).isEmpty())
        assertFalse(sig.value.isNullOrEmpty())
    }
}
