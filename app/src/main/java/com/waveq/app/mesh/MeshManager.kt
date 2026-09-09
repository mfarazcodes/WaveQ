package com.waveq.app.mesh

import android.util.Log
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val TAG = "MeshManager"
private const val SEEN_SET_CAPACITY = 200
private const val SEEN_BEACON_CAPACITY = 500
private const val SOS_MAX_HOPS = 12
private const val DEFAULT_MAX_HOPS = 5

/**
 * Risk updates travel as far as SOS beacons do. A device that fetched a
 * forecast is often the only one in a wide area with connectivity, so its
 * assessment has to reach well beyond its immediate neighbours to be worth
 * anything - the whole point is reaching devices that could never fetch it.
 */
const val RISK_UPDATE_MAX_HOPS = 12

/**
 * Sensor alerts travel as far as risk updates and SOS beacons do.
 *
 * The chat default of 5 is wrong for this: a bridging device is often the only
 * one in the area on the sensor's Wi-Fi, and the phones that most need the
 * warning are the ones furthest from any infrastructure. This is an
 * authoritative hazard alert, so it crosses the whole cluster.
 */
const val SENSOR_ALERT_MAX_HOPS = 12
private const val STORE_REPLAY_THROTTLE_MS = 150L

/**
 * Receive-path rate limit, per endpoint.
 *
 * Sized against the busiest legitimate traffic this app produces: a
 * store-and-forward replay sends one envelope every
 * [STORE_REPLAY_THROTTLE_MS] (about 7/s), and everything else - chat, risk
 * updates, alerts - is far rarer. 10/s sustained with a burst of 60 leaves ample
 * headroom while still bounding what one device can do.
 *
 * Without this, every inbound envelope triggered a rebroadcast to all peers plus
 * a Room write, so a single malfunctioning or hostile device could saturate the
 * mesh, fill the 500-row store with junk and burn every peer's battery.
 */
private const val RECEIVE_BUCKET_CAPACITY = 60.0
private const val RECEIVE_REFILL_PER_SECOND = 10.0

/** Consecutive drops from one endpoint before it is disconnected outright. */
private const val RECEIVE_DROPS_BEFORE_DISCONNECT = 50

/** Bound on tracked endpoints, so the limiter cannot itself become a leak. */
private const val MAX_TRACKED_ENDPOINTS = 64

/** What MeshManager needs from the underlying transport - kept transport-agnostic. */
interface MeshTransport {
    /** Returns how many connected peers the payload was dispatched to; 0 means nothing was in range. */
    fun broadcastExcept(bytes: ByteArray, excludeEndpointId: String?): Int
    fun sendTo(endpointId: String, bytes: ByteArray)

    /** Drops a peer that has exceeded the receive-path rate limit. */
    fun disconnect(endpointId: String)
}

/**
 * Token bucket per endpoint, with a consecutive-drop counter so a peer that
 * keeps hammering is eventually cut loose rather than merely throttled forever.
 */
private class ReceiveRateLimiter {

    private class Bucket(var tokens: Double, var lastRefillMs: Long, var consecutiveDrops: Int = 0)

    private val buckets = LinkedHashMap<String, Bucket>()

    /** True to accept the message; false to drop it. */
    @Synchronized
    fun allow(endpointId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val bucket = buckets.getOrPut(endpointId) {
            Bucket(RECEIVE_BUCKET_CAPACITY, nowMs)
        }
        if (buckets.size > MAX_TRACKED_ENDPOINTS) {
            val oldest = buckets.keys.firstOrNull()
            if (oldest != null && oldest != endpointId) buckets.remove(oldest)
        }

        val elapsedSeconds = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L) / 1000.0
        bucket.lastRefillMs = nowMs
        bucket.tokens = (bucket.tokens + elapsedSeconds * RECEIVE_REFILL_PER_SECOND)
            .coerceAtMost(RECEIVE_BUCKET_CAPACITY)

