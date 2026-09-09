package com.waveq.app.mesh

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "NearbyTransport"
private const val SERVICE_ID = "com.waveq.app.MESH_SERVICE"
private val STRATEGY = Strategy.P2P_CLUSTER

/** How often the watchdog re-arms whichever of advertising/discovery has dropped. */
private const val WATCHDOG_INTERVAL_MS = 10_000L

/** Lengthened watchdog interval while the device is in low-power mode. */
private const val WATCHDOG_INTERVAL_LOW_POWER_MS = 60_000L

/** Base delay before retrying a connection request that lost a simultaneous-discovery race. */
private const val CONNECT_RETRY_BASE_MS = 1_500L
private const val CONNECT_RETRY_JITTER_MS = 2_000L
private const val CONNECT_MAX_ATTEMPTS = 4

/**
 * Honest, observable transport state.
 *
 * Advertising and discovery are tracked separately because they fail
 * separately, and "started" on its own says nothing useful: with Bluetooth off
 * both GMS calls fail while the flag stays true, which is how the UI came to
 * report a running mesh that was neither visible to anyone nor looking for
 * anyone.
 */
/** One connected peer, as the app would address it right now. */
data class MeshPeer(val endpointId: String, val name: String)

data class TransportStatus(
    val isStarted: Boolean = false,
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    /**
     * The exact set [NearbyTransport.broadcastExcept] would send to.
     *
     * Derived from `connectedEndpoints` at publish time rather than counted
     * separately, so the number on screen cannot drift from the set that
     * actually receives messages.
     */
    val peers: List<MeshPeer> = emptyList(),
    val discoveredCount: Int = 0,
    val isLowPower: Boolean = false,
    val lastError: String? = null,
) {
    val peerCount: Int get() = peers.size

    val label: String
        get() = when {
            !isStarted -> "Stopped"
            isAdvertising && isDiscovering && isLowPower -> "Advertising and discovering (low power)"
            isAdvertising && isDiscovering -> "Advertising and discovering"
            isAdvertising -> "Advertising only - not discovering"
            isDiscovering -> "Discovering only - not visible to others"
            else -> "Starting…"
        }

    /** True when the transport claims to be on but neither half is actually working. */
    val isDegraded: Boolean get() = isStarted && !(isAdvertising && isDiscovering)
}

/**
 * Nearby Connections-backed [MeshTransport].
 *
 * Connections are accepted automatically with no pairing prompt - trust is
 * enforced by [ChannelCrypto], not by connection-time confirmation, since any
 * device (member or not) is expected to relay traffic for the mesh to work.
 */
class NearbyTransport(context: Context) : MeshTransport {

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val connectedEndpoints = Collections.synchronizedSet(mutableSetOf<String>())

    /** Endpoints discovery can currently see. Distinct from [connectedEndpoints] - see [onEndpointLost]. */
    private val discoveredEndpoints = Collections.synchronizedSet(mutableSetOf<String>())

    /** Endpoints with a connection request in flight, and how many attempts have been made. */
    private val connectAttempts = Collections.synchronizedMap(mutableMapOf<String, Int>())

    /** endpointId -> the display name that device advertises, for the peer list. */
    private val endpointNames = Collections.synchronizedMap(mutableMapOf<String, String>())

    private var displayName: String = "waveq-device"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    private val _status = MutableStateFlow(TransportStatus())
    /** Single source of truth for transport state, replacing the previous single-slot callbacks. */
    val status: StateFlow<TransportStatus> = _status.asStateFlow()

    /** Set by MeshSession to route decoded envelopes into MeshManager. */
    var onEnvelopeReceived: ((endpointId: String, envelope: MeshEnvelope) -> Unit)? = null

    /** Set by MeshSession to trigger a store-and-forward replay to a freshly connected peer. */
    var onPeerConnected: ((endpointId: String) -> Unit)? = null

