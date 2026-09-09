package com.waveq.app.mesh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    /**
     * The transport's own state flow, mirrored straight through.
     *
     * Replaces the previous single-slot `onPeerCountChanged`/`onRunningChanged`
     * callbacks, which a second ViewModel would silently steal, and which had to
     * be seeded by hand because a callback only fires on change - so a mesh
     * already started by SosBeaconService reported zero peers until something
     * happened to move.
     */
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
    /** What this device is currently carrying for store-and-forward delivery to peers met later. */
    val carryingStats: StateFlow<MessageStore.StoreStats> = _carryingStats

    private val _relayActivity = MutableStateFlow<List<RelayActivityEntry>>(emptyList())
    /** Recent relay events (newest first), so store-and-forward behaviour is visible in the UI. */
    val relayActivity: StateFlow<List<RelayActivityEntry>> = _relayActivity

    init {
        channelRepository.ensureDefaultCityChannel()
        refreshChannels()

        // Envelope routing is wired once, process-wide, in MeshSession.init.
        // Transport state is observed through transport.status rather than
        // assigned callbacks, so two ViewModels cannot clobber each other.

        // These collectors are UI state only. Sounding the siren, posting the
        // alert notification and launching the full-screen takeover all happen
        // in MeshAlertDispatcher on the process-scoped session instead, so they
        // still fire when this ViewModel (and its Activity) no longer exists.
        viewModelScope.launch {
            meshManager.incomingMessages.collect { message ->
                _messagesByChannel.update { current ->
                    val updated = (current[message.channelId] ?: emptyList()) + message
                    current + (message.channelId to updated)
                }
            }
        }

        viewModelScope.launch {
            meshManager.incomingSosBeacons.collect { beacon ->
                val isMine = beacon.senderId == myDeviceId
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

    /**
     * Forces a fresh advertise/discover sweep.
     *
     * Nearby has no "look again" call, and a discovery session that has quietly
     * stalled is otherwise unrecoverable from the UI.
     */
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

    /**
     * Suspends: joining derives a 120k-round PBKDF2 key, which is hundreds of
     * milliseconds to seconds of CPU and must never run on the main thread.
     * Callers are expected to show a loading state for the duration.
     */
    /**
     * Leaves a family channel and forgets its key.
     *
     * Local only. Messages this device already relayed are out in the mesh and
     * on other devices' stores; leaving cannot recall them, and the UI says so.
     */
    fun leaveChannel(channelId: String): Boolean {
        val left = channelRepository.leaveChannel(channelId)
        if (left) {
            refreshChannels()
            _messagesByChannel.update { it - channelId }
        }
        return left
    }

    /**
     * Creating and joining a family channel are the same derivation - the
     * channel id and key both come deterministically from the passphrase, so
     * "create" and "join" differ only in what the user is told, not in what
     * happens. They are separate entry points because the two intentions carry
     * different risks: someone creating a group is choosing a secret that
     * everyone they share it with can read the group with, forever.
     */
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
        )
        meshManager.sendMessage(channelId, payload)
    }

    /**
     * Broadcasts an authoritative flood alert. UI-side convenience only - the
     * gate that actually matters is in [MeshManager.sendMessage] itself, which
     * refuses a FLOOD_ALERT payload outright unless the session role is
     * OPERATOR/ADMIN, regardless of what called it.
     */
    fun sendFloodAlert(channelId: String, severity: Severity, text: String) {
        if (text.isBlank()) return
        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.FLOOD_ALERT,
            text = text,
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            severity = severity.name,
        )
        meshManager.sendMessage(channelId, payload)
    }

    /**
     * Relays a citizen incident report to nearby devices as a TEXT message on
     * the public city channel.
     *
     * Deliberately NOT a FLOOD_ALERT: an alert is an authoritative warning that
     * fires sirens and is restricted to operators. A citizen report is an
     * observation, and it must reach the mesh without needing an operator role.
     *
     * Returns the number of peers the envelope was actually dispatched to, as
     * reported by the transport at the moment of the send - not a separately
     * maintained peer counter, which could be stale in either direction. Nearby's
     * send is fire-and-forget, so this is "handed to N peers", not "confirmed
     * delivered to N peers" - do not present it as a delivery receipt.
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
        // `severity` arrives as a Severity enum name (the canonical stored form).
        // Render the human label in the relayed text, falling back to the raw
        // value if a future sender uses something this build does not know.
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
            // The same report in machine-readable form, coordinates included.
            // The prose above is for humans reading the chat; receivers store
            // from this.
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
     *
     * Sent as a FLOOD_ALERT, not TEXT: this is an authoritative statement, and
     * MeshManager's role gate refuses it outright unless the session is
     * OPERATOR/ADMIN - so a citizen build cannot manufacture a confirmation even
     * by calling this directly. Severity is the report's own, so only a CRITICAL
     * report reaches the siren path on receiving devices; anything lower arrives
     * as an ordinary alert.
     *
     * The body carries the original reference id, which is what lets receivers
     * mark the report they already hold as verified instead of duplicating it.
     */
    fun broadcastVerification(incident: IncidentEntity): Int {
        val payload = MeshPayload(
            senderId = myDeviceId,
            senderName = senderName.value,
            type = MessageType.FLOOD_ALERT,
            text = verificationAnnouncement(incident.id, incident.type, incident.location),
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            severity = incident.severity,
            // Carries the location as a discrete field rather than only inside
            // the sentence above, so the alert UI can put a place name in the
            // place-name row instead of the whole announcement.
            incidentJson = IncidentWire.fromEntity(incident, verifiedBy = incident.verifiedBy).toJson(),
        )
        return meshManager.sendMessage(channelRepository.cityChannelId(), payload)
    }

    fun startVoiceRecording(): File = voiceRecorder.startRecording(getApplication())

    /**
     * Stops recording and sends the clip. Returns false when the recording
     * could not be finalised or read back - the caller should surface that
     * rather than leaving the user believing a voice note went out.
     */
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
        )
        meshManager.sendMessage(channelId, payload, audioBytes = audioBytes)
        return true
    }

    /** Prefer [SosBeaconService] for a real SOS session - it keeps repeating with the screen off. */
    fun sendSosBeacon(beacon: SosBeacon) = meshManager.sendSosBeacon(beacon)

    /** Called after cancelling our own SOS so the UI drops back to idle instead of showing the last-known beacon forever. */
    fun clearMySosBeacon() {
        _sosBeacons.update { current -> current.filterNot { it.value.isMine } }
    }

    override fun onCleared() {
        // transport is process-wide (MeshSession) and may still be carrying an
        // active SosBeaconService broadcast - do not stop it just because this
        // ViewModel's owner (the Activity) went away.
        super.onCleared()
    }
}