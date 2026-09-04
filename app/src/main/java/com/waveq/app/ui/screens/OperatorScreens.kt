package com.waveq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

// ---------------------------------------------------------------------------
// Map
// ---------------------------------------------------------------------------

/**
 * Stand-in for the crisis map (images 5 and 8).
 *
 * The design shows a stylised map with district labels, incident markers with
 * a coloured halo, and a severity legend. Replace the inner Box with MapLibre
 * Native and offline MBTiles - WORKING_CONVENTIONS bans runtime tile fetching
 * from Google or Mapbox in the mobile critical path.
 *
 * The chrome around the map (legend, "Active Incidents" callout) is real and
 * reusable; only the tile surface is a placeholder.
 */
@Composable
fun MapPlaceholder(activeIncidents: Int, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Color(0xFFF0F7FC)), // pale blue map surface from the design
        ) {
            // District label chip
            MapChip("North District", Modifier.align(Alignment.TopStart).padding(12.dp))

            // Floating incident counter
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface)
                    .border(Dimens.borderWidth, BorderLight, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("Active Incidents", style = AppTypography.bodySmall, color = TextSecondary)
                Text("$activeIncidents", style = StatNumberStyle, color = TextPrimary)
            }

            MapChip("City Center", Modifier.align(Alignment.Center))

            // Legend
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface)
                    .border(Dimens.borderWidth, BorderLight, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Text("Severity Levels", style = AppTypography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Severity.entries.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(s.solid))
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, style = AppTypography.bodySmall, color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.badgeRadius))
            .background(Surface)
            .border(Dimens.borderWidth, BorderLight, RoundedCornerShape(Dimens.badgeRadius))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = AppTypography.bodySmall, color = TextPrimary)
    }
}

/** Incident marker: solid dot with a translucent halo, as in image 5. */
@Composable
fun IncidentMarker(severity: Severity, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(severity.solid.copy(alpha = 0.18f)))
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(severity.solid)
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
)

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
fun IncidentCard(incident: Incident, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = incident.severity.solid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(incident.id, style = AppTypography.bodySmall, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                SeverityBadge(incident.severity)
            }
            Spacer(Modifier.height(10.dp))
            Text(incident.type, style = AppTypography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(incident.location, style = AppTypography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Text(incident.description, style = AppTypography.bodyMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BorderLight)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Reported: ${incident.reportedAt}", style = AppTypography.bodySmall, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                if (incident.verified) {
                    StatusPill("Verified", SeverityLowBg, SeverityLowFg)
                } else {
                    StatusPill("Unverified", SurfaceMuted, TextSecondary)
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
 */
@Composable
fun OperatorDashboardScreen(incidents: List<Incident> = sampleIncidents) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        SegmentedTabs(
            options = listOf("Crisis Map", "Analytics"),
            selectedIndex = tab,
            onSelect = { tab = it },
            icons = listOf(Icons.Filled.LocationOn, Icons.Filled.BarChart),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        if (tab == 0) {
            CrisisMapView(
                activeIncidentsCount = incidents.size,
                incidents = incidents
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))
            SectionHeading("Incident Reports")
            Spacer(Modifier.height(6.dp))
            Text("${incidents.size} incidents", style = AppTypography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(Dimens.cardSpacing))
            incidents.forEach {
                IncidentCard(it)
                Spacer(Modifier.height(Dimens.cardSpacing))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactStatCard("Total Reports", "5", TextPrimary, Modifier.weight(1f))
                CompactStatCard("Confirmed", "4", AccentGreen, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactStatCard("Pending Review", "1", AccentAmber, Modifier.weight(1f))
                CompactStatCard("Avg Response Time", "12 min", TextPrimary, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.sectionSpacing))
            // 1. Reports by Severity
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeading(text = "Reports by Severity")
                    DonutChart(
                        data = listOf(
                            ChartSlice("Critical", 2f, SeverityCriticalBg),
                            ChartSlice("High", 1f, SeverityHighBg),
                            ChartSlice("Medium", 1f, SeverityMediumBg),
                            ChartSlice("Low", 1f, SeverityLowBg)
                        )
                    )
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))

            // 2. Reports by Type
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeading(text = "Reports by Type")
                    HorizontalBarChart(
                        items = listOf(
                            BarItem("Flood", 2f, AccentBlue),
                            BarItem("Fire", 1f, SeverityCriticalBg),
                            BarItem("Medical", 1f, SeverityHighBg),
                            BarItem("Infrastructure", 1f, SeverityMediumBg)
                        )
                    )
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))

            // 3. Status Overview
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeading(text = "Status Overview")
                    DonutChart(
                        data = listOf(
                            ChartSlice("Confirmed", 4f, SeverityLowBg),
                            ChartSlice("Pending Review", 1f, SeverityHighBg)
                        )
                    )
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))

            // 4. 24-Hour Report Trend
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeading(text = "24-Hour Report Trend")
                    SparklineTrendChart(
                        points = listOf(1f, 3f, 2f, 5f, 4f, 7f, 5f)
                    )
                }
            }
