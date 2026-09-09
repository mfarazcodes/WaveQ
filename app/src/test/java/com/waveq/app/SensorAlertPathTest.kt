package com.waveq.app

import com.waveq.app.alerts.AlertDedupCache
import com.waveq.app.mesh.MeshEnvelope
import com.waveq.app.mesh.MeshPayload
import com.waveq.app.mesh.MeshSerialization
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.SENSOR_SENDER_NAME
import com.waveq.app.mesh.SOS_CHANNEL_ID
import com.waveq.app.sensor.SensorAlertPayload
import com.waveq.app.sensor.SensorConfig
import com.waveq.app.sensor.WaterLevelMonitor
import com.waveq.app.sensor.WaterLevelReading
import com.waveq.app.sensor.WaterLevelSource
import com.waveq.app.sensor.sensorEventId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted readings, or an unreachable sensor. No HTTP. */
private class FakeWaterLevelSource(
    private val readings: MutableList<Int?>,
) : WaterLevelSource {
    override suspend fun read(): WaterLevelReading? {
        val next = if (readings.isEmpty()) null else readings.removeAt(0)
        return next?.let { WaterLevelReading(percent = it, raw = it * 10) }
    }

    override suspend fun isReachable(): Boolean = readings.firstOrNull() != null
}

class SensorAlertPathTest {

    private val config = SensorConfig(
        enabled = true,
        host = "10.185.203.84",
        triggerPercent = 50,
        clearPercent = 20,
        consecutiveRequired = 2,
    )

    private fun monitorOver(
        readings: MutableList<Int?>,
        fired: MutableList<Pair<Int, String>>,
    ): WaterLevelMonitor {
        val source = FakeWaterLevelSource(readings)
        return WaterLevelMonitor(
            configProvider = { config },
            sourceFactory = { source },
            onWaterDetected = { percent, host, _, _ -> fired.add(percent to host) },
        )
    }

