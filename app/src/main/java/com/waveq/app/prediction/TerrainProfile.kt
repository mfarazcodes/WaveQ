package com.waveq.app.prediction

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI

/**
 * Terrain characterisation for the flash-flood risk engine.
 *
 * ## Why this file exists
 *
 * A single national rainfall threshold is wrong everywhere in India. 50 mm in
 * three hours is an ordinary monsoon afternoon on the Indo-Gangetic plain and a
 * catchment-emptying event in a steep Himalayan valley, where the same water
 * arrives at the valley floor in under an hour instead of over a day. The
 * engine therefore derives a *catchment response class* from the device's own
 * coordinates and reads its thresholds and response time from that class.
 *
 * ## Calibration status - READ THIS BEFORE DEPLOYMENT
 *
 * **Every numeric constant in this file is an engineering estimate, not a
 * calibrated value.** They are anchored to published qualitative guidance (IMD
 * rainfall categories, standard hydrological time-of-concentration behaviour)
 * and to the physical direction each quantity must move in, but none of them
 * has been fitted against an observed flash-flood dataset for Indian
 * catchments. They are chosen to be *defensible and ordered correctly*, not to
 * be accurate.
 *
 * Before this is used to make real evacuation decisions, the thresholds and
 * response times must be calibrated per basin against historical event data -
 * CWC discharge records, IMD AWS rainfall, and recorded flash-flood events.
 * Each constant below carries a comment naming what it is anchored to and what
 * it would need to be calibrated against. Search this file for "CALIBRATE" to
 * find every one of them.
 */

// ---------------------------------------------------------------------------
// Rainfall intensity-duration thresholds
// ---------------------------------------------------------------------------

/**
 * The rainfall accumulation, per duration window, at which this terrain class
 * is considered to be at its flash-flood onset threshold.
 *
 * This is a discretised intensity-duration-frequency curve: three points on the
 * curve rather than a fitted function, because three points is all the forecast
 * resolution (hourly) can meaningfully support, and a fitted curve would imply
 * a precision the underlying calibration does not have.
 *
 * A shorter window has a *lower* absolute threshold but a *higher* implied
 * intensity - 20 mm in one hour is a more dangerous rate than 50 mm in six.
 */
data class RainfallThresholdCurve(
    val oneHourMm: Double,
    val threeHourMm: Double,
    val sixHourMm: Double,
) {
    /** Threshold for one of the three supported accumulation windows. */
    fun thresholdFor(windowHours: Int): Double = when (windowHours) {
        1 -> oneHourMm
        3 -> threeHourMm
        6 -> sixHourMm
        else -> throw IllegalArgumentException("unsupported window: ${windowHours}h (expected 1, 3 or 6)")
    }

    companion object {
        val WINDOWS_HOURS = listOf(1, 3, 6)
    }
}

// ---------------------------------------------------------------------------
// Catchment response class
// ---------------------------------------------------------------------------

/**
 * How a catchment converts rainfall into a flood wave at the device's location.
 *
 * [responseTimeHours] is the characteristic time from rainfall peak to
 * discharge peak - the lag that sets how much warning is physically available.
 * It is the single most decision-relevant number here: it is what turns a
 * threshold breach into "flooding possible in approximately N hours".
 *
 * CALIBRATE: response times are order-of-magnitude estimates consistent with
 * time-of-concentration behaviour for catchments of these gradients. Real
 * values depend on catchment area, drainage density and channel geometry, none
 * of which are available from a point coordinate. They must be replaced with
 * per-basin values derived from observed rainfall-to-peak lags.
 *
 * CALIBRATE: threshold curves are anchored to IMD's 24-hour rainfall
 * categories (heavy 64.5-115.5 mm, very heavy 115.6-204.4 mm, extremely heavy
 * >204.4 mm) scaled down to sub-daily windows by the rule that flash flooding
 * in steep terrain is driven by short-window intensity rather than daily
 * totals. The scaling factor is judgement, not measurement.
 */
