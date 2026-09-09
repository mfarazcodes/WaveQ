package com.waveq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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

private val DarkColors = darkColorScheme(
    primary = BrandRedNight,
    onPrimary = SurfaceDark,
    secondary = AccentBlueDark,
    onSecondary = SurfaceDark,
    background = AppBackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderLightDark,
    error = SeverityCriticalDark,
    onError = SurfaceDark,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object Dimens {
    val screenPadding = 20.dp
    val cardPadding = 20.dp

    /** Tight grouping inside one block. */
    val cardSpacing = 12.dp

    /** Between sections - roughly double the old rhythm; whitespace is the divider now. */
    val sectionSpacing = 40.dp

    /** Around the one dominant element on a screen. */
    val heroSpacing = 56.dp
    val fieldSpacing = 10.dp

    val cardRadius = 16.dp
    val panelRadius = 20.dp
    val fieldRadius = 12.dp
    val badgeRadius = 999.dp
    val tabTrackRadius = 999.dp

    /** Retained only for the rare hairline separator - cards no longer draw borders. */
    val borderWidth = 1.dp

    val primaryButtonHeight = 54.dp
    val fieldHeight = 54.dp
    val topBarHeight = 60.dp
    val listRowHeight = 64.dp
    val logoSize = 30.dp

    /** The Home hero gauge. */
    val gaugeSize = 260.dp
    val gaugeStroke = 14.dp
}

@Composable
fun WaveQTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extraColors = if (darkTheme) DarkAppExtraColors else LightAppExtraColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            // Status/nav bar ICON appearance, not their background - light icons
            // read on a dark bar, dark icons read on a light one.
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppExtraColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
