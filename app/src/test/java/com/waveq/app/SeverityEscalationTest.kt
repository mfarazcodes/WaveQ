package com.waveq.app

import com.waveq.app.ui.components.SIREN_THRESHOLD
import com.waveq.app.ui.components.Severity
import com.waveq.app.ui.components.soundsAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the escalation threshold.
 *
 * This exists because the bug it guards against shipped twice: the alert path
 * decided escalation with `severity == Severity.CRITICAL`, which excluded
 * EVACUATE - the level above it, and the one an operator picks when people must
 * leave immediately. An equality check cannot express "at or above".
 */
class SeverityEscalationTest {

    @Test
    fun `evacuate ranks above critical`() {
        assertTrue(Severity.EVACUATE.ordinal > Severity.CRITICAL.ordinal)
    }

    @Test
    fun `critical and evacuate sound the alarm`() {
        assertTrue(Severity.CRITICAL.soundsAlarm)
        assertTrue(Severity.EVACUATE.soundsAlarm)
    }

    @Test
    fun `low medium and high do not sound the alarm`() {
        assertFalse(Severity.LOW.soundsAlarm)
        assertFalse(Severity.MEDIUM.soundsAlarm)
        // Intentional: alerts that wake every phone in range must stay rare.
        assertFalse(Severity.HIGH.soundsAlarm)
    }

    @Test
    fun `threshold is critical and every level at or above it escalates`() {
        assertEquals(Severity.CRITICAL, SIREN_THRESHOLD)
        Severity.entries.forEach { severity ->
            assertEquals(
                "escalation for $severity disagrees with the declared threshold",
                severity.ordinal >= SIREN_THRESHOLD.ordinal,
                severity.soundsAlarm,
            )
        }
    }
}
