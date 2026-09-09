package com.waveq.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tokens with no Material3 ColorScheme role to map onto. Exposed the same way
 * MaterialTheme.colorScheme is, via a CompositionLocal, so call sites read
 * `MaterialTheme.appExtraColors.severityLow` instead of a hardcoded light val
 * that would stay wrong in dark mode.
 *
 * Deliberately small. The UI uses exactly one accent - colorScheme.primary -
 * for everything interactive; the only multi-hue system left is the severity
 * scale, which keeps its distinct colors because emergency legibility beats
 * visual minimalism.
 */
@Immutable
data class AppExtraColors(
    val textTertiary: Color,
    val drawerSelected: Color,
    val drawerOnSelected: Color,
    val statusOnline: Color,
    val severityLow: Color,
    val severityMedium: Color,
    val severityHigh: Color,
    val severityCritical: Color,
    val severityEvacuate: Color,
    val severityLowBg: Color,
    val severityLowFg: Color,
    val severityMediumBg: Color,
    val severityMediumFg: Color,
    val severityHighBg: Color,
    val severityHighFg: Color,
    val severityCriticalBg: Color,
    val severityCriticalFg: Color,
)

val LightAppExtraColors = AppExtraColors(
    textTertiary = TextTertiary,
    drawerSelected = DrawerSelected,
    drawerOnSelected = DrawerOnSelected,
    statusOnline = StatusOnline,
    severityLow = SeverityLow,
    severityMedium = SeverityMedium,
    severityHigh = SeverityHigh,
    severityCritical = SeverityCritical,
    severityEvacuate = SeverityEvacuate,
    severityLowBg = SeverityLowBg,
    severityLowFg = SeverityLowFg,
    severityMediumBg = SeverityMediumBg,
    severityMediumFg = SeverityMediumFg,
    severityHighBg = SeverityHighBg,
    severityHighFg = SeverityHighFg,
    severityCriticalBg = SeverityCriticalBg,
    severityCriticalFg = SeverityCriticalFg,
)

private const val SEVERITY_BG_ALPHA = 0.18f

/**
 * Severity tints are low-alpha overlays of the dark-tuned solid rather than
 * baked pastels - the light palette's near-white tints would glare over a
 * near-black surface.
 */
val DarkAppExtraColors = AppExtraColors(
    textTertiary = TextTertiaryDark,
    drawerSelected = DrawerSelectedDark,
    drawerOnSelected = DrawerOnSelectedDark,
    statusOnline = StatusOnline,
    severityLow = SeverityLowDark,
    severityMedium = SeverityMediumDark,
    severityHigh = SeverityHighDark,
    severityCritical = SeverityCriticalDark,
    severityEvacuate = SeverityEvacuateDark,
    severityLowBg = SeverityLowDark.copy(alpha = SEVERITY_BG_ALPHA),
    severityLowFg = SeverityLowDark,
    severityMediumBg = SeverityMediumDark.copy(alpha = SEVERITY_BG_ALPHA),
    severityMediumFg = SeverityMediumDark,
    severityHighBg = SeverityHighDark.copy(alpha = SEVERITY_BG_ALPHA),
    severityHighFg = SeverityHighDark,
    severityCriticalBg = SeverityCriticalDark.copy(alpha = SEVERITY_BG_ALPHA),
    severityCriticalFg = SeverityCriticalDark,
)

val LocalAppExtraColors = staticCompositionLocalOf { LightAppExtraColors }

val MaterialTheme.appExtraColors: AppExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppExtraColors.current
