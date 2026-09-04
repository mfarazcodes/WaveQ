package com.waveq.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
@Composable
fun HomeScreen(
    userName: String = "Operator",
    onReportIncident: () -> Unit,
    onOperatorDashboard: () -> Unit,
    onPublicView: () -> Unit,
    onAdminPanel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text("Welcome, $userName", style = AppTypography.headlineSmall, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("Emergency management dashboard", style = AppTypography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(Dimens.sectionSpacing))

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
            StatCard(
                icon = Icons.Filled.Error, iconTint = AccentRed, background = AccentRedTint,
                value = "5", caption = "Active Incidents",
                badge = { StatusPill("Active", SeverityCritical) },
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Filled.TrendingUp, iconTint = AccentAmber, background = AccentAmberTint,
                value = "2", caption = "High Priority",
                badge = { SeverityBadge(Severity.HIGH) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Dimens.cardSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
            StatCard(
                icon = Icons.Filled.LocationOn, iconTint = AccentBlue, background = AccentBlueTint,
                value = "12", caption = "Today's Reports",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Filled.Notifications, iconTint = AccentGreen, background = AccentGreenTint,
                value = "4", caption = "Confirmed",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Quick Actions")
        Spacer(Modifier.height(Dimens.cardSpacing))

        ActionRowCard(
            Icons.Filled.Error, AccentRed, TileRed,
            "Report New Incident", "Submit emergency report", onReportIncident,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        ActionRowCard(
            Icons.Filled.Groups, AccentBlue, TileBlue,
            "Operator Dashboard", "Validate incident reports", onOperatorDashboard,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        ActionRowCard(
            Icons.Filled.Shield, AccentGreen, TileGreen,
            "Public Crisis View", "View confirmed incidents", onPublicView,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        ActionRowCard(
            Icons.Filled.Settings, AccentPurple, TilePurple,
            "System Administration", "Manage users & settings", onAdminPanel,
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                SectionHeading("Recent Critical Alerts")
                Spacer(Modifier.height(Dimens.sectionSpacing))
                AlertRow("Hurricane - Coastal", "30 min ago", Severity.CRITICAL)
                Spacer(Modifier.height(10.dp))
                AlertRow("Fire - Industrial", "2 hours ago", Severity.HIGH)
                Spacer(Modifier.height(10.dp))
                AlertRow("Flood - Downtown", "1 hour ago", Severity.CRITICAL)
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                SectionHeading("System Status")
                Spacer(Modifier.height(Dimens.sectionSpacing))
                StatusRow("System", "Operational", "Online")
                Spacer(Modifier.height(10.dp))
                StatusRow("SMS Gateway", "Connected", "Active")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun ReportIncidentScreen(onReportDisaster: () -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        AlertLogo(size = 64.dp)
        Spacer(Modifier.height(Dimens.sectionSpacing))
        Text(
            "Report a Disaster or Emergency",
            style = AppTypography.headlineSmall,
            color = TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Help emergency services respond quickly by reporting incidents in your area",
            style = AppTypography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        PrimaryButton(
            text = "Report Disaster",
            onClick = { showSheet = true },
            leadingIcon = Icons.Filled.Error,
            modifier = Modifier.height(76.dp),
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
            InfoTile(
                "Quick Access",
                "Large, easy-to-tap buttons designed for emergency situations",
                Modifier.weight(1f),
            )
            InfoTile(
                "Accessible",
                "Works with low connectivity via SMS/USSD in areas with limited internet",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
    if (showSheet) {
        ReportDisasterSheet(
            onDismiss = { showSheet = false },
            onSubmit = { _, _, _, _ ->
                showSheet = false
                showConfirmation = true
            },
        )
    }
    if (showConfirmation) {
        ReportSubmittedDialog(
            nearbyCount = (8..40).random(),
            onDismiss = { showConfirmation = false },
        )
    }
}

@Composable
private fun InfoTile(title: String, body: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(title, style = AppTypography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text(body, style = AppTypography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
fun PublicCrisisScreen(emergencyNumber: String = "112") {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Filled.Shield, contentDescription = null,
                tint = AccentBlue, modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Public Crisis Dashboard", style = AppTypography.headlineSmall, color = TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Real-time view of confirmed disaster incidents. " +
                    "For emergency assistance, call $emergencyNumber.",
            style = AppTypography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        NoticeCard(
            icon = Icons.Filled.Info,
            title = "Verified Incidents Only",
            body = "This dashboard displays only confirmed incidents that have been " +
                    "validated by emergency operators. Data is updated in real-time.",
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactStatCard("Active Incidents", "4", TextPrimary, Modifier.weight(1f))
            CompactStatCard("Critical Level", "2", SeverityCritical, Modifier.weight(1f))
            CompactStatCard("High Level", "1", SeverityHigh, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        CompactStatCard("Last Updated", "16:59:12", TextPrimary, Modifier.fillMaxWidth())

        Spacer(Modifier.height(Dimens.sectionSpacing))
        CrisisMapView(
            activeIncidentsCount = 4,
            incidents = sampleIncidents
        )
        Spacer(Modifier.height(32.dp))
    }
}