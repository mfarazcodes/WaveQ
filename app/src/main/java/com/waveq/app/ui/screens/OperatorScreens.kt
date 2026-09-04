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

@Composable
fun MapPlaceholder(activeIncidents: Int, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Color(0xFFF0F7FC)),
        ) {
            MapChip("North District", Modifier.align(Alignment.TopStart).padding(12.dp))

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

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Admin panel
// ---------------------------------------------------------------------------

data class AdminUser(val name: String, val email: String, val role: String)

val sampleUsers = listOf(
    AdminUser("Mohd Faraz", "faraz@waveq.org", "Administrator"),
    AdminUser("Ishaan Juneja", "ishaan@waveq.org", "Administrator"),
    AdminUser("Nikita Gupta", "nikita@waveq.org", "Operator"),
    AdminUser("Naitik", "naitik@waveq.org", "Operator"),
    AdminUser("Nishank", "nishank@waveq.org", "Operator"),
    AdminUser("Kajal", "kajal@waveq.org", "Operator"),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AdminScreen(initialUsers: List<AdminUser> = sampleUsers) {
    val users = remember { mutableStateListOf<AdminUser>().apply { addAll(initialUsers) } }

    var showAddDialog by remember { mutableStateOf(false) }
    var newUserName by remember { mutableStateOf("") }
    var newUserEmail by remember { mutableStateOf("") }
    var newUserRole by remember { mutableStateOf("Operator") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text("Admin Management Panel", style = AppTypography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Manage users and monitor system operators",
            style = AppTypography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        // Quick Stats
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactStatCard("Total Users", "${users.size}", TextPrimary, Modifier.weight(1f))
            CompactStatCard("Total Reports", "5", TextPrimary, Modifier.weight(1f))
            CompactStatCard("System Uptime", "99.8%", TextPrimary, Modifier.weight(1f))
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))

        // User Management Card
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading("User Management")
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(Dimens.cardRadius),
                        colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add User", style = AppTypography.titleSmall)
                    }
                }

                Spacer(Modifier.height(Dimens.sectionSpacing))

                users.forEachIndexed { index, u ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name, style = AppTypography.titleSmall, color = TextPrimary)
                            Text(u.email, style = AppTypography.bodySmall, color = TextSecondary)
                        }
                        StatusPill(u.role, SurfaceMuted, TextSecondary)

                        Spacer(Modifier.width(4.dp))

                        // Delete button to remove old users
                        IconButton(
                            onClick = { users.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Remove User",
                                tint = SeverityCritical,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (index < users.lastIndex) {
                        HorizontalDivider(color = BorderLight)
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    // Add User Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text("Add New User", style = AppTypography.headlineSmall, color = TextPrimary)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = newUserName,
                        onValueChange = { newUserName = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newUserEmail,
                        onValueChange = { newUserEmail = it },
                        label = { Text("Email Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Role", style = AppTypography.labelMedium, color = TextPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Operator", "Administrator", "Viewer").forEach { role ->
                            val isSelected = newUserRole == role
                            FilterChip(
                                selected = isSelected,
                                onClick = { newUserRole = role },
                                label = { Text(role, style = AppTypography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ActionBlue,
                                    selectedLabelColor = Surface
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newUserName.isNotBlank() && newUserEmail.isNotBlank()) {
                            users.add(
                                AdminUser(
                                    name = newUserName.trim(),
                                    email = newUserEmail.trim(),
                                    role = newUserRole
                                )
                            )
                            newUserName = ""
                            newUserEmail = ""
                            newUserRole = "Operator"
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Text("Add", color = Surface)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}