enum class CatchmentClass(
    val label: String,
    val responseTimeHours: Double,
    val thresholdCurve: RainfallThresholdCurve,
    val terrainAmplification: Double,
    val description: String,
) {
    /**
     * High Himalaya and comparable steep mountain catchments. Very short lag,
     * very low threshold: steep bare or thin-soiled slopes shed almost all
     * rainfall as immediate surface runoff, and the flood wave reaches the
     * valley floor - where people are - in well under two hours.
     */
    STEEP_HIMALAYAN(
        label = "Steep mountain",
        responseTimeHours = 1.0,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 20.0, threeHourMm = 35.0, sixHourMm = 50.0),
        terrainAmplification = 1.0,
        description = "Steep mountain catchment - runoff reaches the valley floor within about an hour",
    ),

    /** Western Ghats, Northeast hills, Aravalli/Vindhya uplands: steep but soil-mantled. */
    HILLY(
        label = "Hilly",
        responseTimeHours = 2.5,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 30.0, threeHourMm = 55.0, sixHourMm = 80.0),
        terrainAmplification = 0.80,
        description = "Hill catchment - steep slopes with soil cover, runoff concentrates in a few hours",
    ),

    /** Terai/Bhabar, Duars, Shivalik front: where mountain water arrives on flat ground. */
    FOOTHILL(
        label = "Foothill",
        responseTimeHours = 4.0,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 40.0, threeHourMm = 70.0, sixHourMm = 100.0),
        terrainAmplification = 0.65,
        description = "Foothill zone - receives runoff generated on the slopes above it",
    ),

    /** Deccan and other plateau interiors: moderate elevation, low local relief. */
    PLATEAU(
        label = "Plateau",
        responseTimeHours = 6.0,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 50.0, threeHourMm = 85.0, sixHourMm = 120.0),
        terrainAmplification = 0.45,
        description = "Plateau - thin soils over rock, moderate runoff with several hours of lag",
    ),

    /** Indo-Gangetic and other alluvial plains: long lag, high threshold, slow deep flooding. */
    PLAIN(
        label = "Plain",
        responseTimeHours = 12.0,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 60.0, threeHourMm = 100.0, sixHourMm = 150.0),
        terrainAmplification = 0.25,
        description = "Alluvial plain - flooding builds slowly over many hours rather than as a flash wave",
    ),

    /**
     * Low-lying coastal margin. Rated *between* plain and plateau rather than
     * lowest, because drainage here is tide- and backwater-limited: the water
     * has nowhere to go even though the land is flat.
     */
    COASTAL(
        label = "Coastal lowland",
        responseTimeHours = 8.0,
        thresholdCurve = RainfallThresholdCurve(oneHourMm = 45.0, threeHourMm = 80.0, sixHourMm = 110.0),
        terrainAmplification = 0.40,
        description = "Coastal lowland - flat, near sea level, drainage limited by tide and backwater",
    ),
}

// ---------------------------------------------------------------------------
// Classification boundaries
// ---------------------------------------------------------------------------

/**
 * CALIBRATE: elevation in metres above which terrain is treated as high
 * mountain regardless of the locally sampled slope. 2000 m is chosen because
 * essentially all Indian terrain above it is Himalayan or high Northeast, and
 * because the 1 km elevation sampling below systematically *under*-reads slope
 * in deeply incised valleys - a valley floor at 2500 m can sample as flat while
 * sitting directly beneath 1500 m of relief. Erring toward the shorter response
 * time is the safe direction for a warning system.
 */
private const val HIGH_MOUNTAIN_ELEVATION_M = 2000.0

/** CALIBRATE: mountain classification for terrain that is high *and* demonstrably steep. */
private const val MOUNTAIN_ELEVATION_M = 1200.0
private const val STEEP_SLOPE_DEG = 12.0

/** CALIBRATE: slope above which soil-mantled terrain behaves as a hill catchment. */
private const val HILLY_SLOPE_DEG = 6.0

/** CALIBRATE: minimum elevation for hill classification - excludes steep river banks on flat plains. */
private const val HILLY_MIN_ELEVATION_M = 300.0

/**
 * CALIBRATE: slope band for the foothill zone. Gentle by mountain standards but
 * steep enough that runoff still concentrates rather than ponding.
 */
private const val FOOTHILL_SLOPE_DEG = 2.0
private const val FOOTHILL_MAX_ELEVATION_M = 1200.0

/**
 * CALIBRATE: elevation above which flat terrain is plateau rather than plain.
 * ~250 m separates the Deccan and Malwa surfaces from the Indo-Gangetic and
 * coastal alluvium.
 */
private const val PLATEAU_MIN_ELEVATION_M = 250.0

/**
 * CALIBRATE: elevation below which flat terrain is treated as coastal lowland.
 *
 * This is a deliberate approximation and a known weakness. Proper coastal
 * classification needs distance-to-coastline, which is not available offline
 * from a point coordinate without shipping a coastline dataset. Elevation below
 * 15 m is used as the proxy, which correctly catches most of the Indian coastal
 * margin but also misclassifies parts of the lower Gangetic delta - defensible,
 * since deltaic drainage really is backwater-limited in much the same way.
 */
