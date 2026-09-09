package com.waveq.app

import com.waveq.app.sensor.SENSOR_EVENT_BUCKET_MS
import com.waveq.app.sensor.WaterLevelDetector
import com.waveq.app.sensor.sensorEventId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hysteresis and latching, driven directly rather than over HTTP.
 *
 * These rules are what stop a momentary splash sirening every phone in range,
 * so they are tested as a state machine with no network, clock or coroutine.
 */
class WaterLevelDetectorTest {

    private fun detector(trigger: Int = 50, clear: Int = 20, consecutive: Int = 2) =
        WaterLevelDetector(trigger, clear, consecutive)

    @Test
    fun `does not fire below the trigger threshold`() {
        val d = detector()
        repeat(10) { assertFalse(d.onReading(1)) }
        assertFalse(d.latched)
    }

    @Test
    fun `does not fire on a single spike`() {
        val d = detector()
        // One splash. The probe reads submerged for exactly one poll.
        assertFalse(d.onReading(98))
        assertFalse(d.latched)
    }

    @Test
    fun `fires after the required consecutive readings`() {
        val d = detector(consecutive = 2)
        assertFalse(d.onReading(98))
        assertTrue(d.onReading(97))
        assertTrue(d.latched)
    }

    @Test
    fun `a failed read breaks the run`() {
        val d = detector(consecutive = 2)
        assertFalse(d.onReading(98))
        // Sensor unreachable between the two readings: they are not consecutive.
        d.onReadFailure()
        assertFalse(d.onReading(98))
        assertTrue(d.onReading(99))
    }

    @Test
    fun `does not re-fire while latched`() {
        val d = detector(consecutive = 2)
        d.onReading(98)
        assertTrue(d.onReading(98))
        repeat(20) { assertFalse(d.onReading(99)) }
    }

    @Test
    fun `does not re-arm between clear and trigger`() {
        val d = detector(trigger = 50, clear = 20, consecutive = 2)
        d.onReading(98)
        assertTrue(d.onReading(98))
        // Hovering in the latch band: below trigger, above clear.
        d.onReading(30)
        assertTrue("still latched between clear and trigger", d.latched)
        assertFalse(d.onReading(98))
        assertFalse(d.onReading(98))
    }

    @Test
    fun `re-arms only after dropping below the clear threshold`() {
        val d = detector(trigger = 50, clear = 20, consecutive = 2)
        d.onReading(98)
        assertTrue(d.onReading(98))
        d.onReading(1)
        assertFalse("latch released below clear", d.latched)
        assertFalse(d.onReading(98))
        assertTrue("re-arms for a genuine second crossing", d.onReading(98))
    }

    @Test
    fun `two bridges detecting the same crossing share one event id`() {
        val host = "10.185.203.84"
        val base = 1_800_000_000_000L
        // Two devices poll the same probe ~700ms apart.
        assertEquals(sensorEventId(host, base), sensorEventId(host, base + 700))
    }

    @Test
    fun `a later crossing gets a different event id`() {
        val host = "10.185.203.84"
        val base = 1_800_000_000_000L
        assertNotEquals(sensorEventId(host, base), sensorEventId(host, base + SENSOR_EVENT_BUCKET_MS))
    }

    @Test
    fun `different sensors never share an event id`() {
        val at = 1_800_000_000_000L
        assertNotEquals(sensorEventId("10.0.0.1", at), sensorEventId("10.0.0.2", at))
    }
}
