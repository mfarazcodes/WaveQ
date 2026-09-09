package com.waveq.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waveq.app.mesh.ChannelType
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

/**
 * Operator/Admin-only authoritative flood alert broadcast, over the
 * unencrypted City-Wide Alerts channel so it reaches every nearby device
 * regardless of channel membership. Access to this screen is gated at the
 * nav-graph level (see AppNavigation's RequireRole) and again, decisively, in
 * [com.waveq.app.mesh.MeshManager.sendMessage] itself.
 */
@Composable
fun BroadcastAlertScreen(viewModel: MeshViewModel) {
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
            "Sends an authoritative flood alert to every nearby device on City-Wide Alerts, " +
                "regardless of channel membership. CRITICAL alerts trigger the siren and full-screen " +
                "takeover on every receiving device.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        SectionHeading("Severity")
        Spacer(Modifier.height(Dimens.cardSpacing))
        SegmentedTabs(
            options = Severity.entries.map { it.label },
            selectedIndex = Severity.entries.indexOf(severity),
            onSelect = { severity = Severity.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )

        // The consequence of this choice, at the moment of choosing. Severity is
        // not a label here - it decides whether every nearby phone sounds an
        // alarm - and operators have twice reported "the siren did not fire"
        // when the real answer was that they broadcast below CRITICAL.
        Spacer(Modifier.height(Dimens.cardSpacing))
        // Same predicate the alert path uses, so this promise cannot drift from
        // what receiving devices actually do.
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
                cityChannel?.let { viewModel.sendFloodAlert(it.channelId, severity, message) }
                justSent = true
            },
            leadingIcon = Icons.Filled.Campaign,
            enabled = message.isNotBlank() && cityChannel != null,
        )

        if (justSent) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            NoticeCard(
                icon = Icons.Filled.CheckCircle,
                title = "Alert broadcast",
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
