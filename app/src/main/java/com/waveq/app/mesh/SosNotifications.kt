package com.waveq.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waveq.app.MainActivity

private const val ACTIVE_CHANNEL_ID = "sos_active_channel"

/**
 * The legacy "SOS received" channel, deleted rather than reconfigured.
 *
 * It carried an alarm ringtone and its own vibration pattern, so a received SOS
 * sounded twice: this channel AND SirenPlayer's buzzer - and dismissing the
 * takeover stopped only the buzzer. A channel's importance, sound and vibration
 * are immutable once created, so silencing it in code would have fixed new
 * installs and left every existing one still double-sounding. It has to be
 * deleted.
 *
 * Nothing replaces it. Received SOS beacons are presented by
 * [com.waveq.app.alerts.AlertNotifications], which is the single path for every
 * alert type and whose channel is deliberately silent.
 */
private const val LEGACY_ALERT_CHANNEL_ID = "sos_alert_channel"
const val SOS_ACTIVE_NOTIFICATION_ID = 4200

/**
 * The SOS beacon service's own foreground notification.
 *
 * This no longer presents *received* alerts at all - that is
 * [com.waveq.app.alerts.AlertNotifications]' job, and having two presenters was
 * how one SOS came to make two sounds.
 */
object SosNotifications {

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(ACTIVE_CHANNEL_ID, "SOS broadcasting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your SOS beacon is actively broadcasting"
                setShowBadge(false)
            },
        )

        // Removes the old noisy channel from devices that already have it.
        // Safe to call unconditionally; it is a no-op once gone.
        nm.deleteNotificationChannel(LEGACY_ALERT_CHANNEL_ID)
    }

    /**
     * Persistent, low-priority notification for [SosBeaconService]'s foreground
     * state.
     *
     * Deliberately reports what actually happened rather than what was
     * attempted: [reachedPeers] is the number of devices the latest beacon was
     * dispatched to (0 when nothing was in range), and [lastReachedAt] is 0
     * until some beacon has reached at least one peer. A counter of attempted
     * broadcasts would tell a user in an emergency that help is on the way when
     * nothing has left the device.
     */
    fun activeSosNotification(
        context: Context,
        elapsedSeconds: Long,
        transportStarted: Boolean,
        reachedPeers: Int,
        lastReachedPeerCount: Int,
        lastReachedAt: Long,
    ): Notification {
        val cancelIntent = Intent(context, SosBeaconService::class.java).setAction(SosBeaconService.ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(
            context, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPending = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val elapsed = "Elapsed %02d:%02d".format(minutes, seconds)

        val title = when {
            !transportStarted -> "SOS on — but the mesh is OFF"
            reachedPeers > 0 -> "SOS active — reached $reachedPeers device(s)"
            else -> "SOS active — no devices in range"
        }
        val detail = when {
            !transportStarted ->
                "Nothing is being sent. Grant nearby-devices permission and open Mesh Channels."
            lastReachedAt == 0L ->
                "Still searching for nearby devices - nothing has received your beacon yet."
            reachedPeers > 0 ->
                "Your location is going out to nearby devices."
            else ->
                "Out of range now. Last reached $lastReachedPeerCount device(s) ${timeAgo(lastReachedAt)}."
        }

        return NotificationCompat.Builder(context, ACTIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText("$elapsed — $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$elapsed\n$detail"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Cancel", cancelPending)
            .build()
    }

    private fun timeAgo(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }
}
