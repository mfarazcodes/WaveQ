package com.waveq.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waveq.app.mesh.MeshMessage
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.PermissionUtils
import com.waveq.app.mesh.VoicePlaybackState
import com.waveq.app.mesh.VoicePlayer
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

@Composable
fun MeshChatScreen(
    channelId: String,
    viewModel: MeshViewModel,
) {
    val context = LocalContext.current
    val channels by viewModel.channels.collectAsState()
    val messagesByChannel by viewModel.messagesByChannel.collectAsState()
    val channel = channels.firstOrNull { it.channelId == channelId }
    val messages = messagesByChannel[channelId].orEmpty()

    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf<String?>(null) }

    // The microphone is asked for here, at the moment it is first needed, rather
    // than being bundled into the mesh permission set - the mesh transport does
    // not need it, and making it a precondition there silently disabled SOS
    // broadcasting for anyone who declined the prompt.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            recordingError = null
            viewModel.startVoiceRecording()
            isRecording = true
        } else {
            recordingError = "Microphone permission is needed to record a voice note."
        }
    }

    val playback by VoicePlayer.state.collectAsState()

    // Stop and release when the screen goes away - a clip must not keep playing
    // from a chat the user has left.
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop() } }

    // Progress ticker, running only while something is actually playing.
    LaunchedEffect(playback.messageId) {
        while (playback.messageId != null) {
            VoicePlayer.refreshPosition()
            kotlinx.coroutines.delay(200)
        }
    }

    val extra = MaterialTheme.appExtraColors
    Column(modifier = Modifier.fillMaxSize().padding(Dimens.screenPadding)) {
        Text(channel?.name ?: "Channel", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        MicroLabel(if (channel?.isEncrypted == true) "End-to-end encrypted" else "Public channel")
        Spacer(Modifier.height(Dimens.sectionSpacing))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages, key = { it.messageId }) { message ->
                MessageBubble(message, playback)
                Spacer(Modifier.height(16.dp))
            }
        }

        recordingError?.let {
            Spacer(Modifier.height(Dimens.cardSpacing))
            Text(it, style = AppTypography.bodySmall, color = extra.severityCritical)
        }

        Spacer(Modifier.height(Dimens.cardSpacing))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledField(
                label = "",
                value = text,
                onValueChange = { text = it },
                placeholder = "Type a message",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (text.isNotBlank()) {
                    viewModel.sendText(channelId, text)
                    text = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                if (isRecording) {
                    val sent = viewModel.stopVoiceRecordingAndSend(channelId)
                    isRecording = false
                    recordingError = if (sent) null else "Recording failed - nothing was sent. Try again."
                } else if (!PermissionUtils.hasVoicePermission(context)) {
                    micPermissionLauncher.launch(PermissionUtils.requiredVoicePermissions().first())
                } else {
                    recordingError = null
                    viewModel.startVoiceRecording()
                    isRecording = true
                }
            }) {
                Icon(
                    if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Record voice message",
                    tint = if (isRecording) extra.severityCritical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * An automatic flood-risk update.
 *
 * Deliberately unlike a chat bubble: full width, centred, no sender name, no
 * left/right alignment. Risk updates ride the City-Wide channel so they arrive
 * in the same stream as messages people type, and previously rendered exactly
 * like one - under whichever display name the sending device happened to have.
 */
@Composable
private fun SystemMessageRow(message: MeshMessage) {
    val severity = message.severity
        ?.let { name -> Severity.entries.firstOrNull { it.name == name } }
        ?: Severity.LOW
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (message.type == MessageType.SENSOR_ALERT) Icons.Filled.WaterDrop else Icons.Filled.Sensors,
            contentDescription = null,
            tint = if (message.type == MessageType.SENSOR_ALERT) {
                MaterialTheme.appExtraColors.severityCritical
            } else {
                MaterialTheme.appExtraColors.textTertiary
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MicroLabel(
            if (message.type == MessageType.SENSOR_ALERT) {
                "Water level sensor - not sent by a person"
            } else {
                "Automatic flood-risk update - not sent by a person"
            },
        )
            Spacer(Modifier.height(6.dp))
            Text(
                message.text.orEmpty(),
                style = AppTypography.bodyMedium,
                color = severity.colors().solid,
            )
            if (message.hopCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (message.hopCount == 1) {
                        "Relayed 1 hop from a device with connectivity"
                    } else {
                        "Relayed ${message.hopCount} hops from a device with connectivity"
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
        }
    }
}

/**
 * Play/pause, duration and progress for a received clip.
 *
 * Until now this rendered the literal string "Voice message" - the feature was
 * complete end to end except that nothing could hear it.
 */
@Composable
private fun VoiceMessageBubble(message: MeshMessage, playback: VoicePlaybackState) {
    val file = message.audioFile
    val isPlaying = playback.isPlaying(message.messageId)

    if (file == null || !file.exists()) {
        Text(
            "Voice message (no longer available)",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.appExtraColors.textTertiary,
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (isPlaying) VoicePlayer.stop() else VoicePlayer.play(message.messageId, file)
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { if (isPlaying) playback.progress else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isPlaying) {
                    "${formatClipTime(playback.positionMs)} / ${formatClipTime(playback.durationMs)}"
                } else {
                    "Voice message"
                },
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatClipTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun MessageBubble(message: MeshMessage, playback: VoicePlaybackState) {
    // Machine-generated updates are not chat. They get their own full-width
    // system row rather than a participant bubble with a name above it.
    // Machine-generated: a model on a timer, or a physical probe. Neither is a
    // person speaking, so neither gets a chat bubble with a name above it.
    if (message.type == MessageType.RISK_UPDATE || message.type == MessageType.SENSOR_ALERT) {
        SystemMessageRow(message)
        return
    }

    val extra = MaterialTheme.appExtraColors
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
    ) {
        // Sender name sits above the bubble, small and muted, not inside it.
        if (!message.isMine) {
            MicroLabel(message.senderName, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        AppCard(
            modifier = Modifier.fillMaxWidth(0.82f),
            background = if (message.isMine) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (message.type) {
                    MessageType.VOICE -> VoiceMessageBubble(message, playback)
                    MessageType.FLOOD_ALERT -> Text(
                        "⚠️ ${message.text.orEmpty()}",
                        style = AppTypography.bodyMedium,
                        color = extra.severityCritical,
                    )
                    MessageType.TEXT -> Text(
                        message.text.orEmpty(),
                        style = AppTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    MessageType.SOS -> Text(
                        "🆘 ${message.text.orEmpty()}",
                        style = AppTypography.bodyMedium,
                        color = extra.severityCritical,
                    )
                    // Handled above as system rows, never as participant bubbles.
                    MessageType.RISK_UPDATE, MessageType.SENSOR_ALERT -> Unit
                }
            }
        }
    }
}