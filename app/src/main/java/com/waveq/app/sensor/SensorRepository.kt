package com.waveq.app.sensor

import android.content.Context
import android.util.Log
import com.waveq.app.alerts.CriticalAlertTrigger
import com.waveq.app.mesh.MeshManager
import com.waveq.app.mesh.MeshPayload
import com.waveq.app.mesh.MeshSession
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.SENSOR_ALERT_MAX_HOPS
import com.waveq.app.mesh.SENSOR_SENDER_NAME
import com.waveq.app.ui.components.Severity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the water-level probe for the whole process: polls it, escalates locally
 * when it detects water, and broadcasts so devices with no network escalate too.
 *
 * ## Why this does not go through RiskEngine
 *
 * RiskEngine deliberately refuses to escalate without
 * `ESCALATION_REQUIRED_INDICATOR` (RAINFALL_INTENSITY) available and at least
 * `MIN_AVAILABLE_WEIGHT_FOR_ESCALATION` of its indicator weight present. That
 * guard is correct for what it guards: a *forecast inference* built from missing
 * inputs must not sound a siren, and terrain alone once did exactly that.
 *
 * But it makes the engine the wrong home for this. Offline - no Open-Meteo, no
 * forecast, no soil moisture - the engine would correctly refuse, and a probe
 * physically sitting in rising water would be silenced by a confidence floor
 * designed for weather models. A probe is direct observation, not inference. It
 * does not need a forecast to be believed, so it gets its own path and does not
 * clear a forecast-confidence bar.
 *
 * ## Why not MessageType.FLOOD_ALERT
 *
 * FLOOD_ALERT is role-gated to OPERATOR/ADMIN in `MeshManager.sendMessage` and
 * returns 0 on a citizen device. The bridging phone is whichever one happens to
 * be on the sensor's Wi-Fi - most often a citizen - so a sensor alert sent as
 * FLOOD_ALERT would be silently dropped at the sender. Hence
 * [MessageType.SENSOR_ALERT], which is ungated.
 *
 * Modelled on RiskRepository's escalateLocal/sendAssessment pair: escalate here,
 * then broadcast so everyone else escalates too.
 */
object SensorRepository {

    @Volatile private var started = false
    private lateinit var appContext: Context
    private var meshManager: MeshManager? = null
    private var myDeviceId: String = ""

    private val monitor = WaterLevelMonitor(
        configProvider = {
            SensorConfig(
                enabled = SensorSettings.enabled.value,
                host = SensorSettings.host.value,
                triggerPercent = SensorSettings.triggerPercent.value,
                clearPercent = SensorSettings.clearPercent.value,
                consecutiveRequired = SensorSettings.consecutiveRequired.value,
            )
        },
        onWaterDetected = { percent, host, eventId, crossingAtMs ->
            onWaterDetected(percent, host, eventId, crossingAtMs)
        },
    )

    val status: StateFlow<SensorStatus> get() = monitor.status

    @Synchronized
    fun init(context: Context, meshManager: MeshManager, myDeviceId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        appContext = context.applicationContext
        this.meshManager = meshManager
        this.myDeviceId = myDeviceId
        SensorSettings.init(appContext)

        scope.launch(
            CoroutineExceptionHandler { _, t -> Log.e(SENSOR_PATH_TAG, "sensor poll loop failed", t) },
        ) {
            while (true) {
                runCatching { monitor.pollOnce() }
                    .onFailure { Log.w(SENSOR_PATH_TAG, "poll failed", it) }
                // Re-read every tick so a settings change takes effect without a
                // restart. Idle at a slow cadence when unconfigured rather than
                // spinning.
                val seconds = if (SensorSettings.isConfigured()) {
                    SensorSettings.pollSeconds.value
                } else {
                    IDLE_POLL_SECONDS
                }
                delay(seconds * 1000L)
            }
        }
    }

    /**
     * Local escalation, then propagation - the same order as
     * RiskRepository.recomputeDevice: the detecting device must alert its own
     * user whether or not any peer is in range to hear the broadcast.
     */
    private fun onWaterDetected(percent: Int, host: String, eventId: String, crossingAtMs: Long) {
        val context = appContext
        CriticalAlertTrigger.onSensorWaterLevelCritical(context, percent, host)

        val mesh = meshManager
        if (mesh == null) {
            Log.w(SENSOR_PATH_TAG, "no mesh manager - alert not propagated")
            return
        }
        val payload = SensorAlertPayload(
            eventId = eventId,
            sensorHost = host,
            percent = percent,
            bridgeDeviceId = myDeviceId,
            bridgeDeviceName = MeshSession.senderName,
            detectedAtMs = crossingAtMs,
        )
        val peers = mesh.sendMessage(
            channelId = MeshSession.channelRepository.cityChannelId(),
            payload = MeshPayload(
                senderId = myDeviceId,
                // A device name, never the user's: a probe detected water, no
                // person said anything.
                senderName = SENSOR_SENDER_NAME,
                type = MessageType.SENSOR_ALERT,
                text = payload.headline(),
                audioFileName = null,
                timestamp = System.currentTimeMillis(),
                severity = Severity.CRITICAL.name,
                sensorJson = payload.toJson(),
            ),
            maxHops = SENSOR_ALERT_MAX_HOPS,
        )

        // The bridge is associated to a Wi-Fi AP to reach the sensor, while
        // Nearby Connections wants Wi-Fi Direct. That association can degrade
        // the mesh, and it is invisible unless recorded - so the peer count and
        // transport state at the moment of broadcast are logged here rather than
        // left to be mysterious at demo time.
        val transport = MeshSession.transport.status.value
        Log.w(
            SENSOR_PATH_TAG,
            "BRIDGE broadcast eventId=$eventId to $peers peer(s); transport=${transport.label} " +
                "advertising=${transport.isAdvertising} discovering=${transport.isDiscovering} " +
                "connected=${transport.peerCount} discovered=${transport.discoveredCount}" +
                if (peers == 0) " - WARNING: no peers received this" else "",
        )
    }

    /** Reachability check for the Settings "Test connection" button. */
    suspend fun testConnection(host: String): Boolean = LocalHttpWaterLevelSource(host).isReachable()

    fun onSettingsDisabled() = monitor.reset()
}

private const val IDLE_POLL_SECONDS = 10
