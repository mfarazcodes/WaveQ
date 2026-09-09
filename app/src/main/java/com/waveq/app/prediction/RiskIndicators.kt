package com.waveq.app.prediction

import kotlin.math.abs
import kotlin.math.pow

/**
 * The five physical indicators the flash-flood risk score is built from.
 *
 * Each one answers a different physical question, and each is reduced to a
 * normalised 0..1 factor so they can be weighted against each other - but each
 * also keeps its raw value and a plain-language sentence, because a score with
 * no visible working is not defensible to a user or to a reviewer.
 *
 * Nothing here is machine-learned. Every relationship below is an explicit,
 * inspectable rule with a stated physical justification. See [RiskEngine] for
 * where a learned refinement layer would slot in later.
 *
 * ## Calibration status
 *
 * As in [TerrainProfile], **every normalisation constant in this file is an
 * estimate**, chosen so the indicator saturates at a physically sensible point
 * and moves in the correct direction. None is fitted to observed Indian
 * flash-flood events. Search for "CALIBRATE" to find each one.
 */

/**
 * Stable identity for each indicator - used by the UI and by the mesh wire format.
 *
 * [title] is what a person reads under stress, so it is plain language. The
 * hydrological name is kept in [technicalName] and shown as a subtitle: the
 * model's defensibility depends on a reviewer being able to see what each
 * indicator actually is, and "Recent rainfall" alone does not say "exponentially
 * decay-weighted seven-day antecedent precipitation index".
 */
enum class IndicatorId(val title: String, val technicalName: String) {
    ANTECEDENT_PRECIPITATION("Recent rainfall", "Antecedent Precipitation Index (7-day, decay-weighted)"),
    RAINFALL_INTENSITY("Heavy rain forecast", "Rainfall intensity vs local intensity-duration threshold"),
    SOIL_SATURATION("Ground already soaked", "Soil moisture saturation (0-28 cm, depth-weighted)"),
    DISCHARGE_TREND("River rising", "River discharge rate of change vs long-term mean"),
    TERRAIN_AMPLIFICATION("Steep terrain", "Terrain amplification from catchment class and slope"),
}

/**
 * One computed indicator.
 *
 * [rawValue]/[rawLabel] are the measurement as it actually came out; [score] is
 * that measurement mapped onto 0..1; [explanation] is the one-line plain
 * sentence shown in the UI. When [isAvailable] is false the indicator could not
 * be computed at all - its weight is redistributed and confidence drops, rather
 * than a zero being quietly folded into the score as if it were a measurement
 * of "no risk".
 */
data class RiskIndicator(
    val id: IndicatorId,
    val rawValue: Double?,
    val rawLabel: String,
    val score: Double,
    val explanation: String,
    val isAvailable: Boolean,
) {
    val title: String get() = id.title

    companion object {
        fun unavailable(id: IndicatorId, why: String) = RiskIndicator(
            id = id,
            rawValue = null,
            rawLabel = "No data",
            score = 0.0,
            explanation = why,
            isAvailable = false,
        )
    }
}

private const val MS_PER_HOUR = 60 * 60 * 1000L
private const val MS_PER_DAY = 24 * MS_PER_HOUR

// ---------------------------------------------------------------------------
// 1. Antecedent Precipitation Index
// ---------------------------------------------------------------------------

/**
 * CALIBRATE: daily decay constant for the antecedent precipitation index.
 *
 * 0.90 sits in the middle of the 0.85-0.95 range used for API in operational
 * hydrology, and means rainfall from a week ago still carries ~48% of the
 * weight of yesterday's. Higher values suit cool, slow-draining catchments;
 * lower values suit hot ones with high evapotranspiration. A single national
 * value is a simplification - this should eventually vary with season and
 * terrain class.
 */
private const val API_DECAY_PER_DAY = 0.90

/**
 * CALIBRATE: the API value treated as "catchment fully primed", i.e. where this
 * indicator saturates at 1.0.
 *
 * 100 mm of decay-weighted rainfall is roughly a week of steady moderate
 * monsoon rain. Past this point the soil store is assumed full enough that
 * additional antecedent rain adds little to the runoff response, which is why
 * the indicator caps rather than continuing to climb.
 */
private const val API_SATURATION_MM = 100.0

private const val API_LOOKBACK_DAYS = 7

/**
 * Antecedent Precipitation Index: how much rain the catchment has already
 * absorbed over the past week, exponentially weighted so recent rain counts
 * for more.
 *
 * This is what separates "50 mm falling on dry ground" from "50 mm falling on
 * ground that has been soaked for five days" - the same rainfall, wildly
 * different flood outcomes, because a primed catchment has no storage left and
 * converts far more of the rain straight into runoff.
 */
