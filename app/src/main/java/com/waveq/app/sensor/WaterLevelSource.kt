package com.waveq.app.sensor

import android.util.Log
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET


/** Fail fast: a probe that is not answering must not stall the poll loop. */
private const val TIMEOUT_SECONDS = 3L

/**
 * One reading from the probe.
 *
 * [percent] is 0-100, calibrated and clamped on the device itself. In practice
 * it is bimodal - 0-1 in air, 96-100 submerged - because the hardware is a
 * threshold detector at a fixed height. Nothing here should be presented as a
 * measurement of depth.
 */
data class WaterLevelReading(
    val percent: Int,
    val raw: Int,
    val takenAtMs: Long = System.currentTimeMillis(),
)

/**
 * Why a read failed, kept distinguishable instead of collapsed into null.
 *
 * Every failure below used to be logged as `sensor read failed:
 * <ExceptionName>` at DEBUG and reported to the UI as the single word
 * "unreachable". A cleartext block and an unplugged probe are wildly different
 * problems with the same symptom under that treatment, and telling them apart
 * by hand is what made the network-security-config bug take as long as it did.
 */
data class SensorFailure(val kind: Kind, val detail: String) {

    enum class Kind {
        /** Blocked by network-security-config before the request left the phone. */
        CLEARTEXT_BLOCKED,

        /** Nothing answered at that address. */
        UNREACHABLE,

        /** Answered too slowly, or not at all within [TIMEOUT_SECONDS]. */
        TIMEOUT,

        /** Answered, but with something this app cannot read. */
        BAD_RESPONSE,

        /** No usable host configured, so no request was attempted. */
        NOT_CONFIGURED,
    }

    /** One line, safe to put in logcat or in front of a user. */
    val message: String
        get() = when (kind) {
            Kind.CLEARTEXT_BLOCKED ->
                "BLOCKED BY NETWORK SECURITY POLICY - the request never left the phone. " +
                    "The probe is plain HTTP and this build's network-security-config forbids " +
                    "cleartext to that address. This is a build configuration problem, NOT an " +
                    "unreachable sensor: a browser on the same phone will reach the same URL. " +
                    "Debug builds permit cleartext via src/debug/res/xml/network_security_config.xml."
            Kind.UNREACHABLE -> "Nothing answered at that address - check the probe is powered and on this Wi-Fi"
            Kind.TIMEOUT -> "The probe did not answer within ${TIMEOUT_SECONDS}s"
            Kind.BAD_RESPONSE -> "The probe answered with something unreadable"
            Kind.NOT_CONFIGURED -> "No sensor host configured"
        }
}

/**
 * Maps a thrown exception onto a [SensorFailure].
 *
 * Pure and separate from the request so the classification is testable without
 * a device, a network or a probe. Order matters: [UnknownServiceException] is
 * an IOException, so the cleartext case has to be checked before the generic
 * transport cases.
 */
fun classifySensorFailure(t: Throwable): SensorFailure {
    val detail = "${t.javaClass.name}: ${t.message ?: "no message"}"
    // OkHttp raises UnknownServiceException with a message naming CLEARTEXT
    // when network-security-config refuses the scheme. Matched on both, so a
    // future OkHttp that reuses the type for something else is not mislabelled.
    if (t is UnknownServiceException && t.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
        return SensorFailure(SensorFailure.Kind.CLEARTEXT_BLOCKED, detail)
    }
    return when (t) {
        is SocketTimeoutException, is InterruptedIOException ->
            SensorFailure(SensorFailure.Kind.TIMEOUT, detail)
        is ConnectException, is UnknownHostException, is NoRouteToHostException ->
            SensorFailure(SensorFailure.Kind.UNREACHABLE, detail)
        is org.json.JSONException ->
            SensorFailure(SensorFailure.Kind.BAD_RESPONSE, detail)
        is java.io.IOException ->
            SensorFailure(SensorFailure.Kind.UNREACHABLE, detail)
        else ->
            SensorFailure(SensorFailure.Kind.BAD_RESPONSE, detail)
    }
}

/**
 * Where a water-level reading comes from.
 *
 * **There is deliberately no cloud-backed implementation, and one must not be
 * added.** A source that reached the probe through a server would make this
 * alert path depend on internet connectivity - the exact dependency the mesh
 * exists to remove. The scenario this feature is for is a flood that has taken
 * the towers down, where a phone on the local Wi-Fi can still reach a NodeMCU on
 * the same AP and relay to phones with no network at all. A cloud reading is
 * acceptable as a secondary dashboard; it is never acceptable as the alert path.
 */
