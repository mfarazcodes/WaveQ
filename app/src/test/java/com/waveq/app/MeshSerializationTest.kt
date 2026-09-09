package com.waveq.app

import com.waveq.app.mesh.MAX_WIRE_PAYLOAD_BYTES
import com.waveq.app.mesh.MeshEnvelope
import com.waveq.app.mesh.MeshPayload
import com.waveq.app.mesh.MeshSerialization
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.SENSOR_ALERT_MAX_HOPS
import com.waveq.app.mesh.SENSOR_SENDER_NAME
import com.waveq.app.mesh.SYSTEM_SENDER_NAME
import com.waveq.app.mesh.SosBeacon
import com.waveq.app.mesh.batteryPercentOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wire-format tests for [MeshSerialization].
 *
 * These parse bytes that arrive from any unpaired device in range, on a channel
 * with no encryption and no pairing prompt, so the interesting cases are the
 * hostile ones rather than the round-trips.
 *
 * ## A caveat on the test environment
 *
 * These run against the Crockford `org.json` on the unit-test classpath; the
 * device runs Android's own implementation. The two differ in **type coercion**:
 * `JSONObject.getString` on a numeric value coerces on Android and throws on
 * Crockford. Assertions below therefore only pin behaviour where the two agree -
 * missing fields, malformed documents, structurally wrong values - and the
 * coercion divergence is documented rather than asserted. Anything relying on
 * exact coercion semantics needs an instrumentation test.
 */
class MeshSerializationTest {

    // -- helpers -----------------------------------------------------------

    /**
     * Asserts input is rejected by throwing something the transport's receive
     * boundary catches.
     *
     * That boundary catches `Exception` and `OutOfMemoryError` specifically, so
     * anything else - another Error, or worse, a silent return of a corrupt
     * object - would crash the process or poison the mesh.
     */
    private fun assertRejectedCleanly(what: String, block: () -> Any?) {
        try {
            val result = block()
            fail("$what was accepted and produced $result instead of being rejected")
        } catch (e: OutOfMemoryError) {
            // Caught explicitly by NearbyTransport; acceptable, though the
            // length guards should make it unreachable.
        } catch (e: Exception) {
            // The expected path.
        } catch (t: Throwable) {
            fail("$what threw ${t.javaClass.name}, which the receive boundary does not catch")
        }
    }

    private fun payload(
        type: MessageType,
        senderName: String = "Priya",
        text: String? = "hello",
        severity: String? = null,
        riskJson: String? = null,
        incidentJson: String? = null,
        sensorJson: String? = null,
        audioFileName: String? = null,
    ) = MeshPayload(
        senderId = "device-1",
        senderName = senderName,
        type = type,
        text = text,
        audioFileName = audioFileName,
        timestamp = 1_800_000_000_000L,
        severity = severity,
        riskJson = riskJson,
        incidentJson = incidentJson,
        sensorJson = sensorJson,
    )

    private fun beacon(
        latitude: Double? = 28.6139,
        longitude: Double? = 77.2090,
        note: String? = "trapped on second floor",
        batteryPercent: Int? = 43,
    ) = SosBeacon(
        beaconId = "beacon-1",
        sequence = 7,
        senderId = "device-1",
        senderName = "Priya",
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (latitude == null) null else 12.5f,
        batteryPercent = batteryPercent,
        isCharging = false,
        note = note,
        startedAt = 1_800_000_000_000L,
        sentAt = 1_800_000_030_000L,
    )

    // -- envelope round-trips ---------------------------------------------

