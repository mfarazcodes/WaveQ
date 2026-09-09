package com.waveq.app.alerts

/**
 * How old an alert may be and still sound the siren.
 *
 * Store-and-forward replays every unexpired envelope to each newly connected
 * peer, and the message store's TTL is 24 hours. Without this window, every
 * alert a device had ever carried re-sirened on every reconnect - each new
 * endpoint id looked like a peer that had not seen it, and
 * `MessageStore.markDelivered` is keyed on endpoint ids that Nearby regenerates
 * each session.
 *
 * Fifteen minutes is chosen against the thing being claimed rather than against
 * the transport: a siren and a full-screen takeover assert "this is happening
 * now, act". A flood alert broadcast twenty minutes ago may well still be true,
 * but the device has no way to know that, and an alarm for a past event teaches
 * people to ignore the next one.
 *
 * This is NOT a delivery window. A stale alert is still recorded, still
 * relayed, and still appears in the alert list and chat with its original
 * timestamp - a device joining ten minutes into a flood is still told. Only the
 * alarm is withheld. See [freshnessOf].
 */
const val ESCALATION_FRESHNESS_MS = 15 * 60 * 1000L

/**
 * How far ahead of this device's clock another device's timestamp may sit
 * before it is worth logging as skew.
 *
 * Only a reporting threshold - a future-dated alert escalates either way, see
 * [freshnessOf]. Phones in a disaster area have no NTP and their clocks drift;
 * a couple of minutes is normal and not worth a log line.
 */
const val CLOCK_SKEW_TOLERANCE_MS = 2 * 60 * 1000L

/**
 * Whether an alert is recent enough to raise an alarm, and how old it is.
 *
 * [ageMs] is negative for an alert timestamped in the future.
 */
data class FreshnessVerdict(
    val shouldEscalate: Boolean,
    val ageMs: Long,
    val clockSkewed: Boolean,
) {
    /** Ready for a log line: `age=3m12s fresh` / `age=4h1m stale`. */
    val describe: String
        get() = buildString {
            append("age=")
            append(formatAge(ageMs))
            append(if (shouldEscalate) " fresh" else " stale")
            if (clockSkewed) append(" CLOCK-SKEWED (sender's clock is ahead of ours)")
        }
}

/**
 * Decides whether an alert timestamped [timestampMs] may still escalate.
 *
 * ## Clock skew
 *
 * A timestamp in the future counts as FRESH, deliberately. The alternative -
 * treating it as stale, or dropping it - silences a real alert from a device
 * whose clock happens to run fast, which on this app's only network (peers with
 * no internet and no time sync) is a normal condition rather than an attack.
 * Escalating is the safe direction for a life-safety alert, and the exposure is
 * bounded: dedup is persistent and per-id, so a future-dated alert escalates at
 * most once on this device no matter how often it is replayed. It cannot
 * "escalate forever".
 *
 * The skew is reported through [FreshnessVerdict.clockSkewed] rather than acted
 * on, so a device with a badly wrong clock is diagnosable from logcat.
 */
fun freshnessOf(
    timestampMs: Long,
    windowMs: Long,
    now: Long = System.currentTimeMillis(),
): FreshnessVerdict {
    val age = now - timestampMs
    return FreshnessVerdict(
        // `<=` rather than `in 0..windowMs`: a negative age is a future
        // timestamp, which is fresh by the reasoning above.
        shouldEscalate = age <= windowMs,
        ageMs = age,
        clockSkewed = age < -CLOCK_SKEW_TOLERANCE_MS,
    )
}

private fun formatAge(ageMs: Long): String {
    val seconds = ageMs / 1000
    val sign = if (seconds < 0) "-" else ""
    val abs = kotlin.math.abs(seconds)
    return when {
        abs < 60 -> "$sign${abs}s"
        abs < 3600 -> "$sign${abs / 60}m${abs % 60}s"
        else -> "$sign${abs / 3600}h${(abs % 3600) / 60}m"
    }
}
