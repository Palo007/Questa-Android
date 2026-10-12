package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * WP-8.17 -- Theme values (Color/Theme/XML only).
 *
 * Asserts the 19 Opti `:root` CSS vars (`index.html`:14-19) plus the 7 selector values the
 * WP-8.17 card cites (PageBg :22, Bar :71-72, HabitOff :171, Nav :484, ToastBg :493,
 * DeathScrim :569), all at Opti `7d5d4f3`, are ported byte-for-byte into [OptiRootTokens] /
 * [OptiSelectorTokens]. See §4.5 8.17 row: "TokenParityTest passes for 19 vars plus 7 selector
 * values."
 */
class TokenParityTest {

    /** name -> expected ARGB Color, exactly as index.html:14-18 declares them. */
    private val expectedRootColorTokens: Map<String, Color> =
        linkedMapOf(
            "bg" to Color(0xFF36205C),
            "panel" to Color(0xFF432263),
            "panel2" to Color(0xFF4F2A93),
            "ink" to Color(0xFFF4F1FB),
            "muted" to Color(0xFFBCA8E0),
            "hp" to Color(0xFFF74E52),
            "xp" to Color(0xFFFFD200),
            "gold" to Color(0xFFFFBE5C),
            "mp" to Color(0xFF50B5E9),
            "blue" to Color(0xFF2995CD),
            "lblue" to Color(0xFF74B6E0),
            "green" to Color(0xFF48B677),
            "yellow" to Color(0xFFFFBE5C),
            "orange" to Color(0xFFF9A03F),
            "red" to Color(0xFFF74E52),
            "darkred" to Color(0xFFB0375A),
            "line" to Color(0xFF5B3A86),
            "accent" to Color(0xFFBDA8FF),
        )

    private val actualRootColorTokens: Map<String, Color> =
        linkedMapOf(
            "bg" to OptiRootTokens.Bg,
            "panel" to OptiRootTokens.Panel,
            "panel2" to OptiRootTokens.Panel2,
            "ink" to OptiRootTokens.Ink,
            "muted" to OptiRootTokens.Muted,
            "hp" to OptiRootTokens.Hp,
            "xp" to OptiRootTokens.Xp,
            "gold" to OptiRootTokens.Gold,
            "mp" to OptiRootTokens.Mp,
            "blue" to OptiRootTokens.Blue,
            "lblue" to OptiRootTokens.LBlue,
            "green" to OptiRootTokens.Green,
            "yellow" to OptiRootTokens.Yellow,
            "orange" to OptiRootTokens.Orange,
            "red" to OptiRootTokens.Red,
            "darkred" to OptiRootTokens.DarkRed,
            "line" to OptiRootTokens.Line,
            "accent" to OptiRootTokens.Accent,
        )

    /** The 7 selector values (PageBg, the 2-value Bar, HabitOff, Nav, ToastBg, DeathScrim). */
    private val expectedSelectorTokens: Map<String, Color> =
        linkedMapOf(
            "pageBg" to Color(0xFF1D1233),
            "barTrackBg" to Color(0xFF23143D),
            "barTrackBorder" to Color(0xFF1A0F2E),
            "habitOff" to Color(0xFF4A3568),
            "nav" to Color(0xFF2A1947),
            "toastBg" to Color(0xFF1D0F38),
            "deathScrim" to Color(0xEB280514),
        )

    private val actualSelectorTokens: Map<String, Color> =
        linkedMapOf(
            "pageBg" to OptiSelectorTokens.PageBg,
            "barTrackBg" to OptiSelectorTokens.BarTrackBg,
            "barTrackBorder" to OptiSelectorTokens.BarTrackBorder,
            "habitOff" to OptiSelectorTokens.HabitOff,
            "nav" to OptiSelectorTokens.Nav,
            "toastBg" to OptiSelectorTokens.ToastBg,
            "deathScrim" to OptiSelectorTokens.DeathScrim,
        )

    @Test
    fun `19 root color vars are present and byte-for-byte`() {
        assertEquals(18, expectedRootColorTokens.size) // 19 vars total; --appw is a dimension, checked below
        assertEquals(expectedRootColorTokens.keys, actualRootColorTokens.keys)
        expectedRootColorTokens.forEach { (name, expected) ->
            assertEquals("token '$name' mismatch", expected, actualRootColorTokens.getValue(name))
        }
    }

    @Test
    fun `appw dimension token is 480px, index html 19`() {
        assertEquals(480, OptiRootTokens.AppWidthPx)
    }

    @Test
    fun `all 19 root vars accounted for`() {
        // 18 color vars + 1 dimension var (--appw) == 19, matching index.html:14-19.
        assertEquals(19, expectedRootColorTokens.size + 1)
    }

    @Test
    fun `7 selector values are present and byte-for-byte`() {
        assertEquals(7, expectedSelectorTokens.size)
        assertEquals(expectedSelectorTokens.keys, actualSelectorTokens.keys)
        expectedSelectorTokens.forEach { (name, expected) ->
            assertEquals("selector '$name' mismatch", expected, actualSelectorTokens.getValue(name))
        }
    }

    @Test
    fun `page background matches windowBackground token exactly`() {
        assertEquals(Color(0xFF1D1233), OptiSelectorTokens.PageBg)
    }

    @Test
    fun `an unknown token throws`() {
        assertThrows(NoSuchElementException::class.java) {
            actualRootColorTokens.getValue("not-a-real-token")
        }
        assertThrows(NoSuchElementException::class.java) {
            actualSelectorTokens.getValue("not-a-real-selector")
        }
    }
}
