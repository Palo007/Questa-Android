package com.example.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import com.example.data.sync.SyncKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * WP-8.14 (PX-88, provider only): the card appearance prefs, Opti `S.prefs.notesLines / cardThick
 * / showStreaks / width`. Defaults are Opti's `migrate()` defaults (app.js:220): notesLines 3,
 * cardThick 0, width 480, and `showStreaks !== false` (true when unset). The rows that write them
 * are WP-8.15's; the cards that read them are WP-8.18's.
 */
data class CardPrefs(
    val notesLines: Int = DEFAULT_NOTES_LINES,
    val cardThick: Int = DEFAULT_CARD_THICK,
    val showStreaks: Boolean = true,
    val widthPx: Int = DEFAULT_WIDTH_PX,
) {
    companion object {
        const val DEFAULT_NOTES_LINES = 3
        const val DEFAULT_CARD_THICK = 0
        const val DEFAULT_WIDTH_PX = 480

        /** Read from the app DataStore; cardThick is clamped to 0..60 as Opti's `applyCardThick`. */
        fun from(p: Preferences): CardPrefs = CardPrefs(
            notesLines = p[SyncKeys.NOTES_LINES] ?: DEFAULT_NOTES_LINES,
            cardThick = (p[SyncKeys.CARD_THICK] ?: DEFAULT_CARD_THICK).coerceIn(0, 60),
            showStreaks = p[SyncKeys.SHOW_STREAKS] ?: true,
            widthPx = p[SyncKeys.WIDTH_PX] ?: DEFAULT_WIDTH_PX,
        )

        fun flow(data: Flow<Preferences>): Flow<CardPrefs> = data.map { from(it) }.distinctUntilChanged()
    }
}

/** Provided in MainActivity's setContent from the DataStore; defaults outside a provider. */
val LocalCardPrefs: ProvidableCompositionLocal<CardPrefs> = staticCompositionLocalOf { CardPrefs() }
