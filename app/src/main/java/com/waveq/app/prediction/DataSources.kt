package com.waveq.app.prediction

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query as RetrofitQuery

private const val TAG = "PredictionData"

private const val FORECAST_BASE_URL = "https://api.open-meteo.com/"
private const val FLOOD_BASE_URL = "https://flood-api.open-meteo.com/"

private const val DB_NAME = "waveq_prediction_cache.db"

/**
 * How long a cached response is reused before a refetch is attempted while
 * networked. This is *not* an expiry - an hours-old or days-old cached response
 * is still used when there is no network, and its age is surfaced to the user
 * and folded into the confidence value rather than silently discarded.
 */
const val FORECAST_REFRESH_INTERVAL_MS = 60 * 60 * 1000L

/** Grid rounding, in decimal degrees, applied to coordinates before fetching and caching. */
private const val GRID_DEGREES = 0.01 // ~1.1 km; matches the resolution the source data actually has

private const val SOURCE_FORECAST = "forecast"
private const val SOURCE_FLOOD = "flood"
private const val SOURCE_ELEVATION = "elevation"

// ---------------------------------------------------------------------------
// Room cache
// ---------------------------------------------------------------------------

/**
 * One cached API response, stored as the raw body exactly as received.
 *
 * The raw body is kept rather than parsed columns so that a change to the
 * parsing code never invalidates data already on the device - which matters
 * because this cache is the *only* input the engine has when offline, and a
 * device may go for days without connectivity in exactly the situation this
 * app exists for.
 */
@Entity(tableName = "cached_responses")
data class CachedResponseEntity(
    @PrimaryKey val cacheKey: String,
    val source: String,
    val latitude: Double,
    val longitude: Double,
    val body: String,
    val fetchedAt: Long,
)

/**
 * A terrain profile for one grid cell. Cached permanently and never refetched:
 * elevation and slope do not change on any timescale this app cares about, and
 * a permanently cached profile means terrain classification keeps working
 * offline forever once the location has been seen even once.
 */
@Entity(tableName = "terrain_profiles")
data class TerrainProfileEntity(
    @PrimaryKey val cellKey: String,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val slopeDegrees: Double,
    val catchmentClass: String,
    val isSlopeAssumed: Boolean,
    val sampledAt: Long,
)

@Dao
interface PredictionCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResponse(entity: CachedResponseEntity)

    @Query("SELECT * FROM cached_responses WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getResponse(cacheKey: String): CachedResponseEntity?

    /**
     * Most recent response of a given source regardless of coordinates - the
     * fallback when the device has moved to a cell it has never fetched and has
     * no network. Better to reason from nearby-but-stale data, clearly labelled
     * as such, than to show nothing at all.
     */
    @Query("SELECT * FROM cached_responses WHERE source = :source ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun getMostRecentForSource(source: String): CachedResponseEntity?

    @Query("DELETE FROM cached_responses WHERE source = :source AND fetchedAt < :before")
    suspend fun purgeOlderThan(source: String, before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTerrain(entity: TerrainProfileEntity)

    @Query("SELECT * FROM terrain_profiles WHERE cellKey = :cellKey LIMIT 1")
    suspend fun getTerrain(cellKey: String): TerrainProfileEntity?
}

@Database(entities = [CachedResponseEntity::class, TerrainProfileEntity::class], version = 1, exportSchema = false)
abstract class PredictionCacheDatabase : RoomDatabase() {
    abstract fun predictionCacheDao(): PredictionCacheDao
}

// ---------------------------------------------------------------------------
// Retrofit services
// ---------------------------------------------------------------------------

/**
 * Open-Meteo forecast endpoint. Free, no API key, no attribution requirement
 * beyond CC-BY on the data itself.
 *
 * `timeformat=unixtime` is requested so timestamps arrive as epoch seconds
 * rather than local ISO strings - there is no timezone parsing to get wrong,
 * and no dependency on the device clock's timezone being correct, which on a
 * factory-reset phone in a disaster it may not be.
 */
interface OpenMeteoForecastApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @RetrofitQuery("latitude") latitude: Double,
        @RetrofitQuery("longitude") longitude: Double,
        @RetrofitQuery("hourly") hourly: String,
        @RetrofitQuery("past_days") pastDays: Int,
        @RetrofitQuery("forecast_days") forecastDays: Int,
        @RetrofitQuery("timeformat") timeFormat: String = "unixtime",
        @RetrofitQuery("timezone") timezone: String = "UTC",
    ): String

    /** Accepts comma-separated coordinate lists, so all five terrain samples cost one request. */
    @GET("v1/elevation")
    suspend fun elevation(
        @RetrofitQuery("latitude") latitudes: String,
        @RetrofitQuery("longitude") longitudes: String,
    ): String
}

