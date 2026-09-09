package com.waveq.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.location.LocationController
import com.waveq.app.prediction.DischargePoint
import com.waveq.app.prediction.HourlyRainPoint
import com.waveq.app.prediction.RiskAssessment
import com.waveq.app.prediction.RiskIndicator
import com.waveq.app.prediction.RiskProvenance
import com.waveq.app.prediction.RiskRepository
import com.waveq.app.prediction.formatHours
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

/**
 * The screen that makes the risk score defensible.
 *
 * A number on a gauge is not a warning anyone should act on unless they can see
 * where it came from. This screen shows the whole chain: which indicators fired,
 * what each one measured, what threshold it was measured against, how old the
 * data is, how confident the model is and why, and - explicitly - that this is a
 * rule-based physical model rather than a learned one.
 */
@Composable
fun RiskDetailScreen() {
    val state by RiskRepository.state.collectAsState()
    val viewingPlace by LocationController.viewingPlace.collectAsState()
    val devicePlace by LocationController.devicePlace.collectAsState()
    val scope = rememberCoroutineScope()
    // Ticks once a minute so "fetched 12 min ago" ages on screen instead of
    // freezing at whatever it said when the screen opened.
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(Dimens.cardSpacing))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Flash flood risk",
                    style = AppTypography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    viewingPlace?.fullLabel() ?: "On-device assessment",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { RiskRepository.refreshNow(scope) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh risk assessment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // When the user is viewing somewhere else, the assessment for where
        // they actually are is shown alongside it rather than hidden. It is the
        // one that can sound the siren, so it stays visible and stays labelled.
        if (state.isViewingOverridden) {
            Spacer(Modifier.height(Dimens.cardSpacing))
            OtherLocationNotice(
                deviceAssessment = state.device,
                devicePlaceName = devicePlace?.fullLabel(),
                onReset = { LocationController.resetToCurrentLocation() },
            )
        }

        val assessment = state.viewing
        if (assessment == null) {
            Spacer(Modifier.height(Dimens.heroSpacing))
            NoticeCard(
                icon = Icons.Filled.CloudOff,
                title = "No assessment yet",
                body = state.statusMessage
                    ?: "Waiting for location and weather data. Once downloaded, this works offline.",
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))
            NearbyAreasSection(state.nearbyAreas, now)
            Spacer(Modifier.height(Dimens.sectionSpacing))
            ModelDescriptionCard()
            Spacer(Modifier.height(Dimens.sectionSpacing))
            return@Column
        }

        // -- Hero: the score itself -------------------------------------
        Spacer(Modifier.height(Dimens.heroSpacing))
        RiskGauge(
            level = assessment.severity,
            location = assessment.headline,
            fraction = assessment.gaugeFraction(),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(Dimens.heroSpacing))
        Row(Modifier.fillMaxWidth()) {
            MetricStat("${assessment.score}", "Score / 100", Modifier.weight(1f))
            MetricStat(
                assessment.leadTimeHours?.let { formatHours(it) } ?: "-",
                "Lead time",
                Modifier.weight(1f),
            )
            MetricStat(
                "${(assessment.confidence * 100).toInt()}%",
                "Confidence",
                Modifier.weight(1f),
                valueColor = confidenceColor(assessment.confidence),
            )
        }

        // -- Current conditions -----------------------------------------
        if (assessment.currentTemperatureC != null || assessment.currentHumidityPct != null) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            SectionHeading("Conditions right now")
            Spacer(Modifier.height(Dimens.cardSpacing))
            CurrentConditionsCard(assessment)
        }

        // -- Provenance -------------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Where this came from")
        Spacer(Modifier.height(Dimens.cardSpacing))
        ProvenanceCard(assessment, state.sharingDeviceCount, now)

        // -- Indicators -------------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("How this score was reached")
        Spacer(Modifier.height(8.dp))
        Text(
            "Five physical indicators, each measured separately and then weighted. " +
                "Rainfall intensity and soil saturation carry the most weight because together " +
                "they are what starts a flash flood.",
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        assessment.indicators.forEach { indicator ->
            IndicatorRow(indicator)
            Spacer(Modifier.height(10.dp))
        }

        // -- Rainfall chart ---------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Rainfall forecast, next 24 hours")
        Spacer(Modifier.height(Dimens.cardSpacing))
        RainfallChart(
            points = assessment.rainfall24h,
            thresholdMm = assessment.terrainClass.thresholdCurve.oneHourMm,
            now = now,
        )

        // -- Discharge chart --------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("River discharge trend")
        Spacer(Modifier.height(Dimens.cardSpacing))
        DischargeChart(assessment.dischargeTrend)

        // -- Terrain ------------------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        SectionHeading("Local terrain")
        Spacer(Modifier.height(Dimens.cardSpacing))
        TerrainCard(assessment)

        // -- Nearby areas -------------------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        NearbyAreasSection(state.nearbyAreas, now)

        // -- Honesty about the model ---------------------------------------
        Spacer(Modifier.height(Dimens.sectionSpacing))
        ModelDescriptionCard()
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}

