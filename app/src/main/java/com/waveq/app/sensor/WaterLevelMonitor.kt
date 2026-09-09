package com.waveq.app.sensor

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Greppable across the whole sensor path: `adb logcat -s SensorPath`. */
const val SENSOR_PATH_TAG = "SensorPath"

/**
 * What the probe is doing right now, for the UI.
 *
 * [reachable] and `lastPercent == 0` mean opposite things and must never render
 * the same way: 0% is a working sensor reporting dry air, unreachable is no
 * information at all. Conflating them would show a confident "dry" for a sensor
 * that has been underwater since it stopped answering.
 */
data class SensorStatus(
    val enabled: Boolean = false,
    val host: String = "",
    /** Null until a reading has ever succeeded. */
    val lastPercent: Int? = null,
    val lastSuccessfulPollAtMs: Long? = null,
    val reachable: Boolean = false,
    /** True between firing and dropping back below the clear threshold. */
    val latched: Boolean = false,
    /** True when this device can reach the sensor, and is therefore relaying for others. */
    val isBridge: Boolean = false,
    val consecutiveAboveTrigger: Int = 0,
) {
    val isConfigured: Boolean get() = enabled && host.isNotBlank()

    /** Human summary for the status readout. Never claims a depth. */
    val label: String
        get() = when {
            !isConfigured -> "Not configured"
            !reachable -> "Sensor unreachable"
            latched -> "Water detected"
            lastPercent == null -> "Connected, waiting for first reading"
            else -> "No water detected"
        }
}

/**
 * Decides when a run of readings constitutes a water-detection event.
 *
 * Pure and synchronous so the state machine can be tested without HTTP, a clock
 * or a coroutine. [WaterLevelMonitor] owns the polling; this owns the rules.
 *
 * Hysteresis and latching are both mandatory, and they solve different problems.
 * The consecutive-reading requirement means a single sample cannot fire.
 * Latching means that once fired, nothing fires again until the water actually
 * recedes past a *lower* clear threshold. The probe's signal is clean, so
 * neither is noise filtering - they exist so that a splash, or a probe sitting
 * right on the trigger line, cannot siren every phone in range repeatedly.
 */
class WaterLevelDetector(
    private val triggerPercent: Int,
    private val clearPercent: Int,
    private val consecutiveRequired: Int,
) {
    var latched: Boolean = false
        private set

    var consecutiveAboveTrigger: Int = 0
        private set

    /** Returns true exactly once per crossing, on the reading that completes the run. */
    fun onReading(percent: Int): Boolean {
        if (percent >= triggerPercent) {
            consecutiveAboveTrigger++
            if (latched) return false
            if (consecutiveAboveTrigger >= consecutiveRequired) {
                latched = true
                return true
            }
            return false
        }

        consecutiveAboveTrigger = 0
        // Re-arm only below the CLEAR threshold, not merely below trigger:
        // that gap is what stops a reading hovering on the line from firing
        // over and over.
        if (latched && percent < clearPercent) latched = false
        return false
    }

    /** A failed poll breaks the run - two readings either side of a gap are not consecutive. */
    fun onReadFailure() {
        consecutiveAboveTrigger = 0
    }
}

/** A snapshot of the sensor configuration for one poll cycle. */
data class SensorConfig(
    val enabled: Boolean,
    val host: String,
    val triggerPercent: Int,
    val clearPercent: Int,
    val consecutiveRequired: Int,
)

/**
 * Identity of one physical water-detection event, for cross-device dedup.
 *
 * Two phones that can both reach the sensor will each detect the same crossing
 * and each broadcast it. Those are two different messages with two different
 * messageIds, so messageId dedup cannot collapse them and every phone in range
 * would siren twice.
 *
 * The identity is therefore derived from the event rather than the message: the
 * sensor host plus a coarse time bucket of the crossing. Two bridges detecting
 * the same crossing land in the same bucket and collapse to one alert; a genuine
 * second crossing a minute later does not.
 *
 * Deliberately NOT solved by electing a single permitted bridge. Any device that
 * can reach the sensor should be able to relay - that redundancy is the whole
 * point, and a designated bridge is a single point of failure in a life-safety
 * path.
 */
const val SENSOR_EVENT_BUCKET_MS = 60_000L

