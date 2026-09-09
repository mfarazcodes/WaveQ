package com.waveq.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors

/**
 * The one-screen explanation of what the mesh is, shown once on first launch.
 *
 * Deliberately states the cost as plainly as the benefit. The mesh is a
 * foreground service holding a radio open with a permanent notification; a user
 * who is not told that will turn it off the first time they see the notification
 * and never understand what they switched off.
 */
@Composable
fun MeshIntroScreen(
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.screenPadding),
        ) {
            Spacer(Modifier.height(Dimens.heroSpacing))
            Icon(
                Icons.Filled.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text(
                "Turn on the offline mesh",
                style = AppTypography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "WaveQ passes warnings between phones directly over Bluetooth and Wi-Fi, with no " +
                    "mobile network and no internet.",
                style = AppTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Dimens.heroSpacing))
            IntroPoint(
                icon = Icons.Filled.Campaign,
                title = "You receive flood alerts",
                body = "Warnings an operator broadcasts reach you even when the towers are down.",
            )
            IntroPoint(
                icon = Icons.Filled.Sos,
                title = "You see nearby SOS beacons",
                body = "If someone close by calls for help, their position appears on your phone.",
            )
            IntroPoint(
                icon = Icons.Filled.WifiOff,
                title = "Your phone relays for others",
                body = "Messages you cannot read are still carried onward to devices you meet later.",
            )
            IntroPoint(
                icon = Icons.Filled.BatteryStd,
                title = "It runs in the background",
                body = "That means a permanent notification and some battery use. Discovery is " +
                    "throttled below 20%, and you can turn it off at any time in Mesh Channels.",
            )

            Spacer(Modifier.height(Dimens.sectionSpacing))
            Text(
                "Turning it on asks for the nearby-devices permissions Bluetooth needs.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )

            Spacer(Modifier.height(Dimens.cardSpacing))
            PrimaryButton(
                text = "Turn on the mesh",
                onClick = onEnable,
                leadingIcon = Icons.Filled.Hub,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Not now",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Dimens.sectionSpacing))
        }
    }
}

@Composable
private fun IntroPoint(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(body, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Persistent reminder on Home that the mesh is off.
 *
 * Dismissible, because nagging forever is its own failure - but it comes back
 * if the mesh is later switched on and off again, so a dismissal is a decision
 * about now rather than a permanent silence.
 */
@Composable
fun MeshOffCard(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val severityColors = Severity.HIGH.colors()
    AppCard(modifier = modifier.fillMaxWidth(), background = severityColors.bg) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = severityColors.fg,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "The mesh is off",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickableNoRipple(onDismiss),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Flood alerts and SOS beacons from nearby devices will not reach this phone " +
                    "until it is on.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            TextButton(onClick = onEnable) {
                Text(
                    "Turn on the mesh",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(indication = null, interactionSource = null, onClick = onClick)