fun computeAntecedentPrecipitation(forecast: ForecastSnapshot?, now: Long): RiskIndicator {
    if (forecast == null) {
        return RiskIndicator.unavailable(
            IndicatorId.ANTECEDENT_PRECIPITATION,
            "No rainfall history available for this area.",
        )
    }

    val dailyTotals = DoubleArray(API_LOOKBACK_DAYS)
    var sawAnyReading = false
    forecast.timesUtcMs.forEachIndexed { i, time ->
        if (time > now) return@forEachIndexed
        val daysAgo = ((now - time) / MS_PER_DAY).toInt()
        if (daysAgo !in 0 until API_LOOKBACK_DAYS) return@forEachIndexed
        val mm = forecast.precipitationMm.getOrNull(i) ?: return@forEachIndexed
        dailyTotals[daysAgo] += mm
        sawAnyReading = true
    }

    if (!sawAnyReading) {
        return RiskIndicator.unavailable(
            IndicatorId.ANTECEDENT_PRECIPITATION,
            "No rainfall history available for this area.",
        )
    }

    // API = sum over days of (decay ^ days_ago) * rainfall_that_day.
    var index = 0.0
    for (day in 0 until API_LOOKBACK_DAYS) {
        index += API_DECAY_PER_DAY.pow(day + 1) * dailyTotals[day]
    }

    val rawTotal = dailyTotals.sum()
    val score = (index / API_SATURATION_MM).coerceIn(0.0, 1.0)
    val explanation = when {
        score >= 0.75 -> "The ground here has taken heavy rain over the past week and has little room left to absorb more."
        score >= 0.4 -> "Moderate rain over the past week has partly wet the ground already."
        score >= 0.15 -> "Only light rain in the past week - the ground can still absorb a fair amount."
        else -> "The past week has been largely dry, so the ground can soak up a lot of new rain."
    }

    return RiskIndicator(
        id = IndicatorId.ANTECEDENT_PRECIPITATION,
        rawValue = index,
        rawLabel = "%.0f mm weighted (%.0f mm total over 7 days)".format(index, rawTotal),
        score = score,
        explanation = explanation,
        isAvailable = true,
    )
}

// ---------------------------------------------------------------------------
// 2. Rainfall intensity vs the terrain's duration threshold
// ---------------------------------------------------------------------------

/**
 * How far ahead forecast rainfall is scanned when looking for a threshold
 * breach. 24 h matches the chart shown on the detail screen and comfortably
 * exceeds every terrain response time except the plains.
 */
const val INTENSITY_FORECAST_HORIZON_HOURS = 24

/**
 * Result of the intensity comparison. Carries more than the indicator itself
 * because [RiskEngine] needs the *timing* of the worst window to produce a lead
 * time - the indicator score alone says how bad, not when.
 */
data class RainfallIntensityResult(
    val indicator: RiskIndicator,
    /** Worst accumulation-to-threshold ratio found in the horizon; 1.0 means the threshold is exactly met. */
    val worstRatio: Double,
    /** Which duration window (1, 3 or 6 h) produced [worstRatio]. */
    val worstWindowHours: Int,
    /** Hours from now until the start of the first window that reaches the threshold, or null if none does. */
    val hoursUntilThresholdBreach: Double?,
)

/**
 * Compares forecast rainfall against this terrain's intensity-duration
 * threshold curve over 1 h, 3 h and 6 h windows.
 *
 * This is the indicator that carries the most weight, because short-window
 * intensity is what actually distinguishes a flash flood from ordinary
 * seasonal flooding - and it is the one indicator that is meaningfully local,
 * since the same 60 mm is routine on a plain and catastrophic in a steep
 * valley. The threshold it is measured against comes from [CatchmentClass],
 * not from a national constant.
 *
 * The indicator reaches 1.0 when the forecast merely *meets* the threshold,
 * rather than at some multiple of it: the threshold is defined as the onset
 * condition for flash flooding in that terrain, so meeting it is already the
 * top of this indicator's scale.
 */