    @Test
    fun `an unreachable sensor reports unreachable, not zero percent`() = runTest {
        val fired = mutableListOf<Pair<Int, String>>()
        val monitor = monitorOver(mutableListOf(null), fired)

        monitor.pollOnce()

        val status = monitor.status.value
        assertFalse("must not claim reachable", status.reachable)
        assertFalse("must not claim to be bridging", status.isBridge)
        // The distinction that matters: no reading at all, NOT a reading of 0.
        assertNull("unreachable must never render as 0%", status.lastPercent)
        assertEquals("Sensor unreachable", status.label)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a real zero reading is distinct from unreachable`() = runTest {
        val fired = mutableListOf<Pair<Int, String>>()
        val monitor = monitorOver(mutableListOf(0), fired)

        monitor.pollOnce()

        val status = monitor.status.value
        assertTrue(status.reachable)
        assertTrue(status.isBridge)
        assertEquals(0, status.lastPercent)
        assertEquals("No water detected", status.label)
    }

    @Test
    fun `polling fires once after consecutive submerged readings`() = runTest {
        val fired = mutableListOf<Pair<Int, String>>()
        val monitor = monitorOver(mutableListOf(0, 98, 97, 99, 98), fired)

        repeat(5) { monitor.pollOnce() }

        assertEquals("exactly one crossing", 1, fired.size)
        assertEquals(97, fired.first().first)
        assertEquals("10.185.203.84", fired.first().second)
        assertTrue(monitor.status.value.latched)
    }

    @Test
    fun `two bridges on the same crossing produce exactly one alert`() {
        val dedup = AlertDedupCache(200)
        val host = "10.185.203.84"
        val crossing = 1_800_000_000_000L

        // Two devices poll the same probe 800ms apart and both broadcast.
        val bridgeA = sensorEventId(host, crossing)
        val bridgeB = sensorEventId(host, crossing + 800)

        assertTrue("first bridge alerts", dedup.claim(bridgeA))
        assertFalse("second bridge is collapsed", dedup.claim(bridgeB))
        assertEquals(1, dedup.size())
    }

    @Test
    fun `a genuine later crossing alerts again`() {
        val dedup = AlertDedupCache(200)
        val host = "10.185.203.84"
        val first = 1_800_000_000_000L

        assertTrue(dedup.claim(sensorEventId(host, first)))
        // A separate crossing, a minute later - a different bucket.
        assertTrue(dedup.claim(sensorEventId(host, first + 60_000)))
        assertEquals(2, dedup.size())
    }

    @Test
    fun `messageId dedup alone cannot collapse two bridges`() {
        // The reason the event-id layer exists at all: two broadcasts of one
        // physical event carry different messageIds.
        val dedup = AlertDedupCache(200)
        assertTrue(dedup.claim("message-from-bridge-a"))
        assertTrue("distinct messageIds both pass", dedup.claim("message-from-bridge-b"))
    }

    @Test
    fun `a device with no sensor access can still act on a received alert`() {
        // This device never polls anything: it only receives. The payload has to
        // carry everything needed to escalate and to frame the alert as relayed.
        val onTheWire = SensorAlertPayload(
            eventId = sensorEventId("10.185.203.84", 1_800_000_000_000L),
            sensorHost = "10.185.203.84",
            percent = 98,
            bridgeDeviceId = "bridge-device-id",
            bridgeDeviceName = "Priya",
            detectedAtMs = 1_800_000_000_000L,
        ).toJson()

        val received = SensorAlertPayload.fromJson(onTheWire)
        assertNotNull(received)
        requireNotNull(received)
        assertEquals("10.185.203.84", received.sensorHost)
        assertEquals("Priya", received.bridgeDeviceName)
        assertTrue(AlertDedupCache(200).claim(received.eventId))
        // Wording must not imply a depth measurement.
        assertTrue(received.headline().contains("Water detected"))
        assertFalse(received.headline().contains("%"))
    }

    @Test
    fun `a malformed sensor payload is dropped, not guessed at`() {
        assertNull(SensorAlertPayload.fromJson(null))
        assertNull(SensorAlertPayload.fromJson("not json"))
        assertNull(SensorAlertPayload.fromJson("""{"eventId":"x"}"""))
    }

    @Test
    fun `a sensor alert round-trips through the wire format`() {
        val sensorJson = SensorAlertPayload(
            eventId = "10.0.0.5@30000000",
            sensorHost = "10.0.0.5",
            percent = 99,
            bridgeDeviceId = "abc",
            bridgeDeviceName = "Bridge phone",
            detectedAtMs = 1_800_000_000_000L,
        ).toJson()

        val payload = MeshPayload(
            senderId = "abc",
            senderName = SENSOR_SENDER_NAME,
            type = MessageType.SENSOR_ALERT,
            text = "Water detected by sensor at 10.0.0.5",
            audioFileName = null,
            timestamp = 1_800_000_000_000L,
            severity = "CRITICAL",
            sensorJson = sensorJson,
        )

        val decoded = MeshSerialization.payloadFromJson(MeshSerialization.payloadToJson(payload))
        assertEquals(MessageType.SENSOR_ALERT, decoded.type)
        // Never the user's display name.
        assertEquals(SENSOR_SENDER_NAME, decoded.senderName)
        assertEquals(sensorJson, decoded.sensorJson)
        assertEquals("10.0.0.5", SensorAlertPayload.fromJson(decoded.sensorJson)?.sensorHost)
    }

    /**
     * Store-and-forward retention is decided by channel, not by MessageType:
     * MessageStore.record branches only on `channelId == SOS_CHANNEL_ID` and
     * stores everything else with the standard TTL. A SENSOR_ALERT rides the
     * City-Wide channel, so it is retained and replayed to peers that connect
     * later, with no change needed to the store.
     *
     * Asserted here as far as a JVM test can: the envelope survives encoding,
     * and it is not on the SOS channel, so it takes the retained branch. Proving
     * the Room round-trip needs an instrumentation test.
     */
    @Test
    fun `a sensor alert envelope is on a retained channel and round-trips`() {
        val cityChannel = "city-wide-test-channel"
        val envelope = MeshEnvelope(
            messageId = "msg-1",
            channelId = cityChannel,
            isEncrypted = false,
            iv = null,
            payload = "sensor-payload".toByteArray(),
            hopCount = 0,
            maxHops = 12,
        )
        assertFalse("not the SOS channel, so it takes the standard retention branch",
            envelope.channelId == SOS_CHANNEL_ID)

        val decoded = MeshSerialization.decodeEnvelope(MeshSerialization.encodeEnvelope(envelope))
        assertEquals(cityChannel, decoded.channelId)
        assertEquals(12, decoded.maxHops)
    }
}
