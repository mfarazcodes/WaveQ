package com.waveq.app.mesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.data.IncidentWire
import com.waveq.app.data.local.IncidentEntity
import com.waveq.app.data.verificationAnnouncement
import com.waveq.app.ui.components.Severity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One SOS beacon as tracked in the UI: the latest beacon plus when we last heard from it. */
data class SosBeaconRecord(
    val beacon: SosBeacon,
    val receivedAt: Long,
    val isMine: Boolean,
)

/** One human-readable line for the "Mesh Activity" feed, newest first. */
data class RelayActivityEntry(
    val text: String,
    val at: Long,
)

private const val MAX_RELAY_ACTIVITY_ENTRIES = 20

/**
 * Owns the mesh session for as long as the app is running: relay/crypto
 * manager and channel storage live in [MeshSession], shared with
 * [SosBeaconService] so SOS broadcasting survives past this ViewModel's
 * lifecycle. This class adds the UI-facing reactive state on top.
 */
class MeshViewModel(application: Application) : AndroidViewModel(application) {

    init {
        MeshSession.init(application)
        SessionManager.init(application)
    }

    val myDeviceId: String get() = MeshSession.myDeviceId

    private val _senderName = MutableStateFlow(MeshSession.senderName)
    val senderName: StateFlow<String> = _senderName

    fun setSenderName(name: String) {
        MeshSession.senderName = name
        _senderName.value = MeshSession.senderName
    }

    val channelRepository = MeshSession.channelRepository
    private val messageStore = MeshSession.messageStore
    private val transport = MeshSession.transport
    private val meshManager = MeshSession.meshManager
    private val voiceRecorder = VoiceRecorder()

    val transportStatus: StateFlow<TransportStatus> = MeshSession.transport.status

    val isRunning: StateFlow<Boolean> = transportStatus
        .map { it.isStarted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MeshSession.transport.isStarted)

    val peerCount: StateFlow<Int> = transportStatus
        .map { it.peerCount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MeshSession.transport.connectedEndpointCount())

    private val _channels = MutableStateFlow<List<ChannelMeta>>(emptyList())
    val channels: StateFlow<List<ChannelMeta>> = _channels

    private val _messagesByChannel = MutableStateFlow<Map<String, List<MeshMessage>>>(emptyMap())
    val messagesByChannel: StateFlow<Map<String, List<MeshMessage>>> = _messagesByChannel

    private val _sosBeacons = MutableStateFlow<Map<String, SosBeaconRecord>>(emptyMap())
    /** All known SOS beacons, keyed by beaconId - our own included ([SosBeaconRecord.isMine]). */
    val sosBeacons: StateFlow<Map<String, SosBeaconRecord>> = _sosBeacons

    private val _carryingStats = MutableStateFlow(MessageStore.StoreStats(totalCount = 0, sosCount = 0))
    val carryingStats: StateFlow<MessageStore.StoreStats> = _carryingStats

    private val _relayActivity = MutableStateFlow<List<RelayActivityEntry>>(emptyList())
    val relayActivity: StateFlow<List<RelayActivityEntry>> = _relayActivity

    init {
        channelRepository.ensureDefaultCityChannel()
        refreshChannels()

        viewModelScope.launch {
            meshManager.incomingMessages.collect { message ->
                val currentRole = SessionManager.currentRole ?: UserRole.CITIZEN

                // Only operators and admins see RESPONDERS_ONLY alerts (citizen distress)
                if (message.targetScope == AlertScope.RESPONDERS_ONLY &&
                    currentRole != UserRole.OPERATOR && currentRole != UserRole.ADMIN
                ) {
                    return@collect
                }

                _messagesByChannel.update { current ->
                    val updated = (current[message.channelId] ?: emptyList()) + message
                    current + (message.channelId to updated)
                }
            }
        }

        viewModelScope.launch {
            meshManager.incomingSosBeacons.collect { beacon ->
                val currentRole = SessionManager.currentRole ?: UserRole.CITIZEN
                val isMine = beacon.senderId == myDeviceId
                val isFromAuthority = beacon.signerRole == UserRole.OPERATOR.name || beacon.signerRole == UserRole.ADMIN.name

                // Only show other peers' SOS beacons if we are an Operator or Admin, or it is our own beacon
                // If it is from an authority (Operator/Admin), everyone should see it.
                if (!isMine && currentRole != UserRole.OPERATOR && currentRole != UserRole.ADMIN && !isFromAuthority) {
                    return@collect
                }

                _sosBeacons.update { current ->
                    current + (beacon.beaconId to SosBeaconRecord(beacon, System.currentTimeMillis(), isMine))
                }
            }
        }

        refreshCarryingStats()
        viewModelScope.launch {
            meshManager.relayActivity.collect { event ->
                val text = when (event) {
                    is MeshManager.RelayActivityEvent.Carried ->
                        if (event.isSos) "Now carrying a new SOS position for the mesh" else "Now carrying a new message for the mesh"
                    is MeshManager.RelayActivityEvent.DeliveredOnConnect ->
                        "Delivered ${event.count} message(s) (${event.sosCount} SOS) to a newly connected peer"
                }
                val at = when (event) {
                    is MeshManager.RelayActivityEvent.Carried -> event.at
                    is MeshManager.RelayActivityEvent.DeliveredOnConnect -> event.at
                }
                _relayActivity.update { (listOf(RelayActivityEntry(text, at)) + it).take(MAX_RELAY_ACTIVITY_ENTRIES) }
                refreshCarryingStats()
            }
        }
    }

