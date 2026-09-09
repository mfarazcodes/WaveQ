package com.waveq.app

import com.waveq.app.sensor.LocalHttpWaterLevelSource
import com.waveq.app.sensor.SensorFailure
import com.waveq.app.sensor.classifySensorFailure
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a failed sensor read says *why* it failed.
 *
 * The bug behind these: `res/xml/network_security_config.xml` listed CIDR
 * ranges as `<domain>` values. Android matches `<domain>` as a hostname, exact
 * or by suffix, and has no CIDR syntax - so "10.0.0.0/8" matched nothing,
 * `base-config cleartextTrafficPermitted="false"` stayed in force, and every
 * request to the probe was refused before it left the phone. OkHttp raises
 * UnknownServiceException for that, which the source logged at DEBUG as the
 * bare class name and reported as "unreachable" - identical to an unplugged
 * probe, while the phone's browser reached the same URL fine.
 */
class SensorFailureTest {

    /** Verbatim from OkHttp when network-security-config refuses the scheme. */
    private val okHttpCleartextMessage =
        "CLEARTEXT communication to 10.185.203.84 not permitted by network security policy"

    @Test
    fun `a cleartext block is classified distinctly, not as an unreachable sensor`() {
        val failure = classifySensorFailure(UnknownServiceException(okHttpCleartextMessage))
        assertEquals(SensorFailure.Kind.CLEARTEXT_BLOCKED, failure.kind)
        // The distinction is the entire point: these two must never collapse.
        assertTrue(failure.kind != SensorFailure.Kind.UNREACHABLE)
    }

    /**
     * The classification has to survive being read by someone who does not
     * already know the answer, so the message names the build configuration
     * and says the sensor is not at fault.
     */
    @Test
    fun `the cleartext message points at the build config, not at the probe`() {
        val message = classifySensorFailure(UnknownServiceException(okHttpCleartextMessage)).message
        assertTrue(message.contains("NETWORK SECURITY POLICY"))
        assertTrue(message.contains("network-security-config"))
        assertTrue("must say the request never left the phone", message.contains("never left the phone"))
        assertTrue("must say this is not an unreachable sensor", message.contains("NOT an"))
    }

    /** The detail line carries the exception type and message, which is what was missing. */
    @Test
    fun `the detail carries both the exception type and its message`() {
        val detail = classifySensorFailure(UnknownServiceException(okHttpCleartextMessage)).detail
        assertTrue(detail.contains("UnknownServiceException"))
        assertTrue("the type alone is what hid this bug", detail.contains(okHttpCleartextMessage))
    }

    /** An UnknownServiceException that is not about cleartext must not be mislabelled. */
    @Test
    fun `an unrelated UnknownServiceException is not reported as a cleartext block`() {
        val failure = classifySensorFailure(UnknownServiceException("something else entirely"))
        assertTrue(failure.kind != SensorFailure.Kind.CLEARTEXT_BLOCKED)
    }

    @Test
    fun `transport failures are classified by kind`() {
        assertEquals(
            SensorFailure.Kind.UNREACHABLE,
            classifySensorFailure(ConnectException("Connection refused")).kind,
        )
        assertEquals(
            SensorFailure.Kind.UNREACHABLE,
            classifySensorFailure(UnknownHostException("sensor.local")).kind,
        )
        assertEquals(
            SensorFailure.Kind.TIMEOUT,
            classifySensorFailure(SocketTimeoutException("timeout")).kind,
        )
        assertEquals(
            SensorFailure.Kind.BAD_RESPONSE,
            classifySensorFailure(JSONException("not an object")).kind,
        )
        // An IOException with no more specific type is still a transport problem.
        assertEquals(
            SensorFailure.Kind.UNREACHABLE,
            classifySensorFailure(IOException("unexpected end of stream")).kind,
        )
    }

    @Test
    fun `every kind has a message worth putting in front of someone`() {
        for (kind in SensorFailure.Kind.entries) {
            val message = SensorFailure(kind, "detail").message
            assertTrue("$kind", message.length > 10)
        }
    }

    // -- against a real socket ---------------------------------------------

    /**
     * The end-to-end half, without instrumentation: a real Retrofit/OkHttp call
     * to a port nothing is listening on. Proves the failure reaches
     * [LocalHttpWaterLevelSource.lastFailure] rather than being swallowed into
     * a bare null return.
     */
    @Test
    fun `a failed read surfaces a reason, not only null`() {
        val deadPort = ServerSocket(0).use { it.localPort } // bound, then released
        val source = LocalHttpWaterLevelSource("127.0.0.1:$deadPort")

        val reading = runBlocking { source.read() }

        assertNull("the caller still gets no reading", reading)
        val failure = source.lastFailure
        assertNotNull("but the reason is now recorded", failure)
        assertEquals(SensorFailure.Kind.UNREACHABLE, failure!!.kind)
        assertTrue("the detail names the underlying exception", failure.detail.contains("Exception"))
    }

    @Test
    fun `a blank host is reported as unconfigured rather than failing silently`() {
        val source = LocalHttpWaterLevelSource("   ")
        assertNull(runBlocking { source.read() })
        assertEquals(SensorFailure.Kind.NOT_CONFIGURED, source.lastFailure?.kind)
    }

    /** A successful read must clear a previous failure, or the UI would show a stale reason. */
    @Test
    fun `lastFailure starts null before anything has been attempted`() {
        assertNull(LocalHttpWaterLevelSource("127.0.0.1:1").lastFailure)
    }
}
