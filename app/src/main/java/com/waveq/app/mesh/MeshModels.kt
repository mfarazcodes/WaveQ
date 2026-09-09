package com.waveq.app.mesh

/**
 * [SENSOR_ALERT] carries a physical water-level detection from a probe on the
 * local network - see the sensor package.
 *
 * WARNING when adding values here: [MeshSerialization.payloadFromJson] resolves
 * this with `MessageType.valueOf`, which throws on an unknown name. A device on
 * an older build that receives a SENSOR_ALERT therefore takes the
 * malformed-payload path and drops it - it does not crash (the transport's
 * receive boundary catches it) but it also does not alert. Flash every demo
 * device with the same APK.
 */
enum class MessageType { TEXT, VOICE, FLOOD_ALERT, SOS, RISK_UPDATE, SENSOR_ALERT }

/**
 * Reserved channelId for SOS beacons. Every device relays and reads traffic on
 * this channel regardless of what channels it has joined - SOS is never routed
 * through [ChannelRepository] membership or [ChannelCrypto].
 */
const val SOS_CHANNEL_ID = "sos-emergency-broadcast"

/**
 * The sender name stamped on machine-generated [MessageType.RISK_UPDATE]
 * messages.
 *
 * Risk updates used to carry the local user's display name, because they are
 * composed with the same MeshPayload builder as a chat message - so an automatic
 * hourly assessment arrived on other devices looking like a person had typed it,
 * and the chat rendered it under their name. Nothing a model generated may be
 * attributed to a human being.
 *
 * The device that fetched the data is still identified, separately, in the risk
 * payload's own provenance fields - which the risk screen labels as a device,
 * not as a speaker.
 */
const val SYSTEM_SENDER_NAME = "WaveQ risk engine"

/**
 * Sender name stamped on [MessageType.SENSOR_ALERT] messages.
 *
 * Same reasoning as [SYSTEM_SENDER_NAME]: a probe detected water, no person
 * said anything, and nothing machine-generated may be attributed to a human.
 * The bridging device is identified separately inside the payload, as a device.
 */
const val SENSOR_SENDER_NAME = "WaveQ water sensor"

/**
 * Outer envelope - always plaintext, needed for routing/relay decisions.
 *
 * A relay device reads messageId/channelId/hopCount to decide whether to
 * rebroadcast, but [payload] is opaque to it unless it also holds the channel
 * key: either raw plaintext JSON (unencrypted channel) or AES-GCM ciphertext
 * (encrypted channel, decryptable only with [iv] + the channel key).
 */
data class MeshEnvelope(
    val messageId: String,
    val channelId: String,
    val isEncrypted: Boolean,
    val iv: ByteArray?,
    val payload: ByteArray,
    val hopCount: Int,
    val maxHops: Int = 5,
)

/**
 * Inner payload - this is what gets encrypted for private channels.
 *
 * Sender identity lives in here, not in the envelope, so a relay that cannot
 * decrypt a private message also cannot learn who sent it.
 */
data class MeshPayload(
    val senderId: String,
    val senderName: String,
    val type: MessageType,
    val text: String?,
    val audioFileName: String?,
    val timestamp: Long,
    /**
     * Matches a [com.waveq.app.ui.components.Severity] name. Set for
     * [MessageType.FLOOD_ALERT] and for [MessageType.RISK_UPDATE], so a device
     * can tell a CRITICAL risk update from a routine one without parsing the
     * full assessment.
     */
    val severity: String? = null,
    /**
     * Only for [MessageType.RISK_UPDATE]: the serialised
     * [com.waveq.app.prediction.RiskAssessment], carrying the coordinates it
     * was computed for, the timestamp its source data was fetched, and the id
     * of the device that fetched it. See RiskSerialization.
     */
    val riskJson: String? = null,
    /**
     * Only for incident traffic: the serialised
     * [com.waveq.app.data.IncidentWire] behind a report or a verification.
     *
     * Optional and additive - an older build ignores it and falls back to
     * parsing the human-readable [text], which is what this replaces. Coordinates
     * in particular exist nowhere in that prose, so before this field a relayed
     * report could never be plotted on a map.
     */
    val incidentJson: String? = null,
    /**
     * Only for [MessageType.SENSOR_ALERT]: the serialised
     * [com.waveq.app.sensor.SensorAlertPayload] - which sensor, which bridging
     * device, and the event identity used to collapse duplicate broadcasts from
     * two devices that both reached the same probe.
     */
    val sensorJson: String? = null,
)

/** UI-facing decrypted (or already-public) message, ready to render in a chat list. */
data class MeshMessage(
    val messageId: String,
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val type: MessageType,
    val text: String?,
    val audioFile: java.io.File?,
    val timestamp: Long,
    val isMine: Boolean,
    val severity: String? = null,
    /** Only for [MessageType.RISK_UPDATE] - see [MeshPayload.riskJson]. */
    val riskJson: String? = null,
    /** Only for incident traffic - see [MeshPayload.incidentJson]. */
    val incidentJson: String? = null,
    /** Only for [MessageType.SENSOR_ALERT] - see [MeshPayload.sensorJson]. */
    val sensorJson: String? = null,
    /**
     * How many relay hops this message travelled before reaching us. Surfaced
     * so a risk update can honestly say "relayed from a nearby device, 2 hops"
     * rather than presenting relayed data as locally fetched.
     */
    val hopCount: Int = 0,
)

/**
 * An emergency location beacon. Always sent unencrypted on [SOS_CHANNEL_ID],
 * repeated every 30s with an incrementing [sequence] for as long as the sender
 * keeps their SOS active - [beaconId] stays stable for that whole session so
 * relays can dedup repeats and tell a stale beacon from a fresher one.
 */
data class SosBeacon(
    val beaconId: String,
    val sequence: Int,
    val senderId: String,
    val senderName: String,
    /**
     * Null until the device has a real GPS fix, and null on every beacon sent
     * before one arrives.
     *
     * Deliberately nullable rather than defaulted: an earlier version sent
     * `0.0, 0.0` when no fix was available, which every receiver rendered as a
     * genuine position off the coast of West Africa and computed a confident
     * distance from. "Position unknown" and "position is the origin" must stay
     * distinguishable all the way through the wire format and the UI.
     */
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    /**
     * Null when the device could not read its own capacity - see
     * [batteryPercentOrNull].
     *
     * Nullable for the same reason [latitude] is: "unknown" and a number are
     * different claims, and on a screen a responder uses to triage, the wrong
     * number is worse than no number. A missing reading used to arrive here as
     * BatteryManager's Integer.MIN_VALUE sentinel and render as
     * `-2147483648%`; clamping that to 0 would have been worse still, reading
     * as a phone about to die.
     */
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val note: String?,
    val startedAt: Long,
    val sentAt: Long,
) {
    /** True when this beacon carries a real position. */
    val hasFix: Boolean get() = latitude != null && longitude != null
}
