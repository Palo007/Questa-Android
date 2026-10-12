package com.example.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-8.14 (WP-8.11 hand-off): reminders fire only with Opti's notificationsEnabled pref AND the OS permission. */
class PrefNotificationGateTest {

    @Test
    fun `pref off blocks even with the permission (Opti default false)`() {
        assertFalse(PrefNotificationGate({ false }, { true }).canNotify())
    }

    @Test
    fun `pref on still needs the permission`() {
        assertFalse(PrefNotificationGate({ true }, { false }).canNotify())
        assertTrue(PrefNotificationGate({ true }, { true }).canNotify())
    }

    @Test
    fun `a pref read error counts as off`() {
        assertFalse(PrefNotificationGate({ throw IllegalStateException("io") }, { true }).canNotify())
    }
}
