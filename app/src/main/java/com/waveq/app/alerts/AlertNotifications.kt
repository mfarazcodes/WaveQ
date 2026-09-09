package com.waveq.app.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

private const val TAG = "AlertNotifications"
private const val CHANNEL_ID = "critical_alert_channel"
private const val NOTIFICATION_ID_BASE = 9100

/**
 * Which delivery surfaces actually succeeded for one alert.
 *
 * The siren is gated on this. It used to start unconditionally while the two
 * controls that stop it lived only on the takeover Activity - an Activity that
 * does not appear when the app is backgrounded and full-screen-intent access is
 * denied. The result on a real device was a 60-second maximum-volume alarm with
 * no visible alert and no way to stop it.
 */
data class AlertDelivery(
    val notificationPosted: Boolean,
    val fullScreenIntentAttached: Boolean,
    val activityLaunched: Boolean,
) {
    /**
     * True when at least one surface exists through which the user can stop the
     * siren. The notification always carries a Stop siren action, so posting it
     * is sufficient on its own.
     */
    val hasDismissalSurface: Boolean get() = notificationPosted || activityLaunched
}

/**
 * Dedicated high-importance channel for CRITICAL mesh alerts, separate from
 * [com.waveq.app.mesh.SosNotifications]'s channels: this one's sound and
 * vibration are disabled since [SirenPlayer] handles both itself - a channel
 * sound/vibration on top would double them.
 */
object AlertNotifications {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Critical mesh alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SOS beacons and CRITICAL flood alerts that trigger the full-screen siren takeover"
                setBypassDnd(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** Android 14+ gate on whether this app may auto-launch an Activity from a full-screen-intent notification. */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return nm.canUseFullScreenIntent()
    }

    fun hasPostNotificationsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A stable per-alert notification id.
     *
     * A single fixed id meant two concurrent emergencies collapsed into one
     * notification, silently replacing the first. Keyed on the beacon (or the
     * alert content) so an SOS and a flood alert can coexist, while repeats of
     * the same beacon still update in place rather than stacking.
     */
    private fun notificationIdFor(data: CriticalAlertData): Int {
        val key = data.sosBeaconId ?: "${data.alertType}:${data.senderName}:${data.locationLabel}"
        return NOTIFICATION_ID_BASE + (key.hashCode().and(0xFFFF))
    }

    /**
     * Posts the alert and, where possible, opens the takeover.
     *
     * Order of mechanisms, most to least reliable:
     *  1. the notification, which always carries a **Stop siren** action (and
     *     Pin/Dismiss for an SOS) so the alert is actionable without opening
     *     the app at all;
     *  2. `setFullScreenIntent`, which is what actually opens the takeover over
     *     a locked or sleeping screen - the primary launch mechanism;
     *  3. a direct `startActivity`, attempted **only when the app is already in
     *     the foreground**. From the background Android 10+ ignores it silently,
     *     with no exception to catch, so relying on it was the reason a
     *     backgrounded alert produced no visible UI.
     */
    fun notify(
        context: Context,
        data: CriticalAlertData,
        /** May attach setFullScreenIntent - needs the OS grant. */
        attachFullScreenIntent: Boolean,
        /** May open the takeover directly - needs only that the app be foreground. */
        allowDirectLaunch: Boolean,
    ): AlertDelivery {
        ensureChannel(context)
        val notificationId = notificationIdFor(data)

        val activityIntent = data.toActivityIntent(context)
        val contentIntent = PendingIntent.getActivity(
            context, notificationId, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("${data.severityLabel.uppercase()} - ${data.alertType}")
            .setContentText(data.summary())
            .setStyle(NotificationCompat.BigTextStyle().bigText(data.notificationBody()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop siren", actionIntent(context, AlertActionReceiver.ACTION_STOP_SIREN, notificationId, data.sosBeaconId))

        // Pin and Dismiss are the decisions that apply to someone else's
        // emergency, and they must be reachable without opening the app.
        data.sosBeaconId?.let { beaconId ->
            builder
                .addAction(0, "Responding", actionIntent(context, AlertActionReceiver.ACTION_PIN, notificationId, beaconId))
                .addAction(0, "Dismiss", actionIntent(context, AlertActionReceiver.ACTION_DISMISS, notificationId, beaconId))
        }

        if (attachFullScreenIntent) builder.setFullScreenIntent(contentIntent, true)

        var posted = false
        if (hasPostNotificationsPermission(context)) {
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(notificationId, builder.build())
                posted = true
            } catch (e: SecurityException) {
                Log.w(ALERT_PATH_TAG, "notification refused by the system", e)
            }
        } else {
            Log.w(
                ALERT_PATH_TAG,
                "POST_NOTIFICATIONS not granted - no alert notification, and therefore no " +
                    "Stop siren control. See the Alert delivery setup screen.",
            )
        }

        // Deliberately independent of attachFullScreenIntent. These are two
        // different capabilities and conflating them was a real bug: on Android
        // 14 with the full-screen-intent grant denied, the takeover was skipped
        // even with the app in the foreground - where a plain startActivity
        // works perfectly well and needs no grant at all. That is why an
        // operator broadcast produced the siren but no red screen.
        var launched = false
        if (allowDirectLaunch) {
            if (AppForegroundState.isForeground) {
                try {
                    context.startActivity(activityIntent)
                    launched = true
                } catch (e: Exception) {
                    Log.w(ALERT_PATH_TAG, "direct takeover launch refused", e)
                }
            } else {
                // Not an error - a background activity launch is silently
                // ignored on Android 10+, so it is not attempted. The
                // full-screen intent is what opens the takeover over a locked
                // screen; say so rather than failing invisibly.
                Log.i(
                    ALERT_PATH_TAG,
                    "app is backgrounded - relying on the full-screen intent and the " +
                        "notification rather than a direct launch",
                )
            }
        }

        val delivery = AlertDelivery(
            notificationPosted = posted,
            fullScreenIntentAttached = attachFullScreenIntent,
            activityLaunched = launched,
        )
        if (!delivery.hasDismissalSurface) {
            Log.e(
                ALERT_PATH_TAG,
                "alert has NO dismissal surface (notifications denied and takeover unavailable) - " +
                    "the siren will be suppressed rather than left unstoppable",
            )
        }
        return delivery
    }

    private fun actionIntent(
        context: Context,
        action: String,
        notificationId: Int,
        beaconId: String?,
    ): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            .putExtra(AlertActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(AlertActionReceiver.EXTRA_BEACON_ID, beaconId)
        return PendingIntent.getBroadcast(
            context,
            // Distinct request code per (alert, action), or FLAG_UPDATE_CURRENT
            // would have every action share one PendingIntent.
            notificationId * 8 + action.hashCode().and(0x7),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
