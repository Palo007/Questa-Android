package com.example.ui.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.SyncKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-8.15 (PX-89): Opti's Activity Feed filter (app.js:4728-4736, DIAGNOSTIC_KINDS :1051). */
class FeedFilterTest {

    private fun ev(kind: String) = EventLogEntity(timestamp = 1L, kind = kind)
    private val feed = listOf("complete", "conflictResolved", "lifecycle", "clockSkew", "hlcReset", "quotaError", "export", "habitTap").map { ev(it) }

    @Test fun defaults_hide_diagnostics_but_show_conflicts() {
        val f = FeedFilters()
        assertTrue(f.hideSyncDiag)
        assertFalse(f.hideConflictDecisions)
        assertEquals(listOf("complete", "conflictResolved", "export", "habitTap"), f.apply(feed).map { it.kind })
    }

    @Test fun hideSyncDiag_off_shows_every_kind() {
        assertEquals(feed.map { it.kind }, FeedFilters(hideSyncDiag = false).apply(feed).map { it.kind })
    }

    @Test fun conflict_toggle_is_independent_of_hideSyncDiag() {
        assertEquals(
            listOf("complete", "lifecycle", "clockSkew", "hlcReset", "quotaError", "export", "habitTap"),
            FeedFilters(hideSyncDiag = false, hideConflictDecisions = true).apply(feed).map { it.kind }
        )
        assertEquals(listOf("complete", "export", "habitTap"), FeedFilters(hideSyncDiag = true, hideConflictDecisions = true).apply(feed).map { it.kind })
    }

    @Test fun diagnostic_kinds_are_Optis_seven() {
        assertEquals(
            listOf("lifecycle", "storagePersist", "webLocksUnavailable", "clockSkew", "hlcReset", "multiTabClobberAvoided", "quotaError"),
            FeedFilters.DIAGNOSTIC_KINDS
        )
    }

    @Test fun reads_its_two_keys() {
        assertEquals(FeedFilters(true, false), FeedFilters.from(mutablePreferencesOf()))
        val p = mutablePreferencesOf(SyncKeys.HIDE_SYNC_DIAG to false, SyncKeys.HIDE_CONFLICTS to true)
        assertEquals(FeedFilters(hideSyncDiag = false, hideConflictDecisions = true), FeedFilters.from(p))
    }
}
