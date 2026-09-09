package com.waveq.app.alerts

import android.content.Context
import com.waveq.app.mesh.MESSAGE_STORE_TTL_MS
import org.json.JSONObject
import java.util.LinkedHashMap

private const val PREFS_FILE = "waveq_alert_dedup"

/**
 * Where claimed ids are kept so they outlive the process.
 *
 * An interface rather than a direct SharedPreferences call so [AlertDedupCache]
 * stays a plain JVM class: a test simulates a process restart by building a
 * second cache over the same log, with no Android runtime involved.
 */
interface ClaimLog {
    fun load(): Map<String, Long>
    fun save(entries: Map<String, Long>)
}

/** For a cache that only needs to live as long as the process. */
object NoClaimLog : ClaimLog {
    override fun load(): Map<String, Long> = emptyMap()
    override fun save(entries: Map<String, Long>) = Unit
}

/**
 * SharedPreferences-backed claim log, one JSON blob per [key].
 *
 * SharedPreferences rather than Room, matching [AlertSettings]: this is read
 * once at startup and written from a mesh callback with no ViewModel in scope,
 * and adding a table to `MessageStoreDatabase` would need a schema migration on
 * a database that ships without one. The blob is bounded by the cache's
 * capacity, so it stays a few kilobytes.
 */
class SharedPrefsClaimLog(context: Context, private val key: String) : ClaimLog {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun load(): Map<String, Long> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { id -> put(id, obj.getLong(id)) }
            }
        } catch (e: Exception) {
            // A corrupt blob costs at most one duplicate escalation; refusing to
            // start the alert path over it would cost every alert.
            emptyMap()
        }
    }

    override fun save(entries: Map<String, Long>) {
        val obj = JSONObject()
        entries.forEach { (id, at) -> obj.put(id, at) }
        prefs.edit().putString(key, obj.toString()).apply()
    }
}

/**
 * Bounded, expiring "have I already alerted on this?" set.
 *
 * Extracted because [MeshAlertDispatcher] needs three of them keyed on different
 * identities, and the multi-bridge one is the subtlest rule in the sensor path -
 * it deserves to be testable on its own rather than buried in a collector.
 *
 * ## Why it persists
 *
 * This used to be memory-only, so an app restart forgot every id it had already
 * alerted on. Store-and-forward then replayed the same envelopes from a peer -
 * `MessageStore.markDelivered` is keyed on Nearby endpoint ids, which are
 * regenerated each session, so a reconnecting peer always looks like one that
 * has not seen them - and every alert fired again. Persisting the claim is what
 * makes "escalate once" mean once per device rather than once per process.
 *
 * ## Why entries expire
 *
 * [ttlMs] should match the store TTL for the same traffic ([MESSAGE_STORE_TTL_MS]
 * for messages, [SOS_STORE_TTL_MS] for beacons). Past that point nothing can
 * replay the id anyway, so keeping the claim only grows the blob. Capacity is
 * the second bound, for the case where far more ids arrive inside one TTL than
 * expected.
 */
class AlertDedupCache(
    private val capacity: Int,
    private val ttlMs: Long = MESSAGE_STORE_TTL_MS,
    private val log: ClaimLog = NoClaimLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** id -> when this device claimed it. LRU by access order, hard-capped at [capacity]. */
    private val claimedAt = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > capacity
    }

    private var loaded = false

    /** True the first time an id is claimed on this device, false every time after. */
    @Synchronized
    fun claim(id: String): Boolean {
        ensureLoaded()
        purgeExpired()
        if (claimedAt.containsKey(id)) return false
        claimedAt[id] = clock()
        log.save(LinkedHashMap(claimedAt))
        return true
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        purgeExpired()
        return claimedAt.size
    }

    /**
     * Deferred rather than done in the constructor: the dispatcher builds these
     * before the first alert arrives, and reading prefs is disk I/O.
     */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        // Oldest first, so if the persisted set is over capacity the LRU keeps
        // the most recent claims - the ones still replayable.
        log.load().entries.sortedBy { it.value }.forEach { claimedAt[it.key] = it.value }
    }

    private fun purgeExpired() {
        val cutoff = clock() - ttlMs
        val iterator = claimedAt.entries.iterator()
        var removed = false
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
                removed = true
            }
        }
        if (removed) log.save(LinkedHashMap(claimedAt))
    }
}
