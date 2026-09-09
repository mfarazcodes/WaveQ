package com.waveq.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.mesh.ChannelMeta
import com.waveq.app.mesh.ChannelType
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.mesh.PassphraseStrength
import com.waveq.app.mesh.PermissionUtils
import com.waveq.app.sensor.SensorRepository
import com.waveq.app.settings.MeshOnboarding
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MeshChannelsScreen(
    viewModel: MeshViewModel,
    onOpenChannel: (String) -> Unit,
) {
    val context = LocalContext.current
    val extra = MaterialTheme.appExtraColors
    val channels by viewModel.channels.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val carryingStats by viewModel.carryingStats.collectAsState()
    val relayActivity by viewModel.relayActivity.collectAsState()

    var showSectorDialog by remember { mutableStateOf(false) }
    // Create and join are separate entry points: same derivation, very different
    // thing to be told before you commit to it.
    var familyDialogMode by remember { mutableStateOf<FamilyDialogMode?>(null) }
    var channelToLeave by remember { mutableStateOf<ChannelMeta?>(null) }
    // Held at screen level, not inside the dialog, so an in-flight key
    // derivation survives the dialog being dismissed.
    var isJoiningFamily by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()

    val transportStatus by viewModel.transportStatus.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startMesh()
            MeshOnboarding.resetHomeCard(context)
        }
    }

    // No auto-start on screen entry any more. The mesh is now a foreground
    // service holding a radio open and a persistent notification, so it runs
    // only while the user has explicitly switched it on.
    val setMeshEnabled: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.stopMesh()
        } else if (PermissionUtils.hasAllMeshPermissions(context)) {
            viewModel.startMesh()
            // So the Home reminder returns if the mesh is later switched off
            // again, rather than staying silenced by an old dismissal.
            MeshOnboarding.resetHomeCard(context)
        } else {
            permissionLauncher.launch(PermissionUtils.requiredMeshPermissions())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(Dimens.cardSpacing))
        Text("Mesh", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Offline peer-to-peer messaging. Public channels reach every nearby device; " +
                "family channels are end-to-end encrypted.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Hero: the mesh's live state, as one number.
        Spacer(Modifier.height(Dimens.heroSpacing))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (isRunning) "$peerCount" else "—",
                style = AppTypography.displayLarge,
                color = if (isRunning && peerCount > 0) {
                    extra.statusOnline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(10.dp))
            // "Other devices", not "devices in range": with two phones the
            // correct answer is 1, and the old wording read as a total, which
            // made a correct count look like an off-by-one.
            MicroLabel(
                when {
                    !isRunning -> "Mesh off"
                    peerCount == 1 -> "Other device connected"
                    else -> "Other devices connected"
                },
            )

            // Advertising and discovery are reported separately, because they
            // fail separately and a peer count of 0 does not distinguish "nobody
            // is nearby" from "this device is not actually looking".
            Spacer(Modifier.height(8.dp))
            Text(
                transportStatus.label,
                style = AppTypography.bodySmall,
                color = when {
                    !transportStatus.isStarted -> MaterialTheme.appExtraColors.textTertiary
                    transportStatus.isDegraded -> extra.severityHigh
                    else -> extra.statusOnline
                },
            )
            if (transportStatus.isStarted && transportStatus.discoveredCount > transportStatus.peerCount) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${transportStatus.discoveredCount} seen, ${transportStatus.peerCount} connected",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
            // The actual peer list, by name. A count on its own is not
            // diagnosable - this shows exactly which endpoints a broadcast
            // would reach, which is the same set sendMessage uses.
            if (transportStatus.peers.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.cardSpacing))
                transportStatus.peers.forEach { peer ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(extra.statusOnline),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            peer.name,
                            style = AppTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Which device is wired to the hardware, answerable at a glance.
            val sensorStatus by SensorRepository.status.collectAsState()
            if (sensorStatus.isConfigured || sensorStatus.isBridge) {
                Spacer(Modifier.height(Dimens.cardSpacing))
                SensorBridgeBadge(sensorStatus)
            }

            transportStatus.lastError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = AppTypography.bodySmall,
                    color = extra.severityHigh,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        // The service holds a radio open and posts a persistent notification, so
        // it is a switch the user owns rather than something that turns itself on.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Mesh network",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (isRunning) {
                        "Running in the background so alerts reach you with the app closed."
                    } else {
                        "Off. Alerts and SOS beacons from nearby devices will not reach you."
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = isRunning, onCheckedChange = setMeshEnabled)
        }

        if (isRunning) {
            Spacer(Modifier.height(Dimens.cardSpacing))
            SecondaryButton(
                text = "Rescan for devices",
                onClick = { viewModel.rescanMesh() },
                leadingIcon = Icons.Filled.Refresh,
            )
        }

        Spacer(Modifier.height(Dimens.heroSpacing))
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricStat("${carryingStats.totalCount}", "Carried", Modifier.weight(1f))
            MetricStat(
                "${carryingStats.sosCount}",
                "SOS held",
                Modifier.weight(1f),
                valueColor = if (carryingStats.sosCount > 0) extra.severityCritical else MaterialTheme.colorScheme.onSurface,
            )
            MetricStat("${channels.size}", "Channels", Modifier.weight(1f))
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Your channels")
        Spacer(Modifier.height(Dimens.cardSpacing))

        if (channels.isEmpty()) {
            Text(
                "No channels yet - join one below to start messaging.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        } else {
            channels.forEach { channel ->
                ChannelRow(
                    channel = channel,
                    onClick = { onOpenChannel(channel.channelId) },
                    // CITY carries flood alerts and risk updates to every device
                    // and cannot be rejoined from the UI, so it is not leavable.
                    onLeave = if (channel.type != ChannelType.CITY) {
                        { channelToLeave = channel }
                    } else {
                        null
                    },
                )
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Join a channel")
        Spacer(Modifier.height(Dimens.cardSpacing))

        ListRow(
            icon = Icons.Filled.LocationCity,
            title = "Sector channel",
            subtitle = "Public - alerts reach everyone here",
            onClick = { showSectorDialog = true },
        )
        ListRow(
            icon = Icons.Filled.GroupAdd,
            title = "Create family group",
            subtitle = "Choose a passphrase and share it with your family",
            onClick = { familyDialogMode = FamilyDialogMode.CREATE },
        )
        ListRow(
            icon = Icons.Filled.FamilyRestroom,
            title = "Join family group",
            subtitle = "Enter the passphrase someone shared with you",
            onClick = { familyDialogMode = FamilyDialogMode.JOIN },
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Relay activity")
        Spacer(Modifier.height(Dimens.cardSpacing))
        if (relayActivity.isEmpty()) {
            Text(
                "No relay activity yet.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        } else {
            relayActivity.take(8).forEach { entry ->
                Text(
                    "${entry.text} - ${timeAgoLabel(entry.at)}",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 7.dp),
                )
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
    }

    if (showSectorDialog) {
        JoinSectorDialog(
            onDismiss = { showSectorDialog = false },
            onJoin = { name ->
                viewModel.joinSectorChannel(name)
                showSectorDialog = false
            },
        )
    }

    familyDialogMode?.let { mode ->
        FamilyChannelDialog(
            mode = mode,
            strengthOf = { viewModel.channelRepository.passphraseStrength(it) },
            isBusy = isJoiningFamily,
            onDismiss = { if (!isJoiningFamily) familyDialogMode = null },
            onSubmit = { passphrase, onError ->
                isJoiningFamily = true
                screenScope.launch {
                    val result = when (mode) {
                        FamilyDialogMode.CREATE -> viewModel.createFamilyChannel(passphrase)
                        FamilyDialogMode.JOIN -> viewModel.joinFamilyChannel(passphrase)
                    }
                    isJoiningFamily = false
                    result.onSuccess { familyDialogMode = null }
                    result.onFailure { onError(it.message ?: "Could not open that group") }
                }
            },
        )
    }

    channelToLeave?.let { channel ->
        LeaveChannelDialog(
            channel = channel,
            onDismiss = { channelToLeave = null },
            onConfirm = {
                viewModel.leaveChannel(channel.channelId)
                channelToLeave = null
            },
        )
    }
}

private fun timeAgoLabel(at: Long): String {
    val elapsedSeconds = (System.currentTimeMillis() - at) / 1000
    return when {
        elapsedSeconds < 5 -> "just now"
        elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
        elapsedSeconds < 3600 -> "${elapsedSeconds / 60}m ago"
        else -> "${elapsedSeconds / 3600}h ago"
    }
}

@Composable
private fun ChannelRow(channel: ChannelMeta, onClick: () -> Unit, onLeave: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .clickable(onClick = onClick)
            .heightIn(min = Dimens.listRowHeight)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (channel.type) {
                ChannelType.CITY -> Icons.Filled.LocationCity
                ChannelType.SECTOR -> Icons.Filled.Map
                ChannelType.FAMILY -> Icons.Filled.FamilyRestroom
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            MicroLabel(if (channel.isEncrypted) "Encrypted" else "Public")
        }
        if (onLeave != null) {
            IconButton(onClick = onLeave) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Leave ${channel.name}",
                    tint = MaterialTheme.appExtraColors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.appExtraColors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Create and Join share a derivation but not a warning. */
enum class FamilyDialogMode { CREATE, JOIN }

@Composable
private fun LeaveChannelDialog(
    channel: ChannelMeta,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave ${channel.name}?") },
        text = {
            Column {
                Text(
                    "This device will forget the group's encryption key and stop receiving its " +
                        "messages. You will need the passphrase again to rejoin.",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.cardSpacing))
                Text(
                    "Messages you already sent cannot be recalled. They have been relayed to " +
                        "other devices and are stored there; leaving only affects this phone.",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.severityHighFg,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Leave group", color = MaterialTheme.appExtraColors.severityCritical)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinSectorDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Sector Channel") },
        text = {
            LabeledField(
                label = "Sector Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. North District",
            )
        },
        confirmButton = {
            TextButton(onClick = { onJoin(name) }, enabled = name.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FamilyChannelDialog(
    mode: FamilyDialogMode,
    strengthOf: (String) -> PassphraseStrength,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, onError: (String) -> Unit) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val strength = strengthOf(passphrase)
    val isJoining = isBusy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    FamilyDialogMode.CREATE -> "Create family group"
                    FamilyDialogMode.JOIN -> "Join family group"
                },
            )
        },
        text = {
            Column {
                if (mode == FamilyDialogMode.CREATE) {
                    Text(
                        "Anyone who has this passphrase can read everything sent to the group, " +
                            "now and later. There is no member list and no way to remove someone " +
                            "afterwards - the passphrase IS the membership. Choose something you " +
                            "are willing to share only with your family.",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.cardSpacing))
                } else {
                    Text(
                        "Enter the exact passphrase you were given. It is what derives the " +
                            "group's key, so a single character out and you will land in a " +
                            "different, empty group.",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.cardSpacing))
                }
                LabeledField(
                    label = "Passphrase",
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    placeholder = "Shared with your family",
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        passphrase.isEmpty() -> "At least 12 characters"
                        passphrase.length < 12 -> "Too short - need ${12 - passphrase.length} more character(s)"
                        strength == PassphraseStrength.WEAK -> "Weak - add numbers or symbols"
                        strength == PassphraseStrength.MEDIUM -> "Medium strength"
                        else -> "Strong passphrase"
                    },
                    style = AppTypography.bodySmall,
                    color = when (strength) {
                        PassphraseStrength.STRONG -> MaterialTheme.appExtraColors.severityLowFg
                        PassphraseStrength.MEDIUM -> MaterialTheme.appExtraColors.severityMediumFg
                        PassphraseStrength.WEAK -> MaterialTheme.appExtraColors.textTertiary
                    },
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = AppTypography.bodySmall, color = MaterialTheme.appExtraColors.severityCritical)
                }
                if (isJoining) {
                    Spacer(Modifier.height(Dimens.cardSpacing))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Deriving encryption key…",
                            style = AppTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(passphrase) { message -> error = message } },
                enabled = passphrase.length >= 12 && !isJoining,
            ) {
                Text(
                    when (mode) {
                        FamilyDialogMode.CREATE -> "Create group"
                        FamilyDialogMode.JOIN -> "Join group"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isJoining) { Text("Cancel") }
        },
    )
}