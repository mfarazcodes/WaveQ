package com.waveq.app.alerts

import android.content.Context
import android.content.Intent
import com.waveq.app.ui.screens.CriticalAlertActivity

/**
 * Everything the full-screen takeover screen needs to render. Carried as
 * plain Intent extras (not Parcelable) - it only ever crosses the boundary
 * from the trigger dispatcher into [CriticalAlertActivity], so there is
 * nothing a serialization framework would buy here.
 */
data class CriticalAlertData(
    val alertType: String,
    val severityLabel: String,
    val senderName: String,
    /** A place name or coordinates. Never a whole sentence - see [message]. */
    val locationLabel: String?,
    /**
     * The alert's own text: an operator's instructions for a flood alert.
     *
     * Separate from [locationLabel] because both used to be the same field. The
     * flood-alert path passed the entire announcement body as the "location", so
     * the takeover's Location row rendered
     * `[Verified INC-A1B2C3D4] Flood confirmed by an operator at Sector 12` -
     * reference tag, verb and place all crammed into a row labelled Location.
     */
    val message: String? = null,
    val distanceMeters: Float?,
    val sentAt: Long,
    /** Set only when this alert came from an SOS beacon - lets Pin/Dismiss target it. */
    val sosBeaconId: String? = null,
    val sosNote: String? = null,
    /** Sender's battery level, for an SOS beacon. Null for other alert types. */
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
) {
    fun summary(): String {
        val distance = distanceMeters?.let { formatAlertDistance(it) } ?: "distance unknown"
        return "$senderName - $distance"
    }

    /**
     * The expanded notification body.
     *
     * Battery and the sender's note were already carried on SosBeacon and shown
     * in the in-app list, but never reached the takeover or the notification -
     * so the surfaces a user actually sees during an emergency showed less than
     * the list they would only open afterwards. Battery in particular bounds how
     * long the beacon will keep transmitting.
     */
    fun notificationBody(): String = buildString {
        append(summary())
        locationLabel?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
        message?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
        batteryPercent?.let {
            append("\nBattery ")
            append(it)
            append("%")
            if (isCharging) append(" (charging)")
        }
        sosNote?.takeIf { it.isNotBlank() }?.let {
            append("\n\"")
            append(it)
            append("\"")
        }
    }

    fun toActivityIntent(context: Context): Intent =
        Intent(context, CriticalAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_TYPE, alertType)
            putExtra(EXTRA_SEVERITY_LABEL, severityLabel)
            putExtra(EXTRA_SENDER_NAME, senderName)
            putExtra(EXTRA_LOCATION_LABEL, locationLabel)
            putExtra(EXTRA_MESSAGE, message)
            if (distanceMeters != null) putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
            putExtra(EXTRA_SENT_AT, sentAt)
            putExtra(EXTRA_SOS_BEACON_ID, sosBeaconId)
            putExtra(EXTRA_SOS_NOTE, sosNote)
            if (batteryPercent != null) putExtra(EXTRA_BATTERY_PERCENT, batteryPercent)
            putExtra(EXTRA_IS_CHARGING, isCharging)
        }

    companion object {
        private const val EXTRA_ALERT_TYPE = "com.waveq.app.alerts.EXTRA_ALERT_TYPE"
        private const val EXTRA_SEVERITY_LABEL = "com.waveq.app.alerts.EXTRA_SEVERITY_LABEL"
        private const val EXTRA_SENDER_NAME = "com.waveq.app.alerts.EXTRA_SENDER_NAME"
        private const val EXTRA_LOCATION_LABEL = "com.waveq.app.alerts.EXTRA_LOCATION_LABEL"
        private const val EXTRA_MESSAGE = "com.waveq.app.alerts.EXTRA_MESSAGE"
        private const val EXTRA_DISTANCE_METERS = "com.waveq.app.alerts.EXTRA_DISTANCE_METERS"
        private const val EXTRA_SENT_AT = "com.waveq.app.alerts.EXTRA_SENT_AT"
        private const val EXTRA_SOS_BEACON_ID = "com.waveq.app.alerts.EXTRA_SOS_BEACON_ID"
        private const val EXTRA_SOS_NOTE = "com.waveq.app.alerts.EXTRA_SOS_NOTE"
        private const val EXTRA_BATTERY_PERCENT = "com.waveq.app.alerts.EXTRA_BATTERY_PERCENT"
        private const val EXTRA_IS_CHARGING = "com.waveq.app.alerts.EXTRA_IS_CHARGING"

        fun fromIntent(intent: Intent): CriticalAlertData? {
            val alertType = intent.getStringExtra(EXTRA_ALERT_TYPE) ?: return null
            val severityLabel = intent.getStringExtra(EXTRA_SEVERITY_LABEL) ?: return null
            val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: return null
            return CriticalAlertData(
                alertType = alertType,
                severityLabel = severityLabel,
                senderName = senderName,
                locationLabel = intent.getStringExtra(EXTRA_LOCATION_LABEL),
                message = intent.getStringExtra(EXTRA_MESSAGE),
                distanceMeters = if (intent.hasExtra(EXTRA_DISTANCE_METERS)) intent.getFloatExtra(EXTRA_DISTANCE_METERS, 0f) else null,
                sentAt = intent.getLongExtra(EXTRA_SENT_AT, System.currentTimeMillis()),
                sosBeaconId = intent.getStringExtra(EXTRA_SOS_BEACON_ID),
                sosNote = intent.getStringExtra(EXTRA_SOS_NOTE),
                batteryPercent = if (intent.hasExtra(EXTRA_BATTERY_PERCENT)) {
                    intent.getIntExtra(EXTRA_BATTERY_PERCENT, 0)
                } else {
                    null
                },
                isCharging = intent.getBooleanExtra(EXTRA_IS_CHARGING, false),
            )
        }
    }
}

fun formatAlertDistance(meters: Float): String =
    if (meters < 1000) "${meters.toInt()} m away" else "%.1f km away".format(meters / 1000f)
