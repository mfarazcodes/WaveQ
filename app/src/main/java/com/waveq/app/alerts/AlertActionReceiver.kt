package com.waveq.app.alerts

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.waveq.app.mesh.SosTriage

private const val TAG = "AlertActionReceiver"

/**
 * Handles the critical-alert notification's action buttons.
 *
 * These are the controls that make a backgrounded alert actionable at all. Until
 * now the only way to stop the siren was the takeover Activity's two buttons -
 * and that Activity does not appear when the app is backgrounded and
 * full-screen-intent access is denied, which left a 60-second maximum-volume
 * siren with no control anywhere on the device.
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val beaconId = intent.getStringExtra(EXTRA_BEACON_ID)

        when (intent.action) {
            ACTION_STOP_SIREN -> {
                Log.i(TAG, "stop siren requested from notification")
                SirenPlayer.stop()
                // The notification stays: silencing the alarm is not the same as
                // deciding the emergency is dealt with.
            }
            ACTION_PIN -> {
                SirenPlayer.stop()
                beaconId?.let { SosTriage.setPinned(context, it, true) }
                cancel(context, notificationId)
            }
            ACTION_DISMISS -> {
                SirenPlayer.stop()
                beaconId?.let { SosTriage.setDismissed(context, it, true) }
                cancel(context, notificationId)
            }
            else -> Unit
        }
    }

    private fun cancel(context: Context, notificationId: Int) {
        if (notificationId < 0) return
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }

    companion object {
        const val ACTION_STOP_SIREN = "com.waveq.app.action.STOP_SIREN"
        const val ACTION_PIN = "com.waveq.app.action.PIN_BEACON"
        const val ACTION_DISMISS = "com.waveq.app.action.DISMISS_BEACON"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_BEACON_ID = "beacon_id"
    }
}
