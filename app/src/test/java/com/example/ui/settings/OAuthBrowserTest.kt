package com.example.ui.settings

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WP-8.12-U1: the Dropbox login opens in a Custom Tab inside the app's task (no NEW_TASK from an
 * Activity), so the singleTask MainActivity clears it when the questa://oauth redirect arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OAuthBrowserTest {

    private val url = "https://www.dropbox.com/oauth2/authorize?client_id=x&state=s&code_challenge=c"
    private val newTask = Intent.FLAG_ACTIVITY_NEW_TASK

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `from an Activity a Custom Tab intent opens in the app task`() {
        val act = activity()
        val usedCct = OAuthBrowser.open(act, url, customTabsPackage = { "com.brave.browser" })
        assertTrue(usedCct)
        val started = shadowOf(act).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse(url), started.data)
        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION)) // marks it as a Custom Tab
        assertEquals("com.brave.browser", started.`package`)
        assertEquals(0, started.flags and newTask)
    }

    @Test
    fun `a wrapped Activity context is unwrapped, still no NEW_TASK`() {
        val act = activity()
        val usedCct = OAuthBrowser.open(ContextWrapper(act), url, customTabsPackage = { "pkg" })
        assertTrue(usedCct)
        val started = shadowOf(act).nextStartedActivity
        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(0, started.flags and newTask)
    }

    @Test
    fun `from a non-Activity context the Custom Tab needs NEW_TASK`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(OAuthBrowser.open(app, url, customTabsPackage = { "pkg" }))
        val started = shadowOf(app).nextStartedActivity
        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(newTask, started.flags and newTask)
    }

    @Test
    fun `no Custom Tabs browser falls back to the plain ACTION_VIEW path`() {
        val act = activity()
        val usedCct = OAuthBrowser.open(act, url, customTabsPackage = { null })
        assertFalse(usedCct)
        val started = shadowOf(act).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse(url), started.data)
        assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(newTask, started.flags and newTask)
    }

    @Test
    fun `ActivityNotFoundException from the Custom Tab falls back to ACTION_VIEW`() {
        val act = activity()
        val launched = mutableListOf<Intent>()
        val usedCct = OAuthBrowser.open(
            act, url,
            customTabsPackage = { "pkg" },
            start = { _, intent ->
                launched += intent
                if (intent.hasExtra(CustomTabsIntent.EXTRA_SESSION)) throw ActivityNotFoundException("no cct")
            },
        )
        assertFalse(usedCct)
        assertEquals(2, launched.size)
        val fallback = launched[1]
        assertFalse(fallback.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(Uri.parse(url), fallback.data)
        assertEquals(newTask, fallback.flags and newTask)
    }

    @Test
    fun `findActivity unwraps ContextWrapper chains`() {
        val act = activity()
        assertSame(act, OAuthBrowser.findActivity(ContextWrapper(ContextWrapper(act))))
        assertNull(OAuthBrowser.findActivity(ApplicationProvider.getApplicationContext<Application>()))
    }
}