fun sensorEventId(host: String, crossingAtMs: Long): String =
    "$host@${crossingAtMs / SENSOR_EVENT_BUCKET_MS}"

/**
 * Polls the probe and escalates when it detects water.
 *
 * Process-scoped by construction - it takes the scope it runs on and is started
 * from [com.waveq.app.mesh.MeshSession], never from a ViewModel. Same reason
 * MeshAlertDispatcher lives outside the ViewModel layer: an alert has to fire
 * with no UI on screen, and a viewModelScope is cleared the moment the Activity
 * goes away.
 */
class WaterLevelMonitor(
    /**
     * Config is passed in rather than read from [SensorSettings] directly, so
     * the poll loop can be exercised with no Android Context - the state machine
     * is the part worth testing and it must not need a device to run.
     */
    private val configProvider: () -> SensorConfig,
    private val sourceFactory: (String) -> WaterLevelSource = { LocalHttpWaterLevelSource(it) },
    private val onWaterDetected: (percent: Int, host: String, eventId: String, crossingAtMs: Long) -> Unit,
) {
    private val _status = MutableStateFlow(SensorStatus())
    val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private var detector: WaterLevelDetector? = null
    private var detectorKey: String? = null

    /**
     * One poll cycle. Separated from the loop so tests drive it directly without
     * a scheduler.
     */
    suspend fun pollOnce() {
        val config = configProvider()
        val enabled = config.enabled
        val host = config.host
        if (!enabled || host.isBlank()) {
            if (_status.value.isConfigured || _status.value.reachable) {
                Log.i(SENSOR_PATH_TAG, "monitor idle: enabled=$enabled host='${host}'")
            }
            _status.value = SensorStatus(enabled = enabled, host = host)
            detector = null
            detectorKey = null
            return
        }

        val trigger = config.triggerPercent
        val clear = config.clearPercent
        val required = config.consecutiveRequired

        // Rebuild the state machine if the user retunes it, so a changed
        // threshold takes effect without a restart - but keep it otherwise, or
        // the consecutive count would reset every tick and never fire.
        val key = "$host|$trigger|$clear|$required"
        if (detectorKey != key) {
            Log.i(SENSOR_PATH_TAG, "detector configured: host=$host trigger=$trigger clear=$clear consecutive=$required")
            detector = WaterLevelDetector(trigger, clear, required)
            detectorKey = key
        }
        val activeDetector = detector ?: return

        val reading = sourceFactory(host).read()
        if (reading == null) {
            activeDetector.onReadFailure()
            if (_status.value.reachable) {
                Log.w(SENSOR_PATH_TAG, "sensor $host became UNREACHABLE (this is not a 0% reading)")
            }
            _status.value = _status.value.copy(
                enabled = true,
                host = host,
                reachable = false,
                isBridge = false,
                latched = activeDetector.latched,
                consecutiveAboveTrigger = 0,
            )
            return
        }

        val wasReachable = _status.value.reachable
        if (!wasReachable) Log.i(SENSOR_PATH_TAG, "sensor $host REACHABLE - this device is bridging")

        val previousPercent = _status.value.lastPercent
        val fired = activeDetector.onReading(reading.percent)

        if (previousPercent != reading.percent) {
            Log.i(SENSOR_PATH_TAG, "reading $host: ${reading.percent}% (raw=${reading.raw}) latched=${activeDetector.latched}")
        }

        _status.value = _status.value.copy(
            enabled = true,
            host = host,
            lastPercent = reading.percent,
            lastSuccessfulPollAtMs = reading.takenAtMs,
            reachable = true,
            isBridge = true,
            latched = activeDetector.latched,
            consecutiveAboveTrigger = activeDetector.consecutiveAboveTrigger,
        )

        if (fired) {
            val eventId = sensorEventId(host, reading.takenAtMs)
            Log.w(
                SENSOR_PATH_TAG,
                "WATER DETECTED at $host: ${reading.percent}% after ${activeDetector.consecutiveAboveTrigger} " +
                    "consecutive readings >= $trigger%, eventId=$eventId - escalating and broadcasting",
            )
            onWaterDetected(reading.percent, host, eventId, reading.takenAtMs)
        }
    }

    /** Cleared when the user turns the sensor off, so re-enabling starts fresh. */
    fun reset() {
        detector = null
        detectorKey = null
        _status.value = SensorStatus()
    }
}