fun computeRainfallIntensity(
    forecast: ForecastSnapshot?,
    terrain: TerrainProfile?,
    now: Long,
): RainfallIntensityResult {
    val unavailable = { why: String ->
        RainfallIntensityResult(
            indicator = RiskIndicator.unavailable(IndicatorId.RAINFALL_INTENSITY, why),
            worstRatio = 0.0,
            worstWindowHours = 1,
            hoursUntilThresholdBreach = null,
        )
    }

    if (forecast == null) return unavailable("No rainfall forecast available for this area.")
    if (terrain == null) return unavailable("Local terrain is unknown, so no rainfall threshold can be applied.")

    // Future hours only, in order, each with its offset from now.
    val future = forecast.timesUtcMs.mapIndexedNotNull { i, time ->
        val offsetHours = ((time - now).toDouble() / MS_PER_HOUR)
        if (offsetHours < -1.0 || offsetHours > INTENSITY_FORECAST_HORIZON_HOURS) {
            null
        } else {
            offsetHours to forecast.precipitationMm.getOrNull(i)
        }
    }
    if (future.none { it.second != null }) return unavailable("No rainfall forecast available for this area.")

    val curve = terrain.catchmentClass.thresholdCurve
    var worstRatio = 0.0
    var worstWindow = 1
    var worstAccumulation = 0.0
    var worstThreshold = curve.oneHourMm
    var firstBreachAtHours: Double? = null

    for (window in RainfallThresholdCurve.WINDOWS_HOURS) {
        val threshold = curve.thresholdFor(window)
        // Rolling accumulation: every start position within the horizon.
        for (start in 0..(future.size - window).coerceAtLeast(0)) {
            val slice = future.drop(start).take(window)
            if (slice.size < window) break
            if (slice.all { it.second == null }) continue
            val accumulation = slice.sumOf { it.second ?: 0.0 }
            val ratio = accumulation / threshold
            if (ratio > worstRatio) {
                worstRatio = ratio
                worstWindow = window
                worstAccumulation = accumulation
                worstThreshold = threshold
            }
            if (ratio >= 1.0) {
                val startsInHours = slice.first().first.coerceAtLeast(0.0)
                if (firstBreachAtHours == null || startsInHours < firstBreachAtHours!!) {
                    firstBreachAtHours = startsInHours
                }
            }
        }
    }

    val score = worstRatio.coerceIn(0.0, 1.0)
    val explanation = when {
        worstRatio >= 1.0 ->
            "Forecast rain of %.0f mm in %d h reaches the %s threshold of %.0f mm for this terrain."
                .format(worstAccumulation, worstWindow, terrain.catchmentClass.label.lowercase(), worstThreshold)
        worstRatio >= 0.6 ->
            "Forecast rain of %.0f mm in %d h is approaching the %.0f mm threshold for this terrain."
                .format(worstAccumulation, worstWindow, worstThreshold)
        worstRatio > 0.0 ->
            "Forecast rain of %.0f mm in %d h stays well under the %.0f mm threshold for this terrain."
                .format(worstAccumulation, worstWindow, worstThreshold)
        else -> "No meaningful rainfall is forecast in the next %d hours.".format(INTENSITY_FORECAST_HORIZON_HOURS)
    }

    return RainfallIntensityResult(
        indicator = RiskIndicator(
            id = IndicatorId.RAINFALL_INTENSITY,
            rawValue = worstRatio,
            rawLabel = "%.0f mm / %.0f mm over %d h".format(worstAccumulation, worstThreshold, worstWindow),
            score = score,
            explanation = explanation,
            isAvailable = true,
        ),
        worstRatio = worstRatio,
        worstWindowHours = worstWindow,
        hoursUntilThresholdBreach = firstBreachAtHours,
    )
}

// ---------------------------------------------------------------------------
// 3. Soil moisture saturation
// ---------------------------------------------------------------------------

/**
 * CALIBRATE: volumetric water content (m³/m³) taken as the wilting point and as
 * saturation for the soil column.
 *
 * 0.12 and 0.45 are textbook values for a medium-textured loam, which is a
 * reasonable national default but genuinely wrong for both the black cotton
 * soils of the Deccan (which hold far more water) and coarse Himalayan
 * moraine (which holds far less). Proper calibration needs a soil-texture
 * lookup by coordinate.
 */
private const val SOIL_WILTING_POINT = 0.12
private const val SOIL_SATURATION_POINT = 0.45

/**
 * Layer thicknesses in cm, used to depth-weight the two reported layers into
 * one column value. The 7-28 cm layer is three times thicker than the 0-7 cm
 * layer and therefore dominates the column's remaining storage capacity.
 */
private const val SOIL_LAYER_1_CM = 7.0
private const val SOIL_LAYER_2_CM = 21.0

/**
 * How close the soil column already is to saturation.
 *
 * This matters more than almost anything else for flash flood onset: dry soil
 * absorbs most of a downpour, while soil at saturation converts nearly all of
 * it directly to surface runoff. It is the physical mechanism by which day
 * three of a rain event floods when days one and two did not, at identical
 * rainfall.
 */
