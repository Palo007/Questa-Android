package com.example.ui.settings

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.data.local.entities.CharacterEntity
import com.example.data.sync.auth.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP-8.14 (PX-83, I13): Settings -> Interaction -> Notifications -> "Request Permission" shows the
 * Android 13 POST_NOTIFICATIONS prompt; "Allow" turns the pref On (Opti requestNotificationPermission).
 * Runs under `./gradlew connectedDebugAndroidTest` on an API 33 emulator (the WP close command, m9).
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val enabled = MutableStateFlow(false)
    private lateinit var backend: SettingsBackend

    @Before
    fun setUp() {
        // A fresh prompt: revoke first (a no-op when never granted).
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm revoke ${InstrumentationRegistry.getInstrumentation().targetContext.packageName} android.permission.POST_NOTIFICATIONS"
        ).close()
        backend = mockk(relaxed = true)
        every { backend.character } returns flowOf(CharacterEntity(updatedAt = 0L))
        every { backend.connected } returns flowOf(false)
        every { backend.authState } returns flowOf(AuthState.NOT_LINKED)
        every { backend.lastSyncAt } returns flowOf(0L)
        every { backend.lastSyncError } returns flowOf(null)
        every { backend.lastBackupError } returns flowOf(null)
        every { backend.devices } returns flowOf(emptyList())
        every { backend.haptics } returns flowOf(true)
        every { backend.saveBtnTop } returns flowOf(false)
        every { backend.notificationsEnabled } returns enabled
        every { backend.lastExportTs } returns flowOf(0L)
        every { backend.startupStatus } returns flowOf(null)
        every { backend.notificationPermission() } returns "default"
        coEvery { backend.setNotificationsEnabled(any()) } answers { enabled.value = firstArg() }
    }

    @Test
    fun requestPermission_allow_turnsNotificationsOn() {
        val vm = SettingsViewModel(backend)
        rule.setContent { SettingsHost(vm, SettingsRoute.Root, onClose = {}) }
        rule.onNodeWithTag("cat_interaction").performClick()
        rule.onNodeWithTag("multi_notifications").performClick()
        rule.onNodeWithTag("notif_request").performClick()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 10_000)
        assertTrue("the POST_NOTIFICATIONS prompt did not appear (API ${Build.VERSION.SDK_INT})", allow != null)
        allow.click()
        rule.waitUntil(10_000) { enabled.value }
        coVerify { backend.setNotificationsEnabled(true) }
    }
}