/**
 * Shown while a manual location override is active.
 *
 * Two jobs, both about preventing one specific mistake - reading another
 * place's score as your own. It says plainly which place the screen describes,
 * and it surfaces the *device's* risk right next to it, because that is the one
 * that will actually wake the user up.
 */
@Composable
private fun OtherLocationNotice(
    deviceAssessment: RiskAssessment?,
    devicePlaceName: String?,
    onReset: () -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = MaterialTheme.appExtraColors.severityMediumBg,
    ) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                "You are viewing another location",
                style = AppTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Everything below describes the place you picked. Alerts, the siren and your " +
                    "SOS beacon all keep using your real location.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Dimens.cardSpacing))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MicroLabel("Where you are")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        devicePlaceName ?: "Locating...",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (deviceAssessment != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${deviceAssessment.score}/100",
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    SeverityBadge(deviceAssessment.severity)
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))
            TextButton(onClick = onReset, contentPadding = PaddingValues(0.dp)) {
                Text(
                    "Use current location",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Provenance
// ---------------------------------------------------------------------------

/**
 * Says plainly where the assessment came from and how old its data is.
 *
 * This is not decoration. A user deciding whether to move their family needs to
 * know whether they are looking at something this phone measured twelve minutes
 * ago or something that reached them third-hand from a device 20 km away.
 */
@Composable
private fun ProvenanceCard(assessment: RiskAssessment, sharingDeviceCount: Int, now: Long) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                provenanceLabel(assessment, now),
                style = AppTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                confidenceExplanation(assessment, now),
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (sharingDeviceCount == 0) {
                    "No nearby devices are currently sharing risk data."
                } else if (sharingDeviceCount == 1) {
                    "1 nearby device is currently sharing risk data over the mesh."
                } else {
                    "$sharingDeviceCount nearby devices are currently sharing risk data over the mesh."
                },
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        }
    }
}

/**
 * "Fetched on this device 12 min ago" vs "Relayed from a nearby device, 2 hops,
 * data 40 min old". Shared with the Home gauge caption.
 */
fun provenanceLabel(assessment: RiskAssessment, now: Long = System.currentTimeMillis()): String {
    val age = assessment.dataAgeMs(now)?.let { formatAge(it) }
    return when (val provenance = assessment.provenance) {
        is RiskProvenance.Local ->
            if (age == null) {
                "Computed on this device from terrain only - no weather data"
            } else {
                "Fetched on this device $age ago"
            }
        is RiskProvenance.Relayed -> buildString {
            append("Relayed from ")
            append(provenance.sourceDeviceName)
            append(", ")
            append(if (provenance.hops == 1) "1 hop" else "${provenance.hops} hops")
            provenance.distanceKm?.let { append(", %.0f km away".format(it)) }
            append(if (age == null) ", no dated source data" else ", data $age old")
        }
    }
}

/** Compact data age: "12 min", "3 hours", "2 days". */
fun formatAge(ageMs: Long): String {
    val minutes = ageMs / 60_000L
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes min"
        minutes < 60 * 48 -> {
            val hours = minutes / 60
            if (hours == 1L) "1 hour" else "$hours hours"
        }
        else -> {
            val days = minutes / (60 * 24)
            if (days == 1L) "1 day" else "$days days"
        }
    }
}

private fun confidenceExplanation(assessment: RiskAssessment, now: Long): String {
    val missing = assessment.indicators.count { !it.isAvailable }
    val ageMs = assessment.dataAgeMs(now)
    val agePart = when {
        ageMs == null ->
            "No weather data was available at all, so this reflects local terrain only and " +
                "cannot be reported above Medium."
        ageMs < 60 * 60 * 1000L -> "The data behind it is current."
        ageMs < 6 * 60 * 60 * 1000L -> "The data behind it is a few hours old, which lowers confidence."
        ageMs < 24 * 60 * 60 * 1000L -> "The data behind it is most of a day old - treat this as indicative only."
        else -> "The data behind it is over a day old. This is a stale reading, not a current warning."
    }
    val missingPart = when (missing) {
        0 -> "All five indicators had data."
        1 -> "One indicator had no data, so its weight was redistributed."
        else -> "$missing indicators had no data, so their weight was redistributed."
    }
    return "$agePart $missingPart"
}

