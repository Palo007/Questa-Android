package com.example.ui.settings

import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WP-8.15 (PX-88..91): each row writes its key, with Opti's menu flow (app.js:6686-6692, 6861-6998, 8419-8428). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceRowsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var backend: FakeSettingsBackend
    private lateinit var vm: SettingsViewModel
    private val toasts = mutableListOf<String>()
    private val avatarRequests = mutableListOf<Unit>()
    private lateinit var collectors: Job

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeSettingsBackend()
        vm = SettingsViewModel(backend)
        collectors = CoroutineScope(dispatcher).launch {
            launch { vm.toasts.toList(toasts) }
            launch { vm.avatarRequests.toList(avatarRequests) }
        }
    }

    @After fun tearDown() {
        collectors.cancel()
        Dispatchers.resetMain()
    }

    @Test fun state_mirrors_the_prefs() {
        assertEquals(480, vm.state.value.card.widthPx)
        assertTrue(vm.state.value.feed.hideSyncDiag)
        backend.cardPrefs.value = backend.cardPrefs.value.copy(widthPx = 720, notesLines = 5, cardThick = 9, showStreaks = false)
        backend.feedFilters.value = FeedFilters(hideSyncDiag = false, hideConflictDecisions = true)
        backend.hasAvatarImage.value = true
        val s = vm.state.value
        assertEquals(720, s.card.widthPx)
        assertEquals(5, s.card.notesLines)
        assertEquals(9, s.card.cardThick)
        assertFalse(s.card.showStreaks)
        assertEquals(FeedFilters(false, true), s.feed)
        assertTrue(s.hasAvatarImage)
    }

    @Test fun width_and_notes_choices_write_and_close_every_menu() {
        vm.openCategory("appearance"); vm.openOption("width")
        vm.setWidth(560)
        assertEquals("width:560", backend.calls.last())
        assertNull(vm.state.value.option)
        assertNull(vm.state.value.category)

        vm.openCategory("appearance"); vm.openOption("notes")
        vm.setNotesLines(0)
        assertEquals("notes:0", backend.calls.last())
        assertNull(vm.state.value.option)
        assertNull(vm.state.value.category)
    }

    @Test fun slider_and_toggles_write_and_keep_the_category_open() {
        vm.openCategory("appearance")
        vm.setCardThick(12)
        vm.setCardThick(99)
        vm.setCardThick(-3)
        vm.setShowStreaks(false)
        assertEquals(listOf("cardThick:12", "cardThick:60", "cardThick:0", "showStreaks:false"), backend.calls)
        assertEquals("appearance", vm.state.value.category)

        vm.openCategory("activityFeed")
        vm.setHideSyncDiag(false)
        vm.setHideConflictDecisions(true)
        assertEquals(listOf("hideSyncDiag:false", "hideConflicts:true"), backend.calls.takeLast(2))
        assertEquals("activityFeed", vm.state.value.category)
    }

    @Test fun haptics_menu_choice_writes_and_closes() {
        vm.openCategory("interaction"); vm.openOption("haptics")
        vm.chooseHaptics(false)
        assertEquals("haptics:false", backend.calls.last())
        assertNull(vm.state.value.option)
        assertNull(vm.state.value.category)
    }

    @Test fun test_vibration_counts_toasts_and_reopens_the_menu() {
        assertEquals(HapticsDiag("undefined", null, 0), vm.state.value.hapticsDiag)
        var buzzes = 0
        vm.bindHaptics(perform = { buzzes++; true })
        assertEquals(SettingsViewModel.HAPTICS_API, vm.state.value.hapticsDiag.type)

        val d = vm.testVibration()
        assertEquals(HapticsDiag(SettingsViewModel.HAPTICS_API, true, 1), d)
        assertEquals(1, buzzes)
        assertEquals("Vibrate returned: true", toasts.last())
        assertEquals("haptics", vm.state.value.option)
        assertEquals(d, vm.state.value.hapticsDiag)

        // Opti buzz(): counts, but returns false without vibrating when haptics are off.
        backend.haptics.value = false
        assertEquals(HapticsDiag(SettingsViewModel.HAPTICS_API, false, 2), vm.testVibration())
        assertEquals(1, buzzes)
        assertEquals("Vibrate returned: false", toasts.last())
    }

    @Test fun test_vibration_without_a_buzz_returns_false() {
        assertEquals(HapticsDiag("undefined", false, 1), vm.testVibration())
    }

    @Test fun bind_reports_the_system_haptics_switch_in_the_diag() {
        vm.bindHaptics({ true }, systemHapticsOff = true)
        assertEquals(HapticsDiag(SettingsViewModel.HAPTICS_API, null, 0, systemHapticsOff = true), vm.state.value.hapticsDiag)
        assertTrue(AppearanceTexts.hapticsDiagLine(vm.state.value.hapticsDiag).contains("System touch vibration OFF"))
    }

    @Test fun browse_asks_the_host_for_the_picker() {
        vm.browseAvatarImage()
        assertEquals(1, avatarRequests.size)
    }

    @Test fun avatar_set_toasts_and_a_refused_file_shows_Optis_alert() {
        val uri = mockk<Uri>()
        vm.setAvatarImage(uri)
        assertEquals("Avatar image set", toasts.last())
        assertTrue(vm.state.value.hasAvatarImage)

        backend.avatarResult = Result.failure(AvatarRejectedException(AvatarStore.sizeMessage(2_000_000L)))
        vm.setAvatarImage(uri)
        assertEquals(SettingsAlert("Error", "That image is 1.9 MB. Please use one under 1 MB."), vm.state.value.alert)
    }

    @Test fun avatar_remove_toasts() {
        backend.hasAvatarImage.value = true
        vm.removeAvatarImage()
        assertEquals("avatarRemove", backend.calls.last())
        assertEquals("Image removed", toasts.last())
        assertFalse(vm.state.value.hasAvatarImage)
    }
}
