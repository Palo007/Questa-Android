package com.example.ui.settings

import android.app.Application
import android.content.Intent
import com.example.applyOpenSettingsExtra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP-8.14: EXTRA_OPEN_SETTINGS ("dropbox") -> MainViewModel.openSettings, from onCreate and
 * onNewIntent. No real MainActivity here: a created-then-destroyed MainActivity left the next
 * Compose test class's main looper busy (AppNotIdleException in SettingsScreenTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenSettingsExtraTest {

    @Test
    fun `extra values map to routes`() {
        assertEquals("openSettings", EXTRA_OPEN_SETTINGS)
        assertNull(settingsRouteForExtra(null))
        assertEquals(SettingsRoute.Dropbox, settingsRouteForExtra("dropbox"))
        assertEquals(SettingsRoute.Root, settingsRouteForExtra("root"))
        assertEquals(SettingsRoute.Root, settingsRouteForExtra(""))
    }

    @Test
    fun `the dropbox extra opens the Sync block once, then is consumed`() {
        val opened = mutableListOf<SettingsRoute>()
        val intent = Intent().putExtra(EXTRA_OPEN_SETTINGS, OPEN_SETTINGS_DROPBOX)
        applyOpenSettingsExtra(intent) { opened += it }
        assertEquals(listOf(SettingsRoute.Dropbox), opened)
        assertFalse(intent.hasExtra(EXTRA_OPEN_SETTINGS)) // a recreate does not reopen Settings
        applyOpenSettingsExtra(intent) { opened += it }
        assertEquals(1, opened.size)
    }

    @Test
    fun `no extra or no intent opens nothing`() {
        val opened = mutableListOf<SettingsRoute>()
        applyOpenSettingsExtra(Intent()) { opened += it }
        applyOpenSettingsExtra(null) { opened += it }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `MainActivity routes the extra in onCreate and onNewIntent to MainViewModel`() {
        val f = listOf(File("src/main/java/com/example/MainActivity.kt"), File("app/src/main/java/com/example/MainActivity.kt")).first { it.isFile }
        val src = f.readText()
        val onCreate = src.substringAfter("override fun onCreate(").substringBefore("override fun onNewIntent(")
        val onNewIntent = src.substringAfter("override fun onNewIntent(").substringBefore("private fun openSettingsFrom(")
        assertTrue(onCreate.contains("openSettingsFrom(intent)"))
        assertTrue(onNewIntent.contains("openSettingsFrom(intent)"))
        assertTrue(src.contains("applyOpenSettingsExtra(intent) { mainViewModel?.openSettings(it) }"))
    }
}
