package com.example.reminders

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P14 / CR-TWA-005: the TWA finds the Kotlin app through PackageManager by the fixed action
 * [ACTION]. This app must expose exactly one exported, permission-free receiver for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderOwnerMarkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun ownReceivers() = context.packageManager
        .queryBroadcastReceivers(Intent(ACTION), 0)
        .filter { it.activityInfo.packageName == context.packageName }

    @Test
    fun `exactly one exported receiver answers the reminder-owner action`() {
        val found = ownReceivers()
        assertEquals(1, found.size)
        val info = found.single().activityInfo
        assertTrue(info.exported)
        assertNull(info.permission)
        assertEquals("com.example.reminders.ReminderOwnerReceiver", info.name)
    }

    @Test
    fun `explicit package query also finds it`() {
        val found = context.packageManager
            .queryBroadcastReceivers(Intent(ACTION).setPackage(context.packageName), 0)
        assertEquals(1, found.size)
    }

    private companion object {
        /** Fixed string from the CR; independent of applicationId. */
        const val ACTION = "io.github.palo007.questa.action.REMINDER_OWNER"
    }
}