    @Test
    fun `unencrypted envelope round-trips`() {
        val original = MeshEnvelope(
            messageId = "msg-1",
            channelId = "city-wide",
            isEncrypted = false,
            iv = null,
            payload = "payload-bytes".toByteArray(),
            hopCount = 2,
            maxHops = 12,
        )
        val decoded = MeshSerialization.decodeEnvelope(MeshSerialization.encodeEnvelope(original))

        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.channelId, decoded.channelId)
        assertEquals(false, decoded.isEncrypted)
        assertNull(decoded.iv)
        assertArrayEquals(original.payload, decoded.payload)
        assertEquals(2, decoded.hopCount)
        assertEquals(12, decoded.maxHops)
    }

    @Test
    fun `encrypted envelope round-trips with its iv intact`() {
        val iv = ByteArray(12) { it.toByte() }
        val original = MeshEnvelope(
            messageId = "msg-2",
            channelId = "family",
            isEncrypted = true,
            iv = iv,
            payload = ByteArray(64) { (it * 3).toByte() },
            hopCount = 0,
            maxHops = 5,
        )
        val decoded = MeshSerialization.decodeEnvelope(MeshSerialization.encodeEnvelope(original))

        assertTrue(decoded.isEncrypted)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `an empty payload round-trips`() {
        val original = MeshEnvelope("m", "c", false, null, ByteArray(0), 0, 5)
        val decoded = MeshSerialization.decodeEnvelope(MeshSerialization.encodeEnvelope(original))
        assertEquals(0, decoded.payload.size)
    }

    // -- payload round-trips, every MessageType ---------------------------

    @Test
    fun `every MessageType round-trips through the payload JSON`() {
        // Fails the moment a value is added to the enum without being considered
        // here, which is what happened when SENSOR_ALERT landed.
        MessageType.entries.forEach { type ->
            val decoded = MeshSerialization.payloadFromJson(
                MeshSerialization.payloadToJson(payload(type)),
            )
            assertEquals("round-trip failed for $type", type, decoded.type)
            assertEquals("device-1", decoded.senderId)
            assertEquals("hello", decoded.text)
            assertEquals(1_800_000_000_000L, decoded.timestamp)
        }
    }

    @Test
    fun `null optional fields stay null rather than becoming empty strings`() {
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(payload(MessageType.TEXT, text = null)),
        )
        assertNull(decoded.text)
        assertNull(decoded.audioFileName)
        assertNull(decoded.severity)
        assertNull(decoded.riskJson)
        assertNull(decoded.incidentJson)
        assertNull(decoded.sensorJson)
    }

    @Test
    fun `a voice payload keeps its audio file name`() {
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(
                payload(MessageType.VOICE, text = null, audioFileName = "voice_abc.m4a"),
            ),
        )
        assertEquals("voice_abc.m4a", decoded.audioFileName)
    }

    // -- RISK_UPDATE attribution ------------------------------------------

    @Test
    fun `a risk update survives the wire without being attributed to a person`() {
        val riskJson = """{"score":82,"severity":"CRITICAL"}"""
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(
                payload(
                    MessageType.RISK_UPDATE,
                    senderName = SYSTEM_SENDER_NAME,
                    text = "Flooding possible in approximately 2 hours",
                    severity = "CRITICAL",
                    riskJson = riskJson,
                ),
            ),
        )

        assertEquals(MessageType.RISK_UPDATE, decoded.type)
        // The invariant: a machine-generated assessment carries the system
        // identity end to end. If this ever decodes to a human display name,
        // the chat will render a model's output as something a person said.
        assertEquals(SYSTEM_SENDER_NAME, decoded.senderName)
        assertEquals("CRITICAL", decoded.severity)
        assertEquals(riskJson, decoded.riskJson)
    }

    @Test
    fun `a sensor alert survives the wire without being attributed to a person`() {
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(
                payload(
                    MessageType.SENSOR_ALERT,
                    senderName = SENSOR_SENDER_NAME,
                    severity = "CRITICAL",
                    sensorJson = """{"eventId":"10.0.0.5@1","sensorHost":"10.0.0.5","percent":98}""",
                ),
            ),
        )
        assertEquals(SENSOR_SENDER_NAME, decoded.senderName)
        assertEquals(MessageType.SENSOR_ALERT, decoded.type)
    }

    @Test
    fun `nested json payloads survive quoting intact`() {
        // riskJson and friends are JSON documents carried as JSON strings. A
        // quoting bug here would corrupt every relayed risk update.
        val nasty = """{"headline":"He said \"go\" now","n":[1,2,3],"esc":"a\\b"}"""
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(payload(MessageType.RISK_UPDATE, riskJson = nasty)),
        )
        assertEquals(nasty, decoded.riskJson)
    }

    @Test
    fun `unicode and emoji in message text survive the wire`() {
        val text = "बाढ़ का पानी बढ़ रहा है 🌊 — evacuate"
        val decoded = MeshSerialization.payloadFromJson(
            MeshSerialization.payloadToJson(payload(MessageType.TEXT, text = text)),
        )
        assertEquals(text, decoded.text)
    }

    // -- SOS beacons -------------------------------------------------------

    @Test
    fun `an sos beacon round-trips through its envelope payload`() {
        val original = beacon()
        val decoded = MeshSerialization.decodeSosBeacon(MeshSerialization.encodeSosBeacon(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an sos beacon with no fix keeps null coordinates, not zeroes`() {
        val decoded = MeshSerialization.decodeSosBeacon(
            MeshSerialization.encodeSosBeacon(beacon(latitude = null, longitude = null)),
        )
        // The distinction the whole nullable-coordinate change exists for: a
        // beacon sent before a GPS fix must not decode to a position at 0,0.
        assertNull(decoded.latitude)
        assertNull(decoded.longitude)
        assertNull(decoded.accuracyMeters)
        assertEquals(false, decoded.hasFix)
    }

    @Test
    fun `an sos beacon with a missing required field is rejected`() {
        val withoutBeaconId = """{"sequence":1,"senderId":"d","senderName":"n","latitude":null,
            |"longitude":null,"accuracyMeters":null,"batteryPercent":50,"isCharging":false,
            |"note":null,"startedAt":1,"sentAt":2}""".trimMargin()
        assertRejectedCleanly("an SOS beacon with no beaconId") {
            MeshSerialization.sosBeaconFromJson(withoutBeaconId)
        }
    }

    @Test
    fun `an sos beacon with a structurally wrong field is rejected`() {
        // An object where a number belongs - both org.json implementations agree
        // this is not coercible.
        val badSequence = """{"beaconId":"b","sequence":{"a":1},"senderId":"d","senderName":"n",
            |"latitude":null,"longitude":null,"accuracyMeters":null,"batteryPercent":50,
            |"isCharging":false,"note":null,"startedAt":1,"sentAt":2}""".trimMargin()
        assertRejectedCleanly("an SOS beacon whose sequence is an object") {
            MeshSerialization.sosBeaconFromJson(badSequence)
        }
    }

    @Test
    fun `a malformed sos beacon document is rejected`() {
        assertRejectedCleanly("truncated JSON") { MeshSerialization.sosBeaconFromJson("""{"beaconId":""") }
        assertRejectedCleanly("not JSON at all") { MeshSerialization.sosBeaconFromJson("hello") }
        assertRejectedCleanly("empty") { MeshSerialization.sosBeaconFromJson("") }
        assertRejectedCleanly("a JSON array") { MeshSerialization.sosBeaconFromJson("[1,2,3]") }
    }

    @Test
    fun `arbitrary bytes are not decoded as an sos beacon`() {
        assertRejectedCleanly("random bytes") {
            MeshSerialization.decodeSosBeacon(ByteArray(64) { (it * 7).toByte() })
        }
    }

    // -- hostile payload JSON ---------------------------------------------

    @Test
    fun `a payload with a missing required field is rejected`() {
        assertRejectedCleanly("no senderId") {
            MeshSerialization.payloadFromJson("""{"senderName":"n","type":"TEXT","timestamp":1}""")
        }
        assertRejectedCleanly("no type") {
            MeshSerialization.payloadFromJson("""{"senderId":"d","senderName":"n","timestamp":1}""")
        }
        assertRejectedCleanly("no timestamp") {
            MeshSerialization.payloadFromJson("""{"senderId":"d","senderName":"n","type":"TEXT"}""")
        }
    }

    @Test
    fun `an unknown message type is rejected rather than defaulted`() {
        // A device on a newer build sends a type this one has no case for.
        // MessageType.valueOf throws; the receive boundary drops the message.
        // It must never silently become TEXT and render as chat.
        assertRejectedCleanly("an unknown MessageType") {
            MeshSerialization.payloadFromJson(
                """{"senderId":"d","senderName":"n","type":"NOT_A_REAL_TYPE","timestamp":1}""",
            )
        }
    }

    @Test
    fun `a malformed payload document is rejected`() {
        assertRejectedCleanly("truncated") { MeshSerialization.payloadFromJson("""{"senderId":""") }
        assertRejectedCleanly("not JSON") { MeshSerialization.payloadFromJson("garbage") }
        assertRejectedCleanly("empty") { MeshSerialization.payloadFromJson("") }
    }

    // -- truncation and lying lengths -------------------------------------

    @Test
    fun `every truncation of a valid envelope is rejected`() {
        val valid = MeshSerialization.encodeEnvelope(
            MeshEnvelope("msg", "chan", false, null, "abcdefgh".toByteArray(), 1, 5),
        )
        // Every prefix short of the whole thing must be refused - none may
        // produce a partially-populated envelope.
        for (length in 0 until valid.size) {
            assertRejectedCleanly("an envelope truncated to $length bytes") {
                MeshSerialization.decodeEnvelope(valid.copyOf(length))
            }
        }
    }

    @Test
    fun `an unsupported envelope version is rejected`() {
        val valid = MeshSerialization.encodeEnvelope(
            MeshEnvelope("msg", "chan", false, null, ByteArray(4), 0, 5),
        )
        val wrongVersion = valid.copyOf().also { it[0] = 99 }
        assertRejectedCleanly("version 99") { MeshSerialization.decodeEnvelope(wrongVersion) }
    }

    /** Builds an envelope byte string with an arbitrary declared payload length. */
    private fun envelopeWithDeclaredLength(declared: Int, actualPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeByte(1)
            d.writeUTF("msg")
            d.writeUTF("chan")
            d.writeBoolean(false)
            d.writeInt(declared)
            d.write(actualPayload)
            d.writeInt(0)
            d.writeInt(5)
        }
        return out.toByteArray()
    }

    @Test
    fun `a negative declared payload length is rejected`() {
        assertRejectedCleanly("payloadLen = -1") {
            MeshSerialization.decodeEnvelope(envelopeWithDeclaredLength(-1, ByteArray(4)))
        }
        assertRejectedCleanly("payloadLen = Int.MIN_VALUE") {
            MeshSerialization.decodeEnvelope(envelopeWithDeclaredLength(Int.MIN_VALUE, ByteArray(4)))
        }
    }

    @Test
    fun `a declared payload length above the wire ceiling is rejected without allocating`() {
        // The 2 GB OutOfMemoryError case, from a ~20-byte message.
        assertRejectedCleanly("payloadLen = Int.MAX_VALUE") {
            MeshSerialization.decodeEnvelope(envelopeWithDeclaredLength(Int.MAX_VALUE, ByteArray(4)))
        }
        assertRejectedCleanly("payloadLen just over the ceiling") {
            MeshSerialization.decodeEnvelope(
                envelopeWithDeclaredLength(MAX_WIRE_PAYLOAD_BYTES + 1, ByteArray(4)),
            )
        }
    }

    @Test
    fun `a declared payload length exceeding the remaining bytes is rejected`() {
        // Under the ceiling and under the total buffer size, so it passes the
        // length guards - and is then caught by readFully running out of input.
        assertRejectedCleanly("payloadLen overruns the remaining bytes") {
            MeshSerialization.decodeEnvelope(envelopeWithDeclaredLength(200, ByteArray(4)))
        }
    }

    @Test
    fun `a buffer larger than the wire ceiling is rejected before parsing`() {
        assertRejectedCleanly("an oversized buffer") {
            MeshSerialization.decodeEnvelope(ByteArray(MAX_WIRE_PAYLOAD_BYTES + 1))
        }
    }

    // -- payload-with-audio framing ---------------------------------------

    @Test
    fun `a payload with audio round-trips`() {
        val audio = ByteArray(128) { (it * 5).toByte() }
        val (decoded, decodedAudio) = MeshSerialization.decodePayloadWithAudio(
            MeshSerialization.encodePayloadWithAudio(
                payload(MessageType.VOICE, text = null, audioFileName = "v.m4a"),
                audio,
            ),
        )
        assertEquals(MessageType.VOICE, decoded.type)
        assertArrayEquals(audio, decodedAudio)
    }

    @Test
    fun `a payload with no audio decodes to null audio, not an empty array`() {
        val (decoded, audio) = MeshSerialization.decodePayloadWithAudio(
            MeshSerialization.encodePayloadWithAudio(payload(MessageType.TEXT), ByteArray(0)),
        )
        assertEquals(MessageType.TEXT, decoded.type)
        assertNull(audio)
    }

    /** Same framing as [encodePayloadWithAudio] but with an arbitrary declared json length. */
    private fun framedWithDeclaredJsonLength(declared: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(declared)
            d.write(body)
        }
        return out.toByteArray()
    }

    @Test
    fun `a hostile json length in the audio framing is rejected`() {
        val body = MeshSerialization.payloadToJson(payload(MessageType.TEXT)).toByteArray()

        assertRejectedCleanly("jsonLen = -1") {
            MeshSerialization.decodePayloadWithAudio(framedWithDeclaredJsonLength(-1, body))
        }
        assertRejectedCleanly("jsonLen = Int.MAX_VALUE") {
            MeshSerialization.decodePayloadWithAudio(framedWithDeclaredJsonLength(Int.MAX_VALUE, body))
        }
        assertRejectedCleanly("jsonLen above the ceiling") {
            MeshSerialization.decodePayloadWithAudio(
                framedWithDeclaredJsonLength(MAX_WIRE_PAYLOAD_BYTES + 1, body),
            )
        }
        assertRejectedCleanly("jsonLen overruns the remaining bytes") {
            MeshSerialization.decodePayloadWithAudio(framedWithDeclaredJsonLength(body.size + 50, body))
        }
    }

    @Test
    fun `every truncation of a framed audio payload is rejected`() {
        val valid = MeshSerialization.encodePayloadWithAudio(
            payload(MessageType.VOICE, audioFileName = "v.m4a"),
            ByteArray(16),
        )
        // Truncating inside the JSON body yields invalid JSON; truncating inside
        // the length prefix yields EOF. Both must be refused.
        for (length in 0 until valid.size - 16) {
            assertRejectedCleanly("a framed payload truncated to $length bytes") {
                MeshSerialization.decodePayloadWithAudio(valid.copyOf(length))
            }
        }
    }

    @Test
    fun `a buffer larger than the wire ceiling is rejected by the audio framing too`() {
        assertRejectedCleanly("an oversized framed buffer") {
            MeshSerialization.decodePayloadWithAudio(ByteArray(MAX_WIRE_PAYLOAD_BYTES + 1))
        }
    }

    // -- decode-boundary validation ---------------------------------------

    /**
     * Was a characterisation test for finding 1; now asserts the constraint.
     *
     * MeshManager relays on `hopCount < maxHops`, so before this clamp a sender
     * chose its own relay radius: `maxHops = Int.MAX_VALUE` crossed the whole
     * connected cluster whatever the sending path's cap was, and a negative
     * `hopCount` bought extra hops on top of that.
     */
    @Test
    fun `hop fields are clamped to the legitimate range on decode`() {
        val decoded = MeshSerialization.decodeEnvelope(
            MeshSerialization.encodeEnvelope(
                MeshEnvelope("m", "c", false, null, ByteArray(2), -5, Int.MAX_VALUE),
            ),
        )
        assertEquals(0, decoded.hopCount)
        assertEquals(SENSOR_ALERT_MAX_HOPS, decoded.maxHops)
    }

    /**
     * The floor matters as much as the ceiling: `maxHops = 0` or a negative
     * would make `hopCount < maxHops` false immediately, so a peer could stop a
     * message dead at its first hop and no one downstream would ever see it.
     */
    @Test
    fun `a non positive maxHops is raised to one rather than silencing the message`() {
        for (declared in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val decoded = MeshSerialization.decodeEnvelope(
                MeshSerialization.encodeEnvelope(
                    MeshEnvelope("m", "c", false, null, ByteArray(2), 0, declared),
                ),
            )
            assertEquals("maxHops=$declared", 1, decoded.maxHops)
        }
    }

    /**
     * The other half of the clamp: everything this app actually sends must pass
     * through untouched. These are the real caps - the 5-hop chat default and
     * the 12-hop SOS/risk/sensor alert cap - with hop counts from a fresh
     * message and from one mid-relay.
     */
    @Test
    fun `hop values this app legitimately sends are unchanged by the clamp`() {
        for (maxHops in intArrayOf(1, 5, SENSOR_ALERT_MAX_HOPS)) {
            for (hopCount in intArrayOf(0, 3, maxHops)) {
                val decoded = MeshSerialization.decodeEnvelope(
                    MeshSerialization.encodeEnvelope(
                        MeshEnvelope("m", "c", false, null, ByteArray(2), hopCount, maxHops),
                    ),
                )
                assertEquals("hopCount $hopCount/$maxHops", hopCount, decoded.hopCount)
                assertEquals("maxHops $hopCount/$maxHops", maxHops, decoded.maxHops)
            }
        }
    }

    /**
     * Was a characterisation test for finding 2; now asserts the constraint.
     *
     * An off-globe coordinate is not a position, so it takes the path the format
     * already has for no position. The accuracy radius goes with it - a radius
     * around a discarded position describes nothing.
     */
    @Test
    fun `out of range sos coordinates decode as no fix`() {
        val decoded = MeshSerialization.sosBeaconFromJson(
            """{"beaconId":"b","sequence":1,"senderId":"d","senderName":"n","latitude":9999.0,
               |"longitude":-8888.0,"accuracyMeters":1.0,"batteryPercent":50,"isCharging":false,
               |"note":null,"startedAt":1,"sentAt":2}""".trimMargin(),
        )
        assertNull(decoded.latitude)
        assertNull(decoded.longitude)
        assertNull(decoded.accuracyMeters)
    }

    /**
     * This is what the range check is actually for. `hasFix` is what every
     * consumer branches on - CriticalAlertTrigger builds its `Location` from
     * the nullable pair, SosScreen decides whether to show a distance - so an
     * out-of-range beacon has to be indistinguishable from one sent before its
     * device had a GPS fix. Otherwise a responder reads a confident bearing and
     * distance to a place that does not exist.
     */
    @Test
    fun `a beacon with out of range coordinates renders as no fix, not a bogus distance`() {
        val hostile = listOf(
            """"latitude":91.0,"longitude":0.0""",
            """"latitude":-90.5,"longitude":0.0""",
            """"latitude":0.0,"longitude":181.0""",
            """"latitude":0.0,"longitude":-180.001""",
            // One valid half is still not a position.
            """"latitude":51.5,"longitude":99999.0""",
        )
        for (coords in hostile) {
            val decoded = MeshSerialization.sosBeaconFromJson(
                """{"beaconId":"b","sequence":1,"senderId":"d","senderName":"n",$coords,
                   |"accuracyMeters":5.0,"batteryPercent":50,"isCharging":false,
                   |"note":null,"startedAt":1,"sentAt":2}""".trimMargin(),
            )
            assertFalse("hasFix for $coords", decoded.hasFix)
            assertNull("latitude for $coords", decoded.latitude)
            assertNull("longitude for $coords", decoded.longitude)
        }
    }

    /** The edges of the globe are real places and must survive the range check. */
    @Test
    fun `coordinates at the edge of the valid range keep their fix`() {
        for (coords in listOf("""-90.0,"longitude":-180.0""", """90.0,"longitude":180.0""")) {
            val decoded = MeshSerialization.sosBeaconFromJson(
                """{"beaconId":"b","sequence":1,"senderId":"d","senderName":"n","latitude":$coords,
                   |"accuracyMeters":5.0,"batteryPercent":50,"isCharging":false,
                   |"note":null,"startedAt":1,"sentAt":2}""".trimMargin(),
            )
            assertTrue("hasFix for $coords", decoded.hasFix)
            assertEquals(5.0f, decoded.accuracyMeters!!, 0.0f)
        }
    }

    /**
     * Was a characterisation test for finding 3; now asserts the constraint.
     *
     * Constrained at the decode boundary rather than at each consumer: the
     * takeover used to guard this and SosScreen's battery row did not, so the
     * same beacon read differently on two surfaces.
     *
     * Out of range decodes to null rather than being clamped into range. A
     * clamped value is a claim - `0%` reads as a phone about to die, and a
     * responder triaging several beacons would act on it - whereas null is the
     * absence of one, and every surface already renders absence as "—" or
     * "Unknown".
     *
     * This covers only what arrives over the wire. A device's own unreadable
     * gauge is stopped at the source by [batteryPercentOrNull] and travels as an
     * explicit null, so the sentinel case below is reachable only from a pre-fix
     * build or a hostile peer.
     */
    @Test
    fun `sos battery percent out of range decodes as unknown`() {
        fun beaconWithBattery(percent: String) = MeshSerialization.sosBeaconFromJson(
            """{"beaconId":"b","sequence":1,"senderId":"d","senderName":"n","latitude":null,
               |"longitude":null,"accuracyMeters":null,"batteryPercent":$percent,"isCharging":false,
               |"note":null,"startedAt":1,"sentAt":2}""".trimMargin(),
        )
        assertNull(beaconWithBattery("9999").batteryPercent)
        assertNull(beaconWithBattery("-9999").batteryPercent)
        // A pre-fix build's "battery unavailable" sentinel. Current builds send
        // null instead - see `an unavailable battery reading does not reach the
        // UI as a number` below.
        assertNull(beaconWithBattery("${Int.MIN_VALUE}").batteryPercent)
        // A plausible reading is not touched.
        assertEquals(37, beaconWithBattery("37").batteryPercent)
        assertEquals(0, beaconWithBattery("0").batteryPercent)
        assertEquals(100, beaconWithBattery("100").batteryPercent)
    }

    /**
     * The local echo path, which no decode-boundary check can reach.
     *
     * `MeshManager.sendSosBeacon` emits the beacon object straight to this
     * device's own UI - Nearby never delivers our own payload back to us - so a
     * bad reading from `BatteryManager` never passes through
     * `sosBeaconFromJson`. It has to be stopped where it is produced.
     *
     * The assertion is that nothing renderable comes out: null, not a clamped
     * `0` that SosScreen would print as "0%" on an emergency screen.
     */
    @Test
    fun `an unavailable battery reading does not reach the UI as a number`() {
        // Integer.MIN_VALUE is what BATTERY_PROPERTY_CAPACITY returns when the
        // gauge is unavailable; the rest are simply not percentages.
        for (unreadable in intArrayOf(Int.MIN_VALUE, -1, 101, Int.MAX_VALUE)) {
            assertNull("raw=$unreadable", batteryPercentOrNull(unreadable))
        }
        // A real reading is passed through untouched, edges included.
        for (readable in intArrayOf(0, 1, 43, 99, 100)) {
            assertEquals("raw=$readable", readable, batteryPercentOrNull(readable))
        }
    }

    /**
     * "Unknown" has to survive the wire too, or a receiver would see the gap
     * closed by whatever `getInt` defaults to and print a number the sending
     * device never claimed.
     */
    @Test
    fun `a beacon with an unreadable battery keeps null across the wire`() {
        val decoded = MeshSerialization.decodeSosBeacon(
            MeshSerialization.encodeSosBeacon(beacon(batteryPercent = null)),
        )
        assertNull(decoded.batteryPercent)
        // Everything else still arrives, so absence is the battery field only.
        assertEquals("Priya", decoded.senderName)
        assertTrue(decoded.hasFix)
    }

    /**
     * CHARACTERISATION, NOT APPROVAL - finding 4 in the accompanying report.
     *
     * Bytes after the last field are ignored rather than rejected, so a peer can
     * pad any message up to the 32 KB ceiling and two different byte strings can
     * decode to the same envelope.
     */
    @Test
    fun `CHARACTERISATION - trailing bytes after an envelope are ignored`() {
        val valid = MeshSerialization.encodeEnvelope(
            MeshEnvelope("m", "c", false, null, ByteArray(2), 0, 5),
        )
        val padded = valid + ByteArray(500) { 0x41 }
        val decoded = MeshSerialization.decodeEnvelope(padded)
        assertEquals("m", decoded.messageId)
        assertEquals(2, decoded.payload.size)
    }
}