private const val COASTAL_MAX_ELEVATION_M = 15.0

/**
 * Classifies a point into a [CatchmentClass] from its elevation and local slope.
 *
 * The ladder is ordered most-hazardous-first, so a point that satisfies two
 * descriptions gets the shorter response time and lower threshold. In a warning
 * system the cost of an over-short lead time estimate is a false alarm; the
 * cost of an over-long one is no warning at all.
 */
fun classifyCatchment(elevationM: Double, slopeDegrees: Double): CatchmentClass = when {
    // Unconditionally mountain: sampling can under-read slope in incised valleys.
    elevationM >= HIGH_MOUNTAIN_ELEVATION_M -> CatchmentClass.STEEP_HIMALAYAN
    elevationM >= MOUNTAIN_ELEVATION_M && slopeDegrees >= STEEP_SLOPE_DEG -> CatchmentClass.STEEP_HIMALAYAN
    elevationM >= HILLY_MIN_ELEVATION_M && slopeDegrees >= HILLY_SLOPE_DEG -> CatchmentClass.HILLY
    // Ghat scarps drop to low elevation while staying steep - still a hill catchment.
    slopeDegrees >= STEEP_SLOPE_DEG -> CatchmentClass.HILLY
    slopeDegrees >= FOOTHILL_SLOPE_DEG && elevationM <= FOOTHILL_MAX_ELEVATION_M -> CatchmentClass.FOOTHILL
    elevationM >= PLATEAU_MIN_ELEVATION_M -> CatchmentClass.PLATEAU
    elevationM <= COASTAL_MAX_ELEVATION_M -> CatchmentClass.COASTAL
    else -> CatchmentClass.PLAIN
}

// ---------------------------------------------------------------------------
// Terrain profile
// ---------------------------------------------------------------------------

/**
 * The terrain characterisation for one location, cached permanently: elevation
 * and slope do not change on any timescale this app cares about, so this is
 * fetched once per grid cell and then read from the Room cache forever, which
 * also means terrain is always available offline even on a device that has
 * never had connectivity at this location.
 */
data class TerrainProfile(
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    /** Local slope in degrees, estimated by [estimateSlopeDegrees] from four surrounding samples. */
    val slopeDegrees: Double,
    val catchmentClass: CatchmentClass,
    val sampledAt: Long,
    /**
     * True when the four surrounding elevation samples were unavailable and
     * slope was assumed rather than measured. The engine degrades confidence
     * when this is set - a profile built from elevation alone is a guess.
     */
    val isSlopeAssumed: Boolean = false,
) {
    /** One-line plain-language summary for the UI. */
    fun summary(): String =
        "${catchmentClass.label} - ${elevationM.toInt()} m elevation, " +
            (if (isSlopeAssumed) "slope not measured" else "%.1f° slope".format(slopeDegrees))

    companion object {
        /**
         * Offset used when sampling the four surrounding elevation points.
         *
         * ~1 km, per the design: far enough that SRTM-class elevation data
         * (~30-90 m posting) shows a real gradient rather than sampling noise,
         * close enough that the result still describes *this* hillside rather
         * than the regional average.
         */
        const val SAMPLE_OFFSET_KM = 1.0

        /** Mean earth radius, km. Used to convert the sample offset to degrees. */
        private const val EARTH_RADIUS_KM = 6371.0

        /** Degrees of latitude per [SAMPLE_OFFSET_KM]. Constant everywhere. */
        fun latitudeOffsetDegrees(): Double = (SAMPLE_OFFSET_KM / EARTH_RADIUS_KM) * 180.0 / PI

        /**
         * Degrees of longitude per [SAMPLE_OFFSET_KM] at [latitude]. Meridians
         * converge toward the poles, so this must be latitude-corrected or the
         * east-west sample spacing is wrong (by ~30% at Kashmir latitudes).
         */
        fun longitudeOffsetDegrees(latitude: Double): Double {
            val shrink = cos(latitude * PI / 180.0).coerceAtLeast(0.01)
            return latitudeOffsetDegrees() / shrink
        }

        /**
         * Slope assumed when the surrounding samples could not be fetched.
         *
         * CALIBRATE: 1.5° is deliberately gentle. An assumed-steep default
         * would manufacture alarming risk scores out of missing data, which is
         * the failure mode most likely to destroy user trust. The missing
         * measurement is instead surfaced honestly through [isSlopeAssumed] and
         * a reduced confidence value.
         */
        const val ASSUMED_SLOPE_DEG = 1.5
    }
}

