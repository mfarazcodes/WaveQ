package com.waveq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waveq.app.alerts.AlertSettings
import com.waveq.app.alerts.CriticalAlertTrigger
import com.waveq.app.data.ModerationResult
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.location.LocationController
import com.waveq.app.settings.ThemeMode
import com.waveq.app.settings.ThemeSettings
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Map
// ---------------------------------------------------------------------------

/**
 * The crisis map: real OpenStreetMap tiles with one marker per incident,
 * coloured by severity.
 *
 * Replaces the former `MapPlaceholder`, which painted a hardcoded pale-blue
 * rectangle (`Color(0xFFF0F7FC)`) in both themes - the last unthemed surface in
 * the app, and a 320dp near-white panel in dark mode.
 *
 * Incidents filed without a GPS fix carry null coordinates and cannot be
 * plotted; the count of those is stated rather than dropped silently, because a
 * map showing four of seven reports with no explanation is worse than one that
 * says so.
 */
@Composable
fun IncidentMap(
    incidents: List<Incident>,
    modifier: Modifier = Modifier,
    heightDp: Int = 320,
) {
    val plottable = remember(incidents) { incidents.filter { it.hasCoordinates } }
    val unplottable = incidents.size - plottable.size
    val darkTiles = isSystemInDarkTheme()

    val severityColors = Severity.entries.associateWith { it.colors().solid }
    val markers = remember(plottable, severityColors) {
        plottable.map { incident ->
            MapMarker(
                id = incident.id,
                latitude = incident.latitude!!,
                longitude = incident.longitude!!,
                title = "${incident.type} - ${incident.severity.label}",
                snippet = "${incident.location}\n${incident.reportedAt}",
                tint = severityColors[incident.severity],
            )
        }
    }
    // Centre priority: where the device actually is, then the place the user is
    // viewing, then the centroid of what is plotted. Never world zoom - the map
    // used to fall back to zoom 5 over nothing at all when no incident carried
    // coordinates, which is what "opens at worldwide zoom" was.
    val deviceLocation by LocationController.deviceLocation.collectAsState()
    val viewingPlace by LocationController.viewingPlace.collectAsState()
    val center = remember(deviceLocation, viewingPlace, plottable) {
        deviceLocation?.let { GeoPoint(it.latitude, it.longitude) }
            ?: viewingPlace?.let { GeoPoint(it.latitude, it.longitude) }
            ?: plottable.takeIf { it.isNotEmpty() }?.let { incidents ->
                GeoPoint(
                    incidents.mapNotNull { it.latitude }.average(),
                    incidents.mapNotNull { it.longitude }.average(),
                )
            }
    }
    // A district-level view when there is a real anchor, wider only when the
    // centre itself is a guess.
    val zoom = if (center != null) DEFAULT_MAP_ZOOM else FALLBACK_MAP_ZOOM

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .clip(RoundedCornerShape(Dimens.cardRadius)),
        ) {
            OsmMapView(
                markers = markers,
                modifier = Modifier.fillMaxSize(),
                center = center ?: DEFAULT_MAP_CENTER,
                zoom = zoom,
                darkTiles = darkTiles,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    "Active Incidents",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${incidents.size}",
                    style = StatNumberStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(12.dp),
            ) {
                Text(
                    "Severity Levels",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Severity.entries.forEach { level ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(level.colors().solid))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            level.label,
                            style = AppTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (unplottable > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "$unplottable report(s) have no location fix and are not shown on the map.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        }
    }
}

/**
 * Reports the outcome of a confirm/dismiss, and specifically whether the alert
 * reached any peer.
 *
 * A confirmation that went out to zero devices is not a failure of the
 * operator's action - the report is still confirmed locally and will propagate
 * when a peer appears - but they have to know it has not been announced yet.
 */
@Composable
private fun ModerationResultCard(result: ModerationResult, onDismiss: () -> Unit) {
    val extra = MaterialTheme.appExtraColors
    val background = when {
        !result.confirmed -> MaterialTheme.colorScheme.surfaceVariant
        result.alertSent -> extra.severityLowBg
        else -> extra.severityMediumBg
    }
    AppCard(modifier = Modifier.fillMaxWidth(), background = background) {
        Row(
            Modifier.padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !result.confirmed -> "${result.referenceId} dismissed"
                        result.alertSent -> "${result.referenceId} confirmed and alerted"
                        else -> "${result.referenceId} confirmed - alert not sent yet"
                    },
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !result.confirmed ->
                            "It stays in the record and out of the public view. Nothing was broadcast."
                        result.alertSent ->
                            "Alert handed to ${result.peersReached} nearby " +
                                (if (result.peersReached == 1) "device" else "devices") + " over the mesh."
                        else ->
                            "No devices were in range, so nothing has been announced. The mesh " +
                                "carries it to the next peer it meets - check the mesh is switched on."
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** Incident marker: solid dot with a translucent halo, as in image 5. */
@Composable
fun IncidentMarker(severity: Severity, modifier: Modifier = Modifier) {
    val solid = severity.colors().solid
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(solid.copy(alpha = 0.18f)))
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(solid)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------
// Incident data + card
// ---------------------------------------------------------------------------

data class Incident(
    val id: String,
    val type: String,
    val location: String,
    val description: String,
    val severity: Severity,
    val reportedAt: String,
    val verified: Boolean,
    val isRejected: Boolean = false,
    val verifiedBy: String? = null,
    /** Null when the report was filed with no GPS fix - such reports cannot be plotted. */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val isPending: Boolean get() = !verified && !isRejected
}

/** Wire form -> UI form. The stored severity is a [Severity] name; anything else is a sender bug. */
fun IncidentEntity.toIncident(): Incident = Incident(
    id = id,
    type = type,
    location = location,
    description = description,
    severity = Severity.entries.firstOrNull { it.name == severity } ?: Severity.MEDIUM,
    reportedAt = incidentTimestampFormat.format(Date(reportedAtMillis)),
    verified = verified,
    isRejected = isRejected,
    verifiedBy = verifiedBy,
    latitude = latitude,
    longitude = longitude,
)

private val incidentTimestampFormat = SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.getDefault())

/** District-level: close enough to read streets, wide enough to show a town. */
private const val DEFAULT_MAP_ZOOM = 12.0

/** Used only when there is no device fix, no viewing place and nothing plotted. */
private const val FALLBACK_MAP_ZOOM = 9.0

/**
 * Last-resort centre when the app knows nothing about where the user is.
 *
 * Still a real place at a usable zoom rather than 0,0 at world scale - an
 * unhelpful map is better than one that looks broken.
 */
private val DEFAULT_MAP_CENTER = GeoPoint(28.6139, 77.2090)

val sampleIncidents = listOf(
    Incident("INC-001", "Flood", "Downtown Area, City Center", "Severe flooding due to heavy rainfall", Severity.CRITICAL, "01/09/2026, 16:29:12", true),
    Incident("INC-002", "Fire", "Industrial Zone, Sector 12", "Factory fire reported", Severity.HIGH, "01/09/2026, 15:29:12", true),
    Incident("INC-003", "Hurricane", "Coastal Area, East Side", "Hurricane approaching", Severity.CRITICAL, "01/09/2026, 16:59:12", true),
    Incident("INC-004", "Earthquake", "Suburban Area", "Minor tremors reported", Severity.LOW, "01/09/2026, 14:29:12", false),
)

/**
 * Incident card (image 9).
 *
 * The design lays these out horizontally in a scrolling row, which truncates
 * text badly on a 390dp screen - you can see it clipping "Hurricane" and
 * "Verified" in the screenshot. I made them full-width vertical cards instead.
 * That is a deliberate deviation: horizontal scroll for primary content is a
 * usability problem, and worse when the user is in an emergency.
 */
@Composable
fun IncidentCard(
    incident: Incident,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    /** Non-null only for operators; absent means the card renders read-only. */
    onConfirm: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val severityColors = incident.severity.colors()
    val extra = MaterialTheme.appExtraColors
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = severityColors.solid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(incident.id, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                SeverityBadge(incident.severity)
            }
            Spacer(Modifier.height(10.dp))
            Text(incident.type, style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(incident.location, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(incident.description, style = AppTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Reported: ${incident.reportedAt}", style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // Three states, not two: pending, confirmed, dismissed. An
                // operator needs to tell "nobody has looked at this" from
                // "somebody looked and rejected it".
                when {
                    incident.verified ->
                        StatusPill("Confirmed", extra.severityLowBg, extra.severityLowFg)
                    incident.isRejected ->
                        StatusPill("Dismissed", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    else ->
                        StatusPill("Pending", extra.severityMediumBg, extra.severityMediumFg)
                }
            }

            incident.verifiedBy?.let { reviewer ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (incident.verified) {
                        "Confirmed by $reviewer"
                    } else {
                        "Dismissed by $reviewer"
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (incident.isPending && onConfirm != null && onDismiss != null) {
                Spacer(Modifier.height(Dimens.cardSpacing))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.cardRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = extra.severityLow,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Confirm", style = AppTypography.titleSmall)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.cardRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Dismiss", style = AppTypography.titleSmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Operator dashboard
// ---------------------------------------------------------------------------

/**
 * Operator dashboard (images 5, 7 and 11): a two-tab view over the same data.
 *
 * The design puts the map and the incident list side by side, which clips both
 * on a phone. Stacked vertically here.
 *
 * [incidents] has no default on purpose. It used to fall back to
 * [sampleIncidents], and the nav graph called this with no argument - so real
 * filed reports were invisible and nobody noticed, because the screen still
 * looked populated. Making the caller supply the list means that omission
 * cannot recur silently.
 */
@Composable
fun OperatorDashboardScreen(
    incidents: List<Incident>,
    pendingCount: Int = incidents.count { it.isPending },
    moderationResult: ModerationResult? = null,
    onConfirm: (Incident) -> Unit = {},
    onDismiss: (Incident) -> Unit = {},
    onAcknowledgeModeration: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        // What actually happened to the last confirmation, including when the
        // answer is "it reached nobody".
        moderationResult?.let { result ->
            ModerationResultCard(result, onAcknowledgeModeration)
            Spacer(Modifier.height(Dimens.cardSpacing))
        }

        SegmentedTabs(
            options = listOf("Crisis Map", "Analytics"),
            selectedIndex = tab,
            onSelect = { tab = it },
            icons = listOf(Icons.Filled.LocationOn, Icons.Filled.BarChart),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        if (tab == 0) {
            IncidentMap(incidents = incidents)
            Spacer(Modifier.height(Dimens.sectionSpacing))
            SectionHeading("Incident Reports")
            Spacer(Modifier.height(6.dp))
            Text(
                if (pendingCount > 0) {
                    "${incidents.size} incidents - $pendingCount awaiting review"
                } else {
                    "${incidents.size} incidents - none awaiting review"
                },
                style = AppTypography.bodySmall,
                color = if (pendingCount > 0) {
                    MaterialTheme.appExtraColors.severityMediumFg
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            if (incidents.isEmpty()) {
                Text(
                    "No reports have been filed on this device yet.",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
            // Already ordered pending-first by the DAO query.
            incidents.forEach { incident ->
                IncidentCard(
                    incident = incident,
                    onConfirm = { onConfirm(incident) },
                    onDismiss = { onDismiss(incident) },
                )
                Spacer(Modifier.height(Dimens.cardSpacing))
            }
        } else {
            val extra = MaterialTheme.appExtraColors
            // Every number on this tab comes from `incidents`, including the
            // hero and the metric row. They used to be hardcoded strings - "5"
            // total over a donut whose centre said "4", with a confirmed/pending
            // split that matched neither - so the page contradicted itself the
            // moment the charts were wired to real data.
            val confirmedCount = incidents.count { it.verified }
            val pendingCount = incidents.size - confirmedCount

            // Hero: the number that matters, then the rest small and muted.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${incidents.size}",
                    style = AppTypography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                MicroLabel("Total reports")
            }
            Spacer(Modifier.height(Dimens.heroSpacing))
            // "Avg response" was removed rather than recomputed: nothing in this
            // app records when a report was responded to, so any figure here
            // would be invented. An operational metric with no source is worse
            // than a missing one.
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricStat("$confirmedCount", "Confirmed", Modifier.weight(1f), valueColor = extra.severityLow)
                MetricStat("$pendingCount", "Pending", Modifier.weight(1f), valueColor = extra.severityMedium)
            }
            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Charts are driven off the same incident list, so the totals cannot
            // silently disagree with the numbers above them.
            SectionHeading("Reports by severity")
            DonutChart(
                data = Severity.entries
                    .map { level ->
                        ChartSlice(
                            label = level.label,
                            value = incidents.count { it.severity == level }.toFloat(),
                            color = level.colors().solid,
                        )
                    }
                    .filter { it.value > 0f },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            SectionHeading("Reports by type")
            HorizontalBarChart(
                items = incidents
                    .groupBy { it.type }
                    .map { (type, rows) ->
                        BarItem(type, rows.size.toFloat(), MaterialTheme.colorScheme.primary)
                    }
                    .sortedByDescending { it.value },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            SectionHeading("24-hour trend")
            // TODO: placeholder series. Replace with a real hourly count once
            // incidents carry timestamps that can be bucketed - the shape below
            // is illustrative and must not be presented as measured data.
            SparklineTrendChart(
                points = listOf(1f, 0f, 2f, 1f, 3f, 5f, 4f, 6f, 3f),
                modifier = Modifier.fillMaxWidth(),
                xLabels = listOf("00:00", "06:00", "12:00", "18:00", "Now"),
            )
        }
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

// ---------------------------------------------------------------------------
// Admin panel
// ---------------------------------------------------------------------------

data class AdminUser(val name: String, val email: String, val role: String)

val sampleUsers = listOf(
    AdminUser("Admin User", "admin@emergency.gov", "Administrator"),
    AdminUser("John Operator", "john.operator@emergency.gov", "Operator"),
    AdminUser("Jane Smith", "jane.smith@agency.gov", "Operator"),
    AdminUser("Mike Johnson", "mike.johnson@emergency.gov", "Viewer"),
)

/**
 * Admin panel (images 10, 12, 13, 14) - three tabs over one header.
 *
 * The design uses a horizontally scrolling data table with its own scrollbar,
 * which is a desktop pattern. Rendered as stacked rows here; a table that needs
 * horizontal scrolling on a phone is unusable.
 */
@Composable
fun AdminScreen(
    /** Live count from the incident store, so this panel and the dashboard agree. */
    reportCount: Int,
    users: List<AdminUser> = sampleUsers,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var smsEnabled by remember { mutableStateOf(true) }
    var ussdEnabled by remember { mutableStateOf(true) }
    var apiKey by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var ussdCode by remember { mutableStateOf("") }
    var sirenEnabled by remember { mutableStateOf(AlertSettings.isSirenEnabled(context)) }
    var fullScreenEnabled by remember { mutableStateOf(AlertSettings.isFullScreenEnabled(context)) }
    var themeMode by remember { mutableStateOf(ThemeSettings.mode.value) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text("Admin Management Panel", style = AppTypography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Manage users, system settings, and monitor system activity",
            style = AppTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        SegmentedTabs(
            options = listOf("Users", "Settings", "Activity"),
            selectedIndex = tab,
            onSelect = { tab = it },
            icons = listOf(Icons.Filled.Groups, Icons.Filled.Settings, Icons.Filled.MonitorHeart),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        when (tab) {
            0 -> {
                // "99.8% Uptime" was removed rather than recomputed: nothing in
                // this app measures uptime of anything, so any figure here would
                // be invented - and an invented operational metric on an admin
                // panel is exactly the kind of number someone repeats in a
                // meeting as if it were measured.
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricStat("${users.size}", "Users", Modifier.weight(1f))
                    MetricStat("$reportCount", "Reports", Modifier.weight(1f))
                }
                Spacer(Modifier.height(Dimens.sectionSpacing))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading("User management")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { /* TODO: open add-user form */ }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Add user", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(Dimens.cardSpacing))
                users.forEach { u ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(u.name, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text(u.email, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MicroLabel(u.role)
                    }
                }
            }

            1 -> {
                Column(Modifier.fillMaxWidth()) {
                        SectionHeading("Communication")
                        Spacer(Modifier.height(Dimens.cardSpacing))

                        ToggleSetting(
                            title = "SMS Gateway",
                            subtitle = "Enable SMS-based incident reporting for low connectivity areas",
                            checked = smsEnabled,
                            onCheckedChange = { smsEnabled = it },
                        )
                        if (smsEnabled) {
                            IndentedGroup {
                                LabeledField("SMS Provider", "Twilio", {}, "Select provider")
                                Spacer(Modifier.height(Dimens.cardSpacing))
                                LabeledField("API Key", apiKey, { apiKey = it }, "Enter API key")
                                Spacer(Modifier.height(Dimens.cardSpacing))
                                LabeledField("Phone Number", phone, { phone = it }, "+1234567890")
                            }
                        }

                        Spacer(Modifier.height(Dimens.sectionSpacing))
                        ToggleSetting(
                            title = "USSD Gateway",
                            subtitle = "Enable USSD codes for feature phone compatibility",
                            checked = ussdEnabled,
                            onCheckedChange = { ussdEnabled = it },
                        )
                        if (ussdEnabled) {
                            IndentedGroup {
                                LabeledField("USSD Code", ussdCode, { ussdCode = it }, "*123#")
                            }
                        }
                }

                Spacer(Modifier.height(Dimens.sectionSpacing))
                Column(Modifier.fillMaxWidth()) {
                        SectionHeading("Appearance")
                        Spacer(Modifier.height(Dimens.cardSpacing))
                        SegmentedTabs(
                            options = ThemeMode.entries.map { it.label },
                            selectedIndex = ThemeMode.entries.indexOf(themeMode),
                            onSelect = { index ->
                                themeMode = ThemeMode.entries[index]
                                ThemeSettings.setMode(context, themeMode)
                            },
                            icons = listOf(Icons.Filled.BrightnessAuto, Icons.Filled.LightMode, Icons.Filled.DarkMode),
                            modifier = Modifier.fillMaxWidth(),
                        )
                }

                Spacer(Modifier.height(Dimens.sectionSpacing))
                Column(Modifier.fillMaxWidth()) {
                        SectionHeading("Critical alerts")
                        Spacer(Modifier.height(Dimens.cardSpacing))

                        ToggleSetting(
                            title = "Critical Alert Siren",
                            subtitle = "Loud alarm-stream tone and vibration for CRITICAL SOS and flood alerts",
                            checked = sirenEnabled,
                            onCheckedChange = {
                                sirenEnabled = it
                                AlertSettings.setSirenEnabled(context, it)
                            },
                        )
                        Spacer(Modifier.height(Dimens.sectionSpacing))
                        ToggleSetting(
                            title = "Full-Screen Takeover",
                            subtitle = "Show a full-screen alert over the lock screen for CRITICAL alerts",
                            checked = fullScreenEnabled,
                            onCheckedChange = {
                                fullScreenEnabled = it
                                AlertSettings.setFullScreenEnabled(context, it)
                            },
                        )

                        Spacer(Modifier.height(Dimens.sectionSpacing))
                        Text(
                            "Fires the full critical-alert flow locally - siren, notification, and " +
                                "full-screen takeover - without needing a second device or a live mesh.",
                            style = AppTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.cardSpacing))
                        SecondaryButton(
                            text = "Test alert",
                            onClick = { CriticalAlertTrigger.fireTestAlert(context) },
                            leadingIcon = Icons.Filled.NotificationsActive,
                        )
                }
            }

            else -> {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading("Activity log")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { /* TODO: export */ }) {
                            Text("Export", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(Dimens.cardSpacing))
                    listOf(
                        Triple("01/09/2026, 16:59:12", "John Operator", "Incident confirmed"),
                        Triple("01/09/2026, 16:29:12", "Admin User", "User created"),
                        Triple("01/09/2026, 15:59:12", "System", "SMS gateway reconnected"),
                        Triple("01/09/2026, 15:29:12", "System", "Data sync completed"),
                    ).forEach { (ts, user, action) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                            Text(action, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text("$user  ·  $ts", style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Indented sub-settings, marked by whitespace and a hairline rather than a coloured bar. */
@Composable
private fun IndentedGroup(content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Spacer(Modifier.width(16.dp))
        Column(content = content)
    }
}
