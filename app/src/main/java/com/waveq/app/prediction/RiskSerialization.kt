package com.waveq.app.prediction

import com.waveq.app.ui.components.Severity
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format for a [RiskAssessment] travelling over the mesh.
 *
 * JSON, matching every other payload format in this app (see MeshSerialization,
 * ChannelRepository). A risk update is unencrypted on the City-Wide channel by
 * design, so nothing here is secret - the format is chosen for
 * forward-compatibility instead: unknown fields are ignored and missing ones
 * fall back, so a device running an older build can still read the parts of a
 * newer device's assessment that it understands rather than dropping the whole
 * warning.
 *
 * Size matters: Nearby Connections' BYTES payload ceiling is ~32 KB and this
 * shares it with the envelope framing. A full assessment with all five
 * indicators, 24 h of rainfall and two weeks of discharge encodes to roughly
 * 4 KB, and the series are capped below to keep it there.
 */
object RiskSerialization {

    private const val WIRE_VERSION = 1

    /** Caps on the two series, so a malformed or hostile payload cannot blow the size budget. */
    private const val MAX_RAINFALL_POINTS = 30
    private const val MAX_DISCHARGE_POINTS = 20

    fun toJson(
        assessment: RiskAssessment,
        sourceDeviceId: String,
        sourceDeviceName: String,
    ): String {
        val obj = JSONObject()
        obj.put("v", WIRE_VERSION)
        obj.put("assessmentId", assessment.assessmentId)
        obj.put("score", assessment.score)
        obj.put("severity", assessment.severity.name)
        obj.put("leadTimeHours", assessment.leadTimeHours ?: JSONObject.NULL)
        obj.put("confidence", assessment.confidence)
        obj.put("latitude", assessment.latitude)
        obj.put("longitude", assessment.longitude)
        obj.put("terrainClass", assessment.terrainClass.name)
        obj.put("terrainSummary", assessment.terrainSummary)
        obj.put("terrainAssumed", assessment.isTerrainAssumed)
        obj.put("computedAt", assessment.computedAt)
        // The timestamp the SOURCE DATA was fetched - not when this was relayed.
        // Receivers recompute confidence from this, so a twice-relayed
        // two-hour-old assessment reports its real age.
        // Null for a terrain-only assessment. fromJson rejects such a payload, so
        // an assessment with no dated input is never adopted by a peer.
        obj.put("dataFetchedAt", assessment.dataFetchedAt ?: JSONObject.NULL)
        obj.put("headline", assessment.headline)
        obj.put("temperatureC", assessment.currentTemperatureC ?: JSONObject.NULL)
        obj.put("humidityPct", assessment.currentHumidityPct ?: JSONObject.NULL)
        obj.put("sourceDeviceId", sourceDeviceId)
        obj.put("sourceDeviceName", sourceDeviceName)

        val indicators = JSONArray()
        assessment.indicators.forEach { indicator ->
            indicators.put(
                JSONObject().apply {
                    put("id", indicator.id.name)
                    put("rawValue", indicator.rawValue ?: JSONObject.NULL)
                    put("rawLabel", indicator.rawLabel)
                    put("score", indicator.score)
                    put("explanation", indicator.explanation)
                    put("available", indicator.isAvailable)
                },
            )
        }
        obj.put("indicators", indicators)

        val rain = JSONArray()
        assessment.rainfall24h.take(MAX_RAINFALL_POINTS).forEach { point ->
            rain.put(
                JSONObject().apply {
                    put("t", point.timeUtcMs)
                    put("mm", point.precipitationMm)
                    put("p", point.probabilityPct ?: JSONObject.NULL)
                },
            )
        }
        obj.put("rainfall", rain)

        val discharge = JSONArray()
        assessment.dischargeTrend.take(MAX_DISCHARGE_POINTS).forEach { point ->
            discharge.put(
                JSONObject().apply {
                    put("t", point.timeUtcMs)
                    put("q", point.dischargeM3s)
                    put("m", point.meanM3s ?: JSONObject.NULL)
                },
            )
        }
        obj.put("discharge", discharge)

        return obj.toString()
    }

