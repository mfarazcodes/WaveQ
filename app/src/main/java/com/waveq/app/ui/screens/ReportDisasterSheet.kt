package com.waveq.app.ui.screens
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.delay

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

/**
 * Bottom sheet for submitting a disaster report.
 *
 * ASSUMPTION: there is no real GPS/location wiring or backend/mesh broadcast
 * yet. Location auto-fills with a fake value after a short delay, and
 * "nearby users" is a random number for demo purposes. Replace the
 * LaunchedEffect block with FusedLocationProviderClient, and the onSubmit
 * callback with a real API/mesh call, before this ships.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDisasterSheet(
    onDismiss: () -> Unit,
    onSubmit: (type: String, severity: String, location: String, description: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by remember { mutableStateOf(disasterTypes.first().label) }
    var selectedSeverity by remember { mutableStateOf(ReportSeverity.MEDIUM) }
    var location by remember { mutableStateOf("Detecting your location…") }
    var description by remember { mutableStateOf("") }
    var autoLocation by remember { mutableStateOf(true) }

    LaunchedEffect(autoLocation) {
        if (autoLocation) {
            delay(800)
            location = "Sector 12, Meerut, UP"
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
            Text("Location", style = AppTypography.labelLarge, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "",
                value = location,
                onValueChange = { location = it; autoLocation = false },
                placeholder = "Enter location manually",
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Using your device's approximate location. Edit above if it's wrong.",
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