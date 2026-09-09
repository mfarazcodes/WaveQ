package com.waveq.app.sensor

import org.json.JSONObject

/**
 * A water-detection event as it travels over the mesh.
 *
 * [eventId] is the cross-device dedup key - see [sensorEventId]. It is derived
 * from the sensor and the moment of the crossing, NOT from the message, so two
 * devices that both reached the same probe and both broadcast produce two
 * messages carrying one event id, and receiving devices siren once.
 *
 * [bridgeDeviceName] is the device that read the probe, carried so a receiving
 * device can say where the reading came from rather than presenting a relayed
 * alert as its own observation.
 */
data class SensorAlertPayload(
    val eventId: String,
    val sensorHost: String,
    val percent: Int,
    val bridgeDeviceId: String,
    val bridgeDeviceName: String,
    val detectedAtMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("eventId", eventId)
        put("sensorHost", sensorHost)
        put("percent", percent)
        put("bridgeDeviceId", bridgeDeviceId)
        put("bridgeDeviceName", bridgeDeviceName)
        put("detectedAt", detectedAtMs)
    }.toString()

    /**
     * Wording never implies a depth measurement.
     *
     * The probe is a threshold detector at a fixed height: it reports water
     * present or absent. "Water detected" is the honest claim; "water level at
     * 98%" would not be.
     */
    fun headline(): String = "Water detected by sensor at $sensorHost"

    companion object {
        fun fromJson(json: String?): SensorAlertPayload? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val eventId = obj.optString("eventId").takeIf { it.isNotBlank() } ?: return null
                SensorAlertPayload(
                    eventId = eventId,
                    sensorHost = obj.optString("sensorHost").takeIf { it.isNotBlank() } ?: return null,
                    percent = obj.optInt("percent", -1).takeIf { it in 0..100 } ?: return null,
                    bridgeDeviceId = obj.optString("bridgeDeviceId"),
                    bridgeDeviceName = obj.optString("bridgeDeviceName").takeIf { it.isNotBlank() }
                        ?: "A nearby device",
                    detectedAtMs = obj.optLong("detectedAt", System.currentTimeMillis()),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