// 4. 24-Hour Report Trend
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeading(text = "24-Hour Report Trend")
                    SparklineTrendChart(
                        points = listOf(1f, 3f, 2f, 5f, 4f, 7f, 5f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
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
fun AdminScreen(users: List<AdminUser> = sampleUsers) {
    var tab by remember { mutableIntStateOf(0) }
    var smsEnabled by remember { mutableStateOf(true) }
    var ussdEnabled by remember { mutableStateOf(true) }
    var apiKey by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var ussdCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text("Admin Management Panel", style = AppTypography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Manage users, system settings, and monitor system activity",
            style = AppTypography.bodyMedium, color = TextSecondary,
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactStatCard("Total Users", "4", TextPrimary, Modifier.weight(1f))
                    CompactStatCard("Total Reports", "5", TextPrimary, Modifier.weight(1f))
                    CompactStatCard("System Uptime", "99.8%", TextPrimary, Modifier.weight(1f))
                }
                Spacer(Modifier.height(Dimens.sectionSpacing))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dimens.cardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading("User Management")
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { /* TODO: open add-user form */ },
                                shape = RoundedCornerShape(Dimens.cardRadius),
                                colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add User", style = AppTypography.titleSmall)
                            }
                        }
                        Spacer(Modifier.height(Dimens.sectionSpacing))
                        users.forEach { u ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Shield, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.name, style = AppTypography.titleSmall, color = TextPrimary)
                                    Text(u.email, style = AppTypography.bodySmall, color = TextSecondary)
                                }
                                StatusPill(u.role, SurfaceMuted, TextSecondary)
                            }
                            HorizontalDivider(color = BorderLight)
                        }
                    }
                }
            }

            1 -> {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dimens.cardPadding)) {
                        SectionHeading("Communication Settings")
                        Spacer(Modifier.height(Dimens.sectionSpacing))

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
                }
            }

            else -> {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Dimens.cardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading("System Activity Logs")
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(
                                onClick = { /* TODO: export */ },
                                shape = RoundedCornerShape(Dimens.cardRadius),
                                border = androidx.compose.foundation.BorderStroke(Dimens.borderWidth, BorderLight),
                            ) {
                                Text("Export Logs", style = AppTypography.titleSmall, color = TextPrimary)
                            }
                        }
                        Spacer(Modifier.height(Dimens.sectionSpacing))
                        listOf(
                            Triple("01/09/2026, 16:59:12", "John Operator", "Incident confirmed"),
                            Triple("01/09/2026, 16:29:12", "Admin User", "User created"),
                            Triple("01/09/2026, 15:59:12", "System", "SMS gateway reconnected"),
                            Triple("01/09/2026, 15:29:12", "System", "Data sync completed"),
                        ).forEach { (ts, user, action) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                Text(action, style = AppTypography.titleSmall, color = TextPrimary)
                                Spacer(Modifier.height(2.dp))
                                Text("$user  ·  $ts", style = AppTypography.bodySmall, color = TextSecondary)
                            }
                            HorizontalDivider(color = BorderLight)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = AppTypography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // ASSUMPTION: the design's switch reads as near-black when on,
                // not the brand red. Matched to the screenshot.
                checkedThumbColor = Color.White,
                checkedTrackColor = DrawerSelected,
            ),
        )
    }
}

/** The left-bar-indented settings group seen under each toggle in image 14. */
@Composable
private fun IndentedGroup(content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(top = 14.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 40.dp).background(AccentBlue.copy(alpha = 0.4f)))
        Spacer(Modifier.width(14.dp))
        Column(content = content)
    }
}