        return if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            bucket.consecutiveDrops = 0
            true
        } else {
            bucket.consecutiveDrops++
            false
        }
    }

    @Synchronized
    fun shouldDisconnect(endpointId: String): Boolean =
        (buckets[endpointId]?.consecutiveDrops ?: 0) >= RECEIVE_DROPS_BEFORE_DISCONNECT

    @Synchronized
    fun forget(endpointId: String) {
        buckets.remove(endpointId)
    }
}

/** Fixed-capacity LRU set of message ids, used for relay dedup. */
private class LruMessageIdCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > capacity
    }
    private val synced = Collections.synchronizedMap(map)

    /** Returns true (and records it) if this id was already seen. */
    fun containsAndAdd(id: String): Boolean {
        synchronized(synced) {
            if (synced.containsKey(id)) return true
            synced[id] = true
            return false
        }
    }
}

/**
 * Fixed-capacity LRU map of beaconId -> highest sequence seen, used for SOS
 * relay dedup. Kept entirely separate from [LruMessageIdCache] so a burst of
 * regular chat traffic can never evict - or get prioritised over - live SOS
 * state.
 */
private class LruBeaconSequenceCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, Int>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > capacity
    }

    /** Returns true (and records [sequence]) only if it is newer than what's stored for [beaconId]. */
    @Synchronized
    fun registerIfNewer(beaconId: String, sequence: Int): Boolean {
        val previous = map[beaconId]
        if (previous != null && sequence <= previous) return false
        map[beaconId] = sequence
        return true
    }
}

/**
 * Implements the relay-then-decrypt algorithm: every envelope is forwarded to
 * other peers purely based on its plaintext routing fields, regardless of
 * whether this device can read it. Decryption is attempted only afterwards,
 * and only for channels this device has actually joined.
 */