    /**
     * Parses a received risk update.
     *
     * [hops] comes from the envelope's hop count and [distanceKm] from the
     * receiving device's own position, so the returned assessment carries
     * accurate provenance for the UI. Confidence is recomputed here from the
     * original `dataFetchedAt`, never from the relay time.
     *
     * Returns null for anything malformed - this parses attacker-reachable
     * bytes off an unencrypted channel, so every field access is defensive and
     * the whole thing is wrapped.
     */
    fun fromJson(json: String, hops: Int, distanceKm: Double?, now: Long = System.currentTimeMillis()): ReceivedRisk? = try {
        val obj = JSONObject(json)
        val sourceDeviceId = obj.optString("sourceDeviceId").takeIf { it.isNotBlank() } ?: return null
        val sourceDeviceName = obj.optString("sourceDeviceName").takeIf { it.isNotBlank() } ?: "A nearby device"

        val indicators = mutableListOf<RiskIndicator>()
        val indicatorArray = obj.optJSONArray("indicators")
        if (indicatorArray != null) {
            for (i in 0 until indicatorArray.length()) {
                val item = indicatorArray.optJSONObject(i) ?: continue
                val id = runCatching { IndicatorId.valueOf(item.optString("id")) }.getOrNull() ?: continue
                indicators += RiskIndicator(
                    id = id,
                    rawValue = if (item.isNull("rawValue")) null else item.optDouble("rawValue"),
                    rawLabel = item.optString("rawLabel", "No data"),
                    score = item.optDouble("score", 0.0).coerceIn(0.0, 1.0),
                    explanation = item.optString("explanation", ""),
                    isAvailable = item.optBoolean("available", false),
                )
            }
        }

        val rainfall = mutableListOf<HourlyRainPoint>()
        obj.optJSONArray("rainfall")?.let { array ->
            for (i in 0 until minOf(array.length(), MAX_RAINFALL_POINTS)) {
                val item = array.optJSONObject(i) ?: continue
                rainfall += HourlyRainPoint(
                    timeUtcMs = item.optLong("t"),
                    precipitationMm = item.optDouble("mm", 0.0),
                    probabilityPct = if (item.isNull("p")) null else item.optInt("p"),
                )
            }
        }

        val discharge = mutableListOf<DischargePoint>()
        obj.optJSONArray("discharge")?.let { array ->
            for (i in 0 until minOf(array.length(), MAX_DISCHARGE_POINTS)) {
                val item = array.optJSONObject(i) ?: continue
                discharge += DischargePoint(
                    timeUtcMs = item.optLong("t"),
                    dischargeM3s = item.optDouble("q", 0.0),
                    meanM3s = if (item.isNull("m")) null else item.optDouble("m"),
                )
            }
        }

        val severity = runCatching { Severity.valueOf(obj.optString("severity")) }.getOrNull() ?: return null
        val terrainClass = runCatching { CatchmentClass.valueOf(obj.optString("terrainClass")) }
            .getOrNull() ?: CatchmentClass.PLAIN
        val dataFetchedAt = obj.optLong("dataFetchedAt", 0L).takeIf { it > 0L } ?: return null
        val terrainSummary = obj.optString("terrainSummary", "Terrain not reported")

        val assessment = RiskAssessment(
            assessmentId = obj.optString("assessmentId").takeIf { it.isNotBlank() } ?: return null,
            score = obj.optInt("score", 0).coerceIn(0, 100),
            severity = severity,
            leadTimeHours = if (obj.isNull("leadTimeHours")) null else obj.optDouble("leadTimeHours"),
            // Recomputed below from the original fetch time - the transmitted
            // value is the SENDER's confidence at send time and is not trusted.
            confidence = 0.0,
            indicators = indicators,
            latitude = obj.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            longitude = obj.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            terrainClass = terrainClass,
            terrainSummary = terrainSummary,
            // Defaults to true (the pessimistic case) when absent, so an older
            // sender's payload degrades confidence rather than overstating it.
            isTerrainAssumed = obj.optBoolean("terrainAssumed", true),
            computedAt = obj.optLong("computedAt", dataFetchedAt),
            dataFetchedAt = dataFetchedAt,
            rainfall24h = rainfall,
            dischargeTrend = discharge,
            currentTemperatureC = if (obj.isNull("temperatureC")) null else obj.optDouble("temperatureC"),
            currentHumidityPct = if (obj.isNull("humidityPct")) null else obj.optInt("humidityPct"),
            provenance = RiskProvenance.Relayed(
                sourceDeviceId = sourceDeviceId,
                sourceDeviceName = sourceDeviceName,
                hops = hops,
                distanceKm = distanceKm,
            ),
            headline = obj.optString("headline", ""),
        )

        ReceivedRisk(
            assessment = assessment.withRecomputedConfidence(now),
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = sourceDeviceName,
        )
    } catch (e: Exception) {
        null
    }
}

/** A parsed risk update from another device, with its source identity kept alongside. */
data class ReceivedRisk(
    val assessment: RiskAssessment,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
)

/**
 * Great-circle distance in kilometres.
 *
 * Used for the 25 km adoption rule and for the "N km away" provenance label.
 * Haversine rather than the flat-earth approximation because at Indian
 * latitudes the error in the cheap version reaches several percent over the
 * distances that decide whether a warning is adopted or shown as context.
 */
fun distanceKmBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
}
