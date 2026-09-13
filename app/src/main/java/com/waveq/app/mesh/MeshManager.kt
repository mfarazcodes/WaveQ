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

const val RISK_UPDATE_MAX_HOPS = 12
private const val STORE_REPLAY_THROTTLE_MS = 150L

private const val RECEIVE_BUCKET_CAPACITY = 60.0
private const val RECEIVE_REFILL_PER_SECOND = 10.0
private const val RECEIVE_DROPS_BEFORE_DISCONNECT = 50
private const val MAX_TRACKED_ENDPOINTS = 64

interface MeshTransport {
    fun broadcastExcept(bytes: ByteArray, excludeEndpointId: String?): Int
    fun sendTo(endpointId: String, bytes: ByteArray)
    fun disconnect(endpointId: String)
}

private class ReceiveRateLimiter {

    private class Bucket(var tokens: Double, var lastRefillMs: Long, var consecutiveDrops: Int = 0)

    private val buckets = LinkedHashMap<String, Bucket>()

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

private class LruMessageIdCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > capacity
    }
    private val synced = Collections.synchronizedMap(map)

    fun containsAndAdd(id: String): Boolean {
        synchronized(synced) {
            if (synced.containsKey(id)) return true
            synced[id] = true
            return false
        }
    }
}

private class LruBeaconSequenceCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, Int>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > capacity
    }

    @Synchronized
    fun registerIfNewer(beaconId: String, sequence: Int): Boolean {
        val previous = map[beaconId]
        if (previous != null && sequence <= previous) return false
        map[beaconId] = sequence
        return true
    }
}

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
    val peerConnections: SharedFlow<String> = _peerConnections.asSharedFlow()

    /** Called by the transport layer whenever a raw envelope arrives from a peer. */
    fun onEnvelopeReceived(fromEndpointId: String, envelope: MeshEnvelope) {
        if (!rateLimiter.allow(fromEndpointId)) {
            if (rateLimiter.shouldDisconnect(fromEndpointId)) {
                Log.w(TAG, "disconnecting $fromEndpointId - sustained flood on the receive path")
                rateLimiter.forget(fromEndpointId)
                transport.disconnect(fromEndpointId)
            }
            return
        }

        if (envelope.channelId == SOS_CHANNEL_ID) {
            onSosEnvelopeReceived(fromEndpointId, envelope)
            return
        }

        if (seenIds.containsAndAdd(envelope.messageId)) return

        // 1. Relay forward to peers first (TTL bounded)
        if (envelope.hopCount < envelope.maxHops) {
            val relayed = envelope.copy(hopCount = envelope.hopCount + 1)
            transport.broadcastExcept(MeshSerialization.encodeEnvelope(relayed), fromEndpointId)
            storeForRelay(relayed)
        }

        // 2. Attempt local consumption and verification
        tryDecryptAndEmit(envelope, isMine = false)
    }

    fun sendMessage(
        channelId: String,
        payload: MeshPayload,
        audioBytes: ByteArray? = null,
        maxHops: Int = DEFAULT_MAX_HOPS,
    ): Int {
        // Enforce role gating for authoritative flood alerts
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

        seenIds.containsAndAdd(envelope.messageId)

        val reachedPeers = transport.broadcastExcept(
            MeshSerialization.encodeEnvelope(envelope),
            excludeEndpointId = null,
        )
        storeForRelay(envelope)
        tryDecryptAndEmit(envelope, isMine = true)
        return reachedPeers
    }

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

    fun sendSosBeacon(beacon: SosBeacon): Int {
        val currentRole = SessionManager.currentRole ?: UserRole.CITIZEN

        // Compute PoW if this beacon is from a civilian without an operator signature
        val finalizedBeacon = if (beacon.signature == null && beacon.powNonce == null) {
            val nonce = ChannelCrypto.solveSosProofOfWork(beacon.senderId, beacon.sentAt)
            beacon.copy(powNonce = nonce, signerRole = currentRole.name)
        } else {
            beacon
        }

        val envelope = MeshEnvelope(
            messageId = UUID.randomUUID().toString(),
            channelId = SOS_CHANNEL_ID,
            isEncrypted = false,
            iv = null,
            payload = MeshSerialization.encodeSosBeacon(finalizedBeacon),
            hopCount = 0,
            maxHops = SOS_MAX_HOPS,
        )

        seenBeacons.registerIfNewer(finalizedBeacon.beaconId, finalizedBeacon.sequence)

        val reachedPeers = transport.broadcastExcept(MeshSerialization.encodeEnvelope(envelope), excludeEndpointId = null)
        storeForRelay(envelope)

        _incomingSosBeacons.tryEmit(finalizedBeacon)
        return reachedPeers
    }

    private fun onSosEnvelopeReceived(fromEndpointId: String, envelope: MeshEnvelope) {
        val beacon = try {
            MeshSerialization.decodeSosBeacon(envelope.payload)
        } catch (e: Exception) {
            Log.w(TAG, "dropping malformed SOS beacon", e)
            return
        }

        // Dedup check: Ignore if we have seen an equal or higher sequence for this beacon
        if (!seenBeacons.registerIfNewer(beacon.beaconId, beacon.sequence)) return

        // Anti-Spam Verification: If unsigned, verify Proof-of-Work difficulty
        if (beacon.signature == null) {
            val nonce = beacon.powNonce
            if (nonce == null || !ChannelCrypto.verifySosProofOfWork(beacon.senderId, beacon.sentAt, nonce)) {
                Log.w(TAG, "dropping SOS beacon: missing or invalid PoW nonce from ${beacon.senderId}")
                return
            }
        }

        // Role-Based UI Delivery:
        // Citizen SOS beacons only terminate on Operator/Admin devices (or our own beacon echo)
        // If an Operator or Admin broadcasts an SOS, everybody should see it!
        val myRole = SessionManager.currentRole ?: UserRole.CITIZEN
        val isMine = beacon.senderId == myDeviceId
        val isFromAuthority = beacon.signerRole == UserRole.OPERATOR.name || beacon.signerRole == UserRole.ADMIN.name

        if (isMine || myRole == UserRole.OPERATOR || myRole == UserRole.ADMIN || isFromAuthority) {
            _incomingSosBeacons.tryEmit(beacon)
        }

        // Relay across the mesh regardless of our role so responders further away receive it
        if (envelope.hopCount < envelope.maxHops) {
            val relayed = envelope.copy(hopCount = envelope.hopCount + 1)
            transport.broadcastExcept(MeshSerialization.encodeEnvelope(relayed), fromEndpointId)
            storeForRelay(relayed)
        }
    }

    private fun storeForRelay(envelope: MeshEnvelope) {
        scope.launch {
            val wasStored = messageStore.record(envelope)
            if (wasStored) _relayActivity.tryEmit(RelayActivityEvent.Carried(isSos = envelope.channelId == SOS_CHANNEL_ID))
        }
    }

    fun onPeerConnected(endpointId: String) {
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
        val channel = channelRepository.getChannel(envelope.channelId) ?: return

        val plaintext = if (channel.isEncrypted) {
            val key = channelRepository.getKey(envelope.channelId) ?: return
            val iv = envelope.iv ?: return
            ChannelCrypto.decrypt(EncryptedBlob(iv, envelope.payload), key) ?: return
        } else {
            envelope.payload
        }

        val (payload, audioBytes) = MeshSerialization.decodePayloadWithAudio(plaintext)

        // 1. Authenticity check: Verify signature for FLOOD_ALERT messages
        if (payload.type == MessageType.FLOOD_ALERT && !isMine) {
            if (!verifyOperatorBroadcast(payload)) {
                Log.w(TAG, "dropping FLOOD_ALERT: unauthenticated or invalid signature")
                return
            }
        }

        // 2. Audience scope check: Citizen SOS distress messages are restricted to Responders
        val myRole = SessionManager.currentRole ?: UserRole.CITIZEN
        if (payload.targetScope == AlertScope.RESPONDERS_ONLY &&
            myRole != UserRole.OPERATOR && myRole != UserRole.ADMIN && !isMine
        ) {
            // Relayed at the network layer, but ignored by civilian UI
            return
        }

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
                targetScope = payload.targetScope,
                signature = payload.signature,
                signerRole = payload.signerRole,
            ),
        )
    }

    private fun verifyOperatorBroadcast(payload: MeshPayload): Boolean {
        val signature = payload.signature ?: return false
        val trustedPublicKey = SessionManager.getOperatorPublicKey() ?: return false
        val signedData = "${payload.severity}:${payload.text}:${payload.timestamp}".toByteArray(Charsets.UTF_8)
        return ChannelCrypto.verifyAuthority(signedData, signature, trustedPublicKey)
    }
}