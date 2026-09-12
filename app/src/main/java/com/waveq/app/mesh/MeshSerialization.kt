package com.waveq.app.mesh

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

private const val ENVELOPE_VERSION: Byte = 1
private const val GCM_IV_BYTES = 12

/**
 * Hard ceiling on any length field read off the wire.
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
            requireValidLength(payloadLen, bytes.size, "payload")
            val payload = ByteArray(payloadLen).also { d.readFully(it) }
            val hopCount = d.readInt().coerceAtLeast(0)
            val maxHops = d.readInt().coerceIn(1, 12)
            return MeshEnvelope(messageId, channelId, isEncrypted, iv, payload, hopCount, maxHops)
        }
    }

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

        // Target scope & operator signature
        obj.put("targetScope", payload.targetScope.name)
        val sigB64: Any = payload.signature?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL
        obj.put("signature", sigB64)
        obj.put("signerRole", payload.signerRole ?: JSONObject.NULL)
        return obj.toString()
    }

    fun payloadFromJson(json: String): MeshPayload {
        val obj = JSONObject(json)
        val targetScopeStr = obj.optString("targetScope", AlertScope.ALL_PEERS.name)
        val targetScope = runCatching { AlertScope.valueOf(targetScopeStr) }.getOrDefault(AlertScope.ALL_PEERS)

        val signatureBytes = if (obj.isNull("signature")) {
            null
        } else {
            obj.optString("signature").takeIf { it.isNotEmpty() }?.let {
                runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
            }
        }

        val signerRole = if (obj.isNull("signerRole")) {
            null
        } else {
            obj.optString("signerRole").takeIf { it.isNotEmpty() }
        }

        return MeshPayload(
            senderId = obj.getString("senderId"),
            senderName = obj.getString("senderName"),
            type = MessageType.valueOf(obj.getString("type")),
            text = if (obj.isNull("text")) null else obj.getString("text"),
            audioFileName = if (obj.isNull("audioFileName")) null else obj.getString("audioFileName"),
            timestamp = obj.getLong("timestamp"),
            severity = if (obj.isNull("severity")) null else obj.getString("severity"),
            riskJson = if (obj.isNull("riskJson")) null else obj.optString("riskJson").takeIf { it.isNotEmpty() },
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
            targetScope = targetScope,
            signature = signatureBytes,
            signerRole = signerRole,
        )
    }

    fun sosBeaconToJson(beacon: SosBeacon): String {
        val obj = JSONObject()
        obj.put("beaconId", beacon.beaconId)
        obj.put("sequence", beacon.sequence)
        obj.put("senderId", beacon.senderId)
        obj.put("senderName", beacon.senderName)
        obj.put("latitude", beacon.latitude ?: JSONObject.NULL)
        obj.put("longitude", beacon.longitude ?: JSONObject.NULL)
        obj.put("accuracyMeters", beacon.accuracyMeters?.toDouble() ?: JSONObject.NULL)
        obj.put("batteryPercent", beacon.batteryPercent ?: JSONObject.NULL)
        obj.put("isCharging", beacon.isCharging)
        obj.put("note", beacon.note ?: JSONObject.NULL)
        obj.put("startedAt", beacon.startedAt)
        obj.put("sentAt", beacon.sentAt)

        // Anti-spam PoW & Responder Signatures
        val nonceVal: Any = beacon.powNonce ?: JSONObject.NULL
        obj.put("powNonce", nonceVal)

        val sigB64: Any = beacon.signature?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL
        obj.put("signature", sigB64)
        obj.put("signerRole", beacon.signerRole ?: JSONObject.NULL)
        return obj.toString()
    }

    fun sosBeaconFromJson(json: String): SosBeacon {
        val obj = JSONObject(json)
        val rawLatitude = if (obj.isNull("latitude")) null else obj.getDouble("latitude")
        val rawLongitude = if (obj.isNull("longitude")) null else obj.getDouble("longitude")
        val hasUsableFix = rawLatitude != null && rawLongitude != null &&
                rawLatitude in -90.0..90.0 && rawLongitude in -180.0..180.0

        val powNonce = if (obj.isNull("powNonce") || !obj.has("powNonce")) {
            null
        } else {
            obj.getLong("powNonce")
        }

        val signatureBytes = if (obj.isNull("signature") || !obj.has("signature")) {
            null
        } else {
            obj.optString("signature").takeIf { it.isNotEmpty() }?.let {
                runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
            }
        }

        val signerRole = if (obj.isNull("signerRole") || !obj.has("signerRole")) {
            null
        } else {
            obj.optString("signerRole").takeIf { it.isNotEmpty() }
        }

        return SosBeacon(
            beaconId = obj.getString("beaconId"),
            sequence = obj.getInt("sequence"),
            senderId = obj.getString("senderId"),
            senderName = obj.getString("senderName"),
            latitude = if (hasUsableFix) rawLatitude else null,
            longitude = if (hasUsableFix) rawLongitude else null,
            accuracyMeters = if (!hasUsableFix || obj.isNull("accuracyMeters")) {
                null
            } else {
                obj.getDouble("accuracyMeters").toFloat()
            },
            batteryPercent = if (obj.isNull("batteryPercent")) {
                null
            } else {
                obj.getInt("batteryPercent").takeIf { it in 0..100 }
            },
            isCharging = obj.getBoolean("isCharging"),
            note = if (obj.isNull("note")) null else obj.getString("note"),
            startedAt = obj.getLong("startedAt"),
            sentAt = obj.getLong("sentAt"),
            powNonce = powNonce,
            signature = signatureBytes,
            signerRole = signerRole,
        )
    }

    fun encodeSosBeacon(beacon: SosBeacon): ByteArray = sosBeaconToJson(beacon).toByteArray(Charsets.UTF_8)

    fun decodeSosBeacon(bytes: ByteArray): SosBeacon = sosBeaconFromJson(String(bytes, Charsets.UTF_8))

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

    fun decodePayloadWithAudio(bytes: ByteArray): Pair<MeshPayload, ByteArray?> {
        require(bytes.size <= MAX_WIRE_PAYLOAD_BYTES) { "plaintext larger than the wire ceiling" }
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val jsonLen = d.readInt()
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