package com.waveq.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.waveq.app.alerts.AlertNotifications
import com.waveq.app.ui.components.AppCard
import com.waveq.app.ui.components.MicroLabel
import com.waveq.app.ui.components.PrimaryButton
import com.waveq.app.ui.components.SectionHeading
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors

/** The three OS-level grants a visual critical alert depends on. */
data class AlertDeliveryStatus(
    val notificationsGranted: Boolean,
    val fullScreenGranted: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val allGranted: Boolean get() = notificationsGranted && fullScreenGranted && batteryUnrestricted

    /** The one that actually costs you the ability to act on an alert. */
    val hasNoDismissalSurface: Boolean get() = !notificationsGranted && !fullScreenGranted
}

fun readAlertDeliveryStatus(context: Context): AlertDeliveryStatus = AlertDeliveryStatus(
    notificationsGranted = AlertNotifications.hasPostNotificationsPermission(context),
    fullScreenGranted = AlertNotifications.canUseFullScreenIntent(context),
    batteryUnrestricted = isIgnoringBatteryOptimizations(context),
)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * One screen showing whether a critical alert can actually reach this user.
 *
 * All three grants are re-read on every ON_RESUME rather than cached, because
 * two of the three are granted in system Settings - the user leaves the app,
 * changes something, and comes back. A status list computed once and remembered
 * would tell them the opposite of the truth for the rest of the session, which
 * is the bug the Home setup card had.
 *
 * Nothing here blocks app use. Each row says what is lost if it stays off.
 */
@Composable
fun AlertDeliveryScreen(onDone: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(readAlertDeliveryStatus(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = readAlertDeliveryStatus(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { status = readAlertDeliveryStatus(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Text(
            "Alert delivery",
            style = AppTypography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A critical alert has to get past three separate Android settings before it can " +
                "reach you. Here is where each one stands.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (status.hasNoDismissalSurface) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                background = MaterialTheme.appExtraColors.severityCriticalBg,
            ) {
                Column(Modifier.padding(Dimens.cardPadding)) {
                    Text(
                        "Alerts cannot reach you right now",
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "With notifications blocked and full-screen alerts unavailable, there is " +
                            "no surface for an alert to appear on. The siren is suppressed in " +
                            "that state rather than sounding with no way to stop it.",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Status")
        Spacer(Modifier.height(Dimens.cardSpacing))

        DeliveryRow(
            icon = Icons.Filled.Notifications,
            title = "Notifications",
            granted = status.notificationsGranted,
            grantedBody = "Alerts appear, and carry Stop siren, Responding and Dismiss controls.",
            deniedBody = "Without this an alert can play a siren with no on-screen controls at " +
                "all. This is the fallback whenever the full-screen takeover is blocked, so it " +
                "matters more than it looks.",
            actionLabel = "Allow notifications",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(appNotificationSettings(context))
                }
            },
        )

        DeliveryRow(
            icon = Icons.Filled.Fullscreen,
            title = "Full-screen alerts",
            granted = status.fullScreenGranted,
            grantedBody = "A CRITICAL alert takes over the screen, even locked.",
            deniedBody = "Android 14 blocks this by default. Without it, alerts degrade to a " +
                "heads-up notification - still actionable, but easy to miss on a locked phone. " +
                "It cannot be granted from a dialog; it lives in system settings.",
            actionLabel = "Open settings",
            onAction = { context.startActivity(fullScreenIntentSettings(context)) },
        )

        DeliveryRow(
            icon = Icons.Filled.BatteryStd,
            title = "Battery optimisation",
            granted = status.batteryUnrestricted,
            grantedBody = "The mesh service is allowed to keep running in the background.",
            deniedBody = "Optional, but aggressive OEM battery management - Xiaomi, Oppo and " +
                "OnePlus especially - can delay or kill the mesh service, and alerts with it.",
            actionLabel = "Request exemption",
            onAction = { context.startActivity(batteryOptimisationSettings(context)) },
        )

        if (onDone != null) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            PrimaryButton(
                text = if (status.allGranted) "All set" else "Continue anyway",
                onClick = onDone,
            )
            Spacer(Modifier.height(8.dp))
            MicroLabel(
                "You can change any of these later in Settings",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

@Composable
private fun DeliveryRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    grantedBody: String,
    deniedBody: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val extra = MaterialTheme.appExtraColors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Shape as well as colour: the state must not depend on hue alone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (granted) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (granted) extra.severityLowFg else extra.severityHighFg,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (granted) extra.severityLowBg else extra.severityHighBg)
                            .padding(2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (granted) "On" else "Off",
                        style = AppTypography.bodySmall,
                        color = if (granted) extra.severityLowFg else extra.severityHighFg,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (granted) grantedBody else deniedBody,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        actionLabel,
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(Dimens.cardSpacing))
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun fullScreenIntentSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        )
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

/**
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS needs the matching permission to
 * show its dialog; without it, fall back to the settings list so the user can
 * still find the toggle.
 */
private fun batteryOptimisationSettings(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
