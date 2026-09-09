package com.waveq.app

import com.waveq.app.alerts.AlertDedupCache
import com.waveq.app.alerts.CLOCK_SKEW_TOLERANCE_MS
import com.waveq.app.alerts.ClaimLog
import com.waveq.app.alerts.ESCALATION_FRESHNESS_MS
import com.waveq.app.alerts.freshnessOf
import com.waveq.app.mesh.MESSAGE_STORE_TTL_MS
import com.waveq.app.mesh.SOS_STORE_TTL_MS
import com.waveq.app.ui.components.Severity
import com.waveq.app.ui.components.soundsAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * The escalation-freshness rule and the claim log behind it.
 *
 * The bug these pin down: store-and-forward replays every unexpired envelope to
 * each newly connected peer, `MessageStore.markDelivered` is keyed on Nearby
 * endpoint ids that are regenerated every session, and the dedup cache used to
 * be memory-only - so every alert a device had ever carried re-sirened on every
 * reconnect, and again after every app restart.
 */
class AlertFreshnessTest {

    /** A fake for [com.waveq.app.alerts.SharedPrefsClaimLog]; surviving this object is "surviving a restart". */
    private class FakeClaimLog : ClaimLog {
        var stored: Map<String, Long> = emptyMap()
        var saves = 0
        override fun load(): Map<String, Long> = stored
        override fun save(entries: Map<String, Long>) {
            stored = LinkedHashMap(entries)
            saves++
        }
    }

    private val now = 1_800_000_000_000L

    // -- the freshness window ---------------------------------------------

    @Test
    fun `an alert inside the window escalates`() {
        for (age in longArrayOf(0, 1000, MINUTE, 5 * MINUTE, ESCALATION_FRESHNESS_MS)) {
            val verdict = freshnessOf(now - age, ESCALATION_FRESHNESS_MS, now)
            assertTrue("age=${age}ms", verdict.shouldEscalate)
            assertEquals(age, verdict.ageMs)
        }
    }

    @Test
    fun `an alert older than the window is delivered without escalating`() {
        for (age in longArrayOf(ESCALATION_FRESHNESS_MS + 1, 20 * MINUTE, 4 * HOUR, 23 * HOUR)) {
            val verdict = freshnessOf(now - age, ESCALATION_FRESHNESS_MS, now)
            assertFalse("age=${age}ms", verdict.shouldEscalate)
            // Not dropped, not skew: just old. The dispatcher still calls the
            // trigger, with escalationAllowed = false.
            assertFalse(verdict.clockSkewed)
        }
    }

    /**
     * Store-and-forward is unaffected: the whole point is that a device
     * arriving ten minutes into a flood is still told, and one arriving four
     * hours in still gets the message - just not the siren.
     */
    @Test
    fun `a stale alert is still well inside the store TTL, so it is still carried and replayed`() {
        assertTrue(
            "the escalation window must be shorter than the store TTL, or nothing would ever " +
                "be delivered silently",
            ESCALATION_FRESHNESS_MS < MESSAGE_STORE_TTL_MS,
        )
        val sentAt = now - 4 * HOUR
        assertFalse(freshnessOf(sentAt, ESCALATION_FRESHNESS_MS, now).shouldEscalate)
        // MessageStore stamps expiresAt = firstSeenAt + MESSAGE_STORE_TTL_MS and
        // replays everything unexpired, so this envelope is still on the wire.
        assertTrue(now < sentAt + MESSAGE_STORE_TTL_MS)
    }

    /**
     * Mirrors the composition in `CriticalAlertTrigger.fire`: severity says the
     * content warrants an alarm, freshness says an alarm would still be honest,
     * and both are required.
     */
    @Test
    fun `the alarm needs both a high enough severity and a recent enough timestamp`() {
        fun escalates(severity: Severity, ageMs: Long) =
            severity.soundsAlarm && freshnessOf(now - ageMs, ESCALATION_FRESHNESS_MS, now).shouldEscalate

        assertTrue(escalates(Severity.CRITICAL, 5 * MINUTE))
        assertTrue(escalates(Severity.EVACUATE, 5 * MINUTE))
        // Fresh, but below the siren threshold.
        assertFalse(escalates(Severity.HIGH, 5 * MINUTE))
        // Severe enough, but hours old - this is the reported bug.
        assertFalse(escalates(Severity.CRITICAL, 4 * HOUR))
        assertFalse(escalates(Severity.EVACUATE, 4 * HOUR))
    }

    // -- clock skew --------------------------------------------------------

    /**
     * Future-dated counts as fresh. Peers here have no internet and no time
     * sync, so a fast clock is a normal condition rather than an attack, and
     * silencing a real alert over it is the worse failure. Persistent dedup
     * bounds the exposure to one escalation per id.
     */
    @Test
    fun `an alert dated in the future is treated as fresh, not stale`() {
        for (ahead in longArrayOf(1000, MINUTE, 10 * MINUTE, 5 * HOUR, 365 * 24 * HOUR)) {
            val verdict = freshnessOf(now + ahead, ESCALATION_FRESHNESS_MS, now)
            assertTrue("ahead=${ahead}ms", verdict.shouldEscalate)
            assertEquals(-ahead, verdict.ageMs)
        }
    }

