package com.waveq.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------- brand ----
val BrandRed = Color(0xFFE10600)
val BrandRedDark = Color(0xFFB80500)
val BrandRedDeep = Color(0xFF8A0000)

/**
 * Dark-theme brand red: desaturated from [BrandRed] so a full-screen "primary"
 * surface (buttons, the SOS-active screen, the login wordmark) doesn't glare
 * at night. NOT used by CriticalAlertActivity, which stays red-on-red with
 * the full-intensity [BrandRed] in both themes on purpose - that screen
 * already has a siren doing the alarming, so visual subtlety is not the goal.
 */
val BrandRedNight = Color(0xFFD65C56)

// -------------------------------------------------------------- neutral ----
val AppBackground = Color(0xFFF8FAFC)
val Surface = Color(0xFFFFFFFF)
val SurfaceMuted = Color(0xFFF1F5F9)
val BorderLight = Color(0xFFE2E8F0)
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF64748B)
val TextTertiary = Color(0xFF94A3B8)

val DrawerSelected = Color(0xFF0A0A0A)
val DrawerOnSelected = Color(0xFFFFFFFF)

/** Fixed (not theme-aware) dark text for the handful of chips pinned white in both themes - e.g. OutlineOnColorButton on the brand gradient. */
val OnLightChip = Color(0xFF0F172A)

/**
 * Dark neutrals. Background is near-black with a slight blue cast rather than
 * pure black - true black makes elevation (surface vs. surfaceMuted vs. card
 * borders) impossible to read since nothing can visually sit "above" it.
 */
val AppBackgroundDark = Color(0xFF0B1120)
val SurfaceDark = Color(0xFF141B2E)
val SurfaceMutedDark = Color(0xFF1E293B)
val BorderLightDark = Color(0xFF334155)
val TextPrimaryDark = Color(0xFFF1F5F9)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextTertiaryDark = Color(0xFF64748B)

/** Drawer's "selected" pill flips to a light chip on the dark drawer sheet, dark text on top - same contrast intent as light mode, inverted. */
val DrawerSelectedDark = Color(0xFFE2E8F0)
val DrawerOnSelectedDark = Color(0xFF0B1120)

// ------------------------------------------------------------- severity ----
val SeverityLow = Color(0xFF22C55E)
val SeverityMedium = Color(0xFFEAB308)
val SeverityHigh = Color(0xFFF97316)
val SeverityCritical = Color(0xFFEF4444)
val SeverityEvacuate = Color(0xFF7F1D1D)

val SeverityLowBg = Color(0xFFDCFCE7)
val SeverityLowFg = Color(0xFF16A34A)
val SeverityMediumBg = Color(0xFFFEF9C3)
val SeverityMediumFg = Color(0xFFA16207)
val SeverityHighBg = Color(0xFFFFEDD5)
val SeverityHighFg = Color(0xFFC2410C)
val SeverityCriticalBg = Color(0xFFFEE2E2)
val SeverityCriticalFg = Color(0xFFDC2626)

/**
 * Dark-mode severity solids, brightened (not reused from light) so they read
 * clearly against the near-black background - the light -500/-900 shades
 * would look muddy and low-contrast there. Bg/Fg tints are derived from these
 * at theme-construction time (see AppExtraColors) as low-alpha overlays,
 * rather than baked as separate hex constants here.
 */
val SeverityLowDark = Color(0xFF4ADE80)
val SeverityMediumDark = Color(0xFFFACC15)
val SeverityHighDark = Color(0xFFFB923C)
val SeverityCriticalDark = Color(0xFFF87171)

/** Kept darker/more saturated than [SeverityCriticalDark] so "beyond critical" still reads as more severe, without vanishing into the near-black background the way the light palette's near-black Evacuate red would. */
val SeverityEvacuateDark = Color(0xFFDC2626)

// --------------------------------------------------------------- status ----
/** "Online / running" indicator. Not an interactive accent - that is colorScheme.primary. */
val StatusOnline = Color(0xFF22C55E)

/**
 * Used only for colorScheme.secondary, which the redesign does not paint with.
 * The per-feature accent palette (red/amber/blue/green/purple tiles and their
 * pastel tints) was removed with the card-and-tile layout it existed for.
 */
val AccentBlueDark = Color(0xFF60A5FA)
val AccentBlue = Color(0xFF2563EB)
