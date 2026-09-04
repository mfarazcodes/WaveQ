package com.waveq.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.theme.*

// ---------------------------------------------------------------------------
// Severity
// ---------------------------------------------------------------------------

/**
 * Five levels, not the four in the design. EVACUATE is added to match
 * FloodGuard's frozen stage scale - see the note in Color.kt.
 *
 * Every level carries a colour AND a label. WORKING_CONVENTIONS forbids colour
 * as the only signal: orange and red are nearly indistinguishable on a phone
 * screen in bright daylight, and colourblind users get nothing from either.
 */
enum class Severity(
    val label: String,
    val labelHi: String,
    val solid: Color,
    val bg: Color,
    val fg: Color,
) {
    LOW("Low", "कम", SeverityLow, SeverityLowBg, SeverityLowFg),
    MEDIUM("Medium", "मध्यम", SeverityMedium, SeverityMediumBg, SeverityMediumFg),
    HIGH("High", "उच्च", SeverityHigh, SeverityHighBg, SeverityHighFg),
    CRITICAL("Critical", "गंभीर", SeverityCritical, SeverityCriticalBg, SeverityCriticalFg),
    EVACUATE("Evacuate", "तुरंत निकलें", SeverityEvacuate, SeverityCriticalBg, SeverityEvacuate),
}

/**
 * The pill badges throughout the design.
 *
 * Two fills appear: solid (Critical / High / Online / Active) and tinted
 * (Low / Verified). ASSUMPTION: solid signals urgency, tinted signals status.
 * `solid = true` reproduces the "Critical" red pill in image 2.
 */
@Composable
fun SeverityBadge(
    severity: Severity,
    modifier: Modifier = Modifier,
    solid: Boolean = true,
) {
    val bg = if (solid) severity.solid else severity.bg
    val fg = if (solid) Color.White else severity.fg
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.badgeRadius))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(severity.label, style = AppTypography.labelSmall, color = fg)
    }
}

/** Generic status pill: "Online", "Active", "Verified". */
@Composable
fun StatusPill(
    text: String,
    bg: Color,
    fg: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.badgeRadius))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = AppTypography.labelSmall, color = fg)
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

/** The red circular alert logo. Appears in the top bar and as a hero mark. */
@Composable
fun AlertLogo(size: androidx.compose.ui.unit.Dp = Dimens.logoSize) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(BrandRed),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.66f),
        )
    }
}

/**
 * Top bar: logo + red wordmark on the left, hamburger in an outlined square
 * on the right. Present on every screen except Login.
 */
@Composable
fun DisasterTopBar(onMenuClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Surface, shadowElevation = 0.dp, modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.topBarHeight)
                .padding(horizontal = Dimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlertLogo()
            Spacer(Modifier.width(10.dp))
            Text("WaveQ", style = WordmarkStyle, color = BrandRed)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(Dimens.borderWidth, BorderLight, RoundedCornerShape(8.dp))
                    .clickable(onClick = onMenuClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu", tint = TextPrimary)
            }
        }
        HorizontalDivider(color = BorderLight, thickness = Dimens.borderWidth)
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** White card, 1dp border, 12dp radius. The base container for everything. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Color = Surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(RoundedCornerShape(Dimens.cardRadius))
        .background(background)
        .border(Dimens.borderWidth, BorderLight, RoundedCornerShape(Dimens.cardRadius))
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        content = content,
    )
}

/**
 * Dashboard stat card (image 3): tinted fill, icon and badge on the top row,
 * a large number, then a caption.
 */
@Composable
fun StatCard(
    icon: ImageVector,
    iconTint: Color,
    background: Color,
    value: String,
    caption: String,
    badge: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier, background = background) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.weight(1f))
                badge?.invoke()
            }
            Spacer(Modifier.height(20.dp))
            Text(value, style = StatNumberStyle, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            Text(caption, style = AppTypography.bodySmall, color = TextSecondary)
        }
    }
}

/**
 * Compact stat card used in the public and analytics views (images 8, 11):
 * caption on top, coloured number below. No icon.
 */
@Composable
fun CompactStatCard(
    caption: String,
    value: String,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(caption, style = AppTypography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(18.dp))
            Text(value, style = StatNumberStyle, color = valueColor)
        }
    }
}

