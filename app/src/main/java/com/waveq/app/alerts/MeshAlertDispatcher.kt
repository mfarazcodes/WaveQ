package com.waveq.app.alerts

import android.content.Context
import android.util.Log
import com.waveq.app.auth.SessionManager
import com.waveq.app.auth.UserRole
import com.waveq.app.mesh.ChannelCrypto
import com.waveq.app.mesh.MESSAGE_STORE_TTL_MS
import com.waveq.app.mesh.MeshManager
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.SOS_STORE_TTL_MS
import com.waveq.app.sensor.SensorAlertPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ALERTED_BEACON_CAPACITY = 200

/**
 * Shared tag for every decision point on the alert path.
 */
const val ALERT_PATH_TAG = "AlertPath"

/**
 * Process-scoped bridge from mesh traffic to the alert stack (siren,
 * notification, full-screen takeover).
 */
object MeshAlertDispatcher {

    @Volatile private var started = false

    @Volatile
    private var alertedBeaconIds = AlertDedupCache(ALERTED_BEACON_CAPACITY, SOS_STORE_TTL_MS)

    @Volatile
    private var alertedMessageIds = AlertDedupCache(ALERTED_BEACON_CAPACITY, MESSAGE_STORE_TTL_MS)

    @Volatile
    private var alertedSensorEvents = AlertDedupCache(ALERTED_BEACON_CAPACITY, MESSAGE_STORE_TTL_MS)

    private fun claimFirstSighting(beaconId: String): Boolean = alertedBeaconIds.claim(beaconId)

    private fun claimFirstMessage(messageId: String): Boolean = alertedMessageIds.claim(messageId)

    private fun claimFirstSensorEvent(eventId: String): Boolean = alertedSensorEvents.claim(eventId)

    @Synchronized
    fun start(context: Context, meshManager: MeshManager, myDeviceId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        alertedBeaconIds = AlertDedupCache(
            capacity = ALERTED_BEACON_CAPACITY,
            ttlMs = SOS_STORE_TTL_MS,
            log = SharedPrefsClaimLog(appContext, "sos_beacon_ids"),
        )
        alertedMessageIds = AlertDedupCache(
            capacity = ALERTED_BEACON_CAPACITY,
            ttlMs = MESSAGE_STORE_TTL_MS,
            log = SharedPrefsClaimLog(appContext, "message_ids"),
        )
        alertedSensorEvents = AlertDedupCache(
            capacity = ALERTED_BEACON_CAPACITY,
            ttlMs = MESSAGE_STORE_TTL_MS,
            log = SharedPrefsClaimLog(appContext, "sensor_event_ids"),
        )

        // 1. Inbound Authoritative Flood Alerts
        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.FLOOD_ALERT) return@collect
                if (!claimFirstMessage(message.messageId)) {
                    Log.i(ALERT_PATH_TAG, "flood alert ${message.messageId} already alerted - dropping duplicate")
                    return@collect
                }

                val freshness = freshnessOf(message.timestamp, ESCALATION_FRESHNESS_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "flood alert received: id=${message.messageId} severity=${message.severity} " +
                            "isMine=${message.isMine} from=${message.senderName} ${freshness.describe}",
                )

                // Cryptographic Authority Gate:
                // Only cryptographically signed operator/admin alerts can fire full-screen sirens.
                val isAuthenticOperator = if (message.isMine) {
                    true
                } else {
                    val signature = message.signature
                    val trustedPublicKey = SessionManager.getOperatorPublicKey()
                    if (signature != null && trustedPublicKey != null) {
                        val signedBytes = "${message.severity}:${message.text}:${message.timestamp}".toByteArray(Charsets.UTF_8)
                        ChannelCrypto.verifyAuthority(signedBytes, signature, trustedPublicKey)
                    } else {
                        false
                    }
                }

                if (!isAuthenticOperator) {
                    Log.w(
                        ALERT_PATH_TAG,
                        "flood alert ${message.messageId} missing or failed operator signature - full siren/takeover suppressed",
                    )
                }

                val allowEscalation = freshness.shouldEscalate && isAuthenticOperator

                CriticalAlertTrigger.onFloodAlertReceived(
                    appContext,
                    message,
                    escalationAllowed = allowEscalation,
                )
            }
        }

        // 2. Water-Level Sensor Alerts
        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.SENSOR_ALERT) return@collect
                if (message.isMine) return@collect
                if (!claimFirstMessage(message.messageId)) return@collect

                val sensor = SensorAlertPayload.fromJson(message.sensorJson)
                if (sensor == null) {
                    Log.w(ALERT_PATH_TAG, "sensor alert ${message.messageId} had no usable payload - dropped")
                    return@collect
                }
                if (sensor.bridgeDeviceId == myDeviceId) return@collect
                if (!claimFirstSensorEvent(sensor.eventId)) {
                    Log.i(
                        ALERT_PATH_TAG,
                        "sensor event ${sensor.eventId} already alerted - collapsing duplicate " +
                                "broadcast from a second bridge",
                    )
                    return@collect
                }

                val freshness = freshnessOf(sensor.detectedAtMs, ESCALATION_FRESHNESS_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "sensor alert received: event=${sensor.eventId} sensor=${sensor.sensorHost} " +
                            "bridge=${sensor.bridgeDeviceName} percent=${sensor.percent} ${freshness.describe}",
                )

                CriticalAlertTrigger.onRelayedSensorAlert(
                    context = appContext,
                    sensorHost = sensor.sensorHost,
                    bridgeDeviceName = sensor.bridgeDeviceName,
                    detectedAtMs = sensor.detectedAtMs,
                    escalationAllowed = freshness.shouldEscalate,
                )
            }
        }

        // 3. Citizen & Responder SOS Beacons
        scope.launch {
            meshManager.incomingSosBeacons.collect { beacon ->
                if (beacon.senderId == myDeviceId) {
                    Log.i(ALERT_PATH_TAG, "sos beacon ${beacon.beaconId} is our own - suppressed")
                    return@collect
                }

                // Role Guard: Citizen SOS alerts ONLY notify Operators and Admins.
                // Civilian nodes act purely as relays and stay silent.
                val myRole = SessionManager.currentRole ?: UserRole.CITIZEN
                if (myRole != UserRole.OPERATOR && myRole != UserRole.ADMIN) {
                    Log.i(ALERT_PATH_TAG, "sos beacon ${beacon.beaconId} silenced - local device is CITIZEN")
                    return@collect
                }

                if (!claimFirstSighting(beacon.beaconId)) return@collect

                // Anti-Spam Gate: Unsigned beacons must pass PoW validation
                if (beacon.signature == null) {
                    val nonce = beacon.powNonce
                    if (nonce == null || !ChannelCrypto.verifySosProofOfWork(beacon.senderId, beacon.sentAt, nonce)) {
                        Log.w(ALERT_PATH_TAG, "sos beacon ${beacon.beaconId} failed PoW verification - dropped")
                        return@collect
                    }
                }

                val freshness = freshnessOf(beacon.sentAt, SOS_STORE_TTL_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "sos beacon received by responder: id=${beacon.beaconId} from=${beacon.senderName} " +
                            freshness.describe,
                )

                CriticalAlertTrigger.onSosBeaconReceived(
                    appContext,
                    beacon,
                    escalationAllowed = freshness.shouldEscalate,
                )
            }
        }
    }
}