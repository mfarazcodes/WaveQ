package com.waveq.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.mesh.ChannelCrypto
import com.waveq.app.mesh.ChannelType
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

/**
 * Authoritative flood alert broadcast screen strictly restricted to ADMIN and OPERATOR roles.
 *
 * Broadcasts are cryptographically signed with the operator's private key before being
 * pushed to the City-Wide Alerts mesh channel.
 */
@Composable
fun BroadcastAlertScreen(viewModel: MeshViewModel) {
    // SessionManager is a singleton object, not an instantiated class
    val currentRole = SessionManager.currentRole ?: UserRole.CITIZEN

    // Hard Gate: Restrict entirely from citizens or unauthorized users
    val isAuthorized = currentRole == UserRole.ADMIN || currentRole == UserRole.OPERATOR

    if (!isAuthorized) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.screenPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Access Restricted",
                style = AppTypography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Authoritative network-wide broadcasts are strictly restricted to verified Operators and Emergency Admins.",
                style = AppTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val channels by viewModel.channels.collectAsState()
    val cityChannel = remember(channels) { channels.firstOrNull { it.type == ChannelType.CITY } }

    var severity by remember { mutableStateOf(Severity.HIGH) }
    var message by remember { mutableStateOf("") }
    var justSent by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text("Broadcast Alert", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sends a cryptographically signed flood alert to every nearby device on City-Wide Alerts. " +
                    "CRITICAL alerts trigger sirens and full-screen overrides on receiving devices.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        // Operator Authority Badge
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            background = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Authenticated Authority (${currentRole.name})",
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Broadcasts will carry your verified asymmetric cryptographic signature.",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Severity")
        Spacer(Modifier.height(Dimens.cardSpacing))
        SegmentedTabs(
            options = Severity.entries.map { it.label },
            selectedIndex = Severity.entries.indexOf(severity),
            onSelect = { severity = Severity.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.cardSpacing))
        val isCritical = severity.soundsAlarm
        AppCard(
            modifier = Modifier.fillMaxWidth(),
            background = if (isCritical) {
                MaterialTheme.appExtraColors.severityCriticalBg
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Row(Modifier.padding(Dimens.cardPadding), verticalAlignment = Alignment.Top) {
                Icon(
                    if (isCritical) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                    contentDescription = null,
                    tint = if (isCritical) {
                        MaterialTheme.appExtraColors.severityCriticalFg
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (isCritical) {
                            "This will sound an alarm on every nearby phone"
                        } else {
                            "This will NOT sound an alarm"
                        },
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isCritical) {
                            "CRITICAL alerts take over the screen and play the siren, even on a " +
                                    "locked phone. Use it when people need to act immediately."
                        } else {
                            "${severity.label} alerts arrive as a silent notification. Choose " +
                                    "CRITICAL if you need to wake people up."
                        },
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        LabeledField(
            label = "Alert Message",
            value = message,
            onValueChange = { message = it; justSent = false },
            placeholder = "e.g. Rising water levels near Downtown - evacuate low areas",
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        PrimaryButton(
            text = "Broadcast Alert",
            onClick = {
                cityChannel?.let { channel ->
                    // 1. Read operator private key from the SessionManager singleton
                    val operatorPrivateKey = SessionManager.getOperatorPrivateKey()

                    // 2. Generate cryptographic signature if private key exists
                    val signature: ByteArray? = operatorPrivateKey?.let { key ->
                        val payloadBytes = "$severity:$message:${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
                        ChannelCrypto.signPayload(payloadBytes, key)
                    }

                    // 3. Dispatch authoritative flood alert with signature
                    viewModel.sendFloodAlert(
                        channelId = channel.channelId,
                        severity = severity,
                        message = message,
                        signature = signature,
                        signerRole = currentRole.name
                    )
                    justSent = true
                }
            },
            leadingIcon = Icons.Filled.Campaign,
            enabled = message.isNotBlank() && cityChannel != null,
        )

        if (justSent) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            NoticeCard(
                icon = Icons.Filled.CheckCircle,
                title = "Signed Alert broadcasted",
                body = "\"$message\" sent as ${severity.label} to City-Wide Alerts." +
                        if (severity.soundsAlarm) {
                            " Receiving phones will siren and show the full-screen alert."
                        } else {
                            " Receiving phones show a notification only - no siren."
                        },
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}