/**
 * Estimates local slope from elevation sampled at the point itself and at four
 * points roughly [TerrainProfile.SAMPLE_OFFSET_KM] to the north, south, east
 * and west.
 *
 * The two opposing pairs give a finite-difference gradient in each direction
 * over a 2 km baseline; the slope is the magnitude of that gradient vector,
 * converted to degrees. This is the standard 4-neighbour gradient used in
 * raster terrain analysis, at a coarse posting.
 *
 * Note this measures the *regional* gradient over 1 km, not the local channel
 * bank. That is the intended quantity: catchment response is governed by
 * hillslope gradient, not by the metre-scale roughness at the device.
 */
fun estimateSlopeDegrees(
    northM: Double,
    southM: Double,
    eastM: Double,
    westM: Double,
): Double {
    val baselineM = TerrainProfile.SAMPLE_OFFSET_KM * 2_000.0 // two samples, one on each side
    val gradientNs = (northM - southM) / baselineM
    val gradientEw = (eastM - westM) / baselineM
    val gradient = hypot(gradientNs, gradientEw)
    return atan(gradient) * 180.0 / PI
}

// ---------------------------------------------------------------------------
// Building a profile from coordinates
// ---------------------------------------------------------------------------

/**
 * Derives a [TerrainProfile] for a coordinate, reading from the permanent Room
 * cache first and only touching the elevation API when the cell has never been
 * seen.
 *
 * Once a location has been profiled even once, terrain classification works
 * offline forever - which is the whole point, since terrain is the input that
 * makes the thresholds local, and a device in a Himalayan valley during a
 * washout is exactly the device that cannot fetch anything.
 */
class TerrainProfileProvider(private val dataSource: OpenMeteoDataSource) {

    /**
     * Returns the terrain profile for this point, or null when the cell has
     * never been profiled and there is no network to profile it now.
     *
     * Five elevation samples - the point itself plus four at ~1 km N/S/E/W -
     * are requested in a single batched call, so a full profile costs one HTTP
     * request, once, per ~11 km cell for the lifetime of the install.
     */
    suspend fun profileFor(latitude: Double, longitude: Double): TerrainProfile? {
        dataSource.cachedTerrain(latitude, longitude)?.let { return it }

        val dLat = TerrainProfile.latitudeOffsetDegrees()
        val dLon = TerrainProfile.longitudeOffsetDegrees(latitude)
        val samplePoints = listOf(
            latitude to longitude, // 0: centre
            (latitude + dLat) to longitude, // 1: north
            (latitude - dLat) to longitude, // 2: south
            latitude to (longitude + dLon), // 3: east
            latitude to (longitude - dLon), // 4: west
        )

        val elevations = dataSource.elevations(samplePoints)
        val profile = if (elevations != null && elevations.size == samplePoints.size) {
            val centre = elevations[0]
            val slope = estimateSlopeDegrees(
                northM = elevations[1],
                southM = elevations[2],
                eastM = elevations[3],
                westM = elevations[4],
            )
            TerrainProfile(
                latitude = latitude,
                longitude = longitude,
                elevationM = centre,
                slopeDegrees = slope,
                catchmentClass = classifyCatchment(centre, slope),
                sampledAt = System.currentTimeMillis(),
                isSlopeAssumed = false,
            )
        } else {
            // Fall back to a centre-only elevation lookup: elevation alone still
            // separates mountain from plain, which is most of the classification
            // signal. The missing slope is recorded, not hidden.
            val centreOnly = dataSource.elevations(listOf(latitude to longitude))?.firstOrNull()
                ?: return null
            TerrainProfile(
                latitude = latitude,
                longitude = longitude,
                elevationM = centreOnly,
                slopeDegrees = TerrainProfile.ASSUMED_SLOPE_DEG,
                catchmentClass = classifyCatchment(centreOnly, TerrainProfile.ASSUMED_SLOPE_DEG),
                sampledAt = System.currentTimeMillis(),
                isSlopeAssumed = true,
            )
        }

        // Only a fully-measured profile is cached permanently. A slope-assumed
        // profile is usable now but must not be frozen onto the device forever -
        // the next refresh with a working network gets to improve on it.
        if (!profile.isSlopeAssumed) dataSource.storeTerrain(profile)
        return profile
    }
}
