package com.waveq.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import com.waveq.app.util.VoiceRecorder

enum class ReportSeverity(val label: String, val color: Color) {
    LOW("Low", SeverityLow),
    MEDIUM("Medium", SeverityMedium),
    HIGH("High", SeverityHigh),
    CRITICAL("Critical", SeverityCritical)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDisasterSheet(
    onDismiss: () -> Unit,
    onSubmit: (type: String, severity: String, location: String, description: String) -> Unit,
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf("Flood") }
    var selectedSeverity by remember { mutableStateOf(ReportSeverity.HIGH) }
    var location by remember { mutableStateOf("Sector 62, Industrial Area") }
    var description by remember { mutableStateOf("") }

    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var hasAudioRecorded by remember { mutableStateOf(false) }

    val voiceRecorder = remember { VoiceRecorder(context) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            photoBitmap = bitmap
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) cameraLauncher.launch(null)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && !isRecordingAudio) {
            val started = voiceRecorder.startRecording()
            if (started) isRecordingAudio = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Report Emergency", style = AppTypography.headlineSmall, color = TextPrimary)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Incident Type Selection
            SectionHeading("Incident Type")
            Spacer(Modifier.height(8.dp))
            val types = listOf("Flood", "Fire", "Earthquake", "Hurricane", "Landslide", "Other")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.take(3).forEach { type ->
                    val isSelected = selectedType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ActionBlue else SurfaceMuted)
                            .clickable { selectedType = type }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            style = AppTypography.bodySmall,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.drop(3).forEach { type ->
                    val isSelected = selectedType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ActionBlue else SurfaceMuted)
                            .clickable { selectedType = type }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            style = AppTypography.bodySmall,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Severity Level Selection
            SectionHeading("Severity Level")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReportSeverity.entries.forEach { severity ->
                    val isSelected = selectedSeverity == severity
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) severity.color.copy(alpha = 0.2f) else SurfaceMuted)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) severity.color else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedSeverity = severity }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = severity.label,
                            style = AppTypography.bodySmall,
                            color = if (isSelected) severity.color else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Location Field
            SectionHeading("Incident Location")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AccentBlue) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ActionBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Description Field
            SectionHeading("Situation Details")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("Briefly describe water level, trapped individuals, road blocks...", color = TextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ActionBlue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                maxLines = 4
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))

            // Evidence Attachments
            SectionHeading("Evidence Attachments (Optional)")
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = ActionBlue)
                    Spacer(Modifier.width(6.dp))
                    Text(if (photoBitmap == null) "Photo" else "Retake", style = AppTypography.bodySmall)
                }

                Button(
                    onClick = {
                        if (!isRecordingAudio) {
                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else {
                            voiceRecorder.stopRecording()
                            isRecordingAudio = false
                            hasAudioRecorded = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecordingAudio) SeverityCritical else SurfaceMuted
                    ),
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (isRecordingAudio) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = if (isRecordingAudio) Color.White else TextPrimary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            isRecordingAudio -> "Stop"
                            hasAudioRecorded -> "Recorded"
                            else -> "Voice Note"
                        },
                        color = if (isRecordingAudio) Color.White else TextPrimary,
                        style = AppTypography.bodySmall
                    )
                }
            }

            if (photoBitmap != null || hasAudioRecorded) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    photoBitmap?.let { bmp ->
                        Box(modifier = Modifier.size(56.dp)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Captured Evidence",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { photoBitmap = null },
                                modifier = Modifier.size(18.dp).align(Alignment.TopEnd)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                            }
                        }
                    }

                    if (hasAudioRecorded) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceMuted)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Voice memo attached", style = AppTypography.labelSmall, color = TextPrimary)
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    voiceRecorder.clear()
                                    hasAudioRecorded = false
                                },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = TextSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            PrimaryButton(
                text = "Submit Disaster Report",
                onClick = {
                    if (isRecordingAudio) {
                        voiceRecorder.stopRecording()
                    }
                    onSubmit(selectedType, selectedSeverity.label, location, description)
                },
                leadingIcon = Icons.AutoMirrored.Filled.Send,
                modifier = Modifier.height(52.dp)
            )
        }
    }
}