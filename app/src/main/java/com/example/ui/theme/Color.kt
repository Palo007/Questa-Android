package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WP-8.17 -- Theme values (Color/Theme/XML only).
 *
 * Opti `:root` CSS custom properties (`index.html`:14-19 at `7d5d4f3`) plus the seven selector
 * colors the plan's token table cites (`PARITY_FIX_PLAN.html`, WP-8.17 card / §4.5 8.17 row:
 * "Same token table as r2 ... The values are verified against G13 at `7d5d4f3`."). `TokenParityTest`
 * asserts every value below matches Opti byte-for-byte; do not "improve" any of them here.
 */
object OptiRootTokens {
    // index.html:14
    val Bg = Color(0xFF36205C) // --bg
    val Panel = Color(0xFF432263) // --panel
    val Panel2 = Color(0xFF4F2A93) // --panel2
    val Ink = Color(0xFFF4F1FB) // --ink
    val Muted = Color(0xFFBCA8E0) // --muted

    // index.html:15
    val Hp = Color(0xFFF74E52) // --hp
    val Xp = Color(0xFFFFD200) // --xp
    val Gold = Color(0xFFFFBE5C) // --gold
    val Mp = Color(0xFF50B5E9) // --mp

    // index.html:16
    val Blue = Color(0xFF2995CD) // --blue
    val LBlue = Color(0xFF74B6E0) // --lblue
    val Green = Color(0xFF48B677) // --green
    val Yellow = Color(0xFFFFBE5C) // --yellow

    // index.html:17
    val Orange = Color(0xFFF9A03F) // --orange
    val Red = Color(0xFFF74E52) // --red
    val DarkRed = Color(0xFFB0375A) // --darkred

    // index.html:18
    val Line = Color(0xFF5B3A86) // --line
    val Accent = Color(0xFFBDA8FF) // --accent

    // index.html:19 -- --appw is a dimension token (480px), not a color; kept in px for parity.
    const val AppWidthPx = 480
}

/**
 * Selector values from the same token table (7 entries): the page background plus the six
 * hard-coded selector colors the WP-8.17 card cites by line.
 */
object OptiSelectorTokens {
    val PageBg = Color(0xFF1D1233) // html,body background, index.html:22
    val BarTrackBg = Color(0xFF23143D) // .bar background, index.html:71
    val BarTrackBorder = Color(0xFF1A0F2E) // .bar border, index.html:72
    val HabitOff = Color(0xFF4A3568) // .task.habit .check.off color, index.html:171
    val Nav = Color(0xFF2A1947) // nav background, index.html:484
    val ToastBg = Color(0xFF1D0F38) // .toastMsg background, index.html:493
    val DeathScrim = Color(0xEB280514) // .deathOverlay background rgba(40,5,20,.92), index.html:569
}

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

val BackgroundDark = OptiSelectorTokens.PageBg
val SurfaceDark = OptiRootTokens.Panel
val PrimaryNeon = OptiRootTokens.Accent
val SecondaryPurple = OptiRootTokens.Panel2
val TertiaryPurple = OptiRootTokens.Blue
val BorderLavender = OptiRootTokens.Line
val TextPrimary = OptiRootTokens.Ink
val TextSecondary = OptiRootTokens.Muted
val SuccessCyan = OptiRootTokens.Green
val WarningHP = OptiRootTokens.Hp
val GoldColor = OptiRootTokens.Gold
val MpColor = OptiRootTokens.Mp
