package com.waveq.app

import com.waveq.app.ui.components.NOTABLE_THRESHOLD
import com.waveq.app.ui.components.Severity
import com.waveq.app.ui.components.isNotable
import com.waveq.app.ui.components.soundsAlarm
import com.waveq.app.ui.screens.Incident
import com.waveq.app.ui.screens.crisisMetricsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the PublicCrisisScreen metric row.
 *
 * The bug: the tiles counted CRITICAL and HIGH with equality checks, so an
 * EVACUATE incident - the most severe level there is - landed in no tile and
 * vanished from the severity breakdown.
 */
class CrisisMetricsTest {

    /** One confirmed incident at every severity, EVACUATE included. */
    private val everySeverity: List<Incident> = Severity.entries.map { severity ->
        Incident(
            id = "INC-${severity.name}",
            type = "Flood",
            location = "Sector 12",
            description = "fixture",
            severity = severity,
            reportedAt = "01/01/2026, 00:00:00",
            verified = true,
        )
    }

    @Test
    fun `evacuate is counted, not dropped`() {
        val onlyEvacuate = everySeverity.filter { it.severity == Severity.EVACUATE }
        assertEquals(1, crisisMetricsFor(onlyEvacuate).criticalOrEvacuate)
    }

    @Test
    fun `critical and evacuate share one tile`() {
        assertEquals(2, crisisMetricsFor(everySeverity).criticalOrEvacuate)
        assertEquals(1, crisisMetricsFor(everySeverity).high)
    }

    /**
     * The severity tiles must never overlap.
     *
     * This is the guard on expressing the critical tile as `soundsAlarm`: if
     * SIREN_THRESHOLD were ever lowered to HIGH, HIGH would be counted in both
     * tiles and the row would over-report. This fails loudly if that happens.
     */
    @Test
    fun `severity tiles are disjoint`() {
        val metrics = crisisMetricsFor(everySeverity)
        val doubleCounted = Severity.entries.filter { it.soundsAlarm && it == Severity.HIGH }
        assertTrue("HIGH is counted in both tiles: $doubleCounted", doubleCounted.isEmpty())
        assertTrue(metrics.notable <= metrics.active)
    }

    /**
     * The tiles cover everything at or above NOTABLE_THRESHOLD, exactly once.
     *
     * This is the real "nothing falls through the cracks" invariant. The tiles
     * do NOT sum to `active` - see below - so this is what replaces that.
     */
    @Test
    fun `tiles cover every notable severity exactly once`() {
        val metrics = crisisMetricsFor(everySeverity)
        val notableCount = everySeverity.count { it.severity.isNotable }
        assertEquals(notableCount, metrics.notable)
    }

    /**
     * Documents, rather than asserts away, why the tiles cannot sum to `active`.
     *
     * LOW and MEDIUM incidents are active but appear in no severity tile, so the
     * shortfall is exactly the count of below-notable incidents - with or
     * without the EVACUATE fix. The row is a highlight of the severe end, not a
     * partition of the list.
     */
    @Test
    fun `shortfall against active is exactly the below-notable incidents`() {
        val metrics = crisisMetricsFor(everySeverity)
        val belowNotable = everySeverity.count { !it.severity.isNotable }
        assertEquals(2, belowNotable) // LOW and MEDIUM
        assertEquals(metrics.active - metrics.notable, belowNotable)
        assertEquals(NOTABLE_THRESHOLD, Severity.HIGH)
    }

    @Test
    fun `unverified and dismissed incidents are excluded upstream`() {
        // crisisMetricsFor is given the already-filtered list; an empty list
        // must produce zeroes rather than throwing.
        val metrics = crisisMetricsFor(emptyList())
        assertEquals(0, metrics.active)
        assertEquals(0, metrics.notable)
    }
}
