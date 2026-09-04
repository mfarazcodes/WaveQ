package com.waveq.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.data.local.WaveQDatabase
import com.waveq.app.data.sync.IncidentSyncWorker
import com.waveq.app.model.defaultSafeZones
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    userName: String = "Operator",
    onReportIncident: () -> Unit,
    onOperatorDashboard: () -> Unit,
    onPublicView: () -> Unit,
    onAdminPanel: () -> Unit,
    onEvacuationMap: () -> Unit = {},
) {
    val context = LocalContext.current
    var userLatitude by remember { mutableStateOf<Double?>(null) }
    var userLongitude by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    userLatitude = loc.latitude
                    userLongitude = loc.longitude
                }
            }
        } catch (_: SecurityException) {}
    }

    val nearestShelter = remember(userLatitude, userLongitude) {
        if (userLatitude != null && userLongitude != null) {
            defaultSafeZones.minByOrNull { it.distanceTo(userLatitude!!, userLongitude!!) }
        } else {
            defaultSafeZones.first()
        } ?: defaultSafeZones.first()
    }

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
                icon = Icons.AutoMirrored.Filled.TrendingUp, iconTint = AccentAmber, background = AccentAmberTint,
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onEvacuationMap)
        ) {
            NearestSafeZoneCard(
                safeZone = nearestShelter,
                userLat = userLatitude,
                userLng = userLongitude
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
            Icons.Filled.DirectionsRun, AccentGreen, TileGreen,
            "Evacuation Map & Route", "Real-time shelters & safe path", onEvacuationMap,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        ActionRowCard(
            Icons.Filled.Groups, AccentBlue, TileBlue,
            "Operator Dashboard", "Triage and verify incident reports", onOperatorDashboard,
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
fun ReportIncidentScreen(onReportDisaster: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
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
                "Offline Ready",
                "Saves reports locally on device and syncs automatically when network reconnects",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showSheet) {
        ReportDisasterSheet(
            onDismiss = { showSheet = false },
            onSubmit = { type, severity, location, description ->
                showSheet = false
                showConfirmation = true

                scope.launch(Dispatchers.IO) {
                    val dao = WaveQDatabase.getDatabase(context).incidentDao()
                    val timestamp = SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
                    val id = "INC-${System.currentTimeMillis().toString().takeLast(4)}"

                    dao.insertIncident(
                        IncidentEntity(
                            id = id,
                            type = type,
                            location = location,
                            description = description,
                            severity = severity,
                            reportedAt = timestamp,
                            verified = false,
                            isSynced = false
                        )
                    )

                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()

                    val syncRequest = OneTimeWorkRequestBuilder<IncidentSyncWorker>()
                        .setConstraints(constraints)
                        .build()

                    WorkManager.getInstance(context).enqueue(syncRequest)
                }
            },
        )
    }

    if (showConfirmation) {
        ReportSubmittedDialog(
            onDismiss = { showConfirmation = false }
        )
    }
}

@Composable
fun ReportSubmittedDialog(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Report Submitted",
                    style = AppTypography.headlineSmall,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your emergency incident has been recorded locally and will sync automatically.",
                    style = AppTypography.bodyMedium,
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    text = "Done",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
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