    private fun refreshCarryingStats() {
        viewModelScope.launch { _carryingStats.value = messageStore.stats() }
    }

    fun startMesh() {
        if (isRunning.value) return
        if (!PermissionUtils.hasAllMeshPermissions(getApplication())) return
        MeshForegroundService.start(getApplication())
    }

    fun stopMesh() {
        MeshForegroundService.stop(getApplication())
    }

    fun rescanMesh() {
        if (!isRunning.value) {
            startMesh()
            return
        }
        transport.rescan()
    }

    fun refreshChannels() {
        _channels.value = channelRepository.getChannels()
    }

    fun joinSectorChannel(name: String): Result<ChannelMeta> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Sector name cannot be empty"))
        val channel = channelRepository.createOrJoinPublicChannel(ChannelType.SECTOR, name.trim())
        refreshChannels()
        return Result.success(channel)
    }

    fun leaveChannel(channelId: String): Boolean {
        val left = channelRepository.leaveChannel(channelId)
        if (left) {
            refreshChannels()
            _messagesByChannel.update { it - channelId }
        }
        return left
    }

    suspend fun createFamilyChannel(passphrase: String): Result<ChannelMeta> =
        joinFamilyChannel(passphrase)

    suspend fun joinFamilyChannel(passphrase: String): Result<ChannelMeta> {
        val result = withContext(Dispatchers.Default) {
            channelRepository.createOrJoinFamilyChannel(passphrase)
        }
        if (result.isSuccess) refreshChannels()
        return result
    }

    fun sendText(channelId: String, text: String) {
        if (text.isBlank()) return
        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.TEXT,
            text = text,
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            targetScope = AlertScope.ALL_PEERS,
        )
        meshManager.sendMessage(channelId, payload)
    }

    /**
     * Broadcasts an authoritative flood alert with cryptographic signature and role stamp.
     */
    fun sendFloodAlert(
        channelId: String,
        severity: Severity,
        message: String,
        signature: ByteArray? = null,
        signerRole: String? = null
    ) {
        if (message.isBlank()) return
        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.FLOOD_ALERT,
            text = message,
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            severity = severity.name,
            targetScope = AlertScope.ALL_PEERS,
            signature = signature,
            signerRole = signerRole
        )
        meshManager.sendMessage(channelId, payload)
    }

    /**
     * Relays a citizen incident report. Citizens send observations marked as ALL_PEERS (chat text).
     */
    fun broadcastIncidentReport(
        referenceId: String,
        type: String,
        severity: String,
        location: String,
        description: String,
        latitude: Double? = null,
        longitude: Double? = null,
        reportedAtMillis: Long = System.currentTimeMillis(),
    ): Int {
        val severityLabel = Severity.entries.firstOrNull { it.name == severity }?.label ?: severity
        val body = buildString {
            append("[Report $referenceId] ")
            append(type)
            append(" - ")
            append(severityLabel)
            append(" severity at ")
            append(location)
            if (description.isNotBlank()) {
                append(". ")
                append(description)
            }
        }
        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.TEXT,
            text = body,
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            targetScope = AlertScope.ALL_PEERS,
            incidentJson = IncidentWire(
                id = referenceId,
                type = type,
                severity = severity,
                location = location,
                description = description,
                latitude = latitude,
                longitude = longitude,
                reportedAtMillis = reportedAtMillis,
            ).toJson(),
        )
        return meshManager.sendMessage(channelRepository.cityChannelId(), payload)
    }

    /**
     * Announces that an operator has confirmed a citizen report.
     * Signs with operator private key if available.
     */
    fun broadcastVerification(incident: IncidentEntity): Int {
        val textBody = verificationAnnouncement(incident.id, incident.type, incident.location)
        val now = System.currentTimeMillis()
        val currentRole = SessionManager.currentRole ?: UserRole.OPERATOR

        val signature = SessionManager.getOperatorPrivateKey()?.let { privKey ->
            val payloadBytes = "${incident.severity}:$textBody:$now".toByteArray(Charsets.UTF_8)
            ChannelCrypto.signPayload(payloadBytes, privKey)
        }

        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.FLOOD_ALERT,
            text = textBody,
            audioFileName = null,
            timestamp = now,
            severity = incident.severity,
            targetScope = AlertScope.ALL_PEERS,
            signature = signature,
            signerRole = currentRole.name,
            incidentJson = IncidentWire.fromEntity(incident, verifiedBy = incident.verifiedBy).toJson(),
        )
        return meshManager.sendMessage(channelRepository.cityChannelId(), payload)
    }

    fun startVoiceRecording(): File = voiceRecorder.startRecording(getApplication())

    fun stopVoiceRecordingAndSend(channelId: String): Boolean {
        val file = voiceRecorder.stopRecording() ?: return false
        val audioBytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return false
        }
        if (audioBytes.isEmpty()) return false

        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.VOICE,
            text = null,
            audioFileName = file.name,
            timestamp = System.currentTimeMillis(),
            targetScope = AlertScope.ALL_PEERS,
        )
        meshManager.sendMessage(channelId, payload, audioBytes = audioBytes)
        return true
    }

    /** SOS beacon dispatch with responder-only routing */
    fun sendSosBeacon(beacon: SosBeacon) = meshManager.sendSosBeacon(beacon)

    fun clearMySosBeacon() {
        _sosBeacons.update { current -> current.filterNot { it.value.isMine } }
    }

    override fun onCleared() {
        super.onCleared()
    }
}