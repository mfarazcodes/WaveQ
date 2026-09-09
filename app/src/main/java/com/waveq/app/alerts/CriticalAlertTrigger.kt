package com.waveq.app.alerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.waveq.app.data.IncidentWire
import com.waveq.app.mesh.MeshMessage
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.SosBeacon
import com.waveq.app.mesh.SosBeaconService
import com.waveq.app.mesh.awaitLastLocation
import com.waveq.app.ui.components.SIREN_THRESHOLD
import com.waveq.app.ui.components.Severity
import com.waveq.app.ui.components.soundsAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "CriticalAlertTrigger"

/**
 * Single funnel for every mesh alert: an operator flood alert or an SOS beacon
 * arriving over the mesh, or a local/relayed risk escalation (the Admin Panel's
 * "Test Alert" button simulates that last path, for demoing without a live
 * mesh). This is the one place that decides whether to sound at all - the siren,
 * the notification, and the full-screen takeover never make that call
 * independently.
 */
object CriticalAlertTrigger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * An operator flood alert from another device.
     *
     * Every severity is delivered. Whether it also sounds the siren and takes
     * over the screen is decided by [soundsAlarm] - at or above
     * [SIREN_THRESHOLD], which is CRITICAL and EVACUATE.
     *
     * Two bugs have lived on this line. It used to `return` outright for
     * anything below CRITICAL, so an operator broadcasting at the default
     * severity - HIGH, see BroadcastAlertScreen - produced nothing at all on
     * receiving devices. And once every severity delivered, the escalation check
     * was still `== Severity.CRITICAL`, which excluded EVACUATE: the most severe
     * level in the scale arrived as a quiet notification.
     */
    fun onFloodAlertReceived(
        context: Context,
        message: MeshMessage,
        escalationAllowed: Boolean = true,
    ) {
        if (message.isMine) return
        if (message.type != MessageType.FLOOD_ALERT) return
        val severity = Severity.entries.firstOrNull { it.name == message.severity } ?: Severity.HIGH
        Log.i(
            ALERT_PATH_TAG,
            "flood alert parsed: severity=${severity.name} " +
                (if (severity.soundsAlarm) "(will escalate)" else "(notification only - below $SIREN_THRESHOLD)"),
        )

        // A confirmation alert carries the incident structurally, so the place
        // name goes in the place-name row and the announcement goes in the
        // message block. Without the structured payload (an operator broadcast
        // typed by hand, or an older build) there is no separate place name, so
        // the row is left empty rather than filled with the whole sentence.
        val incident = IncidentWire.fromJson(message.incidentJson)
        val beaconLocation = incident?.latitude?.let { lat ->
            incident.longitude?.let { lon ->
                Location("mesh").apply { latitude = lat; longitude = lon }
            }
        }
        fire(
            context,
            alertType = if (incident != null) "${incident.type} confirmed" else "Flood Alert",
            severity = severity,
            senderName = message.senderName,
            sentAt = message.timestamp,
            location = beaconLocation,
            locationLabel = incident?.location?.takeIf { it.isNotBlank() },
            message = incident?.description?.takeIf { it.isNotBlank() }
                ?: message.text?.takeIf { it.isNotBlank() },
            escalationAllowed = escalationAllowed,
        )
    }

    /** SOS beacons are always CRITICAL by nature - fire once per session, not on every 30s repeat (caller gates that). */
    fun onSosBeaconReceived(context: Context, beacon: SosBeacon, escalationAllowed: Boolean = true) {
        // A beacon sent before its device had a GPS fix carries no position.
        // Passing null here means the takeover shows "Unknown" for both location
        // and distance rather than a fabricated coordinate and a distance
        // measured from it.
        val beaconLocation = beacon.latitude?.let { lat ->
            beacon.longitude?.let { lon ->
                Location("mesh").apply { latitude = lat; longitude = lon }
            }
        }
        fire(
            context,
            alertType = "SOS Beacon",
            severity = Severity.CRITICAL,
            senderName = beacon.senderName,
            sentAt = beacon.sentAt,
            location = beaconLocation,
            locationLabel = if (beaconLocation == null) "Location not yet acquired" else null,
            sosBeaconId = beacon.beaconId,
            sosNote = beacon.note,
            // Already null-or-in-range: MeshSerialization.sosBeaconFromJson
            // nulls anything outside 0..100 on decode. Guarding again here would
            // put the same invariant in two places, and the second copy is the
            // one that drifts.
            batteryPercent = beacon.batteryPercent,
            isCharging = beacon.isCharging,
            escalationAllowed = escalationAllowed,
        )
    }

    /** Entry point for a local risk model to report CRITICAL conditions (e.g. a flood-risk sensor reading). */
    fun onLocalRiskCritical(context: Context, description: String, location: Location? = null) {
        fire(
            context,
            alertType = "Local Risk Alert",
            severity = Severity.CRITICAL,
            senderName = "This device",
            sentAt = System.currentTimeMillis(),
            location = location,
            locationLabel = description,
        )
    }

    /**
     * A CRITICAL risk assessment that arrived over the mesh from another
     * device, rather than being computed here.
     *
     * Same siren and full-screen path as a CRITICAL flood alert, but labelled
     * with the source device and stamped with the moment its SOURCE DATA was
     * fetched - not the relay time - so "sent 40 minutes ago" reports the age of
     * the evidence rather than the age of the last hop.
     *
     * The caller ([com.waveq.app.prediction.RiskRepository]) is responsible for
     * the freshness and distance rules: this is only reached for an update that
     * was close enough and recent enough to be adopted as the device's own risk.
     */
    fun onRelayedRiskCritical(
        context: Context,
        sourceName: String,
        description: String,
        dataFetchedAt: Long,
        location: Location? = null,
    ) {
        fire(
            context,
            alertType = "Flood Risk (relayed)",
            severity = Severity.CRITICAL,
            senderName = sourceName,
            sentAt = dataFetchedAt,
            location = location,
            locationLabel = description,
        )
    }

    /**
     * A physical water-level probe on the local network has detected water.
     *
     * Mirrors [onLocalRiskCritical] and funnels into the same [fire] - siren
     * gating, the dismissal-surface check and the full-screen takeover are all
     * handled there, and none of it is duplicated here.
     *
     * Always CRITICAL. A probe reporting water is direct observation, not a
     * forecast: there is no confidence to degrade and nothing to weigh it
     * against. Wording never implies a depth - the hardware is a threshold
     * detector at a fixed height.
     */
    fun onSensorWaterLevelCritical(context: Context, percent: Int, host: String) {
        fire(
            context,
            alertType = "Water level sensor",
            severity = Severity.CRITICAL,
            senderName = "Sensor at $host",
            sentAt = System.currentTimeMillis(),
            location = null,
            locationLabel = "Sensor $host",
            message = "Water detected at the sensor. This is a direct reading from a probe on " +
                "the local network, not a forecast.",
        )
    }

    /**
     * A water-level detection relayed from another device over the mesh.
     *
     * Framed as relayed, identifying both the bridging device and the sensor,
     * so a receiving phone never presents someone else's reading as its own
     * observation - the same distinction [onRelayedRiskCritical] draws.
     */
    fun onRelayedSensorAlert(
        context: Context,
        sensorHost: String,
        bridgeDeviceName: String,
        detectedAtMs: Long,
        escalationAllowed: Boolean = true,
    ) {
        fire(
            context,
            alertType = "Water level sensor (relayed)",
            severity = Severity.CRITICAL,
            senderName = bridgeDeviceName,
            sentAt = detectedAtMs,
            location = null,
            locationLabel = "Sensor $sensorHost",
            message = "Water detected by a sensor reported by $bridgeDeviceName. Relayed over " +
                "the mesh - this device is not connected to the sensor itself.",
            escalationAllowed = escalationAllowed,
        )
    }

    /** Fires the full flow locally with synthetic data - no live mesh or second device required. */
    fun fireTestAlert(context: Context) {
        fire(
            context,
            alertType = "Test Alert",
            severity = Severity.CRITICAL,
            senderName = "Test Device",
            sentAt = System.currentTimeMillis(),
            location = null,
            locationLabel = "Simulated - no live mesh required",
        )
    }

    /**
     * One delivery path for every alert type: SOS beacons, operator flood
     * alerts, local and relayed risk escalations, and the test alert. Only the
     * content and the severity differ.
     */
    private fun fire(
        context: Context,
        alertType: String,
        severity: Severity,
        senderName: String,
        sentAt: Long,
        location: Location?,
        locationLabel: String?,
        message: String? = null,
        sosBeaconId: String? = null,
        sosNote: String? = null,
        batteryPercent: Int? = null,
        isCharging: Boolean = false,
        escalationAllowed: Boolean = true,
    ) {
        // The user already knows their own situation - don't hijack their
        // screen mid-emergency with someone else's alert. Note this is our OWN
        // SOS state, so it can never suppress an alert on a receiving device
        // that is not itself broadcasting.
        if (SosBeaconService.isActive) {
            Log.i(ALERT_PATH_TAG, "suppressed: this device has its own SOS active")
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            val distance = location?.let { distanceFromDevice(appContext, it) }
            val data = CriticalAlertData(
                alertType = alertType,
                severityLabel = severity.label,
                senderName = senderName,
                locationLabel = locationLabel ?: location?.let { "%.5f, %.5f".format(it.latitude, it.longitude) },
                message = message,
                distanceMeters = distance,
                sentAt = sentAt,
                sosBeaconId = sosBeaconId,
                sosNote = sosNote,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
            )

            // Only CRITICAL escalates to the siren and the takeover. Lower
            // severities still deliver - as a heads-up notification with the
            // same actions - rather than vanishing.
            //
            // Identical for SOS beacons and operator FLOOD_ALERTs: one funnel,
            // one audio decision, one set of notification actions. Only `data`
            // differs between them.
            // Two independent conditions, both required. Severity says the
            // content warrants an alarm; escalationAllowed says the alert is
            // recent enough for one to be honest - see [freshnessOf]. A stale
            // alert still posts its notification, on a channel with
            // `setSound(null, null)`, so it lands silently in the shade with its
            // original timestamp rather than vanishing.
            val isEscalation = severity.soundsAlarm && escalationAllowed
            val takeoverWanted = isEscalation && AlertSettings.isFullScreenEnabled(appContext)
            Log.i(
                ALERT_PATH_TAG,
                "fire: type=$alertType severity=${severity.name} escalate=$isEscalation " +
                    "escalationAllowed=$escalationAllowed " +
                    "takeoverSetting=${AlertSettings.isFullScreenEnabled(appContext)} " +
                    "sirenSetting=${AlertSettings.isSirenEnabled(appContext)} " +
                    "fsiGranted=${AlertNotifications.canUseFullScreenIntent(appContext)} " +
                    "foreground=${AppForegroundState.isForeground}",
            )

            // Two separate capabilities, deliberately not conflated: the
            // full-screen intent needs an OS grant and works over a locked
            // screen; a direct launch needs no grant but only works in the
            // foreground. Gating both on the grant meant a foreground CRITICAL
            // alert on Android 14 sounded with no takeover.
            val delivery = AlertNotifications.notify(
                context = appContext,
                data = data,
                attachFullScreenIntent = takeoverWanted &&
                    AlertNotifications.canUseFullScreenIntent(appContext),
                allowDirectLaunch = takeoverWanted,
            )

            Log.i(
                ALERT_PATH_TAG,
                "delivery: posted=${delivery.notificationPosted} " +
                    "fsiAttached=${delivery.fullScreenIntentAttached} " +
                    "launched=${delivery.activityLaunched} " +
                    "dismissable=${delivery.hasDismissalSurface}",
            )

            val sirenWanted = isEscalation && AlertSettings.isSirenEnabled(appContext)
            if (sirenWanted && delivery.hasDismissalSurface) {
                Log.i(ALERT_PATH_TAG, "starting siren")
                SirenPlayer.start(appContext)
            } else if (sirenWanted) {
                Log.w(
                    ALERT_PATH_TAG,
                    "siren SUPPRESSED for $alertType: no notification and no takeover, so there " +
                        "would be no way to stop it. Grant notifications in Alert delivery.",
                )
            } else {
                Log.i(
                    ALERT_PATH_TAG,
                    "no siren: escalate=$isEscalation sirenSetting=${AlertSettings.isSirenEnabled(appContext)}",
                )
            }
        }
    }

    private suspend fun distanceFromDevice(context: Context, alertLocation: Location): Float? {
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) return null
        val last = LocationServices.getFusedLocationProviderClient(context).awaitLastLocation()
        return last?.distanceTo(alertLocation)
    }
}
