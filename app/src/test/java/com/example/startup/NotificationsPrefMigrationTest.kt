package com.example.startup

import androidx.datastore.preferences.core.edit
import com.example.data.sync.SyncKeys
import com.example.reminders.NotificationsPrefMigration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-8.14 review fix 2: Opti's notificationsEnabled default (false, app.js:220) for a fresh
 * install, but true for an upgraded Kotlin device (D21 class LEGACY, or armed reminders), so its
 * reminders do not go silent. A present key is never changed.
 */
class NotificationsPrefMigrationTest : StartupTestBase() {

    private fun wire() {
        h.notificationsDefault = { cls ->
            NotificationsPrefMigration.runIfNeeded(h.prefs, upgrade = cls == InstallStamp.LEGACY, armedCount = { armed })
        }
    }

    private var armed = 0

    @Test
    fun `fresh install - notifications default off`() = runBlocking {
        wire()
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals(false, h.p()[SyncKeys.NOTIFICATIONS_ENABLED])
    }

    @Test
    fun `upgrade (D21 legacy install) - notifications on`() = runBlocking {
        wire()
        seedLinkedLegacy()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(true, h.p()[SyncKeys.NOTIFICATIONS_ENABLED])
    }

    @Test
    fun `armed reminders turn it on even without the legacy class`() = runBlocking {
        assertEquals(true, NotificationsPrefMigration.runIfNeeded(h.prefs, upgrade = false, armedCount = { 2 }))
        h.reset()
        h.prefs.edit { it[SyncKeys.REM_ARMED] = "[\"k1\"]" }
        assertEquals(true, NotificationsPrefMigration.runIfNeeded(h.prefs, upgrade = false, armedCount = { 0 }))
    }

    @Test
    fun `a present key is unchanged, also on later launches`() = runBlocking {
        wire()
        h.prefs.edit { it[SyncKeys.NOTIFICATIONS_ENABLED] = false }
        armed = 5
        seedLinkedLegacy()
        assertTrue(h.launch(dbExisted = true, dsHadKeys = true))
        assertEquals(false, h.p()[SyncKeys.NOTIFICATIONS_ENABLED])
        assertNull(NotificationsPrefMigration.runIfNeeded(h.prefs, upgrade = true, armedCount = { 5 }))
        h.prefs.edit { it[SyncKeys.NOTIFICATIONS_ENABLED] = true }
        assertNull(NotificationsPrefMigration.runIfNeeded(h.prefs, upgrade = false, armedCount = { 0 }))
        assertEquals(true, h.p()[SyncKeys.NOTIFICATIONS_ENABLED])
    }

    @Test
    fun `a failing default never closes the barrier`() = runBlocking {
        h.notificationsDefault = { throw IllegalStateException("io") }
        assertTrue(h.launch(dbExisted = false, dsHadKeys = false))
        assertEquals(1, h.barrierOpened)
        assertFalse(h.p().contains(SyncKeys.NOTIFICATIONS_ENABLED))
    }
}
