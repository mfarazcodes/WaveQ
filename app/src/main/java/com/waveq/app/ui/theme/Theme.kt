package com.waveq.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Surface,
    secondary = AccentBlue,
    onSecondary = Surface,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    outline = BorderLight,
    error = SeverityCritical,
    onError = Surface,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object Dimens {
    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val cardSpacing = 12.dp
    val sectionSpacing = 20.dp
    val fieldSpacing = 8.dp

    val cardRadius = 12.dp
    val panelRadius = 16.dp
    val fieldRadius = 8.dp
    val badgeRadius = 999.dp
    val tabTrackRadius = 24.dp

    val borderWidth = 1.dp

    val primaryButtonHeight = 52.dp
    val fieldHeight = 52.dp
    val topBarHeight = 60.dp
    val iconTile = 40.dp
    val logoSize = 34.dp
}

@Composable
fun DisasterReportTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}