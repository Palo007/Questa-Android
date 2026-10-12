package com.example.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * CR-KT-018: [SettingsViewModel.syncBadge] combines connected / localEditAt / lastSyncAt /
 * lastSyncError with a 5 s poll that runs only while collected (lifecycle-aware in the UI).
 * Virtual time only: the scheduler is driven by hand, no runTest (the poll never goes idle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncBadgeViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var backend: FakeSettingsBackend
    private lateinit var vm: SettingsViewModel
    private var collector: Job? = null
    private val h = 3_600_000L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeSettingsBackend()
        backend.connected.value = true
        backend.lastSyncAt.value = backend.now - 1000
        vm = SettingsViewModel(backend)
    }

    @After fun tearDown() {
        collector?.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun subscribe() {
        collector = CoroutineScope(dispatcher).launch { vm.syncBadge.collect { } }
        scheduler.runCurrent()
    }

    private fun mode() = vm.syncBadge.value.mode

    @Test fun initialValue_isHidden() = assertEquals(SyncBadgeState.HIDDEN, vm.syncBadge.value)

    @Test fun freshSync_noEdit_isHidden() {
        subscribe()
        assertEquals(SyncBadgeMode.HIDDEN, mode())
    }

    @Test fun userEditAfterSync_isOrange_thenErrorIsRed_thenSyncClearsIt() {
        subscribe()
        backend.localEditAt.value = backend.now
        scheduler.runCurrent()
        assertEquals(SyncBadgeMode.UNSYNCED, mode())
        backend.lastSyncError.value = "boom"
        scheduler.runCurrent()
        assertEquals(SyncBadgeMode.ERROR, mode())
        // A successful round clears the error and stamps lastSyncAt after the edit.
        backend.lastSyncError.value = null
        backend.lastSyncAt.value = backend.now + 1
        scheduler.runCurrent()
        assertEquals(SyncBadgeMode.HIDDEN, mode())
    }

    @Test fun syncOff_isHidden_evenWithEditAndError() {
        subscribe()
        backend.localEditAt.value = backend.now
        backend.lastSyncError.value = "boom"
        backend.connected.value = false
        scheduler.runCurrent()
        assertEquals(SyncBadgeMode.HIDDEN, mode())
    }

    @Test fun neverSynced_withEdit_isOrange() {
        subscribe()
        backend.lastSyncAt.value = 0L
        backend.localEditAt.value = backend.now
        scheduler.runCurrent()
        assertEquals(SyncBadgeMode.UNSYNCED, mode())
    }

    @Test fun poll_turnsFreshIntoStale_andUpdatesTheLabel() {
        subscribe()
        assertEquals(SyncBadgeMode.HIDDEN, mode())
        backend.now += 2 * h
        scheduler.advanceTimeBy(SettingsViewModel.SYNC_BADGE_POLL_MS + 1)
        assertEquals(SyncBadgeMode.STALE, mode())
        assertEquals("Last synced 2 hours ago", vm.syncBadge.value.label)
        backend.now += h
        scheduler.advanceTimeBy(SettingsViewModel.SYNC_BADGE_POLL_MS)
        assertEquals("Last synced 3 hours ago", vm.syncBadge.value.label)
    }

    @Test fun noPoll_beforeFiveSeconds() {
        subscribe()
        backend.now += 2 * h
        scheduler.advanceTimeBy(SettingsViewModel.SYNC_BADGE_POLL_MS - 1)
        assertEquals(SyncBadgeMode.HIDDEN, mode())
    }

    @Test fun noCollector_noPoll_thenResubscribe_reEvaluatesAtOnce() {
        subscribe()
        collector!!.cancel()
        scheduler.runCurrent()
        backend.now += 2 * h
        scheduler.advanceTimeBy(10 * SettingsViewModel.SYNC_BADGE_POLL_MS)
        assertEquals("background: nothing re-evaluated", SyncBadgeMode.HIDDEN, mode())
        subscribe()
        assertEquals("resume: re-evaluated at once", SyncBadgeMode.STALE, mode())
    }

    @Test fun pollIntervalIsFiveSeconds() = assertEquals(5000L, SettingsViewModel.SYNC_BADGE_POLL_MS)
}
