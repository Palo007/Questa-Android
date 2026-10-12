package com.example.ui.settings

import androidx.datastore.preferences.core.Preferences
import com.example.data.local.entities.EventLogEntity
import com.example.data.sync.SyncKeys
import com.example.data.sync.merge.ConflictEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * WP-8.15 (PX-88): Opti's Appearance choices (`openOpt('width')` / `openOpt('notes')`,
 * app.js:6874-6894) and the row values of `openCat('appearance')` (app.js:6823-6851).
 * The default width 480 (app.js:2018, :220) is not one of the options, so its row reads "Custom".
 */
val WIDTH_OPTIONS: List<Pair<String, Int>> = listOf("Slim" to 430, "Medium" to 560, "Wide" to 720, "Full" to 3000)

val NOTES_OPTIONS: List<Pair<String, Int>> = listOf("Off" to 0, "1 line" to 1, "2 lines" to 2, "3 lines" to 3, "5 lines" to 5)

object AppearanceTexts {
    const val WIDTH_LABEL = "Interface width"
    const val WIDTH_DESC = "Caps the width on a monitor and keeps it centered."
    const val WIDTH_TITLE = "Interface width"
    const val WIDTH_HINT = "On a phone it always fills the screen. This caps the width on a monitor and keeps it centered."
    const val NOTES_LABEL = "Note lines"
    const val NOTES_DESC = "Lines of a task's notes shown in the list preview."
    const val NOTES_TITLE = "Note lines on cards"
    const val NOTES_HINT = "How many lines of a task's notes preview on the list (default 3, like Habitica)."
    const val THICK_LABEL = "Card thickness"
    const val THICK_DESC = "Minimum height of each card."
    const val STREAKS_LABEL = "Streak badges"
    const val STREAKS_DESC = "Show the 🔥 streak count on daily cards. Streaks keep counting either way."
    const val HIDE_DIAG_LABEL = "Hide sync & diagnostic events"
    const val HIDE_DIAG_DESC = "Hide background sync (conflict resolved) and diagnostic events from the Activity Feed."
    const val HIDE_CONFLICTS_LABEL = "Hide conflict decisions"
    const val HIDE_CONFLICTS_DESC = "Hide sync conflict resolution events from the Activity Feed."
    const val HAPTICS_HINT = "Vibration feedback when you tap buttons, check tasks, and complete dailies. Requires device support."
    const val HAPTICS_DND = "If buzz() returns \"accepted\" but no vibration is felt: Android DND / Silent mode suppresses vibration silently. The API has no way to detect this."
    const val AVATAR_TIP = "Type an emoji, or upload a PNG, JPEG or GIF (max 1 MB) to use as your avatar. An uploaded image takes priority over the emoji."

    /** Opti `widthLabels[wv] || 'Custom'`. */
    fun widthLabel(px: Int): String = WIDTH_OPTIONS.firstOrNull { it.second == px }?.first ?: "Custom"

    /** Opti `nl === 0 ? 'Off' : nl + ' line' + (nl === 1 ? '' : 's')`. */
    fun notesLabel(n: Int): String = if (n == 0) "Off" else "$n line" + (if (n == 1) "" else "s")

    /** Opti `cp === 0 ? 'Default' : '+' + cp + ' px'` (app.js:6842). */
    fun cardThickLabel(px: Int): String = if (px == 0) "Default" else "+$px px"

    /** Opti's haptics diagnostics line (app.js:6966). */
    fun hapticsDiagLine(d: HapticsDiag): String =
        "API: " + d.type + " · Last: " + (d.lastResult?.toString() ?: "(none)") + " · Count: " + d.count +
            // MIUI/HyperOS and OEM skins ship haptic_feedback_enabled=0, which silently zeroes
            // the TOUCH vibration usage (vibrator service log: "ignored_for_settings").
            if (d.systemHapticsOff) " · System touch vibration OFF — enable it in Android settings (Sound & vibration)" else ""
}

/**
 * WP-8.15 (PX-89): the Activity Feed filters (Opti `renderEvents` filter, app.js:4728-4736).
 * `conflictResolved` has its own toggle; every other kind in [DIAGNOSTIC_KINDS] is hidden by
 * [hideSyncDiag]. Defaults are Opti `migrate()`'s (hideSyncDiag true, hideConflictDecisions false).
 */
data class FeedFilters(val hideSyncDiag: Boolean = true, val hideConflictDecisions: Boolean = false) {

    fun shows(kind: String): Boolean =
        if (kind == ConflictEvent.CONFLICT_RESOLVED) !hideConflictDecisions
        else !(hideSyncDiag && kind in DIAGNOSTIC_KINDS)

    fun apply(events: List<EventLogEntity>): List<EventLogEntity> = events.filter { shows(it.kind) }

    companion object {
        /** Opti `DIAGNOSTIC_KINDS` (app.js:1114). */
        val DIAGNOSTIC_KINDS: List<String> = com.example.data.sync.EventKinds.DIAGNOSTIC_KINDS

        fun from(p: Preferences): FeedFilters = FeedFilters(
            hideSyncDiag = p[SyncKeys.HIDE_SYNC_DIAG] ?: true,
            hideConflictDecisions = p[SyncKeys.HIDE_CONFLICTS] ?: false,
        )

        fun flow(data: Flow<Preferences>): Flow<FeedFilters> = data.map { from(it) }.distinctUntilChanged()
    }
}

/**
 * WP-8.15 (PX-91): Opti `getBuzzDiag()` (app.js:98): the haptics API, the last result and the
 * number of buzzes. Kotlin's buzz is `View.performHapticFeedback`, which returns whether it ran.
 */
data class HapticsDiag(
    val type: String = "undefined",
    val lastResult: Boolean? = null,
    val count: Int = 0,
    /** The OS "haptic feedback" switch is off: the system silently drops every touch/unknown vibration. */
    val systemHapticsOff: Boolean = false,
)
