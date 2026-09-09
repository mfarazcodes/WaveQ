package com.waveq.app.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

private const val TAG = "PlaceSearch"
private const val GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com/"

/** Results per query. Ten is enough to disambiguate a repeated name without becoming a wall of text. */
private const val SEARCH_RESULT_COUNT = 10

/** Reverse geocoding is a nice-to-have; it must never hold up showing a risk score. */
private const val REVERSE_GEOCODE_TIMEOUT_MS = 5_000L

/** Open-Meteo geocoding - free, keyless, same family as the weather endpoints. */
private interface OpenMeteoGeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int,
        @Query("countryCode") countryCode: String,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json",
    ): String
}

/**
 * Place lookup for the location picker.
 *
 * Two directions, deliberately using different providers:
 *  - **forward** (text -> coordinates) via Open-Meteo's geocoding API, matching
 *    the rest of the app's data sources;
 *  - **reverse** (coordinates -> name) via Android's built-in [Geocoder],
 *    because Open-Meteo has no reverse endpoint and the platform one needs no
 *    extra dependency or key.
 *
 * This keeps its own HTTP client rather than sharing the risk engine's. The two
 * have opposite usage shapes - the risk engine makes a couple of requests an
 * hour in the background forever, while this makes a burst of them only while
 * the picker is open - and keeping them separate means a user typing in the
 * search box can never contend with, or stall, a background risk refresh.
 */
object PlaceSearch {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val api: OpenMeteoGeocodingApi = Retrofit.Builder()
        .baseUrl(GEOCODING_BASE_URL)
        .client(httpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(OpenMeteoGeocodingApi::class.java)

    /**
     * Searches Indian place names.
     *
     * Restricted to India twice over: the API is asked for `countryCode=IN`,
     * and the parsed results are filtered on `country_code` as well. The second
     * filter is not redundant - it means that if the API ever ignores or
     * rejects the parameter, the picker still cannot offer a user a town in
     * another country whose risk assessment would be meaningless to them.
     */
    suspend fun search(query: String): Result<List<PlaceRef>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext Result.success(emptyList())

        try {
            val body = api.search(name = trimmed, count = SEARCH_RESULT_COUNT, countryCode = "IN")
            Result.success(parseResults(body))
        } catch (e: IOException) {
            // Offline is the expected case for this app, not an exception - the
            // caller shows recents and saved places instead of an error.
            Log.w(TAG, "place search failed (offline?)", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "place search failed", e)
            Result.failure(e)
        }
    }

    private fun parseResults(body: String): List<PlaceRef> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("country_code") != "IN") return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val latitude = item.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            val longitude = item.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            PlaceRef(
                name = name,
                admin1 = item.optString("admin1").takeIf { it.isNotBlank() },
                admin2 = item.optString("admin2").takeIf { it.isNotBlank() },
                country = item.optString("country").takeIf { it.isNotBlank() },
                latitude = latitude,
                longitude = longitude,
            )
        }
    }

    /**
     * Best-effort name for a coordinate, for labelling the device's own
     * position.
     *
     * Returns a coordinate-only [PlaceRef] rather than null when geocoding is
     * unavailable, so the UI always has something to show. Never blocks for
     * long: this only decides a caption, and a missing caption must never delay
     * a flood risk score.
     */
    suspend fun describeCoordinates(context: Context, latitude: Double, longitude: Double): PlaceRef {
        val fallback = PlaceRef(
            name = "Current location",
            admin1 = null,
            admin2 = null,
            country = null,
            latitude = latitude,
            longitude = longitude,
        )
        if (!Geocoder.isPresent()) return fallback

        val address = withTimeoutOrNull(REVERSE_GEOCODE_TIMEOUT_MS) {
            runCatching { geocode(context, latitude, longitude) }.getOrNull()
        } ?: return fallback

        val name = address.locality
            ?: address.subLocality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: return fallback

        return fallback.copy(
            name = name,
            admin2 = address.subAdminArea?.takeIf { it != name },
            admin1 = address.adminArea?.takeIf { it != name },
            country = address.countryName,
        )
    }

    private suspend fun geocode(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): android.location.Address? {
        val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
            }
        }
    }
}