class MeshManager(
    private val transport: MeshTransport,
    private val channelRepository: ChannelRepository,
    private val messageStore: MessageStore,
    private val myDeviceId: String,
    private val cacheDir: File,
) {
    private val rateLimiter = ReceiveRateLimiter()
    private val seenIds = LruMessageIdCache(SEEN_SET_CAPACITY)
    private val seenBeacons = LruBeaconSequenceCache(SEEN_BEACON_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<MeshMessage> = _incomingMessages.asSharedFlow()

    private val _incomingSosBeacons = MutableSharedFlow<SosBeacon>(extraBufferCapacity = 64)
    val incomingSosBeacons: SharedFlow<SosBeacon> = _incomingSosBeacons.asSharedFlow()

    /** Store-and-forward activity, surfaced by the UI to make the otherwise-invisible relay behaviour visible. */
    sealed class RelayActivityEvent {
        data class Carried(val isSos: Boolean, val at: Long = System.currentTimeMillis()) : RelayActivityEvent()
        data class DeliveredOnConnect(
            val count: Int,
            val sosCount: Int,
            val endpointId: String,
            val at: Long = System.currentTimeMillis(),
        ) : RelayActivityEvent()
    }

    private val _relayActivity = MutableSharedFlow<RelayActivityEvent>(extraBufferCapacity = 64)
    val relayActivity: SharedFlow<RelayActivityEvent> = _relayActivity.asSharedFlow()

    private val _peerConnections = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /**
     * Endpoint ids of peers as they connect. Lets a feature push its current
     * state to a new neighbour immediately rather than waiting for its next
     * scheduled broadcast - used by the risk repository so a device that walks
     * into range gets the local flood assessment at once.
     */
    val peerConnections: SharedFlow<String> = _peerConnections.asSharedFlow()

    /** Called by the transport layer whenever a raw envelope arrives from a peer. */
    fun onEnvelopeReceived(fromEndpointId: String, envelope: MeshEnvelope) {
        // Rate limit before anything else - relay, store write, decrypt. This is
        // the first thing an inbound envelope touches, so a flooding peer costs
        // one map lookup rather than a broadcast to every neighbour plus a Room
        // insert. SOS is limited too: an attacker able to bypass the limit by
        // setting a channel id would make the limiter pointless.
        if (!rateLimiter.allow(fromEndpointId)) {
            if (rateLimiter.shouldDisconnect(fromEndpointId)) {
                Log.w(TAG, "disconnecting $fromEndpointId - sustained flood on the receive path")
                rateLimiter.forget(fromEndpointId)
                transport.disconnect(fromEndpointId)
            }
            return
        }

        // SOS takes a completely separate path: dedup'd by (beaconId, sequence)
        // rather than messageId, so it is never dropped or delayed by regular
        // chat traffic churning the messageId seen-set.
        if (envelope.channelId == SOS_CHANNEL_ID) {
            onSosEnvelopeReceived(fromEndpointId, envelope)
            return
        }

        // 1 + 2: dedup via seen-set, atomically checked and recorded.
        if (seenIds.containsAndAdd(envelope.messageId)) return

        // 3: relay first, decrypt second - unconditional on whether we can read it.
        if (envelope.hopCount < envelope.maxHops) {
            val relayed = envelope.copy(hopCount = envelope.hopCount + 1)
            transport.broadcastExcept(MeshSerialization.encodeEnvelope(relayed), fromEndpointId)
            storeForRelay(relayed)
        }

        // 4: attempt to decrypt only if we're actually a member of this channel.
        tryDecryptAndEmit(envelope, isMine = false)
    }

    /**
     * Encrypts (if required) and broadcasts a new outbound message, echoing it
     * locally.
     *
     * [maxHops] is overridable because not all traffic is equal: chat stays at
     * the default radius, while risk updates use [RISK_UPDATE_MAX_HOPS] so they
     * reach offline devices far from whoever had connectivity.
     *
     * Returns the number of connected peers the envelope was actually dispatched
     * to, and 0 when the send was refused (role gate, unknown channel, missing
     * key) or nothing was in range. Callers that report delivery to the user must
     * use this rather than a separately-maintained peer counter, which can be
     * stale in both directions. Like [sendSosBeacon], this is a dispatch count,
     * not a delivery acknowledgement.
     */
    fun sendMessage(
        channelId: String,
        payload: MeshPayload,
        audioBytes: ByteArray? = null,
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): Int {
        // The enforcement point that actually matters: even if a UI path is
        // missed (a screen someone forgets to gate, a future caller), an
        // authoritative flood alert never leaves this device unless the
        // session role is OPERATOR/ADMIN. Everything else in this class
        // (relay, decrypt, SOS) stays open to every role - only origination
        // of a FLOOD_ALERT is restricted.
        if (payload.type == MessageType.FLOOD_ALERT) {
            val role = SessionManager.currentRole
            if (role != UserRole.OPERATOR && role != UserRole.ADMIN) {
                Log.w(TAG, "sendMessage: FLOOD_ALERT blocked - role=$role is not OPERATOR/ADMIN")
                return 0
            }
        }

        val channel = channelRepository.getChannel(channelId) ?: run {
            Log.w(TAG, "sendMessage: not a member of channel")
            return 0
        }

        val plaintext = MeshSerialization.encodePayloadWithAudio(payload, audioBytes ?: ByteArray(0))
        val messageId = UUID.randomUUID().toString()

        val envelope = if (channel.isEncrypted) {
            val key = channelRepository.getKey(channelId) ?: run {
                Log.w(TAG, "sendMessage: encrypted channel with no key")
                return 0
            }
            val blob = ChannelCrypto.encrypt(plaintext, key)
            MeshEnvelope(
                messageId = messageId,
                channelId = channelId,
                isEncrypted = true,
                iv = blob.iv,
                payload = blob.ciphertext,
                hopCount = 0,
                maxHops = maxHops,
            )
        } else {
            MeshEnvelope(
                messageId = messageId,
                channelId = channelId,
                isEncrypted = false,
                iv = null,
                payload = plaintext,
                hopCount = 0,
                maxHops = maxHops,
            )
        }

        // Prevent reprocessing our own message if it loops back through the mesh.
        seenIds.containsAndAdd(envelope.messageId)

        val reachedPeers = transport.broadcastExcept(
            MeshSerialization.encodeEnvelope(envelope),
            excludeEndpointId = null,
        )
        storeForRelay(envelope)

        // Nearby never delivers our own outbound payload back to us - echo locally.
        tryDecryptAndEmit(envelope, isMine = true)
        return reachedPeers
    }

    /**
     * Sends a message to one specific peer rather than broadcasting it.
     *
     * Used to hand a newly connected neighbour the current state of something
     * immediately - a risk assessment they may have no way of fetching
     * themselves. Deliberately not stored for relay and not echoed locally:
     * this is a targeted top-up of a peer that just arrived, and the broadcast
     * that originally produced this state has already been stored.
     *
     * Only unencrypted channels are supported, because that is all this is
     * needed for; sending on an encrypted channel here would silently skip the
     * encryption the channel promises, so it is refused instead.
     */
    fun sendMessageTo(
        endpointId: String,
        channelId: String,
        payload: MeshPayload,
        maxHops: Int = DEFAULT_MAX_HOPS,
    ) {
        val channel = channelRepository.getChannel(channelId) ?: return
        if (channel.isEncrypted) {
            Log.w(TAG, "sendMessageTo: refusing to send on an encrypted channel")
            return
        }

        val envelope = MeshEnvelope(
            messageId = UUID.randomUUID().toString(),
            channelId = channelId,
            isEncrypted = false,
            iv = null,
            payload = MeshSerialization.encodePayloadWithAudio(payload, ByteArray(0)),
            hopCount = 0,
            maxHops = maxHops,
        )
        seenIds.containsAndAdd(envelope.messageId)
        transport.sendTo(endpointId, MeshSerialization.encodeEnvelope(envelope))
    }

    /**
     * Broadcasts an SOS beacon: always plaintext, always on [SOS_CHANNEL_ID],
     * never through [ChannelRepository]/[ChannelCrypto] - every device must be
     * able to read it whether or not it shares a passphrase with the sender.
     *
     * Returns the number of peers the beacon actually went out to, so the SOS
     * UI can say "reached N devices" or "no devices in range" truthfully
     * rather than counting attempts.
     */
    fun sendSosBeacon(beacon: SosBeacon): Int {
        val envelope = MeshEnvelope(
            messageId = UUID.randomUUID().toString(),
            channelId = SOS_CHANNEL_ID,
            isEncrypted = false,
            iv = null,
            payload = MeshSerialization.encodeSosBeacon(beacon),
            hopCount = 0,
            maxHops = SOS_MAX_HOPS,
        )

        // Record locally so a copy of our own beacon looping back through the
        // mesh doesn't get treated as a "new" beacon and re-emitted.
        seenBeacons.registerIfNewer(beacon.beaconId, beacon.sequence)

        val reachedPeers = transport.broadcastExcept(MeshSerialization.encodeEnvelope(envelope), excludeEndpointId = null)
        storeForRelay(envelope)

        // Nearby never delivers our own outbound payload back to us - echo locally.
        _incomingSosBeacons.tryEmit(beacon)
        return reachedPeers
    }

    private fun onSosEnvelopeReceived(fromEndpointId: String, envelope: MeshEnvelope) {
        val beacon = try {
            MeshSerialization.decodeSosBeacon(envelope.payload)
        } catch (e: Exception) {
            Log.w(TAG, "dropping malformed SOS beacon", e)
            return
        }

        // Not newer than what we've already relayed for this beaconId: drop, no relay.
        if (!seenBeacons.registerIfNewer(beacon.beaconId, beacon.sequence)) return

        _incomingSosBeacons.tryEmit(beacon)

        if (envelope.hopCount < envelope.maxHops) {
            val relayed = envelope.copy(hopCount = envelope.hopCount + 1)
            transport.broadcastExcept(MeshSerialization.encodeEnvelope(relayed), fromEndpointId)
            storeForRelay(relayed)
        }
    }

    /** Persists [envelope] for store-and-forward, off the calling (transport callback) thread. */
    private fun storeForRelay(envelope: MeshEnvelope) {
        scope.launch {
            val wasStored = messageStore.record(envelope)
            if (wasStored) _relayActivity.tryEmit(RelayActivityEvent.Carried(isSos = envelope.channelId == SOS_CHANNEL_ID))
        }
    }

    /**
     * Called when a peer freshly connects: replays every unexpired envelope
     * this device is carrying that hasn't already reached them - SOS beacons
     * first, oldest-first otherwise - so store-and-forward delivery actually
     * happens the moment a new peer comes into range. Hop counts are not
     * touched; they were already applied when each envelope was first stored.
     */
    fun onPeerConnected(endpointId: String) {
        // A reconnecting peer starts with a full bucket rather than inheriting
        // the drop count that got it disconnected.
        rateLimiter.forget(endpointId)
        _peerConnections.tryEmit(endpointId)
        scope.launch {
            val pending = messageStore.pendingFor(endpointId)
            if (pending.isEmpty()) return@launch
            var delivered = 0
            var deliveredSos = 0
            for (entry in pending) {
                transport.sendTo(endpointId, entry.envelopeBytes)
                messageStore.markDelivered(entry, endpointId)
                delivered++
                if (entry.isSos) deliveredSos++
                delay(STORE_REPLAY_THROTTLE_MS)
            }
            _relayActivity.tryEmit(RelayActivityEvent.DeliveredOnConnect(delivered, deliveredSos, endpointId))
        }
    }

    /**
     * Writes a received voice clip under a name this device derives, never one
     * supplied by the sender.
     *
     * `payload.audioFileName` comes straight off the wire and used to be passed
     * to `File(cacheDir, name)` unsanitised, so a name like
     * `../databases/waveq_incidents` escaped the cache directory and overwrote
     * arbitrary files in the app sandbox - reachable by any nearby device, since
     * connections are auto-accepted and the City-Wide channel is unencrypted.
     *
     * The messageId is used instead, but it is also attacker-controlled (it is
     * just a `readUTF` off the envelope), so it is reduced to safe characters
     * rather than trusted. The canonical-path check afterwards is belt and
     * braces: if the sanitiser is ever weakened, the write still cannot land
     * outside the cache directory.
     */
    private fun writeReceivedAudio(messageId: String, audioBytes: ByteArray): File? {
        val safeId = messageId.filter { it.isLetterOrDigit() || it == '-' }.take(64)
            .ifBlank { UUID.randomUUID().toString() }
        val target = File(cacheDir, "voice_$safeId.m4a")
        val cacheRoot = cacheDir.canonicalPath
        if (!target.canonicalPath.startsWith(cacheRoot + File.separator)) {
            Log.w(TAG, "refusing to write received audio outside the cache directory")
            return null
        }
        return try {
            target.also { it.writeBytes(audioBytes) }
        } catch (e: Exception) {
            Log.w(TAG, "could not write received audio", e)
            null
        }
    }

    private fun tryDecryptAndEmit(envelope: MeshEnvelope, isMine: Boolean) {
        val channel = channelRepository.getChannel(envelope.channelId) ?: return // not a member: relay-only

        val plaintext = if (channel.isEncrypted) {
            val key = channelRepository.getKey(envelope.channelId) ?: return
            val iv = envelope.iv ?: return
            ChannelCrypto.decrypt(EncryptedBlob(iv, envelope.payload), key) ?: return // bad key/tampered: drop silently
        } else {
            envelope.payload
        }

        val (payload, audioBytes) = MeshSerialization.decodePayloadWithAudio(plaintext)

        val audioFile = if (audioBytes != null && audioBytes.isNotEmpty() && payload.audioFileName != null) {
            writeReceivedAudio(envelope.messageId, audioBytes)
        } else {
            null
        }

        _incomingMessages.tryEmit(
            MeshMessage(
                messageId = envelope.messageId,
                channelId = envelope.channelId,
                senderId = payload.senderId,
                senderName = payload.senderName,
                type = payload.type,
                text = payload.text,
                audioFile = audioFile,
                timestamp = payload.timestamp,
                isMine = isMine || payload.senderId == myDeviceId,
                severity = payload.severity,
                riskJson = payload.riskJson,
                incidentJson = payload.incidentJson,
                sensorJson = payload.sensorJson,
                hopCount = envelope.hopCount,
            ),
        )
    }
}
