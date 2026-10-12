package com.example.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P13 / CR-PWA-002: a synced tag colour is free text. `android.graphics.Color.parseColor`
 * throws on bad input, which crashed the dashboard render (DashboardScreen tag filter row and
 * tag picker). Robolectric is required: a plain JVM test hits the android.jar stub, which
 * throws for every input, so the valid-colour check would be meaningless there.
 * Fallback is Opti's unset-tag grey #888 (DEC-14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeTagColorTest {

    private val grey = Color(0xFF888888)

    @Test
    fun cssInjection_fallsBackToGrey() {
        assertEquals(grey, safeTagColor("red;background:url(javascript:alert(1))"))
    }

    @Test
    fun badHex_fallsBackToGrey() {
        assertEquals(grey, safeTagColor("#zzz"))
    }

    @Test
    fun badHexDigits_fallsBackToGrey() {
        assertEquals(grey, safeTagColor("#zzzzzz"))
    }

    @Test
    fun emptyAndBlankAndNull_fallBackToGrey() {
        assertEquals(grey, safeTagColor(""))
        assertEquals(grey, safeTagColor("   "))
        assertEquals(grey, safeTagColor(null))
    }

    @Test
    fun validHex_isParsed() {
        assertEquals(Color(0xFF4CAF50), safeTagColor("#4CAF50"))
    }

    // DEC-16: short and 8-digit hex are read in CSS order, like the PWA renders them.
    @Test
    fun shortHex_isExpanded() {
        assertEquals(Color(0xFFFF0000), safeTagColor("#f00"))
    }

    @Test
    fun shortHexWithAlpha_isCssOrder() {
        assertEquals(Color(0xAAFF0000), safeTagColor("#f00a"))
    }

    @Test
    fun eightDigitHex_isCssRrggbbaa() {
        assertEquals(Color(0x44112233), safeTagColor("#11223344"))
    }

    @Test
    fun validNamedColour_isParsed() {
        assertEquals(Color(0xFFFF0000), safeTagColor("red"))
    }
}
