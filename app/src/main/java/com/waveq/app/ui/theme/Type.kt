package com.waveq.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppFontFamily = FontFamily.SansSerif

val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp,
    ),
)

val StatNumberStyle = TextStyle(
    fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
    fontSize = 34.sp, lineHeight = 40.sp,
)

val WordmarkStyle = TextStyle(
    fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp, lineHeight = 24.sp,
)