interface WaterLevelSource {
    /** A reading, or null when the sensor could not be reached or answered unusably. */
    suspend fun read(): WaterLevelReading?

    /** Whether `/health` currently answers. Used by the Settings "Test connection" button. */
    suspend fun isReachable(): Boolean
}

private interface SensorApi {
    @GET("reading")
    suspend fun reading(): String

    @GET("health")
    suspend fun health(): String
}

/**
 * Reads the probe over plain HTTP on the local network.
 *
 * Follows the app's single parsing idiom - Retrofit with the scalars converter
 * returning a raw String body, parsed with org.json - exactly as
 * [com.waveq.app.prediction.DataSources] does, so no JSON binding or codegen
 * dependency is added for one endpoint.
 *
 * Cleartext is permitted for debug builds only, by
 * src/debug/res/xml/network_security_config.xml. A release build blocks the
 * request before it leaves the phone and [read] reports
 * [SensorFailure.Kind.CLEARTEXT_BLOCKED] - see the release config in
 * src/main/res/xml for why the LAN cannot simply be exempted.
 */
class LocalHttpWaterLevelSource(private val host: String) : WaterLevelSource {

    private val api: SensorApi? = buildApi(host)

    /**
     * Why the last attempt failed, or null if the last attempt succeeded.
     *
     * Volatile because the poll loop writes it on Dispatchers.IO and anything
     * reporting it reads from elsewhere.
     */
    @Volatile
    var lastFailure: SensorFailure? = null
        private set

    private fun buildApi(host: String): SensorApi? {
        val cleaned = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleaned.isBlank()) return null
        return try {
            Retrofit.Builder()
                .baseUrl("http://$cleaned/")
                .client(
                    OkHttpClient.Builder()
                        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build(),
                )
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()
                .create(SensorApi::class.java)
        } catch (e: IllegalArgumentException) {
            // A host the user mistyped badly enough that it is not a valid URL.
            Log.w(SENSOR_PATH_TAG, "invalid sensor host '$host': ${e.message}", e)
            null
        }
    }

    override suspend fun read(): WaterLevelReading? = withContext(Dispatchers.IO) {
        val service = api ?: return@withContext failed("read", SensorFailure(SensorFailure.Kind.NOT_CONFIGURED, "no host"))
        try {
            val body = service.reading()
            val obj = JSONObject(body)
            // The device clamps percent itself, but it is off-device input, so
            // it is clamped again here rather than trusted.
            val percent = obj.optInt("percent", -1).takeIf { it in 0..100 }
                ?: return@withContext failed(
                    "read",
                    SensorFailure(SensorFailure.Kind.BAD_RESPONSE, "percent missing or out of range in: $body"),
                )
            lastFailure = null
            WaterLevelReading(percent = percent, raw = obj.optInt("raw", -1))
        } catch (e: Exception) {
            // Unreachable, blocked, timed out, or answered with something
            // unparseable. All of them mean "no reading" to the caller and are
            // deliberately NOT reported as 0% - but they are no longer
            // indistinguishable in the log.
            failed("read", classifySensorFailure(e))
        }
    }

    override suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val service = api ?: run {
            failed("health", SensorFailure(SensorFailure.Kind.NOT_CONFIGURED, "no host"))
            return@withContext false
        }
        try {
            val ok = JSONObject(service.health()).optString("status") == "ok"
            if (ok) lastFailure = null else failed("health", SensorFailure(SensorFailure.Kind.BAD_RESPONSE, "status was not ok"))
            ok
        } catch (e: Exception) {
            failed("health", classifySensorFailure(e))
            false
        }
    }

    /**
     * Records [failure], logs it, and returns null so a caller can
     * `return@withContext failed(...)` in one step.
     *
     * WARN rather than DEBUG: a sensor that is not answering is the whole
     * feature not working, and it was previously invisible at the default log
     * level. The exception type and message are both included - the type alone
     * is what hid the cleartext block, since UnknownServiceException says
     * nothing while its message names the policy.
     */
    private fun failed(op: String, failure: SensorFailure): WaterLevelReading? {
        lastFailure = failure
        Log.w(
            SENSOR_PATH_TAG,
            "sensor $op failed for '$host' [${failure.kind}] ${failure.message} (cause: ${failure.detail})",
        )
        return null
    }
}