/** Open-Meteo flood endpoint - GloFAS-derived river discharge, also free and keyless. */
interface OpenMeteoFloodApi {
    @GET("v1/flood")
    suspend fun flood(
        @RetrofitQuery("latitude") latitude: Double,
        @RetrofitQuery("longitude") longitude: Double,
        @RetrofitQuery("daily") daily: String,
        @RetrofitQuery("past_days") pastDays: Int,
        @RetrofitQuery("forecast_days") forecastDays: Int,
        @RetrofitQuery("timeformat") timeFormat: String = "unixtime",
        @RetrofitQuery("timezone") timezone: String = "UTC",
    ): String
}

// ---------------------------------------------------------------------------
// Parsed snapshots
// ---------------------------------------------------------------------------

/**
 * Hourly weather for one point.
 *
 * All series are index-aligned with [timesUtcMs] and may contain nulls -
 * Open-Meteo returns null for hours where a variable is unavailable, and the
 * indicators must handle that rather than substituting zero, because "no data"
 * and "no rain" are very different inputs to a flood model.
 */
data class ForecastSnapshot(
    val latitude: Double,
    val longitude: Double,
    val timesUtcMs: List<Long>,
    val precipitationMm: List<Double?>,
    val precipitationProbabilityPct: List<Int?>,
    val temperatureC: List<Double?>,
    val relativeHumidityPct: List<Int?>,
    val soilMoisture0To7: List<Double?>,
    val soilMoisture7To28: List<Double?>,
    val fetchedAt: Long,
    val isFromCache: Boolean,
) {
    /**
     * Index of the hour containing [now], or the closest available hour.
     * Returns -1 only when the series is empty.
     */
    fun indexOfNow(now: Long): Int {
        if (timesUtcMs.isEmpty()) return -1
        var best = 0
        var bestDelta = Long.MAX_VALUE
        timesUtcMs.forEachIndexed { i, t ->
            val delta = kotlin.math.abs(t - now)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return best
    }
}

/** Daily river discharge for the reach nearest the point. */
data class FloodSnapshot(
    val latitude: Double,
    val longitude: Double,
    val timesUtcMs: List<Long>,
    val dischargeM3s: List<Double?>,
    val dischargeMeanM3s: List<Double?>,
    val fetchedAt: Long,
    val isFromCache: Boolean,
) {
    fun indexOfNow(now: Long): Int {
        if (timesUtcMs.isEmpty()) return -1
        // Daily series: the last entry at or before now, else the first entry.
        val idx = timesUtcMs.indexOfLast { it <= now }
        return if (idx >= 0) idx else 0
    }
}

// ---------------------------------------------------------------------------
// Data source
// ---------------------------------------------------------------------------

/**
 * Fetches and caches every external input the risk engine needs.
 *
 * The contract every method here honours: **a network failure is never an
 * error the caller has to handle.** Each method returns the freshest data it
 * can produce - live if the network allowed it, cached otherwise - with
 * [ForecastSnapshot.isFromCache] and `fetchedAt` saying honestly which it was.
 * Only a total absence of both live and cached data returns null.
 */
class OpenMeteoDataSource(context: Context) {

    private val appContext = context.applicationContext

    private val db = Room.databaseBuilder(appContext, PredictionCacheDatabase::class.java, DB_NAME).build()
    private val dao = db.predictionCacheDao()

    private val httpClient = OkHttpClient.Builder()
        // Short timeouts on purpose: this runs on a phone that may be on a
        // barely-alive network during a disaster. Falling back to cache quickly
        // is far more useful than blocking for 30s on a connection that will
        // fail anyway.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val forecastApi: OpenMeteoForecastApi = Retrofit.Builder()
        .baseUrl(FORECAST_BASE_URL)
        .client(httpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(OpenMeteoForecastApi::class.java)

    private val floodApi: OpenMeteoFloodApi = Retrofit.Builder()
        .baseUrl(FLOOD_BASE_URL)
        .client(httpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(OpenMeteoFloodApi::class.java)

    /** Whether a usable network is currently available. Checked before every fetch attempt. */
    fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // -- Forecast ----------------------------------------------------------

    /**
     * Hourly precipitation, humidity, temperature and soil moisture for the
     * point, covering the past 7 days (needed by the antecedent precipitation
     * index) and the next 2 days (needed by the intensity-duration comparison).
     */
    suspend fun forecast(latitude: Double, longitude: Double, forceRefresh: Boolean = false): ForecastSnapshot? =
        withContext(Dispatchers.IO) {
            val lat = snapToGrid(latitude)
            val lon = snapToGrid(longitude)
            val key = cacheKey(SOURCE_FORECAST, lat, lon)

            val cached = dao.getResponse(key)
            val isCacheFresh = cached != null &&
                System.currentTimeMillis() - cached.fetchedAt < FORECAST_REFRESH_INTERVAL_MS
            if (cached != null && isCacheFresh && !forceRefresh) {
                return@withContext parseForecast(cached.body, lat, lon, cached.fetchedAt, isFromCache = true)
            }

            if (isNetworkAvailable()) {
                try {
                    val body = forecastApi.forecast(
                        latitude = lat,
                        longitude = lon,
                        hourly = FORECAST_HOURLY_VARIABLES,
                        pastDays = FORECAST_PAST_DAYS,
                        forecastDays = FORECAST_FUTURE_DAYS,
                    )
                    val now = System.currentTimeMillis()
                    val parsed = parseForecast(body, lat, lon, now, isFromCache = false)
                    if (parsed != null) {
                        dao.putResponse(CachedResponseEntity(key, SOURCE_FORECAST, lat, lon, body, now))
                        return@withContext parsed
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "forecast fetch failed, falling back to cache", e)
                } catch (e: Exception) {
                    // HttpException, JSON errors, anything else: cache is still better than nothing.
                    Log.w(TAG, "forecast fetch/parse failed, falling back to cache", e)
                }
            }

            val fallback = cached ?: dao.getMostRecentForSource(SOURCE_FORECAST) ?: return@withContext null
            parseForecast(fallback.body, fallback.latitude, fallback.longitude, fallback.fetchedAt, isFromCache = true)
        }

    private fun parseForecast(
        body: String,
        latitude: Double,
        longitude: Double,
        fetchedAt: Long,
        isFromCache: Boolean,
    ): ForecastSnapshot? = try {
        val hourly = JSONObject(body).getJSONObject("hourly")
        val times = longList(hourly.optJSONArray("time")).map { it * 1000L }
        if (times.isEmpty()) {
            null
        } else {
            ForecastSnapshot(
                latitude = latitude,
                longitude = longitude,
                timesUtcMs = times,
                precipitationMm = doubleList(hourly.optJSONArray("precipitation"), times.size),
                precipitationProbabilityPct = doubleList(hourly.optJSONArray("precipitation_probability"), times.size)
                    .map { it?.toInt() },
                temperatureC = doubleList(hourly.optJSONArray("temperature_2m"), times.size),
                relativeHumidityPct = doubleList(hourly.optJSONArray("relative_humidity_2m"), times.size)
                    .map { it?.toInt() },
                soilMoisture0To7 = doubleList(hourly.optJSONArray("soil_moisture_0_to_7cm"), times.size),
                soilMoisture7To28 = doubleList(hourly.optJSONArray("soil_moisture_7_to_28cm"), times.size),
                fetchedAt = fetchedAt,
                isFromCache = isFromCache,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "malformed forecast body", e)
        null
    }

    // -- Flood -------------------------------------------------------------

    /** Daily river discharge and its long-term mean for the river reach nearest the point. */
    suspend fun flood(latitude: Double, longitude: Double, forceRefresh: Boolean = false): FloodSnapshot? =
        withContext(Dispatchers.IO) {
            val lat = snapToGrid(latitude)
            val lon = snapToGrid(longitude)
            val key = cacheKey(SOURCE_FLOOD, lat, lon)

            val cached = dao.getResponse(key)
            val isCacheFresh = cached != null &&
                System.currentTimeMillis() - cached.fetchedAt < FORECAST_REFRESH_INTERVAL_MS
            if (cached != null && isCacheFresh && !forceRefresh) {
                return@withContext parseFlood(cached.body, lat, lon, cached.fetchedAt, isFromCache = true)
            }

            if (isNetworkAvailable()) {
                try {
                    val body = floodApi.flood(
                        latitude = lat,
                        longitude = lon,
                        daily = FLOOD_DAILY_VARIABLES,
                        pastDays = FLOOD_PAST_DAYS,
                        forecastDays = FLOOD_FUTURE_DAYS,
                    )
                    val now = System.currentTimeMillis()
                    val parsed = parseFlood(body, lat, lon, now, isFromCache = false)
                    if (parsed != null) {
                        dao.putResponse(CachedResponseEntity(key, SOURCE_FLOOD, lat, lon, body, now))
                        return@withContext parsed
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "flood fetch failed, falling back to cache", e)
                } catch (e: Exception) {
                    Log.w(TAG, "flood fetch/parse failed, falling back to cache", e)
                }
            }

            val fallback = cached ?: dao.getMostRecentForSource(SOURCE_FLOOD) ?: return@withContext null
            parseFlood(fallback.body, fallback.latitude, fallback.longitude, fallback.fetchedAt, isFromCache = true)
        }

    private fun parseFlood(
        body: String,
        latitude: Double,
        longitude: Double,
        fetchedAt: Long,
        isFromCache: Boolean,
    ): FloodSnapshot? = try {
        val daily = JSONObject(body).getJSONObject("daily")
        val times = longList(daily.optJSONArray("time")).map { it * 1000L }
        if (times.isEmpty()) {
            null
        } else {
            FloodSnapshot(
                latitude = latitude,
                longitude = longitude,
                timesUtcMs = times,
                dischargeM3s = doubleList(daily.optJSONArray("river_discharge"), times.size),
                dischargeMeanM3s = doubleList(daily.optJSONArray("river_discharge_mean"), times.size),
                fetchedAt = fetchedAt,
                isFromCache = isFromCache,
            )
        }
    } catch (e: Exception) {
        // Not every point in India is on a modelled river reach; the flood API
        // legitimately returns no discharge series for such points. That is a
        // missing indicator, not an error.
        Log.w(TAG, "malformed or empty flood body", e)
        null
    }

    // -- Elevation ---------------------------------------------------------

    /**
     * Elevation in metres for a batch of points, in the order given.
     *
     * Cached permanently rather than on the refresh interval - terrain height
     * does not change. Returns null if the batch could not be fetched and was
     * not already cached.
     */
    suspend fun elevations(points: List<Pair<Double, Double>>): List<Double>? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        val snapped = points.map { snapToGrid(it.first) to snapToGrid(it.second) }
        val key = cacheKey(
            SOURCE_ELEVATION,
            snapped.first().first,
            snapped.first().second,
        ) + ":${snapped.size}"

        dao.getResponse(key)?.let { cached ->
            parseElevations(cached.body, snapped.size)?.let { return@withContext it }
        }

        if (!isNetworkAvailable()) return@withContext null

        try {
            val body = forecastApi.elevation(
                latitudes = snapped.joinToString(",") { it.first.toString() },
                longitudes = snapped.joinToString(",") { it.second.toString() },
            )
            val parsed = parseElevations(body, snapped.size) ?: return@withContext null
            dao.putResponse(
                CachedResponseEntity(
                    cacheKey = key,
                    source = SOURCE_ELEVATION,
                    latitude = snapped.first().first,
                    longitude = snapped.first().second,
                    body = body,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            parsed
        } catch (e: IOException) {
            Log.w(TAG, "elevation fetch failed", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "elevation fetch/parse failed", e)
            null
        }
    }

    private fun parseElevations(body: String, expected: Int): List<Double>? = try {
        val array = JSONObject(body).optJSONArray("elevation")
        val values = doubleList(array, expected)
        // A partially-null elevation batch cannot produce a valid slope, so
        // reject it outright rather than silently substituting zeros.
        if (values.size < expected || values.any { it == null }) null else values.map { it!! }
    } catch (e: Exception) {
        Log.w(TAG, "malformed elevation body", e)
        null
    }

    // -- Terrain profile persistence --------------------------------------

    suspend fun cachedTerrain(latitude: Double, longitude: Double): TerrainProfile? = withContext(Dispatchers.IO) {
        val entity = dao.getTerrain(terrainCellKey(latitude, longitude)) ?: return@withContext null
        TerrainProfile(
            latitude = entity.latitude,
            longitude = entity.longitude,
            elevationM = entity.elevationM,
            slopeDegrees = entity.slopeDegrees,
            catchmentClass = runCatching { CatchmentClass.valueOf(entity.catchmentClass) }
                .getOrElse { return@withContext null },
            sampledAt = entity.sampledAt,
            isSlopeAssumed = entity.isSlopeAssumed,
        )
    }

    suspend fun storeTerrain(profile: TerrainProfile) = withContext(Dispatchers.IO) {
        dao.putTerrain(
            TerrainProfileEntity(
                cellKey = terrainCellKey(profile.latitude, profile.longitude),
                latitude = profile.latitude,
                longitude = profile.longitude,
                elevationM = profile.elevationM,
                slopeDegrees = profile.slopeDegrees,
                catchmentClass = profile.catchmentClass.name,
                isSlopeAssumed = profile.isSlopeAssumed,
                sampledAt = profile.sampledAt,
            ),
        )
    }

    companion object {
        /** Exactly the variables the indicators consume - nothing fetched that nothing reads. */
        const val FORECAST_HOURLY_VARIABLES =
            "precipitation,precipitation_probability,temperature_2m,relative_humidity_2m," +
                "soil_moisture_0_to_7cm,soil_moisture_7_to_28cm"

        /** 7 days back: the window the antecedent precipitation index integrates over. */
        const val FORECAST_PAST_DAYS = 7

        /** 2 days forward: covers every intensity window plus the longest terrain response time. */
        const val FORECAST_FUTURE_DAYS = 2

        const val FLOOD_DAILY_VARIABLES = "river_discharge,river_discharge_mean"
        const val FLOOD_PAST_DAYS = 7
        const val FLOOD_FUTURE_DAYS = 2

        /**
         * Terrain cache cell, ~11 km. Deliberately much coarser than the
         * weather grid: a catchment response class is a property of the
         * landscape, so re-fetching five elevation samples every time the user
         * walks a kilometre would be wasted requests for an identical answer.
         */
        private const val TERRAIN_CELL_DEGREES = 0.1

        fun snapToGrid(value: Double): Double = round(value / GRID_DEGREES) * GRID_DEGREES

        private fun cacheKey(source: String, latitude: Double, longitude: Double): String =
            "%s:%.2f:%.2f".format(source, latitude, longitude)

        fun terrainCellKey(latitude: Double, longitude: Double): String {
            val lat = round(latitude / TERRAIN_CELL_DEGREES) * TERRAIN_CELL_DEGREES
            val lon = round(longitude / TERRAIN_CELL_DEGREES) * TERRAIN_CELL_DEGREES
            return "%.1f:%.1f".format(lat, lon)
        }
    }
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

private fun longList(array: JSONArray?): List<Long> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        if (array.isNull(i)) null else array.optLong(i)
    }
}

/**
 * Reads a numeric series, preserving nulls as nulls and padding to [size] so
 * every series stays index-aligned with the time series. Zero is never
 * substituted for missing - "no measurement" must stay distinguishable from
 * "no rainfall" all the way through to the indicators.
 */
private fun doubleList(array: JSONArray?, size: Int): List<Double?> {
    if (array == null) return List(size) { null }
    return (0 until size).map { i ->
        if (i >= array.length() || array.isNull(i)) null else array.optDouble(i).takeIf { !it.isNaN() }
    }
}
