package com.waveq.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppFontFamily = FontFamily.SansSerif

/**
 * A deliberately short scale: two light display sizes for hero numerals, three
 * text sizes, two label sizes. Fewer distinct sizes than before, and the
 * weights carry the hierarchy instead - big numbers are Light, everything
 * secondary is small and Medium.
 */
val AppTypography = Typography(
    // Hero numerals - large and light, negative tracking so big digits don't
    // read as shouty.
    displayLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Light,
        fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Light,
        fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp,
    ),

    // Screen titles.
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp,
    ),

    // Row titles and inline emphasis.
    titleMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),

    // Body copy - generous line height, this app is read under stress.
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp,
    ),

    // Wide-tracked labels. Rendered uppercase at the call site - see MicroLabel.
    labelLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp,
    ),
)

/** Secondary metric numbers - the small row under a hero, still light-weight. */
val StatNumberStyle = TextStyle(
    fontFamily = AppFontFamily, fontWeight = FontWeight.Light,
    fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp,
)

/** Small uppercase caption sitting under a number or above a section. */
val MicroLabelStyle = TextStyle(
    fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
    fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp,
)

val WordmarkStyle = TextStyle(
    fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
    fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp,
)