@Composable
private fun confidenceColor(confidence: Double): Color {
    val extra = MaterialTheme.appExtraColors
    return when {
        confidence >= 0.7 -> extra.statusOnline
        confidence >= 0.4 -> extra.severityMedium
        else -> extra.severityHigh
    }
}

/**
 * Temperature and humidity at the assessed point.
 *
 * Both were already being fetched for every forecast and then discarded.
 * Humidity is the one number on this screen a person can sanity-check against
 * what they can feel outside, which is worth more than it looks on a screen
 * otherwise full of derived values.
 */
@Composable
private fun CurrentConditionsCard(assessment: RiskAssessment) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(Dimens.cardPadding),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            assessment.currentTemperatureC?.let { temperature ->
                MetricStat("%.0f°C".format(temperature), "Temperature", Modifier.weight(1f))
            }
            assessment.currentHumidityPct?.let { humidity ->
                MetricStat("$humidity%", "Humidity", Modifier.weight(1f))
            }
            val rainNow = assessment.rainfall24h.firstOrNull()?.precipitationMm
            if (rainNow != null) {
                MetricStat("%.1f mm".format(rainNow), "Rain this hour", Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Indicators
// ---------------------------------------------------------------------------

@Composable
private fun IndicatorRow(indicator: RiskIndicator) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        indicator.title,
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    // The hydrological name, kept so the model stays inspectable
                    // even though the headline is now plain language.
                    Text(
                        indicator.id.technicalName,
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.appExtraColors.textTertiary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    if (indicator.isAvailable) "${(indicator.score * 100).toInt()}%" else "n/a",
                    style = AppTypography.titleSmall,
                    color = if (indicator.isAvailable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.appExtraColors.textTertiary
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            ScoreBar(score = if (indicator.isAvailable) indicator.score else 0.0)

            Spacer(Modifier.height(10.dp))
            Text(
                indicator.rawLabel,
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                indicator.explanation,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal 0..1 bar. Colour steps with the value so a high indicator reads as high at a glance. */
@Composable
private fun ScoreBar(score: Double) {
    val extra = MaterialTheme.appExtraColors
    val fill = when {
        score >= 0.75 -> extra.severityCritical
        score >= 0.5 -> extra.severityHigh
        score >= 0.25 -> extra.severityMedium
        else -> extra.severityLow
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(score.coerceIn(0.0, 1.0).toFloat())
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(fill),
        )
    }
}

// ---------------------------------------------------------------------------
// Charts
// ---------------------------------------------------------------------------

private val CHART_HEIGHT = 150.dp

/**
 * Hourly rainfall as bars, with this terrain's own 1-hour threshold drawn
 * across it.
 *
 * The threshold line is the point of the chart: raw millimetres mean little on
 * their own, but "the forecast bars are approaching the line where this terrain
 * floods" is immediately readable, and it is drawn from the local catchment
 * class rather than a national constant.
 */
@Composable
private fun RainfallChart(points: List<HourlyRainPoint>, thresholdMm: Double, now: Long) {
    if (points.isEmpty()) {
        EmptyChart("No rainfall forecast available for this location.")
        return
    }

    val maxRain = points.maxOf { it.precipitationMm }
    // Scale so the bars stay readable; the threshold line is only drawn when it
    // fits, and is stated in text either way rather than being silently omitted.
    val yMax = maxOf(maxRain * 1.15, 1.0)
    val isThresholdOnChart = thresholdMm <= yMax

    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thresholdColor = MaterialTheme.appExtraColors.severityHigh

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clip(RoundedCornerShape(Dimens.cardRadius))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val barSlot = size.width / points.size
                val barWidth = (barSlot * 0.62f).coerceAtLeast(1f)

                points.forEachIndexed { i, point ->
                    val fraction = (point.precipitationMm / yMax).toFloat().coerceIn(0f, 1f)
                    val barHeight = size.height * fraction
                    val left = i * barSlot + (barSlot - barWidth) / 2f
                    // Track behind every bar, so hours with no rain still read
                    // as "measured, zero" rather than as a gap in the data.
                    drawRect(
                        color = trackColor,
                        topLeft = Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                    )
                    if (barHeight > 0f) {
                        drawRect(
                            color = barColor,
                            topLeft = Offset(left, size.height - barHeight),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        )
                    }
                }

                if (isThresholdOnChart) {
                    val y = size.height - (size.height * (thresholdMm / yMax).toFloat())
                    drawLine(
                        color = thresholdColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MicroLabel("Now")
            MicroLabel("+${points.size / 2}h")
            MicroLabel("+${points.size}h")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append("Peak %.1f mm in one hour. ".format(maxRain))
                append("Dashed line: the %.0f mm/h threshold for this terrain".format(thresholdMm))
                append(if (isThresholdOnChart) "." else " (above the top of this chart).")
            },
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * River discharge over time against its long-term mean.
 *
 * The mean line is what makes the series interpretable: an absolute discharge
 * figure says nothing without knowing what is normal for that river.
 */
@Composable
private fun DischargeChart(points: List<DischargePoint>) {
    if (points.size < 2) {
        EmptyChart("No modelled river reach near this location, so no discharge trend is available.")
        return
    }

    val values = points.map { it.dischargeM3s }
    val meanValues = points.mapNotNull { it.meanM3s }
    val maxValue = maxOf(values.max(), meanValues.maxOrNull() ?: 0.0) * 1.15
    val minValue = minOf(values.min(), meanValues.minOrNull() ?: values.min()) * 0.85
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    val lineColor = MaterialTheme.colorScheme.primary
    val meanColor = MaterialTheme.appExtraColors.textTertiary

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clip(RoundedCornerShape(Dimens.cardRadius))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width
                fun yFor(value: Double): Float =
                    size.height - (((value - minValue) / span).toFloat().coerceIn(0f, 1f) * size.height)

                // Mean reference line first, so the discharge trace sits over it.
                meanValues.averageOrNull()?.let { mean ->
                    val y = yFor(mean)
                    drawLine(
                        color = meanColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                }

                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = lineColor,
                        start = Offset(i * stepX, yFor(values[i])),
                        end = Offset((i + 1) * stepX, yFor(values[i + 1])),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Latest %.1f m³/s against a mean of %s. Dashed line: the long-term mean for this reach."
                .format(values.last(), meanValues.averageOrNull()?.let { "%.1f m³/s".format(it) } ?: "unknown"),
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

@Composable
private fun EmptyChart(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Dimens.cardPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = AppTypography.bodySmall,
            color = MaterialTheme.appExtraColors.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Terrain, nearby areas, model description
// ---------------------------------------------------------------------------

@Composable
private fun TerrainCard(assessment: RiskAssessment) {
    val terrainClass = assessment.terrainClass
    val curve = terrainClass.thresholdCurve
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(terrainClass.label, style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                assessment.terrainSummary,
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                terrainClass.description + ".",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
            Text(
                "Rainfall thresholds used here: %.0f mm in 1 h, %.0f mm in 3 h, %.0f mm in 6 h. "
                    .format(curve.oneHourMm, curve.threeHourMm, curve.sixHourMm) +
                    "Water typically peaks about ${formatHours(terrainClass.responseTimeHours)} after heavy rain starts.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NearbyAreasSection(nearbyAreas: List<RiskAssessment>, now: Long) {
    if (nearbyAreas.isEmpty()) return
    SectionHeading("Nearby areas")
    Spacer(Modifier.height(8.dp))
    Text(
        "Assessments from other devices that are too far away or too old to apply here. " +
            "Shown as context only - they are not your local risk.",
        style = AppTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Dimens.cardSpacing))
    nearbyAreas.forEach { area ->
        AppCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(Dimens.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        area.headline,
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        provenanceLabel(area, now),
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.appExtraColors.textTertiary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SeverityBadge(area.severity)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * States plainly what the model is. Required, not optional: presenting a
 * rule-based score as a prediction from a trained model would be a lie, and an
 * uncalibrated model presented as validated would be a dangerous one.
 */
@Composable
private fun ModelDescriptionCard() {
    NoticeCard(
        icon = Icons.Filled.Info,
        title = "How this model works",
        body = "This is a physically-grounded rule-based model, not a machine-learning prediction. " +
            "It combines rainfall intensity against local terrain thresholds, soil saturation, " +
            "the past week's rainfall, river discharge trend and catchment steepness into a " +
            "weighted score, entirely on this device. Every input and weight is shown above. " +
            "The thresholds are engineering estimates and have not yet been calibrated against " +
            "recorded flood events, so treat this as decision support, not an official warning.",
    )
}
