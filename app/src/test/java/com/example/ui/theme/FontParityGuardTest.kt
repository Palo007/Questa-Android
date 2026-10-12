package com.example.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PX-103 (audit V16) -- font guard.
 *
 * Opti `index.html`:23 sets `font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,
 * sans-serif`, which resolves to the system sans-serif (Roboto) on Android. Kotlin parity is the
 * platform default family (Type.kt:14 `FontFamily.Default`). This guard fails if a custom or
 * non-sans font family is introduced into the app Typography, or a bundled font resource appears.
 */
class FontParityGuardTest {

    /** Families that resolve to the Android system sans-serif (Roboto). */
    private val systemSans: Set<FontFamily?> = setOf(null, FontFamily.Default, FontFamily.SansSerif)

    private fun allStyles(): Map<String, TextStyle> =
        with(Typography) {
            linkedMapOf(
                "displayLarge" to displayLarge,
                "displayMedium" to displayMedium,
                "displaySmall" to displaySmall,
                "headlineLarge" to headlineLarge,
                "headlineMedium" to headlineMedium,
                "headlineSmall" to headlineSmall,
                "titleLarge" to titleLarge,
                "titleMedium" to titleMedium,
                "titleSmall" to titleSmall,
                "bodyLarge" to bodyLarge,
                "bodyMedium" to bodyMedium,
                "bodySmall" to bodySmall,
                "labelLarge" to labelLarge,
                "labelMedium" to labelMedium,
                "labelSmall" to labelSmall,
            )
        }

    @Test
    fun bodyLarge_usesPlatformDefaultFamily() {
        assertEquals(FontFamily.Default, Typography.bodyLarge.fontFamily)
    }

    @Test
    fun everyTypographyStyle_resolvesToSystemSans() {
        val offenders = allStyles().filterValues { it.fontFamily !in systemSans }
        assertTrue("Non-system-sans font families (PX-103/V16): $offenders", offenders.isEmpty())
    }

    @Test
    fun noBundledFontResources() {
        // Unit tests run with the module dir (app/) as working directory.
        val res = File("src/main/res")
        assertTrue("res dir not found from ${File(".").absolutePath}", res.isDirectory)
        val fontDir = File(res, "font")
        val bundled = fontDir.listFiles()?.map { it.name }.orEmpty()
        assertFalse("Bundled font resources break V16 parity: $bundled", bundled.isNotEmpty())
        val assetFonts =
            File("src/main/assets").walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "woff", "woff2") }
                .map { it.name }
                .toList()
        assertTrue("Bundled font assets break V16 parity: $assetFonts", assetFonts.isEmpty())
    }
}