    /**
     * Whether advertising/discovery has been requested on this process-wide
     * transport - by [MeshViewModel] or by [SosBeaconService]. Note this being
     * true does NOT mean traffic can flow; check [TransportStatus.isDegraded].
     */
    @Volatile
    var isStarted: Boolean = false
        private set

    fun connectedEndpointCount(): Int = connectedEndpoints.size

    /**
     * The set of endpoints a broadcast would reach right now.
     *
     * [broadcastExcept] and [publishStatus] both call this, so the count the UI
     * shows and the list `sendMessage` dispatches to are the same thing by
     * construction.
     */
    private fun currentTargets(): List<String> = synchronized(connectedEndpoints) {
        connectedEndpoints.toList()
    }

    /**
     * All status writes go through [MutableStateFlow.update].
     *
     * They were `_status.value = _status.value.copy(...)` - a read-modify-write
     * executed from the GMS callback thread, the Task listeners on main, the
     * watchdog on Dispatchers.Default and the foreground service. Two of those
     * landing together lost one update, so a peer that had just connected could
     * be erased by an advertising-success callback built from a stale snapshot.
     * That is the "sometimes 0" with a live mesh.
     */
    private fun publishStatus(error: String? = null, clearError: Boolean = false) {
        val targets = currentTargets()
        val peers = targets.map { MeshPeer(it, endpointNames[it] ?: "Unknown device") }
        val discovered = discoveredEndpoints.size
        _status.update { current ->
            current.copy(
                isStarted = isStarted,
                peers = peers,
                discoveredCount = discovered,
                lastError = if (clearError) null else error ?: current.lastError,
            )
        }
    }

    // -- Lifecycle ---------------------------------------------------------

    fun start(displayName: String) {
        this.displayName = displayName
        val wasStarted = isStarted
        isStarted = true
        publishStatus(clearError = true)

        startAdvertising()
        startDiscovery()

        if (!wasStarted) {
            registerRadioStateReceiver()
            startWatchdog()
        }
    }

    fun stop() {
        isStarted = false
        watchdogJob?.cancel()
        watchdogJob = null
        unregisterRadioStateReceiver()

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        discoveredEndpoints.clear()
        connectAttempts.clear()
        endpointNames.clear()
        _status.update { TransportStatus(isLowPower = it.isLowPower) }
    }

    /**
     * Tears discovery and advertising down and brings them straight back up.
     *
     * Exposed to the UI as "Rescan": Nearby offers no way to force a fresh sweep,
     * and a stalled discovery session is otherwise invisible and unrecoverable
     * without toggling the whole mesh off and on.
     */
    fun rescan() {
        if (!isStarted) return
        Log.i(TAG, "rescan requested")
        connectionsClient.stopDiscovery()
        connectionsClient.stopAdvertising()
        discoveredEndpoints.clear()
        connectAttempts.clear()
        _status.update { it.copy(isAdvertising = false, isDiscovering = false, lastError = null) }
        startAdvertising()
        startDiscovery()
    }

    /**
     * Below the battery threshold, discovery is stopped and only advertising is
     * kept: the device stays reachable by peers that find it, without paying for
     * a continuous scan. See MeshForegroundService.
     */
    fun setLowPowerMode(enabled: Boolean) {
        if (_status.value.isLowPower == enabled) return
        _status.update { it.copy(isLowPower = enabled) }
        if (!isStarted) return
        if (enabled) {
            connectionsClient.stopDiscovery()
            _status.update { it.copy(isDiscovering = false) }
        } else {
            startDiscovery()
        }
    }