fun computeSoilSaturation(forecast: ForecastSnapshot?, now: Long): RiskIndicator {
    if (forecast == null) {
        return RiskIndicator.unavailable(IndicatorId.SOIL_SATURATION, "No soil moisture data available for this area.")
    }
    val index = forecast.indexOfNow(now)
    if (index < 0) {
        return RiskIndicator.unavailable(IndicatorId.SOIL_SATURATION, "No soil moisture data available for this area.")
    }

    val shallow = forecast.soilMoisture0To7.getOrNull(index)
    val deep = forecast.soilMoisture7To28.getOrNull(index)
    if (shallow == null && deep == null) {
        return RiskIndicator.unavailable(IndicatorId.SOIL_SATURATION, "No soil moisture data available for this area.")
    }

    // Depth-weighted column average; falls back to whichever layer is present.
    val theta = when {
        shallow != null && deep != null ->
            (shallow * SOIL_LAYER_1_CM + deep * SOIL_LAYER_2_CM) / (SOIL_LAYER_1_CM + SOIL_LAYER_2_CM)
        shallow != null -> shallow
        else -> deep!!
    }

    val score = ((theta - SOIL_WILTING_POINT) / (SOIL_SATURATION_POINT - SOIL_WILTING_POINT))
        .coerceIn(0.0, 1.0)
    val percentOfCapacity = score * 100.0
    val explanation = when {
        score >= 0.85 -> "The soil is close to saturated - almost all new rain will run off instead of soaking in."
        score >= 0.6 -> "The soil is quite wet, so a large share of new rain will run off rather than soak in."
        score >= 0.3 -> "The soil holds moderate moisture and can still absorb some rain."
        else -> "The soil is dry and can absorb most of the rain that falls on it."
    }

    return RiskIndicator(
        id = IndicatorId.SOIL_SATURATION,
        rawValue = theta,
        rawLabel = "%.2f m³/m³ (%.0f%% of capacity)".format(theta, percentOfCapacity),
        score = score,
        explanation = explanation,
        isAvailable = true,
    )
}

// ---------------------------------------------------------------------------
// 4. River discharge rate of change
// ---------------------------------------------------------------------------

/**
 * CALIBRATE: the rise, as a fraction of the reach's mean discharge per day, at
 * which the rate-of-change component saturates.
 *
 * A river gaining half its own long-term mean flow in a day is rising sharply
 * by any standard. This is the quantity that distinguishes a river already in
 * flood but stable from one climbing fast, which is why it is weighted above
 * the absolute level below.
 */
private const val DISCHARGE_RISE_FULL_SCALE = 0.5

/** CALIBRATE: multiple of mean discharge at which the absolute-level component saturates. */
private const val DISCHARGE_LEVEL_FULL_SCALE = 2.5

/** Rate of change dominates: a rising river is the warning, a high steady one is the existing condition. */
private const val DISCHARGE_RISE_WEIGHT = 0.7
private const val DISCHARGE_LEVEL_WEIGHT = 0.3

/**
 * How fast the nearest modelled river reach is rising, relative to its own
 * long-term mean flow.
 *
 * Measuring the rate rather than the level is deliberate. A large river sitting
 * permanently at a high absolute discharge is not news; the same river gaining
 * a third of its mean flow in a day is. Normalising by the reach's own mean
 * also makes the number comparable between a Himalayan torrent and the
 * Brahmaputra, which differ by orders of magnitude in absolute terms.
 */