/** Quick Actions row: tinted icon tile, title, subtitle. Whole row is tappable. */
@Composable
fun ActionRowCard(
    icon: ImageVector,
    iconTint: Color,
    tileColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.iconTile)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = AppTypography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = AppTypography.bodySmall, color = TextSecondary)
            }
        }
    }
}

/**
 * Recent Critical Alerts row (image 2): tinted background matching severity,
 * icon, title, relative time, and a severity badge on the right.
 */
@Composable
fun AlertRow(
    title: String,
    timeAgo: String,
    severity: Severity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(Dimens.cardRadius))
        .background(severity.bg)
        .border(Dimens.borderWidth, severity.solid.copy(alpha = 0.25f), RoundedCornerShape(Dimens.cardRadius))
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = severity.solid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(timeAgo, style = AppTypography.bodySmall, color = TextSecondary)
        }
        SeverityBadge(severity)
    }
}

/** System Status row (image 2): name, sub-label, status pill. */
@Composable
fun StatusRow(
    name: String,
    detail: String,
    pillText: String,
    pillColor: Color = StatusOnline,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(SurfaceMuted)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AppTypography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = AppTypography.bodySmall, color = TextSecondary)
        }
        StatusPill(pillText, pillColor)
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/** Full-width red CTA: "Sign In", "Report Disaster". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = BrandRed,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
        shape = RoundedCornerShape(Dimens.cardRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            // ASSUMPTION: disabled state is not shown anywhere in the design.
            disabledContainerColor = BrandRed.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = AppTypography.titleMedium)
    }
}

/** White button with a border, used on the red gradient card ("Demo as Citizen"). */
@Composable
fun OutlineOnColorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(Dimens.cardRadius),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = TextPrimary),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = AppTypography.titleSmall)
    }
}

/** Field label above a filled input, as in the Login screen. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Text(label, style = AppTypography.labelLarge, color = TextPrimary)
        Spacer(Modifier.height(Dimens.fieldSpacing))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = AppTypography.bodyMedium, color = TextTertiary) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(Dimens.fieldRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.fieldHeight),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceMuted,
                unfocusedContainerColor = SurfaceMuted,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = BrandRed,
            ),
        )
    }
}

/**
 * Pill segmented control: Login/Sign Up, Crisis Map/Analytics, admin tabs.
 *
 * The design shows a grey track with a white raised pill on the selected item.
 * ASSUMPTION: the selection slide is animated. I have not added the animation -
 * add `animateDpAsState` on the pill offset if you want it, but it is cosmetic
 * and not worth Day 6 time.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.tabTrackRadius))
            .background(SurfaceMuted)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.tabTrackRadius))
                    .background(if (selected) Surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.getOrNull(i)?.let {
                    Icon(it, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = AppTypography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = TextPrimary,
                )
            }
        }
    }
}

/** Section heading above a group of cards ("Quick Actions"). */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppTypography.titleMedium, color = TextPrimary, modifier = modifier)
}

/**
 * Informational notice with an outline border - the "Verified Incidents Only"
 * block in image 8.
 */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.cardPadding)) {
            Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = AppTypography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(body, style = AppTypography.bodySmall, color = TextSecondary)
            }
        }
    }
}

/** The red-to-orange gradient panel behind "Quick Demo Access". */
@Composable
fun GradientPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.panelRadius))
            .background(
                Brush.linearGradient(listOf(GradientRedStart, GradientOrangeEnd)),
            )
            .padding(Dimens.cardPadding),
        content = content,
    )
}

/**
 * Placeholder for the charts in images 7 and 11 (bar, pie, line).
 *
 * Compose has no built-in charting. Add Vico
 * (com.patrykandpatrick.vico:compose-m3) or MPAndroidChart via AndroidView and
 * replace this. Left as a labelled stub rather than a fake chart so nobody
 * ships a picture of data that does not exist.
 */
@Composable
fun ChartPlaceholder(title: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(title, style = AppTypography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text("Chart - wire up Vico", style = AppTypography.bodySmall, color = TextTertiary)
            }
        }
    }
}
