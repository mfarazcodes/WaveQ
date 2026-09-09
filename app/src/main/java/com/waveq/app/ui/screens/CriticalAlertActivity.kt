package com.waveq.app.ui.screens

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.MainActivity
import com.waveq.app.alerts.CriticalAlertData
import com.waveq.app.alerts.SirenPlayer
import com.waveq.app.alerts.formatAlertDistance
import com.waveq.app.mesh.SosTriage
import com.waveq.app.ui.theme.*

private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

/**
 * Full-screen takeover for a CRITICAL mesh alert.
 *
 * A separate Activity (not a screen in the normal nav graph) so it can
 * request show-when-locked/turn-screen-on independent of MainActivity's
 * lifecycle, and so it can be launched directly from a notification action or
 * from [com.waveq.app.alerts.CriticalAlertTrigger] with no Activity already
 * on screen. Back is disabled - dismissal requires one of the two explicit
 * buttons, both of which stop the siren before finishing.
 */
class CriticalAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOverLockScreen()
        acquireShortWakeLock()
        onBackPressedDispatcher.addCallback(this) {
            // No-op: force an explicit "I'm Safe" / "I Need Help" choice.
        }

        val data = CriticalAlertData.fromIntent(intent)
        if (data == null) {
            finish()
            return
        }

        setContent {
            // Forced dark=true regardless of the user's theme setting: this
            // screen's background is always the full-intensity BrandRed (see
            // class doc), never the desaturated dark-mode primary, so the
            // status bar needs light/white icons unconditionally too.
            WaveQTheme(darkTheme = true) {
                CriticalAlertScreen(
                    data = data,
                    onImSafe = {
                        SirenPlayer.stop()
                        finish()
                    },
                    onNeedHelp = {
                        SirenPlayer.stop()
                        openSosPrearmed(data)
                        finish()
                    },
                    // For someone else's SOS, "I'm safe" and "I need help" are
                    // the wrong two questions - the alert is not about you. Pin
                    // and Dismiss are the decisions that actually apply.
                    onPin = data.sosBeaconId?.let { beaconId ->
                        {
                            SirenPlayer.stop()
                            SosTriage.setPinned(this, beaconId, true)
                            openSosScreen()
                            finish()
                        }
                    },
                    onDismissBeacon = data.sosBeaconId?.let { beaconId ->
                        {
                            SirenPlayer.stop()
                            SosTriage.setDismissed(this, beaconId, true)
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    /** Just enough to guarantee the CPU stays up while the takeover renders - self-expires, no manual release needed. */
    private fun acquireShortWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waveq:critical_alert")
            .acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun openSosScreen() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
    }

    private fun openSosPrearmed(data: CriticalAlertData) {
        val note = data.sosNote?.takeIf { it.isNotBlank() }
            ?: "Responding to ${data.alertType.lowercase()} from ${data.senderName}"
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_PREARM_SOS_NOTE, note)
            },
        )
    }
}

@Composable
private fun CriticalAlertScreen(
    data: CriticalAlertData,
    onImSafe: () -> Unit,
    onNeedHelp: () -> Unit,
    onPin: (() -> Unit)? = null,
    onDismissBeacon: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandRed)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Dimens.screenPadding),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.Warning, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            data.severityLabel.uppercase(), style = AppTypography.headlineMedium, color = Color.White,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            data.alertType, style = AppTypography.headlineSmall, color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.cardRadius))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(Dimens.cardPadding),
        ) {
            AlertDetailRow(Icons.Filled.Person, "From", data.senderName)
            AlertDetailRow(Icons.Filled.LocationOn, "Location", data.locationLabel ?: "Unknown")
            AlertDetailRow(
                Icons.Filled.Straighten, "Distance",
                data.distanceMeters?.let { formatAlertDistance(it) } ?: "Unknown",
            )
            // Battery bounds how long the beacon will keep transmitting, and the
            // note is the sender's own words. Both were already on SosBeacon and
            // shown in the in-app list, but never reached the surface a user
            // actually sees during the emergency.
            data.batteryPercent?.let { battery ->
                AlertDetailRow(
                    if (data.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    "Battery",
                    "$battery%${if (data.isCharging) " (charging)" else ""}",
                )
            }
            AlertDetailRow(Icons.Filled.Schedule, "Sent", formatAlertTimeAgo(System.currentTimeMillis() - data.sentAt))
        }

        (data.sosNote ?: data.message)?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(Dimens.cardSpacing))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.cardRadius))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(Dimens.cardPadding),
            ) {
                Text(
                    if (data.sosNote != null) "THEIR MESSAGE" else "WHAT TO DO",
                    style = AppTypography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "\"$note\"",
                    style = AppTypography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (onPin != null && onDismissBeacon != null) {
            Button(
                onClick = onPin,
                modifier = Modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
                shape = RoundedCornerShape(Dimens.cardRadius),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandRed),
            ) {
                Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("I'm Responding", style = AppTypography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Dimens.cardSpacing))
            OutlinedButton(
                onClick = onDismissBeacon,
                modifier = Modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
                shape = RoundedCornerShape(Dimens.cardRadius),
                border = BorderStroke(Dimens.borderWidth, Color.White),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Dismiss", style = AppTypography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Dismissing hides this on your phone only. The beacon keeps broadcasting and " +
                    "other devices still see it.",
                style = AppTypography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Button(
                onClick = onNeedHelp,
                modifier = Modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
                shape = RoundedCornerShape(Dimens.cardRadius),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandRed),
            ) {
                Icon(Icons.Filled.Sos, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("I Need Help", style = AppTypography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Dimens.cardSpacing))
            OutlinedButton(
                onClick = onImSafe,
                modifier = Modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
                shape = RoundedCornerShape(Dimens.cardRadius),
                border = BorderStroke(Dimens.borderWidth, Color.White),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("I'm Safe", style = AppTypography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AlertDetailRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = AppTypography.bodyMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
        Text(value, style = AppTypography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatAlertTimeAgo(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}