fun computeDischargeTrend(flood: FloodSnapshot?, now: Long): RiskIndicator {
    if (flood == null) {
        return RiskIndicator.unavailable(
            IndicatorId.DISCHARGE_TREND,
            "No modelled river reach near this location, so river levels are not part of this score.",
        )
    }
    val index = flood.indexOfNow(now)
    if (index < 0) {
        return RiskIndicator.unavailable(
            IndicatorId.DISCHARGE_TREND,
            "No modelled river reach near this location, so river levels are not part of this score.",
        )
    }

    val current = flood.dischargeM3s.getOrNull(index)
    val mean = flood.dischargeMeanM3s.getOrNull(index)
        ?: flood.dischargeMeanM3s.filterNotNull().takeIf { it.isNotEmpty() }?.average()
    if (current == null || mean == null || mean <= 0.0) {
        return RiskIndicator.unavailable(
            IndicatorId.DISCHARGE_TREND,
            "No modelled river reach near this location, so river levels are not part of this score.",
        )
    }

    // Rise measured against the most recent prior day that has a reading.
    val previous = (index - 1 downTo 0).firstNotNullOfOrNull { flood.dischargeM3s.getOrNull(it) }
    val risePerDay = if (previous != null) current - previous else 0.0
    val riseRatio = risePerDay / mean
    val levelRatio = current / mean

    val riseScore = (riseRatio / DISCHARGE_RISE_FULL_SCALE).coerceIn(0.0, 1.0)
    val levelScore = ((levelRatio - 1.0) / (DISCHARGE_LEVEL_FULL_SCALE - 1.0)).coerceIn(0.0, 1.0)
    val score = (DISCHARGE_RISE_WEIGHT * riseScore + DISCHARGE_LEVEL_WEIGHT * levelScore).coerceIn(0.0, 1.0)

    val explanation = when {
        previous == null -> "River flow is %.0f%% of its usual level, but there is no recent history to judge a trend."
            .format(levelRatio * 100)
        riseRatio >= 0.25 -> "The nearby river is rising fast - up %.0f%% of its usual flow since yesterday."
            .format(abs(riseRatio) * 100)
        riseRatio > 0.05 -> "The nearby river is rising slowly, currently at %.0f%% of its usual flow."
            .format(levelRatio * 100)
        riseRatio < -0.05 -> "The nearby river is falling, currently at %.0f%% of its usual flow."
            .format(levelRatio * 100)
        else -> "The nearby river is steady at %.0f%% of its usual flow.".format(levelRatio * 100)
    }

    return RiskIndicator(
        id = IndicatorId.DISCHARGE_TREND,
        rawValue = riseRatio,
        rawLabel = "%.1f m³/s now, mean %.1f m³/s (%+.0f%%/day)".format(current, mean, riseRatio * 100),
        score = score,
        explanation = explanation,
        isAvailable = true,
    )
}

// ---------------------------------------------------------------------------
// 5. Terrain amplification
// ---------------------------------------------------------------------------

/**
 * CALIBRATE: slope in degrees at which the slope component of terrain
 * amplification saturates. 15° is steep hillslope - beyond it, runoff is
 * already essentially all rapid surface flow and further steepening changes
 * little about the response.
 */
private const val SLOPE_FULL_SCALE_DEG = 15.0

/** The response class captures catchment behaviour; measured slope refines it locally. */
private const val TERRAIN_CLASS_WEIGHT = 0.7
private const val TERRAIN_SLOPE_WEIGHT = 0.3

/**
 * How much this terrain amplifies a given amount of rain into a flood wave.
 *
 * Unlike the other four indicators this one is effectively constant for a
 * location - it is the standing multiplier that says the same storm is simply
 * more dangerous here than there. It is weighted lowest of the five for exactly
 * that reason: it explains why a place is vulnerable, not whether today is the
 * day.
 */
fun computeTerrainAmplification(terrain: TerrainProfile?): RiskIndicator {
    if (terrain == null) {
        return RiskIndicator.unavailable(
            IndicatorId.TERRAIN_AMPLIFICATION,
            "Local terrain has not been measured yet - connect to the internet once to profile this area.",
        )
    }

    val classScore = terrain.catchmentClass.terrainAmplification
    val slopeScore = (terrain.slopeDegrees / SLOPE_FULL_SCALE_DEG).coerceIn(0.0, 1.0)
    val score = (TERRAIN_CLASS_WEIGHT * classScore + TERRAIN_SLOPE_WEIGHT * slopeScore).coerceIn(0.0, 1.0)

    val responseTime = terrain.catchmentClass.responseTimeHours
    val explanation = buildString {
        append(terrain.catchmentClass.description)
        append(", so water typically peaks about ")
        append(formatHours(responseTime))
        append(" after heavy rain begins.")
        if (terrain.isSlopeAssumed) append(" Slope could not be measured here, so this is based on elevation alone.")
    }

    return RiskIndicator(
        id = IndicatorId.TERRAIN_AMPLIFICATION,
        rawValue = terrain.slopeDegrees,
        rawLabel = terrain.summary(),
        score = score,
        explanation = explanation,
        isAvailable = true,
    )
}

/** Shared hour formatting - "45 minutes", "2 hours", "2.5 hours". */
fun formatHours(hours: Double): String = when {
    hours < 1.0 -> "${(hours * 60).toInt()} minutes"
    hours < 1.5 -> "1 hour"
    abs(hours - hours.toInt()) < 0.1 -> "${hours.toInt()} hours"
    else -> "%.1f hours".format(hours)
}
