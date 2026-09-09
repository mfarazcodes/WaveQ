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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

private data class DisasterTypeOption(val label: String, val icon: ImageVector)

private val disasterTypes = listOf(
    DisasterTypeOption("Flood", Icons.Filled.Water),
    DisasterTypeOption("Fire", Icons.Filled.LocalFireDepartment),
    DisasterTypeOption("Hurricane", Icons.Filled.Air),
    DisasterTypeOption("Earthquake", Icons.Filled.Landscape),
    DisasterTypeOption("Other", Icons.Filled.Error),
)

/**
 * Names match [com.waveq.app.ui.components.Severity] exactly, because the
 * selected value is stored on IncidentEntity and read back with
 * `Severity.valueOf`. The sheet reports `name`, never `label` - storing "Critical"
 * where every other consumer in the app writes "CRITICAL" made the column
 * unparseable the moment anything tried to read it.
 */
private enum class ReportSeverity(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
}

/** Theme-aware - not baked into the enum, which can't read the current theme. */
@Composable
private fun ReportSeverity.color(): androidx.compose.ui.graphics.Color {
    val extra = MaterialTheme.appExtraColors
    return when (this) {
        ReportSeverity.LOW -> extra.severityLow
        ReportSeverity.MEDIUM -> extra.severityMedium
        ReportSeverity.HIGH -> extra.severityHigh
        ReportSeverity.CRITICAL -> extra.severityCritical
    }
}

/**
 * Bottom sheet for submitting a disaster report.
 *
 * The location field is typed by the user. It previously auto-filled the
 * hardcoded string "Sector 12, Meerut, UP" after an 800 ms delay, under copy
 * claiming it came from the device's GPS - harmless while the report went
 * nowhere, but the report now persists to Room and is relayed over the mesh, so
 * that placeholder was being filed and transmitted as a real observation.
 * Submit stays disabled until a location is actually entered.
 *
 * TODO: offer the device's reverse-geocoded place name as a one-tap suggestion
 * (LocationController.devicePlace already holds it) rather than making the user
 * type it. Coordinates are already attached separately - see the caller.
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
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = 32.dp),
        ) {
            Text("Report a Disaster", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "Saved on this device and relayed to nearby devices over the mesh. " +
                    "Sending reports to emergency services is not available in this build.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))

            Text("Type", style = AppTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(disasterTypes) { type ->
                    val selected = type.label == selectedType
                    FilterChip(
                        selected = selected,
                        onClick = { selectedType = type.label },
                        label = { Text(type.label, style = AppTypography.titleSmall) },
                        leadingIcon = {
                            Icon(type.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        shape = RoundedCornerShape(Dimens.badgeRadius),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text("Severity", style = AppTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportSeverity.entries.forEach { sev ->
                    val selected = sev == selectedSeverity
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) sev.color() else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedSeverity = sev },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            sev.label,
                            style = AppTypography.labelSmall,
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text("Location", style = AppTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "",
                value = location,
                onValueChange = { location = it },
                placeholder = "e.g. Sector 12, near the bus stand",
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Describe where this is happening. Your device's coordinates are attached " +
                    "automatically when a GPS fix is available.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text("Description", style = AppTypography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
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
                onClick = { onSubmit(selectedType, selectedSeverity.name, location.trim(), description) },
                leadingIcon = Icons.Filled.Campaign,
                // A report with no location is not actionable by anyone who
                // receives it, so it cannot be filed.
                enabled = location.isNotBlank(),
            )
        }
    }
}

/**
 * Confirmation shown after a report is filed.
 *
 * States exactly what happened and nothing more: stored locally, and how many
 * mesh peers it was actually handed to. It must not claim delivery to
 * authorities - IncidentSyncWorker has no backend, it flips isSynced and logs -
 * and it must not present the peer count as a delivery receipt, since Nearby's
 * send is fire-and-forget.
 */
@Composable
fun ReportSubmittedDialog(
    referenceId: String,
    peersReached: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.appExtraColors.severityLow,
            )
        },
        title = { Text("Report saved") },
        text = {
            Column {
                Text("Reference $referenceId")
                Spacer(Modifier.height(8.dp))
                Text(
                    if (peersReached > 0) {
                        "Saved on this device and handed to $peersReached nearby " +
                            (if (peersReached == 1) "device" else "devices") + " over the mesh."
                    } else {
                        "Saved on this device. No mesh devices are in range right now, " +
                            "so it will be relayed when one comes into range."
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Uploading reports to emergency services is not available in this build.",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