    @Test
    fun `only skew beyond the tolerance is reported, and reporting does not change the verdict`() {
        val withinTolerance = freshnessOf(now + MINUTE, ESCALATION_FRESHNESS_MS, now)
        assertFalse(withinTolerance.clockSkewed)
        assertTrue(withinTolerance.shouldEscalate)

        val beyondTolerance = freshnessOf(now + CLOCK_SKEW_TOLERANCE_MS + 1, ESCALATION_FRESHNESS_MS, now)
        assertTrue(beyondTolerance.clockSkewed)
        assertTrue("skew is logged, not acted on", beyondTolerance.shouldEscalate)
        assertTrue(beyondTolerance.describe.contains("CLOCK-SKEWED"))
    }

    // -- persistent dedup --------------------------------------------------

    /**
     * The restart half of the bug. A replayed alert must not re-escalate after
     * the process dies, which a memory-only cache could not prevent.
     */
    @Test
    fun `a replayed alert does not re-escalate after a process restart`() {
        val log = FakeClaimLog()
        var clock = now

        val beforeRestart = AlertDedupCache(200, MESSAGE_STORE_TTL_MS, log) { clock }
        assertTrue("first sighting escalates", beforeRestart.claim("msg-1"))
        assertFalse("a duplicate in the same process does not", beforeRestart.claim("msg-1"))

        // Process death, app relaunch, peer reconnects with a new endpoint id
        // and replays the same envelope from its store.
        clock += 30 * MINUTE
        val afterRestart = AlertDedupCache(200, MESSAGE_STORE_TTL_MS, log) { clock }
        assertFalse("the replay must not re-escalate", afterRestart.claim("msg-1"))
        assertTrue("an alert never seen before still escalates", afterRestart.claim("msg-2"))
    }

    /** Contrast: without a log this is exactly the reported behaviour. */
    @Test
    fun `a memory only cache forgets across a restart, which is the bug`() {
        var clock = now
        assertTrue(AlertDedupCache(200, MESSAGE_STORE_TTL_MS) { clock }.claim("msg-1"))
        clock += 30 * MINUTE
        assertTrue(AlertDedupCache(200, MESSAGE_STORE_TTL_MS) { clock }.claim("msg-1"))
    }

    @Test
    fun `claims expire once nothing can replay them, so the log cannot grow forever`() {
        val log = FakeClaimLog()
        var clock = now
        assertTrue(AlertDedupCache(200, MESSAGE_STORE_TTL_MS, log) { clock }.claim("msg-1"))

        // Just inside the store TTL: still replayable, so still claimed.
        clock += MESSAGE_STORE_TTL_MS - MINUTE
        assertFalse(AlertDedupCache(200, MESSAGE_STORE_TTL_MS, log) { clock }.claim("msg-1"))

        // Past it: MessageStore has purged the envelope, so the claim has
        // nothing left to protect and is dropped.
        clock += 2 * MINUTE
        val expired = AlertDedupCache(200, MESSAGE_STORE_TTL_MS, log) { clock }
        assertTrue(expired.claim("msg-1"))
        assertEquals(1, expired.size())
    }

    @Test
    fun `sos claims use the longer sos ttl`() {
        val log = FakeClaimLog()
        var clock = now
        assertTrue(AlertDedupCache(200, SOS_STORE_TTL_MS, log) { clock }.claim("beacon-1"))
        // Past the message TTL would be irrelevant here; what matters is the 6h SOS TTL.
        clock += SOS_STORE_TTL_MS - MINUTE
        assertFalse(AlertDedupCache(200, SOS_STORE_TTL_MS, log) { clock }.claim("beacon-1"))
        clock += 2 * MINUTE
        assertTrue(AlertDedupCache(200, SOS_STORE_TTL_MS, log) { clock }.claim("beacon-1"))
    }

    @Test
    fun `the claim set stays bounded by capacity`() {
        val log = FakeClaimLog()
        val cache = AlertDedupCache(3, MESSAGE_STORE_TTL_MS, log) { now }
        repeat(50) { assertTrue(cache.claim("msg-$it")) }
        assertEquals(3, cache.size())
        assertEquals(3, log.stored.size)
    }

    /** A restart must not resurrect more ids than the cache is allowed to hold. */
    @Test
    fun `an oversized persisted log is trimmed to capacity on load, keeping the newest`() {
        val log = FakeClaimLog()
        log.stored = (1..10).associate { "msg-$it" to now - (10 - it) * MINUTE }
        val cache = AlertDedupCache(3, MESSAGE_STORE_TTL_MS, log) { now }
        assertEquals(3, cache.size())
        // msg-10 is the most recent claim, so it is one of the three kept.
        assertFalse(cache.claim("msg-10"))
    }
}
