package com.waveq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.R
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
 *
 * Colors are NOT baked in here (an enum constant can't read the current
 * theme) - resolve them per-composition via [Severity.colors].
 */
enum class Severity(val label: String, val labelHi: String) {
    LOW("Low", "कम"),
    MEDIUM("Medium", "मध्यम"),
    HIGH("High", "उच्च"),
    CRITICAL("Critical", "गंभीर"),
    EVACUATE("Evacuate", "तुरंत निकलें"),
}

/**
 * The severity at which an alert stops being a notification and starts being an
 * alarm: siren, vibration, and the full-screen takeover.
 *
 * Declared once, here, because two places need it - the alert path that decides
 * whether to sound, and the operator-facing warning that promises what will
 * happen. Those were two independent `== Severity.CRITICAL` comparisons, which
 * is a promise and an implementation free to drift apart: an operator could be
 * told one thing while receiving devices did another.
 *
 * HIGH deliberately sits below the line. Alerts that wake every phone in range
 * have to stay rare to stay effective, and BroadcastAlertScreen says plainly
 * that a HIGH broadcast will not sound an alarm.
 */
val SIREN_THRESHOLD: Severity = Severity.CRITICAL

/**
 * Whether this severity sounds the alarm on receiving devices.
 *
 * An **ordinal comparison, not equality**, and that is the whole point:
 * [Severity.EVACUATE] ranks above [Severity.CRITICAL], so an equality check
 * silently excluded the most severe level in the scale. An operator telling
 * people to leave immediately got a quiet notification while a CRITICAL alert
 * sirened - the inverse of what the scale means. Any level added above the
 * threshold in future is included automatically.
 */
val Severity.soundsAlarm: Boolean
    get() = ordinal >= SIREN_THRESHOLD.ordinal

/**
 * The severity at which an alert is worth listing after the fact.
 *
 * Deliberately a **separate** constant from [SIREN_THRESHOLD], not derived from
 * it. "Loud enough to wake you" and "worth showing in a list of recent alerts"
 * are two different policies that happen to overlap today, and they have to stay
 * independently movable: raising the siren bar to reduce alarm fatigue must not
 * silently empty the recent-alerts list, and widening the list must not start
 * sounding alarms.
 *
 * They are currently one level apart - HIGH is listed but does not sound.
 */
val NOTABLE_THRESHOLD: Severity = Severity.HIGH

/**
 * Whether this severity belongs in a recent-alerts list.
 *
 * Ordinal comparison for the same reason as [soundsAlarm]: an equality or
 * explicit-set check silently drops [Severity.EVACUATE], which ranks above
 * CRITICAL. The recent-alerts list used to test
 * `severity != CRITICAL && severity != HIGH`, so an EVACUATE alert would siren
 * and take over the screen and then not appear in the list of what had just
 * happened.
 */
val Severity.isNotable: Boolean
    get() = ordinal >= NOTABLE_THRESHOLD.ordinal

data class SeverityColors(val solid: Color, val bg: Color, val fg: Color)

/**
 * Theme-aware severity colors. This is the one palette the minimal redesign
 * keeps multi-hued: everything else interactive uses the single accent, but a
 * severity scale that all reads as one colour is useless in an emergency.
 */
@Composable
fun Severity.colors(): SeverityColors {
    val extra = MaterialTheme.appExtraColors
    return when (this) {
        Severity.LOW -> SeverityColors(extra.severityLow, extra.severityLowBg, extra.severityLowFg)
        Severity.MEDIUM -> SeverityColors(extra.severityMedium, extra.severityMediumBg, extra.severityMediumFg)
        Severity.HIGH -> SeverityColors(extra.severityHigh, extra.severityHighBg, extra.severityHighFg)
        Severity.CRITICAL -> SeverityColors(extra.severityCritical, extra.severityCriticalBg, extra.severityCriticalFg)
        // Reuses Critical's bg tint and its own solid as fg, same relationship as the light palette.
        Severity.EVACUATE -> SeverityColors(extra.severityEvacuate, extra.severityCriticalBg, extra.severityEvacuate)
    }
}

/** Where a level sits on the risk gauge arc, 0..1. */
fun Severity.gaugeFraction(): Float = (ordinal + 1f) / Severity.entries.size

// ---------------------------------------------------------------------------
// Type helpers
// ---------------------------------------------------------------------------

/** Small uppercase wide-tracked caption. The only label style in the app. */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.appExtraColors.textTertiary,
) {
    Text(text.uppercase(), style = MicroLabelStyle, color = color, modifier = modifier)
}

/** Section marker. Replaces the old bold heading - flat label, no card around the content. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    MicroLabel(text, modifier)
}

/** A light-weight number with a micro label beneath. The secondary metric unit. */
@Composable
fun MetricStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = StatNumberStyle, color = valueColor)
        Spacer(Modifier.height(6.dp))
        MicroLabel(label)
    }
}

// ---------------------------------------------------------------------------
// Hero gauge
// ---------------------------------------------------------------------------

private const val GAUGE_START_ANGLE = 140f
private const val GAUGE_SWEEP = 260f

/**
 * The one dominant element on Home: an arc showing where the current risk sits
 * on the severity scale, the level name at centre, location beneath.
 */
