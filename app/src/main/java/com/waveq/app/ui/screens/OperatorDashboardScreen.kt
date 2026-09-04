package com.waveq.app.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.data.local.WaveQDatabase
import com.waveq.app.ui.components.SectionHeading
import com.waveq.app.ui.components.StatusPill
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WaveQDatabase.getDatabase(context).incidentDao() }
    val incidents by dao.getAllIncidents().collectAsState(initial = emptyList())

    var filterVerifiedOnly by remember { mutableStateOf(false) }

    val filteredList = remember(incidents, filterVerifiedOnly) {
        if (filterVerifiedOnly) incidents.filter { it.verified } else incidents
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = { Text("Operator Triage Center", style = AppTypography.titleMedium, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    FilterChip(
                        selected = filterVerifiedOnly,
                        onClick = { filterVerifiedOnly = !filterVerifiedOnly },
                        label = { Text(if (filterVerifiedOnly) "Verified Only" else "All Reports") },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No pending reports to triage", style = AppTypography.titleMedium, color = TextPrimary)
                    Text("Incoming community incident alerts will appear here", style = AppTypography.bodySmall, color = TextSecondary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Queue (${filteredList.size})", style = AppTypography.titleSmall, color = TextSecondary)
                        Text("Pending Sync: ${incidents.count { !it.isSynced }}", style = AppTypography.titleSmall, color = AccentAmber)
                    }
                }

                items(filteredList, key = { it.id }) { incident ->
                    OperatorIncidentCard(
                        incident = incident,
                        onVerify = {
                            scope.launch(Dispatchers.IO) {
                                dao.updateIncident(incident.copy(verified = true))
                            }
                        },
                        onEscalate = {
                            scope.launch(Dispatchers.IO) {
                                dao.updateIncident(incident.copy(severity = "Critical"))
                            }
                        },
                        onDismiss = {
                            scope.launch(Dispatchers.IO) {
                                dao.deleteIncident(incident)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OperatorIncidentCard(
    incident: IncidentEntity,
    onVerify: () -> Unit,
    onEscalate: () -> Unit,
    onDismiss: () -> Unit
) {
    var isPlayingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(incident.type, style = AppTypography.headlineSmall, color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    val severityColor = when (incident.severity.lowercase()) {
                        "critical" -> SeverityCritical
                        "high" -> SeverityHigh
                        "medium" -> SeverityMedium
                        else -> SeverityLow
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(severityColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(incident.severity, style = AppTypography.labelSmall, color = severityColor)
                    }
                }

                if (incident.verified) {
                    StatusPill("VERIFIED", AccentGreen)
                } else {
                    StatusPill("UNCONFIRMED", AccentAmber)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(incident.location, style = AppTypography.bodySmall, color = TextSecondary)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(incident.reportedAt, style = AppTypography.bodySmall, color = TextSecondary)
            }

            if (incident.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(incident.description, style = AppTypography.bodyMedium, color = TextPrimary)
            }

            // Evidence Bar (Audio / Photo indicators)
            if (incident.audioPath != null || incident.photoUri != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    incident.audioPath?.let { path ->
                        FilledTonalButton(
                            onClick = {
                                if (isPlayingAudio) {
                                    mediaPlayer?.stop()
                                    mediaPlayer?.release()
                                    mediaPlayer = null
                                    isPlayingAudio = false
                                } else {
                                    try {
                                        val file = File(path)
                                        if (file.exists()) {
                                            mediaPlayer = MediaPlayer().apply {
                                                setDataSource(path)
                                                prepare()
                                                start()
                                                setOnCompletionListener {
                                                    isPlayingAudio = false
                                                }
                                            }
                                            isPlayingAudio = true
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (isPlayingAudio) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isPlayingAudio) "Stop Memo" else "Play Memo", style = AppTypography.labelSmall)
                        }
                    }

                    if (incident.photoUri != null) {
                        OutlinedButton(
                            onClick = { /* Open image viewer */ },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Evidence Photo", style = AppTypography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            // Operator Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = SeverityCritical)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onEscalate) {
                    Text("Escalate", color = AccentAmber)
                }
                Spacer(Modifier.width(6.dp))
                if (!incident.verified) {
                    Button(
                        onClick = onVerify,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Verify")
                    }
                }
            }
        }
    }
}