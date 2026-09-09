package com.waveq.app.prediction

import com.waveq.app.ui.components.Severity
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The flash-flood risk engine.
 *
 * ## What this is, and what it is not
 *
 * This is a **physically-grounded rule-based engine**, not a machine-learned
 * model. Every number it produces traces back through an explicit weighted sum
 * of five inspectable indicators to a documented physical relationship and a
 * named constant. Nothing in this release is trained, fitted, or inferred from
 * data. The UI says so in those words, deliberately: a rule-based model whose
 * working is visible is defensible, while an unvalidated "AI prediction" is
 * not, and in a life-safety context claiming the latter would be dishonest.
 *
 * The engine is nevertheless structured so a learned refinement layer can be
 * added later behind the same interface - see [RiskRefinement] and the TODO in
 * [assess].
 *
 * ## Calibration status
 *
 * The weights and cutoffs below are reasoned, documented and internally
 * consistent, but like every constant in [TerrainProfile] and [RiskIndicators]
 * they are **not calibrated against observed flash-flood events**. They must be
 * validated against historical events before this drives real evacuation
 * decisions.
 */

// ---------------------------------------------------------------------------
// Output types
// ---------------------------------------------------------------------------

/** One hour of the rainfall forecast, for the 24 h chart on the detail screen. */
data class HourlyRainPoint(
    val timeUtcMs: Long,
    val precipitationMm: Double,
    val probabilityPct: Int?,
)

/** One day of river discharge, for the trend chart on the detail screen. */
data class DischargePoint(
    val timeUtcMs: Long,
    val dischargeM3s: Double,
    val meanM3s: Double?,
)

/**
 * Where an assessment came from.
 *
 * This is surfaced everywhere the score is, because in an emergency the user
 * needs to know how much to trust what they are looking at: a reading this
 * device fetched twelve minutes ago and a reading relayed twice across the mesh
 * from a device 20 km away are not the same claim.
 */
sealed class RiskProvenance {
    /** Computed on this device from data it fetched itself. */
    data object Local : RiskProvenance()

    /** Received over the mesh from another device that had connectivity. */
    data class Relayed(
        val sourceDeviceId: String,
        val sourceDeviceName: String,
        val hops: Int,
        val distanceKm: Double?,
    ) : RiskProvenance()
}

/**
 * A complete risk assessment: the score, how it was reached, how old its inputs
 * are, and how much to trust it.
 *
 * [dataFetchedAt] is the timestamp of the *source data*, not of the
 * computation. That distinction is the whole basis of honest confidence
 * reporting across the mesh - an assessment relayed twice still reports the
 * moment its inputs were fetched, so confidence degrades from the real age of
 * the evidence rather than being reset by each relay hop.
 */
data class RiskAssessment(
    val assessmentId: String,
    val score: Int,
    val severity: Severity,
    /** Estimated hours until flooding could begin, or null when nothing is expected. */
    val leadTimeHours: Double?,
    /** 0..1. Degrades with data age and with missing inputs. */
    val confidence: Double,
    val indicators: List<RiskIndicator>,
    val latitude: Double,
    val longitude: Double,
    val terrainClass: CatchmentClass,
    val terrainSummary: String,
    /**
     * True when the terrain slope behind this assessment was assumed rather
     * than measured. Carried explicitly (and over the wire) because confidence
     * is recomputed on every relay hop and must not have to re-derive this by
     * pattern-matching the summary text.
     */
    val isTerrainAssumed: Boolean,
    /** When this assessment was computed. */
    val computedAt: Long,
    /**
     * When the *source data* behind it was fetched. The basis for confidence and
     * freshness.
     *
     * Null when no dated input existed at all - a terrain-only assessment. It
     * previously fell back to "now", which made an assessment built from no
     * weather data whatsoever report itself as perfectly fresh.
     */
    val dataFetchedAt: Long?,
    val rainfall24h: List<HourlyRainPoint>,
    val dischargeTrend: List<DischargePoint>,
    /**
     * Conditions right now at the assessed point.
     *
     * temperature_2m and relative_humidity_2m were already being fetched by
     * DataSources for every forecast and then never shown to anyone. Humidity in
     * particular is what a person can check against what they can feel, which
     * makes the rest of the screen easier to trust.
     */
    val currentTemperatureC: Double?,
    val currentHumidityPct: Int?,
    val provenance: RiskProvenance,
    /** One-line summary suitable for a notification or the gauge caption. */
    val headline: String,
) {
    /** Age of the underlying data in milliseconds as of [now], or null when there was no dated input. */
    fun dataAgeMs(now: Long = System.currentTimeMillis()): Long? =
        dataFetchedAt?.let { (now - it).coerceAtLeast(0L) }

    val isCritical: Boolean get() = severity == Severity.CRITICAL

    /** Where the score sits on the 0..1 gauge arc. */
    fun gaugeFraction(): Float = (score / 100f).coerceIn(0f, 1f)

    /**
     * Recomputes confidence for the current moment from the ORIGINAL data
     * timestamp. Called when an assessment arrives over the mesh, and
     * periodically for a local one, so a two-hour-old assessment shows
     * honestly degraded confidence rather than the confidence it had when it
     * was computed.
     */
    fun withRecomputedConfidence(now: Long = System.currentTimeMillis()): RiskAssessment =
        copy(confidence = RiskEngine.confidenceFor(indicators, dataFetchedAt, isTerrainAssumed, now))
}

