package com.waveq.app.mesh

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

private const val ENVELOPE_VERSION: Byte = 1
private const val GCM_IV_BYTES = 12

/**
 * Hard ceiling on any length field read off the wire.
 *
 * Nearby Connections' BYTES payload limit is 32 KB, so nothing legitimate can
 * exceed it - and every length below is attacker-controlled. A declared length
 * of 0x7FFFFFFF used to be passed straight to `ByteArray(...)`, allocating 2 GB
 * and throwing OutOfMemoryError from a ~20-byte message sent by any unpaired
 * device in range. OutOfMemoryError is an Error, not an Exception, so the
 * transport's `catch (e: Exception)` would not have caught it either.
 *
 * Deliberately a plain constant rather than ConnectionsClient.MAX_BYTES_DATA_SIZE:
 * this file is transport-agnostic and must not depend on GMS.
 */
const val MAX_WIRE_PAYLOAD_BYTES = 32 * 1024

/**
 * Wire encoding for [MeshEnvelope]/[MeshPayload]. MeshEnvelope is packed as a
 * flat binary blob (for Nearby Connections' Payload.fromBytes); MeshPayload is
 * JSON since it only ever appears as the plaintext input/output of encryption,
 * never on the wire by itself.
 */
object MeshSerialization {

    fun encodeEnvelope(envelope: MeshEnvelope): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(ENVELOPE_VERSION.toInt())
            d.writeUTF(envelope.messageId)
            d.writeUTF(envelope.channelId)
            d.writeBoolean(envelope.isEncrypted)
            if (envelope.isEncrypted) {
                val iv = envelope.iv ?: throw IllegalArgumentException("encrypted envelope missing iv")
                require(iv.size == GCM_IV_BYTES) { "iv must be $GCM_IV_BYTES bytes" }
                d.write(iv)
            }
            d.writeInt(envelope.payload.size)
            d.write(envelope.payload)
            d.writeInt(envelope.hopCount)
            d.writeInt(envelope.maxHops)
        }
        return out.toByteArray()
    }

    fun decodeEnvelope(bytes: ByteArray): MeshEnvelope {
        require(bytes.size <= MAX_WIRE_PAYLOAD_BYTES) { "envelope larger than the wire ceiling" }
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val version = d.readByte()
            require(version == ENVELOPE_VERSION) { "unsupported envelope version $version" }
            val messageId = d.readUTF()
            val channelId = d.readUTF()
            val isEncrypted = d.readBoolean()
            val iv = if (isEncrypted) ByteArray(GCM_IV_BYTES).also { d.readFully(it) } else null
            val payloadLen = d.readInt()
            // Checked BEFORE allocating, against both the protocol ceiling and
            // the buffer we actually have. Either check alone is insufficient:
            // the ceiling stops the 2 GB allocation, the buffer size stops a
            // merely-large lie from allocating 32 KB per malformed message.
            requireValidLength(payloadLen, bytes.size, "payload")
            val payload = ByteArray(payloadLen).also { d.readFully(it) }
            // Hop fields are attacker-controlled and they are the only thing
            // bounding how far a message travels. Unclamped, a peer declaring
            // maxHops = Int.MAX_VALUE makes one message traverse the entire
            // connected cluster regardless of the 5-hop chat default or the
            // 12-hop alert cap, and a negative hopCount buys extra hops on top
            // of whatever cap it declares.
            //
            // Clamped rather than rejected: a nonsense hop field is far more
            // likely to be an older or buggy build than an attack, and dropping
            // the message outright would silently lose a life-safety alert whose
            // body is perfectly good. Constraining it costs nothing legitimate -
            // every sender in this app already declares a value inside this
            // range, so real traffic passes through untouched.
            val hopCount = d.readInt().coerceAtLeast(0)
            val maxHops = d.readInt().coerceIn(1, SENSOR_ALERT_MAX_HOPS)
            return MeshEnvelope(messageId, channelId, isEncrypted, iv, payload, hopCount, maxHops)
        }
    }

    /** Throws unless [length] is a plausible size for a field inside a [total]-byte buffer. */
    private fun requireValidLength(length: Int, total: Int, field: String) {
        require(length >= 0) { "negative $field length: $length" }
        require(length <= MAX_WIRE_PAYLOAD_BYTES) { "$field length $length exceeds the wire ceiling" }
        require(length <= total) { "$field length $length exceeds the $total-byte buffer" }
    }

    fun payloadToJson(payload: MeshPayload): String {
        val obj = JSONObject()
        obj.put("senderId", payload.senderId)
        obj.put("senderName", payload.senderName)
        obj.put("type", payload.type.name)
        obj.put("text", payload.text ?: JSONObject.NULL)
        obj.put("audioFileName", payload.audioFileName ?: JSONObject.NULL)
        obj.put("timestamp", payload.timestamp)
        obj.put("severity", payload.severity ?: JSONObject.NULL)
        obj.put("riskJson", payload.riskJson ?: JSONObject.NULL)
        obj.put("incidentJson", payload.incidentJson ?: JSONObject.NULL)
        obj.put("sensorJson", payload.sensorJson ?: JSONObject.NULL)
        return obj.toString()
    }

    fun payloadFromJson(json: String): MeshPayload {
        val obj = JSONObject(json)
        return MeshPayload(
            senderId = obj.getString("senderId"),
            senderName = obj.getString("senderName"),
            type = MessageType.valueOf(obj.getString("type")),
            text = if (obj.isNull("text")) null else obj.getString("text"),
            audioFileName = if (obj.isNull("audioFileName")) null else obj.getString("audioFileName"),
            timestamp = obj.getLong("timestamp"),
            severity = if (obj.isNull("severity")) null else obj.getString("severity"),
            // optString, not getString: a payload from an older build has no
            // such field and must still parse rather than throwing.
            riskJson = if (obj.isNull("riskJson")) null else obj.optString("riskJson").takeIf { it.isNotEmpty() },
            // Absent from payloads sent by older builds; optString, not
            // getString, so those still parse rather than throwing.
            incidentJson = if (obj.isNull("incidentJson")) {
                null
            } else {
                obj.optString("incidentJson").takeIf { it.isNotEmpty() }
            },
            sensorJson = if (obj.isNull("sensorJson")) {
                null
            } else {
                obj.optString("sensorJson").takeIf { it.isNotEmpty() }
            },
        )
    }

    fun sosBeaconToJson(beacon: SosBeacon): String {
        val obj = JSONObject()
        obj.put("beaconId", beacon.beaconId)
        obj.put("sequence", beacon.sequence)
        obj.put("senderId", beacon.senderId)
        obj.put("senderName", beacon.senderName)
        // Explicit JSON null, never a sentinel coordinate: a receiver must be
        // able to tell "no fix yet" from a position at 0,0.
        obj.put("latitude", beacon.latitude ?: JSONObject.NULL)
        obj.put("longitude", beacon.longitude ?: JSONObject.NULL)
        obj.put("accuracyMeters", beacon.accuracyMeters?.toDouble() ?: JSONObject.NULL)
        // Explicit JSON null for an unreadable capacity, same convention as the
        // coordinates above - a receiver must be able to tell "unknown" from a
        // reading.
        obj.put("batteryPercent", beacon.batteryPercent ?: JSONObject.NULL)
        obj.put("isCharging", beacon.isCharging)
        obj.put("note", beacon.note ?: JSONObject.NULL)
        obj.put("startedAt", beacon.startedAt)
        obj.put("sentAt", beacon.sentAt)
        return obj.toString()
    }

    fun sosBeaconFromJson(json: String): SosBeacon {
        val obj = JSONObject(json)
        val rawLatitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude")
        val rawLongitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude")
        // An off-globe coordinate is not a position, so it takes the path the
        // format already has for "no position": null. The alternative - passing
        // 9999.0 through - would render a confident bearing and distance to a
        // place that does not exist, which is worse than admitting no fix on a
        // screen a responder uses to decide where to go. NaN fails these
        // comparisons and is nulled with the rest.
        val hasUsableFix = rawLatitude != null && rawLongitude != null &&
            rawLatitude in -90.0..90.0 && rawLongitude in -180.0..180.0
        return SosBeacon(
            beaconId = obj.getString("beaconId"),
            sequence = obj.getInt("sequence"),
            senderId = obj.getString("senderId"),
            senderName = obj.getString("senderName"),
            latitude = if (hasUsableFix) rawLatitude else null,
            longitude = if (hasUsableFix) rawLongitude else null,
            // Dropped along with the coordinates: an accuracy radius around a
            // position that was discarded describes nothing.
            accuracyMeters = if (!hasUsableFix || obj.isNull("accuracyMeters")) {
                null
            } else {
                obj.getDouble("accuracyMeters").toFloat()
            },
            // Absent or explicitly null from a sender that could not read its
            // own capacity, and null again for anything outside 0..100.
            //
            // Not clamped: a pre-fix build's Integer.MIN_VALUE sentinel would
            // clamp to 0 and render as "0%", which on an SOS triage screen reads
            // as a phone about to die and gets acted on. Out of range means the
            // sender did not report a capacity, so it takes the same path as an
            // absent one. SosScreen's battery row rendered this value raw, so an
            // out-of-range percent reached the screen unchallenged.
            batteryPercent = if (obj.isNull("batteryPercent")) {
                null
            } else {
                obj.getInt("batteryPercent").takeIf { it in 0..100 }
            },
            isCharging = obj.getBoolean("isCharging"),
            note = if (obj.isNull("note")) null else obj.getString("note"),
            startedAt = obj.getLong("startedAt"),
            sentAt = obj.getLong("sentAt"),
        )
    }

    /** SOS beacons are always plaintext - no encryption framing, unlike [encodePayloadWithAudio]. */
    fun encodeSosBeacon(beacon: SosBeacon): ByteArray = sosBeaconToJson(beacon).toByteArray(Charsets.UTF_8)

    fun decodeSosBeacon(bytes: ByteArray): SosBeacon = sosBeaconFromJson(String(bytes, Charsets.UTF_8))

    /** Frames payload metadata + raw audio bytes as one plaintext buffer for voice messages. */
    fun encodePayloadWithAudio(payload: MeshPayload, audioBytes: ByteArray): ByteArray {
        val json = payloadToJson(payload).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(json.size)
            d.write(json)
            d.write(audioBytes)
        }
        return out.toByteArray()
    }

    /** Inverse of [encodePayloadWithAudio]. Returns null audio bytes for a plain text-only buffer. */
    fun decodePayloadWithAudio(bytes: ByteArray): Pair<MeshPayload, ByteArray?> {
        require(bytes.size <= MAX_WIRE_PAYLOAD_BYTES) { "plaintext larger than the wire ceiling" }
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val jsonLen = d.readInt()
            // Same reasoning as decodeEnvelope: this length is attacker-supplied
            // on an unencrypted channel and reaches ByteArray(...) directly.
            requireValidLength(jsonLen, bytes.size, "json")
            val jsonBytes = ByteArray(jsonLen).also { d.readFully(it) }
            val payload = payloadFromJson(String(jsonBytes, Charsets.UTF_8))
            val remaining = bytes.size - 4 - jsonLen
            require(remaining >= 0) { "json length $jsonLen overruns the buffer" }
            val audio = if (remaining > 0) ByteArray(remaining).also { d.readFully(it) } else null
            return payload to audio
        }
    }
}
