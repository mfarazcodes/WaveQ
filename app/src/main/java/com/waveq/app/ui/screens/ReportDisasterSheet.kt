package com.waveq.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import java.util.Locale

private data class DisasterTypeOption(val label: String, val icon: ImageVector)

private val disasterTypes = listOf(
    DisasterTypeOption("Flood", Icons.Filled.Water),
    DisasterTypeOption("Fire", Icons.Filled.LocalFireDepartment),
    DisasterTypeOption("Hurricane", Icons.Filled.Air),
    DisasterTypeOption("Earthquake", Icons.Filled.Landscape),
    DisasterTypeOption("Other", Icons.Filled.Error),
)

private enum class ReportSeverity(val label: String, val color: androidx.compose.ui.graphics.Color) {
    LOW("Low", SeverityLow),
    MEDIUM("Medium", SeverityMedium),
    HIGH("High", SeverityHigh),
    CRITICAL("Critical", SeverityCritical),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDisasterSheet(
    onDismiss: () -> Unit,
    onSubmit: (type: String, severity: String, location: String, description: String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedType by remember { mutableStateOf(disasterTypes.first().label) }
    var selectedSeverity by remember { mutableStateOf(ReportSeverity.MEDIUM) }
    var location by remember { mutableStateOf("Detecting GPS location…") }
    var description by remember { mutableStateOf("") }
    var isLocating by remember { mutableStateOf(false) }

    fun resolveCoordinates(lat: Double, lng: Double) {
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lng, 1) { addresses ->
                    val addr = addresses.firstOrNull()
                    if (addr != null) {
                        val subLocality = addr.subLocality ?: addr.featureName ?: ""
                        val locality = addr.locality ?: addr.subAdminArea ?: ""
                        val state = addr.adminArea ?: ""
                        val parts = listOf(subLocality, locality, state).filter { it.isNotBlank() }
                        location = if (parts.isNotEmpty()) parts.joinToString(", ") else "$lat, $lng"
                    } else {
                        location = "$lat, $lng"
                    }
                    isLocating = false
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                val addr = addresses?.firstOrNull()
                if (addr != null) {
                    val subLocality = addr.subLocality ?: addr.featureName ?: ""
                    val locality = addr.locality ?: addr.subAdminArea ?: ""
                    val state = addr.adminArea ?: ""
                    val parts = listOf(subLocality, locality, state).filter { it.isNotBlank() }
                    location = if (parts.isNotEmpty()) parts.joinToString(", ") else "$lat, $lng"
                } else {
                    location = "$lat, $lng"
                }
                isLocating = false
            }
        } catch (e: Exception) {
            location = "$lat, $lng"
            isLocating = false
        }
    }

    @SuppressLint("MissingPermission")
    fun requestFreshLocation() {
        isLocating = true
        location = "Fetching pinpoint GPS location…"
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5000)
            .build()

        fusedClient.getCurrentLocation(request, cancellationTokenSource.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    resolveCoordinates(loc.latitude, loc.longitude)
                } else {
                    fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            resolveCoordinates(lastLoc.latitude, lastLoc.longitude)
                        } else {
                            location = "Unable to get GPS. Enter manually."
                            isLocating = false
                        }
                    }
                }
            }
            .addOnFailureListener {
                location = "Location lookup failed. Enter manually."
                isLocating = false
            }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            requestFreshLocation()
        } else {
            location = "Location permission denied. Enter manually."
            isLocating = false
        }
    }

    LaunchedEffect(Unit) {
        val fineCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fineCheck == PackageManager.PERMISSION_GRANTED) {
            requestFreshLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = 32.dp),
        ) {
            Text("Report a Disaster", style = AppTypography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "This will be sent to emergency operators and broadcast to nearby users.",
                style = AppTypography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))

            Text("Type", style = AppTypography.labelLarge, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(disasterTypes) { type ->
                    val selected = type.label == selectedType
                    FilterChip(
                        selected = selected,
                        onClick = { selectedType = type.label },
                        label = { Text(type.label) },
                        leadingIcon = {
                            Icon(type.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandRed,
                            selectedLabelColor = Surface,
                            selectedLeadingIconColor = Surface,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text("Severity", style = AppTypography.labelLarge, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportSeverity.entries.forEach { sev ->
                    val selected = sev == selectedSeverity
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) sev.color else SurfaceMuted)
                            .clickable { selectedSeverity = sev },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            sev.label,
                            style = AppTypography.labelSmall,
                            color = if (selected) Surface else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Location", style = AppTypography.labelLarge, color = TextPrimary)
                if (isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandRed)
                }
            }
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "",
                value = location,
                onValueChange = { location = it },
                placeholder = "Enter location manually",
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Auto-detected via high-accuracy GPS. Tap to edit manually.",
                style = AppTypography.bodySmall,
                color = TextTertiary,
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text("Description", style = AppTypography.labelLarge, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "",
                value = description,
                onValueChange = { description = it },
                placeholder = "What's happening? Any immediate dangers?",
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            PrimaryButton(
                text = "Submit & Alert Nearby Users",
                onClick = { onSubmit(selectedType, selectedSeverity.label, location, description) },
                leadingIcon = Icons.Filled.Campaign,
            )
        }
    }
}

@Composable
fun ReportSubmittedDialog(nearbyCount: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SeverityLow) },
        title = { Text("Report Sent") },
        text = {
            Text("Emergency operators have been notified, and an alert has been broadcast to $nearbyCount users within 5 km.")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}