    private fun startAdvertising() {
        connectionsClient.startAdvertising(
            displayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        )
            // The flag is set from the Task result, never optimistically: with
            // Bluetooth off this fails and the UI must say so.
            .addOnSuccessListener {
                _status.update { current -> current.copy(isAdvertising = true, lastError = null) }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "startAdvertising failed", error)
                _status.update { current ->
                    current.copy(
                        isAdvertising = false,
                        lastError = "Cannot advertise: ${error.message ?: "unknown error"}",
                    )
                }
            }
    }

    private fun startDiscovery() {
        if (_status.value.isLowPower) return
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        )
            .addOnSuccessListener {
                _status.update { current -> current.copy(isDiscovering = true, lastError = null) }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "startDiscovery failed", error)
                _status.update { current ->
                    current.copy(
                        isDiscovering = false,
                        lastError = "Cannot discover: ${error.message ?: "unknown error"}",
                    )
                }
            }
    }

    /**
     * Re-arms whichever half has dropped.
     *
     * Nothing in Nearby tells you that advertising or discovery has stopped, and
     * both can fail at start time (radios off) or be torn down by the platform
     * later. Without this, turning Bluetooth on after launching the app left the
     * mesh permanently dead with no indication why.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isStarted) {
                val interval = if (_status.value.isLowPower) {
                    WATCHDOG_INTERVAL_LOW_POWER_MS
                } else {
                    WATCHDOG_INTERVAL_MS
                }
                delay(interval)
                if (!isStarted) break
                if (!_status.value.isAdvertising) startAdvertising()
                if (!_status.value.isDiscovering && !_status.value.isLowPower) startDiscovery()
                reconnectStalledEndpoints()
            }
        }
    }

    /**
     * Re-arms endpoints that are visible to discovery but never connected.
     *
     * The per-endpoint retry budget is exhaustible: four simultaneous-discovery
     * collisions and that peer was abandoned permanently, because nothing reset
     * the counter while the endpoint stayed discovered. One device would show
     * the other and not vice versa - an asymmetric connection, and exactly the
     * shape of "1 device when there are 2".
     */
    private fun reconnectStalledEndpoints() {
        val stalled = synchronized(discoveredEndpoints) { discoveredEndpoints.toList() }
            .filter { it !in connectedEndpoints }
        for (endpointId in stalled) {
            connectAttempts.remove(endpointId)
            Log.i(TAG, "re-arming stalled connection to $endpointId")
            requestConnectionTo(endpointId)
        }
    }

    // -- Radio state -------------------------------------------------------

    private var radioReceiver: BroadcastReceiver? = null

    /**
     * Restarts the transport when Bluetooth or location services are switched on.
     *
     * This is the fix for "peers appear inconsistently after enabling Bluetooth
     * and location": both GMS calls fail while the radios are off, and nothing
     * previously retried, so whether the mesh worked depended entirely on
     * whether the radios happened to be on at the moment the user first opened
     * the Mesh Channels screen.
     */
    private fun registerRadioStateReceiver() {
        if (radioReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_ON) {
                            Log.i(TAG, "bluetooth switched on - restarting transport")
                            rescan()
                        }
                    }
                    LocationManager.MODE_CHANGED_ACTION -> {
                        Log.i(TAG, "location mode changed - restarting transport")
                        rescan()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        radioReceiver = receiver
    }

    private fun unregisterRadioStateReceiver() {
        radioReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        radioReceiver = null
    }

    // -- Sending -----------------------------------------------------------

    /**
     * Returns the number of connected peers the payload was dispatched to -
     * zero when nothing was in range, so callers can report honestly instead
     * of assuming a send happened. Note this is a dispatch count, not a
     * delivery acknowledgement: Nearby may still fail the transfer later.
     */
    override fun broadcastExcept(bytes: ByteArray, excludeEndpointId: String?): Int {
        val targets = currentTargets().filter { it != excludeEndpointId }
        if (targets.isEmpty()) return 0
        connectionsClient.sendPayload(targets, Payload.fromBytes(bytes))
            .addOnFailureListener { Log.w(TAG, "sendPayload failed", it) }
        return targets.size
    }

    override fun sendTo(endpointId: String, bytes: ByteArray) {
        connectionsClient.sendPayload(listOf(endpointId), Payload.fromBytes(bytes))
            .addOnFailureListener { Log.w(TAG, "sendPayload (single) failed", it) }
    }

    override fun disconnect(endpointId: String) {
        Log.w(TAG, "disconnecting $endpointId")
        connectedEndpoints.remove(endpointId)
        discoveredEndpoints.remove(endpointId)
        // Marked at the retry ceiling so the discovery callback does not
        // immediately reconnect a peer we just dropped for flooding.
        connectAttempts[endpointId] = CONNECT_MAX_ATTEMPTS
        connectionsClient.disconnectFromEndpoint(endpointId)
        publishStatus()
    }

    // -- Callbacks ---------------------------------------------------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            // The ENTIRE receive path is wrapped, not just the decode.
            //
            // Only decodeEnvelope used to be guarded; onEnvelopeReceived was
            // outside the try, and everything it reaches - payloadFromJson,
            // MessageType.valueOf, the audio file write, ChannelCrypto - can
            // throw on attacker-controlled bytes. One malformed message from any
            // nearby device took the whole process down, on an unencrypted
            // channel that needs no pairing.
            //
            // OutOfMemoryError is caught explicitly because it is an Error, not
            // an Exception: MeshSerialization now bounds every wire length, but
            // a crash here is worse than a dropped message under any
            // circumstances, and this is the boundary where that trade is made.
            try {
                val envelope = MeshSerialization.decodeEnvelope(bytes)
                onEnvelopeReceived?.invoke(endpointId, envelope)
            } catch (e: Exception) {
                Log.w(TAG, "dropping malformed envelope from $endpointId: ${e.javaClass.simpleName}")
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "dropping oversized envelope from $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads complete atomically; nothing to track mid-transfer.
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName.ifBlank { "Unknown device" }
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.w(TAG, "acceptConnection failed for $endpointId", it) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectAttempts.remove(endpointId)
                connectedEndpoints.add(endpointId)
                publishStatus()
                onPeerConnected?.invoke(endpointId)
            } else {
                connectedEndpoints.remove(endpointId)
                publishStatus()
                // Two devices that discover each other at the same moment both
                // call requestConnection; one side loses and gets a rejection
                // here. Previously nothing retried, which is the main reason a
                // peer that was clearly in range would simply never appear.
                scheduleConnectRetry(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            connectAttempts.remove(endpointId)
            publishStatus()
            // Still discovered? Try to get back on. Nearby does not re-announce
            // an endpoint that never left discovery range.
            if (endpointId in discoveredEndpoints) scheduleConnectRetry(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName.ifBlank { "Unknown device" }
            discoveredEndpoints.add(endpointId)
            publishStatus()
            requestConnectionTo(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            // ONLY the discovered set. This is a discovery-layer event about an
            // advertiser going out of range; an already-connected peer can be
            // "lost" here while its connection is perfectly alive, and removing
            // it from connectedEndpoints dropped it from every broadcast target
            // list while the UI showed the peer count falling for no reason.
            discoveredEndpoints.remove(endpointId)
            connectAttempts.remove(endpointId)
            endpointNames.remove(endpointId)
            publishStatus()
        }
    }

    private fun requestConnectionTo(endpointId: String) {
        if (endpointId in connectedEndpoints) return
        val attempt = (connectAttempts[endpointId] ?: 0) + 1
        connectAttempts[endpointId] = attempt
        connectionsClient.requestConnection(displayName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener {
                Log.w(TAG, "requestConnection to $endpointId failed (attempt $attempt)", it)
                scheduleConnectRetry(endpointId)
            }
    }

    /** Jittered retry, so two devices that collided do not collide again on the same schedule. */
    private fun scheduleConnectRetry(endpointId: String) {
        val attempt = connectAttempts[endpointId] ?: 0
        if (attempt >= CONNECT_MAX_ATTEMPTS) {
            Log.w(TAG, "giving up connecting to $endpointId after $attempt attempts")
            return
        }
        scope.launch {
            delay(CONNECT_RETRY_BASE_MS * attempt + Random.nextLong(CONNECT_RETRY_JITTER_MS))
            if (!isStarted) return@launch
            if (endpointId in connectedEndpoints) return@launch
            if (endpointId !in discoveredEndpoints) return@launch
            requestConnectionTo(endpointId)
        }
    }
}