@Composable
fun RiskGauge(
    level: Severity,
    location: String,
    fraction: Float = level.gaugeFraction(),
    modifier: Modifier = Modifier,
) {
    val levelColors = level.colors()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val strokeDp = Dimens.gaugeStroke

    Box(modifier = modifier.size(Dimens.gaugeSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = strokeDp.toPx()
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

            drawArc(
                color = track,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = levelColors.solid,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            MicroLabel("Flood risk")
            Spacer(Modifier.height(12.dp))
            Text(level.label, style = AppTypography.displayMedium, color = levelColors.solid)
            Spacer(Modifier.height(12.dp))
            Text(
                location,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The gauge before there is anything to show.
 *
 * Deliberately not [RiskGauge] with a LOW level: an empty arc labelled "Low"
 * would be a claim the app cannot support, and "no data" must never read as
 * "no risk" on a flood warning screen.
 */
@Composable
fun RiskGaugeEmpty(caption: String, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val strokeDp = Dimens.gaugeStroke

    Box(modifier = modifier.size(Dimens.gaugeSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = strokeDp.toPx()
            drawArc(
                color = track,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP,
                useCenter = false,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(size.width - strokePx, size.height - strokePx),
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            MicroLabel("Flood risk")
            Spacer(Modifier.height(12.dp))
            Text("--", style = AppTypography.displayMedium, color = MaterialTheme.appExtraColors.textTertiary)
            Spacer(Modifier.height(12.dp))
            Text(
                caption,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------

@Composable
fun SeverityBadge(
    severity: Severity,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    val severityColors = severity.colors()
    val bg = if (solid) severityColors.solid else severityColors.bg
    val fg = if (solid) Color.White else severityColors.fg
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.badgeRadius))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(severity.label.uppercase(), style = MicroLabelStyle, color = fg)
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
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text.uppercase(), style = MicroLabelStyle, color = fg)
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

/** The red circular alert logo. Appears in the top bar and as a hero mark. */
@Composable
fun AlertLogo(size: androidx.compose.ui.unit.Dp = Dimens.logoSize) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/**
 * Top bar: logo + wordmark on the left, SOS and menu on the right. Flat - it
 * sits on the page background with no divider or shadow, so the screen reads
 * as one surface.
 */
@Composable
fun DisasterTopBar(onMenuClick: () -> Unit, modifier: Modifier = Modifier, onSosClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .height(Dimens.topBarHeight)
            .padding(horizontal = Dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlertLogo()
        Spacer(Modifier.width(12.dp))
        // Read from app_name rather than hardcoded, so the wordmark and the
        // launcher label can never disagree again.
        Text(
            stringResource(R.string.app_name),
            style = WordmarkStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (onSosClick != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSosClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Sos, contentDescription = "SOS", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Icon(
            Icons.Filled.Menu,
            contentDescription = "Open menu",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onMenuClick),
        )
    }
}

/**
 * Persistent app-wide banner shown on every screen while at least one other
 * device has an active (non-stale) SOS beacon out. Not a Snackbar - it does
 * not auto-dismiss, since the emergency it reports is still ongoing.
 */
@Composable
fun SosBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Sos, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (count == 1) "1 active SOS nearby - tap to view" else "$count active SOS beacons nearby - tap to view",
            style = AppTypography.titleSmall,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Shown while this user has pinned one or more SOS beacons as ones they are
 * responding to. Distinct from [SosBanner]: that says "somebody nearby needs
 * help", this says "you said you would go".
 */
@Composable
fun RespondingBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.appExtraColors.severityHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PushPin, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (count == 1) "Responding to 1 SOS - tap to view" else "Responding to $count SOS - tap to view",
            style = AppTypography.titleSmall,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * A block of raised tone. No border, no shadow - separation comes from the
 * background step and the whitespace around it.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .clip(RoundedCornerShape(Dimens.cardRadius))
        .background(background)
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        content = content,
    )
}

/**
 * Flat navigation row: icon, label, optional subtitle, chevron. Replaces the
 * old coloured icon tiles - the icon is muted and the row itself is the
 * affordance.
 */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.listRowHeight)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.appExtraColors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Recent-alert row: severity tint as the whole background, no border. The
 * severity colour is the point, so it carries the block.
 */
@Composable
fun AlertRow(
    title: String,
    timeAgo: String,
    severity: Severity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val severityColors = severity.colors()
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(Dimens.cardRadius))
        .background(severityColors.bg)
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(timeAgo, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SeverityBadge(severity)
    }
}

/** System status row: name, sub-label, status pill. Flat, no fill. */
@Composable
fun StatusRow(
    name: String,
    detail: String,
    pillText: String,
    pillColor: Color = MaterialTheme.appExtraColors.statusOnline,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(detail, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(pillText, pillColor)
    }
}

/** Informational notice: raised tone block, icon, no border. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.cardPadding)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(body, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

/** The single accent-filled CTA. One per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
        shape = RoundedCornerShape(Dimens.cardRadius),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = containerColor.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = AppTypography.titleMedium)
    }
}

/** Muted companion to [PrimaryButton] - a tone step, not an outline. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
        shape = RoundedCornerShape(Dimens.cardRadius),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = AppTypography.titleSmall)
    }
}

/** Micro label above a filled, borderless input. */
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
        if (label.isNotEmpty()) {
            MicroLabel(label)
            Spacer(Modifier.height(Dimens.fieldSpacing))
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = AppTypography.bodyMedium, color = MaterialTheme.appExtraColors.textTertiary) },
            singleLine = true,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            textStyle = AppTypography.bodyMedium,
            shape = RoundedCornerShape(Dimens.fieldRadius),
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.fieldHeight),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * Pill segmented control. The selected segment is a neutral tone lift rather
 * than the accent, so it stays correct when the segments themselves carry
 * meaning (severity pickers).
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    val selectedFill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.tabTrackRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val contentColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.appExtraColors.textTertiary
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dimens.tabTrackRadius))
                    .background(if (selected) selectedFill else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.getOrNull(i)?.let {
                    Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = AppTypography.titleSmall, color = contentColor)
            }
        }
    }
}
