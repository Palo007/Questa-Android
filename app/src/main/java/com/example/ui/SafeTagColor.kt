package com.example.ui

import androidx.compose.ui.graphics.Color

/** Opti's unset-tag grey `#888` (DEC-14). */
val TagColorFallback = Color(0xFF888888)

/**
 * CR-PWA-002: a tag `color` is free text in synced data. `android.graphics.Color.parseColor`
 * throws IllegalArgumentException on bad input (and StringIndexOutOfBounds on ""), which crashed
 * the render. Blank or unparseable input returns [TagColorFallback]; stored data is not changed.
 *
 * DEC-16: the PWA renders the colour as CSS, so hex is read in CSS order like the PWA:
 * `#rgb` -> `#rrggbb`, `#rgba` -> `#rrggbbaa`, and CSS `#rrggbbaa` -> Android `#aarrggbb`.
 */
fun safeTagColor(raw: String?): Color {
    if (raw.isNullOrBlank()) return TagColorFallback
    return try {
        Color(android.graphics.Color.parseColor(cssHexToAndroid(raw.trim())))
    } catch (e: IllegalArgumentException) {
        TagColorFallback
    }
}

/** Turns a CSS hex colour into the form `parseColor` reads the same way. Other text is returned as is. */
internal fun cssHexToAndroid(s: String): String {
    if (!s.startsWith("#")) return s
    val hex = s.substring(1)
    if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return s
    val full = if (hex.length == 3 || hex.length == 4) hex.map { "$it$it" }.joinToString("") else hex
    return when (full.length) {
        8 -> "#" + full.substring(6, 8) + full.substring(0, 6) // CSS rrggbbaa -> Android aarrggbb
        else -> "#$full"
    }
}