/** Everything the engine reads. Grouped so the refinement hook can see the same inputs the rules did. */
data class RiskInputs(
    val forecast: ForecastSnapshot?,
    val flood: FloodSnapshot?,
    val terrain: TerrainProfile?,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Hook for a future learned refinement layer.
 *
 * TODO(learned-layer): a trained model would slot in here, taking the
 * rule-based assessment plus its raw inputs and returning an adjusted one -
 * most likely nudging [RiskAssessment.score] and [RiskAssessment.confidence]
 * while leaving the indicator breakdown intact so the explanation shown to the
 * user stays truthful. Two hard requirements when that lands:
 *  1. the rule-based score must remain computable and displayable on its own,
 *     because it is what makes the output explainable and it must keep working
 *     when the model is unavailable; and
 *  2. the UI copy in RiskDetailScreen that currently says "rule-based, not
 *     machine-learned" must be updated in the same change - shipping a learned
 *     layer behind text claiming otherwise would be worse than shipping neither.
 */
interface RiskRefinement {
    suspend fun refine(base: RiskAssessment, inputs: RiskInputs): RiskAssessment
}

// ---------------------------------------------------------------------------
// The engine
// ---------------------------------------------------------------------------

object RiskEngine {

    // -- Weights -----------------------------------------------------------

    /**
     * Indicator weights, summing to 1.0.
     *
     * Rainfall intensity and soil saturation carry over half the total between
     * them because together they are the flash-flood onset mechanism: intense
     * rain falling on ground that cannot absorb it. The remaining three modify
     * that core signal - antecedent rain explains *why* the soil is in the
     * state it is, discharge confirms a response is already underway, and
     * terrain says how violently this landscape converts runoff into a flood
     * wave.
     *
     * Terrain is weighted lowest despite being the most location-specific input
     * because it is effectively constant: it explains why a place is vulnerable
     * in general, not whether today is the day. Its real influence enters the
     * model through the thresholds it sets for the rainfall indicator, not
     * through this weight.
     *
     * CALIBRATE: these are judgement-based relative weights, not fitted
     * coefficients.
     */
    private val WEIGHTS: Map<IndicatorId, Double> = mapOf(
        IndicatorId.RAINFALL_INTENSITY to 0.32,
        IndicatorId.SOIL_SATURATION to 0.26,
        IndicatorId.ANTECEDENT_PRECIPITATION to 0.16,
        IndicatorId.DISCHARGE_TREND to 0.14,
        IndicatorId.TERRAIN_AMPLIFICATION to 0.12,
    )

    // -- Severity cutoffs --------------------------------------------------

    /**
     * Score cutoffs on the 0-100 scale.
     *
     * CRITICAL at 75 is set where it is because CRITICAL is not just a label
     * here - it sounds a siren and takes over the screen. That action needs the
     * rainfall indicator to be at or near its threshold *and* at least one
     * other major indicator elevated; a single maxed-out indicator cannot reach
     * 75 on its own under the weights above. False sirens destroy the trust the
     * whole system depends on.
     *
     * [Severity.EVACUATE] is deliberately never produced by the model. An
     * evacuation order is a human decision made by an authority, not something
     * a rule-based engine on a phone gets to declare.
     */
    private const val CUTOFF_MEDIUM = 25
    private const val CUTOFF_HIGH = 50
    private const val CUTOFF_CRITICAL = 75

    // -- Escalation floor --------------------------------------------------

    /**
     * The rainfall-vs-threshold comparison must be available before a score may
     * be reported as HIGH or CRITICAL.
     *
     * [weightedScore] renormalises by whichever weight was actually available,
     * which is right for a score but wrong for an escalation: with only the
     * terrain indicator present, `weighted / availableWeight` is the terrain
     * score alone, scaled to 0-100. A steep catchment therefore reached 86-100
     * - CRITICAL, siren, full-screen takeover - from no weather data at all.
     * Terrain says a place is vulnerable in general; it can never say today is
     * the day, so on its own it must not sound anything.
     */
    private val ESCALATION_REQUIRED_INDICATOR = IndicatorId.RAINFALL_INTENSITY

    /**
     * Second condition on escalation: this fraction of the total indicator
     * weight must be available.
     *
     * 0.40 admits rainfall + terrain (0.44), which is the thinnest input set
     * that still contains a real weather measurement, and excludes every
     * combination that does not - terrain alone (0.12), terrain + discharge
     * (0.26). Set against the weights above, not fitted.
     */
    private const val MIN_AVAILABLE_WEIGHT_FOR_ESCALATION = 0.40

    /** Ceiling applied when the escalation conditions are not met: the top of MEDIUM. */
    private const val SCORE_CAP_WITHOUT_WEATHER = CUTOFF_HIGH - 1

    // -- Confidence --------------------------------------------------------

    /** Data younger than this is treated as fully fresh. Matches the refresh interval. */
    private const val CONFIDENCE_FRESH_MS = 60 * 60 * 1000L

    /** Age at which confidence has fallen to [CONFIDENCE_AT_STALE]. */
    private const val CONFIDENCE_STALE_MS = 12 * 60 * 60 * 1000L
    private const val CONFIDENCE_AT_STALE = 0.45

    /** Age at which confidence bottoms out. Three-day-old data can never look confident. */
    private const val CONFIDENCE_ANCIENT_MS = 72 * 60 * 60 * 1000L
    private const val CONFIDENCE_AT_ANCIENT = 0.10
    private const val CONFIDENCE_FLOOR = 0.05

    /**
     * Weight of input completeness in the confidence value. Even a fully
     * complete input set is capped by age; even a sparse one retains the 0.40
     * base, since three good indicators out of five is still a real signal.
     */
    private const val COMPLETENESS_BASE = 0.40

    /** Penalty applied when terrain slope was assumed rather than measured. */
    private const val ASSUMED_TERRAIN_CONFIDENCE_FACTOR = 0.85

    // -- Assessment --------------------------------------------------------

    /**
     * Computes a risk assessment from the given inputs.
     *
     * Every input is optional. Missing indicators have their weight
     * redistributed across the ones that are available rather than being
     * scored as zero - scoring a missing measurement as "no risk" would let a
     * failed fetch quietly suppress a real warning - and the resulting
     * incompleteness is reported through [RiskAssessment.confidence].
     */
    suspend fun assess(
        inputs: RiskInputs,
        now: Long = System.currentTimeMillis(),
        refinement: RiskRefinement? = null,
    ): RiskAssessment {
        val forecast = inputs.forecast
        val flood = inputs.flood
        val terrain = inputs.terrain

        val intensity = computeRainfallIntensity(forecast, terrain, now)
        val indicators = listOf(
            intensity.indicator,
            computeSoilSaturation(forecast, now),
            computeAntecedentPrecipitation(forecast, now),
            computeDischargeTrend(flood, now),
            computeTerrainAmplification(terrain),
        )

        val mayEscalate = mayEscalate(indicators)
        val rawScore = weightedScore(indicators)
        // Capped, not just clamped at the severity mapping, so the number the UI
        // prints and the severity it prints agree with each other.
        val score = if (mayEscalate) rawScore else minOf(rawScore, SCORE_CAP_WITHOUT_WEATHER)
        val severity = severityFor(score)
        val dataFetchedAt = oldestInputTimestamp(forecast, flood)
        val confidence = confidenceFor(indicators, dataFetchedAt, terrain?.isSlopeAssumed ?: true, now)
        val leadTime = leadTimeHours(intensity, terrain, severity)

        val base = RiskAssessment(
            assessmentId = UUID.randomUUID().toString(),
            score = score,
            severity = severity,
            leadTimeHours = leadTime,
            confidence = confidence,
            indicators = indicators,
            latitude = inputs.latitude,
            longitude = inputs.longitude,
            terrainClass = terrain?.catchmentClass ?: CatchmentClass.PLAIN,
            terrainSummary = terrain?.summary() ?: "Terrain not yet measured for this area",
            isTerrainAssumed = terrain?.isSlopeAssumed ?: true,
            computedAt = now,
            dataFetchedAt = dataFetchedAt,
            rainfall24h = extractRainfall24h(forecast, now),
            dischargeTrend = extractDischargeTrend(flood),
            currentTemperatureC = forecast?.let { it.temperatureC.getOrNull(it.indexOfNow(now)) },
            currentHumidityPct = forecast?.let { it.relativeHumidityPct.getOrNull(it.indexOfNow(now)) },
            provenance = RiskProvenance.Local,
            headline = headlineFor(severity, leadTime, mayEscalate),
        )

        // TODO(learned-layer): the trained refinement runs here, behind the same
        // interface, once there is validated training data to build it from.
        return refinement?.refine(base, inputs) ?: base
    }

    /**
     * Weighted sum over available indicators, renormalised by the weight that
     * was actually available, then scaled to 0-100.
     */
    private fun weightedScore(indicators: List<RiskIndicator>): Int {
        var weighted = 0.0
        var availableWeight = 0.0
        for (indicator in indicators) {
            if (!indicator.isAvailable) continue
            val weight = WEIGHTS[indicator.id] ?: continue
            weighted += weight * indicator.score.coerceIn(0.0, 1.0)
            availableWeight += weight
        }
        if (availableWeight <= 0.0) return 0
        return ((weighted / availableWeight) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * Whether this input set is strong enough for the score to be allowed above
     * MEDIUM. See [ESCALATION_REQUIRED_INDICATOR] and
     * [MIN_AVAILABLE_WEIGHT_FOR_ESCALATION].
     */
    private fun mayEscalate(indicators: List<RiskIndicator>): Boolean {
        val hasRainfall = indicators.any { it.id == ESCALATION_REQUIRED_INDICATOR && it.isAvailable }
        if (!hasRainfall) return false
        val totalWeight = WEIGHTS.values.sum()
        if (totalWeight <= 0.0) return false
        val availableWeight = indicators.filter { it.isAvailable }.sumOf { WEIGHTS[it.id] ?: 0.0 }
        return availableWeight / totalWeight >= MIN_AVAILABLE_WEIGHT_FOR_ESCALATION
    }

    /** Maps a 0-100 score onto the app's existing severity scale. */
    fun severityFor(score: Int): Severity = when {
        score >= CUTOFF_CRITICAL -> Severity.CRITICAL
        score >= CUTOFF_HIGH -> Severity.HIGH
        score >= CUTOFF_MEDIUM -> Severity.MEDIUM
        else -> Severity.LOW
    }

    /**
     * Confidence in 0..1, from the age of the source data and how complete the
     * input set was.
     *
     * The age term dominates by design. The requirement it exists to satisfy:
     * a score computed from three-day-old cached data must never be presented
     * confidently, no matter how complete or how alarming that stale data was.
     */
    fun confidenceFor(
        indicators: List<RiskIndicator>,
        dataFetchedAt: Long?,
        isTerrainAssumed: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Double {
        // No dated input at all: there is nothing here that can be called
        // current, so this sits at the floor rather than at full freshness.
        if (dataFetchedAt == null) {
            val noDataCompleteness = completenessFactor(indicators)
            val noDataTerrain = if (isTerrainAssumed) ASSUMED_TERRAIN_CONFIDENCE_FACTOR else 1.0
            return (CONFIDENCE_FLOOR * noDataCompleteness * noDataTerrain).coerceIn(0.0, 1.0)
        }
        val ageMs = (now - dataFetchedAt).coerceAtLeast(0L)
        val ageFactor = when {
            ageMs <= CONFIDENCE_FRESH_MS -> 1.0
            ageMs <= CONFIDENCE_STALE_MS -> lerp(
                1.0,
                CONFIDENCE_AT_STALE,
                (ageMs - CONFIDENCE_FRESH_MS).toDouble() / (CONFIDENCE_STALE_MS - CONFIDENCE_FRESH_MS),
            )
            ageMs <= CONFIDENCE_ANCIENT_MS -> lerp(
                CONFIDENCE_AT_STALE,
                CONFIDENCE_AT_ANCIENT,
                (ageMs - CONFIDENCE_STALE_MS).toDouble() / (CONFIDENCE_ANCIENT_MS - CONFIDENCE_STALE_MS),
            )
            else -> CONFIDENCE_FLOOR
        }

        val terrainFactor = if (isTerrainAssumed) ASSUMED_TERRAIN_CONFIDENCE_FACTOR else 1.0

        return (ageFactor * completenessFactor(indicators) * terrainFactor).coerceIn(0.0, 1.0)
    }

    private fun completenessFactor(indicators: List<RiskIndicator>): Double {
        val totalWeight = WEIGHTS.values.sum()
        val availableWeight = indicators.filter { it.isAvailable }.sumOf { WEIGHTS[it.id] ?: 0.0 }
        val completeness = if (totalWeight > 0) availableWeight / totalWeight else 0.0
        return COMPLETENESS_BASE + (1.0 - COMPLETENESS_BASE) * completeness
    }

    /**
     * Estimated hours until flooding could begin.
     *
     * Two terms: how long until the rain that breaches the threshold actually
     * falls, plus the catchment's own response time - the lag between that rain
     * and peak discharge. The second term is what makes this local: the same
     * forecast gives roughly an hour of warning in a steep valley and half a
     * day on a plain.
     */
    private fun leadTimeHours(
        intensity: RainfallIntensityResult,
        terrain: TerrainProfile?,
        severity: Severity,
    ): Double? {
        val responseTime = terrain?.catchmentClass?.responseTimeHours ?: return null
        intensity.hoursUntilThresholdBreach?.let { return it + responseTime }
        // No outright breach forecast, but the score is already elevated: the
        // best available estimate is the catchment's own response time.
        return if (severity == Severity.HIGH || severity == Severity.CRITICAL) responseTime else null
    }

    private fun headlineFor(severity: Severity, leadTimeHours: Double?, mayEscalate: Boolean): String = when {
        // Say plainly that this is terrain only, rather than letting a capped
        // MEDIUM read as a measured "conditions are building".
        !mayEscalate -> "No live weather data - this reflects local terrain only"
        leadTimeHours != null && (severity == Severity.CRITICAL || severity == Severity.HIGH) ->
            "Flooding possible in approximately ${formatHours(leadTimeHours)}"
        severity == Severity.MEDIUM && leadTimeHours != null ->
            "Conditions building - flooding possible in approximately ${formatHours(leadTimeHours)}"
        severity == Severity.MEDIUM -> "Conditions are building but no flooding is expected right now"
        else -> "No flash flood signal in the next ${INTENSITY_FORECAST_HORIZON_HOURS} hours"
    }

    /**
     * The assessment is only as fresh as its *oldest* input, so the whole thing
     * ages at the rate of whichever source was fetched longest ago.
     *
     * Returns null when there was no dated input at all. It used to fall back to
     * "now", which stamped a terrain-only assessment - built from no weather
     * data whatsoever - as freshly fetched, giving it full confidence and a
     * zero-age provenance label.
     */
    private fun oldestInputTimestamp(forecast: ForecastSnapshot?, flood: FloodSnapshot?): Long? =
        listOfNotNull(forecast?.fetchedAt, flood?.fetchedAt).minOrNull()

    private fun extractRainfall24h(forecast: ForecastSnapshot?, now: Long): List<HourlyRainPoint> {
        if (forecast == null) return emptyList()
        return forecast.timesUtcMs.mapIndexedNotNull { i, time ->
            val hoursAhead = (time - now).toDouble() / (60 * 60 * 1000L)
            if (hoursAhead < -1.0 || hoursAhead > INTENSITY_FORECAST_HORIZON_HOURS) return@mapIndexedNotNull null
            HourlyRainPoint(
                timeUtcMs = time,
                precipitationMm = forecast.precipitationMm.getOrNull(i) ?: 0.0,
                probabilityPct = forecast.precipitationProbabilityPct.getOrNull(i),
            )
        }
    }

    private fun extractDischargeTrend(flood: FloodSnapshot?): List<DischargePoint> {
        if (flood == null) return emptyList()
        return flood.timesUtcMs.mapIndexedNotNull { i, time ->
            val discharge = flood.dischargeM3s.getOrNull(i) ?: return@mapIndexedNotNull null
            DischargePoint(
                timeUtcMs = time,
                dischargeM3s = discharge,
                meanM3s = flood.dischargeMeanM3s.getOrNull(i),
            )
        }
    }

    private fun lerp(from: Double, to: Double, t: Double): Double = from + (to - from) * t.coerceIn(0.0, 1.0)
}
