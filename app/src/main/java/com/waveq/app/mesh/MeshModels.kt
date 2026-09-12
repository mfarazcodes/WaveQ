package com.waveq.app.mesh

import java.io.File

/**
 * Message types supported across the mesh network.
 * Declared first so routing limits and serialisers can resolve it.
 */
enum class MessageType { TEXT, VOICE, FLOOD_ALERT, SOS, RISK_UPDATE, SENSOR_ALERT }

/**
 * Defines who should process and display an alert.
 * Intermediate nodes relay packets regardless of scope, but UI notifications
 * and alarms terminate only on matching roles.
 */
enum class AlertScope {
    ALL_PEERS,
    RESPONDERS_ONLY
}

/**
 * Discrete hop ceiling rules per traffic type to mitigate packet multiplication
 * and uncontrolled mesh flooding.
 */
object MeshHopLimits {
    const val HARD_CEILING = 8
    const val CHAT_MAX_HOPS = 3
    const val SENSOR_MAX_HOPS = 5
    const val SOS_MAX_HOPS = 6
    const val BROADCAST_MAX_HOPS = 8

    fun getLimitFor(type: MessageType): Int = when (type) {
        MessageType.TEXT, MessageType.VOICE -> CHAT_MAX_HOPS
        MessageType.SENSOR_ALERT -> SENSOR_MAX_HOPS
        MessageType.SOS -> SOS_MAX_HOPS
        MessageType.FLOOD_ALERT, MessageType.RISK_UPDATE -> BROADCAST_MAX_HOPS
    }
}

const val SENSOR_ALERT_MAX_HOPS = MeshHopLimits.SENSOR_MAX_HOPS

const val SOS_CHANNEL_ID = "sos-emergency-broadcast"
const val SYSTEM_SENDER_NAME = "WaveQ risk engine"
const val SENSOR_SENDER_NAME = "WaveQ water sensor"

/**
 * Outer envelope - always plaintext, needed for routing/relay decisions.
 */
data class MeshEnvelope(
    val messageId: String,
    val channelId: String,
    val isEncrypted: Boolean,
    val iv: ByteArray?,
    val payload: ByteArray,
    val hopCount: Int,
    val maxHops: Int = MeshHopLimits.CHAT_MAX_HOPS,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshEnvelope
        return messageId == other.messageId &&
                channelId == other.channelId &&
                isEncrypted == other.isEncrypted &&
                hopCount == other.hopCount &&
                maxHops == other.maxHops &&
                (iv?.contentEquals(other.iv ?: byteArrayOf()) ?: (other.iv == null)) &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + hopCount
        result = 31 * result + maxHops
        return result
    }
}

/**
 * Inner payload - encrypted for private channels.
 */
data class MeshPayload(
    val senderId: String,
    val senderName: String,
    val type: MessageType,
    val text: String?,
    val audioFileName: String?,
    val timestamp: Long,
    val severity: String? = null,
    val riskJson: String? = null,
    val incidentJson: String? = null,
    val sensorJson: String? = null,
    val targetScope: AlertScope = AlertScope.ALL_PEERS,
    val signature: ByteArray? = null,
    val signerRole: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshPayload
        return senderId == other.senderId &&
                senderName == other.senderName &&
                type == other.type &&
                text == other.text &&
                audioFileName == other.audioFileName &&
                timestamp == other.timestamp &&
                severity == other.severity &&
                riskJson == other.riskJson &&
                incidentJson == other.incidentJson &&
                sensorJson == other.sensorJson &&
                targetScope == other.targetScope &&
                signerRole == other.signerRole &&
                (signature?.contentEquals(other.signature ?: byteArrayOf()) ?: (other.signature == null))
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (audioFileName?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (severity?.hashCode() ?: 0)
        result = 31 * result + (riskJson?.hashCode() ?: 0)
        result = 31 * result + (incidentJson?.hashCode() ?: 0)
        result = 31 * result + (sensorJson?.hashCode() ?: 0)
        result = 31 * result + targetScope.hashCode()
        result = 31 * result + (signerRole?.hashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * UI-facing decrypted (or public) message ready to render in lists.
 */
data class MeshMessage(
    val messageId: String,
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val type: MessageType,
    val text: String?,
    val audioFile: File?,
    val timestamp: Long,
    val isMine: Boolean,
    val severity: String? = null,
    val riskJson: String? = null,
    val incidentJson: String? = null,
    val sensorJson: String? = null,
    val hopCount: Int = 0,
    val targetScope: AlertScope = AlertScope.ALL_PEERS,
    val signature: ByteArray? = null,
    val signerRole: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshMessage
        return messageId == other.messageId &&
                channelId == other.channelId &&
                senderId == other.senderId &&
                senderName == other.senderName &&
                type == other.type &&
                text == other.text &&
                audioFile == other.audioFile &&
                timestamp == other.timestamp &&
                isMine == other.isMine &&
                severity == other.severity &&
                riskJson == other.riskJson &&
                incidentJson == other.incidentJson &&
                sensorJson == other.sensorJson &&
                hopCount == other.hopCount &&
                targetScope == other.targetScope &&
                signerRole == other.signerRole &&
                (signature?.contentEquals(other.signature ?: byteArrayOf()) ?: (other.signature == null))
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (audioFile?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isMine.hashCode()
        result = 31 * result + (severity?.hashCode() ?: 0)
        result = 31 * result + (riskJson?.hashCode() ?: 0)
        result = 31 * result + (incidentJson?.hashCode() ?: 0)
        result = 31 * result + (sensorJson?.hashCode() ?: 0)
        result = 31 * result + hopCount
        result = 31 * result + targetScope.hashCode()
        result = 31 * result + (signerRole?.hashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Emergency location beacon.
 */
data class SosBeacon(
    val beaconId: String,
    val sequence: Int,
    val senderId: String,
    val senderName: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val note: String?,
    val startedAt: Long,
    val sentAt: Long,
    val powNonce: Long? = null,
    val signature: ByteArray? = null,
    val signerRole: String? = null,
) {
    val hasFix: Boolean get() = latitude != null && longitude != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SosBeacon
        return beaconId == other.beaconId &&
                sequence == other.sequence &&
                senderId == other.senderId &&
                senderName == other.senderName &&
                latitude == other.latitude &&
                longitude == other.longitude &&
                accuracyMeters == other.accuracyMeters &&
                batteryPercent == other.batteryPercent &&
                isCharging == other.isCharging &&
                note == other.note &&
                startedAt == other.startedAt &&
                sentAt == other.sentAt &&
                powNonce == other.powNonce &&
                signerRole == other.signerRole &&
                (signature?.contentEquals(other.signature ?: byteArrayOf()) ?: (other.signature == null))
    }

    override fun hashCode(): Int {
        var result = beaconId.hashCode()
        result = 31 * result + sequence
        result = 31 * result + senderId.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + (accuracyMeters?.hashCode() ?: 0)
        result = 31 * result + (batteryPercent?.hashCode() ?: 0)
        result = 31 * result + isCharging.hashCode()
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + startedAt.hashCode()
        result = 31 * result + sentAt.hashCode()
        result = 31 * result + (powNonce?.hashCode() ?: 0)
        result = 31 * result + (signerRole?.hashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        return result
    }
}