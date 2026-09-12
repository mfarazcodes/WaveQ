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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

private const val WATCHDOG_INTERVAL_MS = 10_000L
private const val WATCHDOG_INTERVAL_LOW_POWER_MS = 60_000L

private const val CONNECT_RETRY_BASE_MS = 1_500L
private const val CONNECT_RETRY_JITTER_MS = 2_000L
private const val CONNECT_MAX_ATTEMPTS = 4

data class MeshPeer(val endpointId: String, val name: String)

data class TransportStatus(
    val isStarted: Boolean = false,
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val peers: List < MeshPeer > = emptyList(),
    val discoveredCount: Int = 0,
    val isLowPower: Boolean = false,
    val lastError: String? = null,
) {
    val peerCount: Int get() = peers.size

    val label: String
        get() = when {
            !isStarted -> "Stopped"
            isAdvertising && isDiscovering && isLowPower -> "Advertising and discovering (BLE Low Power)"
            isAdvertising && isDiscovering -> "Active mesh (BLE Control + Wi-Fi Upgrades)"
            isAdvertising -> "Advertising only - not discovering"
            isDiscovering -> "Discovering only - not visible to others"
            else -> "Starting…"
        }

    val isDegraded: Boolean get() = isStarted && !(isAdvertising && isDiscovering)
}

class NearbyTransport(context: Context) : MeshTransport {

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private val connectedEndpoints = ConcurrentHashMap.newKeySet < String > ()
    private val discoveredEndpoints = ConcurrentHashMap.newKeySet < String > ()
    private val connectAttempts = ConcurrentHashMap < String, Int > ()
    private val endpointNames = ConcurrentHashMap < String, String > ()
    private val incomingVoiceFiles = ConcurrentHashMap < Long, File > ()

    private var displayName: String = "waveq-device"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    private val _status = MutableStateFlow < TransportStatus > (TransportStatus())
    val status: StateFlow < TransportStatus > = _status.asStateFlow()

    var onEnvelopeReceived: ((endpointId: String, envelope: MeshEnvelope) -> Unit)? = null
    var onVoiceFileReceived: ((endpointId: String, file: File) -> Unit)? = null
    var onPeerConnected: ((endpointId: String) -> Unit)? = null

    @Volatile
    var isStarted: Boolean = false
        private set

    fun connectedEndpointCount(): Int = connectedEndpoints.size

    private fun currentTargets(): List < String > = connectedEndpoints.toList()

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
        incomingVoiceFiles.clear()
        _status.update { TransportStatus(isLowPower = it.isLowPower) }
    }

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
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(true)
            .build()

        connectionsClient.startAdvertising(
            displayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions,
        )
            .addOnSuccessListener {
                _status.update { current -> current.copy(isAdvertising = true, lastError = null) }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "startAdvertising failed", error)
                val msg = if (error.message != null) error.message else "unknown error"
                _status.update { current ->
                    current.copy(
                        isAdvertising = false,
                        lastError = "Cannot advertise: " + msg,
                    )
                }
            }
    }

    private fun startDiscovery() {
        if (_status.value.isLowPower) return

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .setLowPower(true)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions,
        )
            .addOnSuccessListener {
                _status.update { current -> current.copy(isDiscovering = true, lastError = null) }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "startDiscovery failed", error)
                val msg = if (error.message != null) error.message else "unknown error"
                _status.update { current ->
                    current.copy(
                        isDiscovering = false,
                        lastError = "Cannot discover: " + msg,
                    )
                }
            }
    }

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

    private fun reconnectStalledEndpoints() {
        val stalled = discoveredEndpoints.toList().filter { it !in connectedEndpoints }
        for (endpointId in stalled) {
            connectAttempts.remove(endpointId)
            Log.i(TAG, "re-arming stalled connection to " + endpointId)
            requestConnectionTo(endpointId)
        }
    }

    private var radioReceiver: BroadcastReceiver? = null

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

    fun broadcastAudioFile(file: File, excludeEndpointId: String?): Int {
        val targets = currentTargets().filter { it != excludeEndpointId }
        if (targets.isEmpty()) return 0
        try {
            val filePayload = Payload.fromFile(file)
            connectionsClient.sendPayload(targets, filePayload)
                .addOnFailureListener { Log.w(TAG, "broadcastAudioFile failed", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating File payload", e)
            return 0
        }
        return targets.size
    }

    override fun disconnect(endpointId: String) {
        Log.w(TAG, "disconnecting " + endpointId)
        connectedEndpoints.remove(endpointId)
        discoveredEndpoints.remove(endpointId)
        connectAttempts[endpointId] = CONNECT_MAX_ATTEMPTS
        connectionsClient.disconnectFromEndpoint(endpointId)
        publishStatus()
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    try {
                        val envelope = MeshSerialization.decodeEnvelope(bytes)
                        onEnvelopeReceived?.invoke(endpointId, envelope)
                    } catch (e: Exception) {
                        Log.w(TAG, "dropping malformed envelope from " + endpointId + ": " + e.javaClass.simpleName)
                    } catch (e: OutOfMemoryError) {
                        Log.w(TAG, "dropping oversized envelope from " + endpointId)
                    }
                }
                Payload.Type.FILE -> {
                    @Suppress("DEPRECATION")
                    val fileObj = payload.asFile()?.asJavaFile()
                    if (fileObj is File) {
                        incomingVoiceFiles[payload.id] = fileObj
                    }
                }
                Payload.Type.STREAM -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val file = incomingVoiceFiles.remove(update.payloadId)
                if (file != null) {
                    onVoiceFileReceived?.invoke(endpointId, file)
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                incomingVoiceFiles.remove(update.payloadId)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName.ifBlank { "Unknown device" }
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { Log.w(TAG, "acceptConnection failed for " + endpointId, it) }
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
                scheduleConnectRetry(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            connectAttempts.remove(endpointId)
            publishStatus()
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
                Log.w(TAG, "requestConnection to " + endpointId + " failed (attempt " + attempt + ")", it)
                scheduleConnectRetry(endpointId)
            }
    }

    private fun scheduleConnectRetry(endpointId: String) {
        val attempt = connectAttempts[endpointId] ?: 0
        if (attempt >= CONNECT_MAX_ATTEMPTS) {
            Log.w(TAG, "giving up connecting to " + endpointId + " after " + attempt + " attempts")
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