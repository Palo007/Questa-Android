package com.example.ui.settings

import com.example.data.sync.backup.UiPrefsOverlay
import com.example.ui.CardPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/** WP-8.15 (PX-88): Opti's Appearance choices, labels and defaults (app.js:6823-6894, :2018, :220). */
class WidthOptionsTest {

    @Test fun widths_are_430_560_720_3000_in_Opti_order() {
        assertEquals(listOf("Slim" to 430, "Medium" to 560, "Wide" to 720, "Full" to 3000), WIDTH_OPTIONS)
    }

    @Test fun default_width_is_480_and_reads_Custom() {
        assertEquals(480, CardPrefs().widthPx)
        assertEquals(480, UiPrefsOverlay.DEFAULT_WIDTH)
        assertEquals("Custom", AppearanceTexts.widthLabel(480))
        assertEquals("Slim", AppearanceTexts.widthLabel(430))
        assertEquals("Full", AppearanceTexts.widthLabel(3000))
    }

    @Test fun note_lines_options_and_labels() {
        assertEquals(listOf("Off" to 0, "1 line" to 1, "2 lines" to 2, "3 lines" to 3, "5 lines" to 5), NOTES_OPTIONS)
        assertEquals(3, CardPrefs().notesLines)
        assertEquals("Off", AppearanceTexts.notesLabel(0))
        assertEquals("1 line", AppearanceTexts.notesLabel(1))
        assertEquals("3 lines", AppearanceTexts.notesLabel(3))
        assertEquals("4 lines", AppearanceTexts.notesLabel(4))
    }

    @Test fun card_thickness_label() {
        assertEquals("Default", AppearanceTexts.cardThickLabel(0))
        assertEquals("+12 px", AppearanceTexts.cardThickLabel(12))
        assertEquals("+60 px", AppearanceTexts.cardThickLabel(60))
    }

    @Test fun haptics_diag_line() {
        assertEquals("API: undefined · Last: (none) · Count: 0", AppearanceTexts.hapticsDiagLine(HapticsDiag()))
        assertEquals("API: performHapticFeedback · Last: true · Count: 2", AppearanceTexts.hapticsDiagLine(HapticsDiag("performHapticFeedback", true, 2)))
        assertEquals(
            "API: performHapticFeedback · Last: true · Count: 2 · System touch vibration OFF — enable it in Android settings (Sound & vibration)",
            AppearanceTexts.hapticsDiagLine(HapticsDiag("performHapticFeedback", true, 2, systemHapticsOff = true)),
        )
    }
}
