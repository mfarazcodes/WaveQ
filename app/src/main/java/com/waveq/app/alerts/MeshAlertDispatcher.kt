package com.waveq.app.alerts

import android.content.Context
import android.util.Log
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
 *
 * One tag from mesh receipt through to the siren, so the whole chain is
 * greppable on a device with no debugger attached:
 *
 *     adb logcat -s AlertPath
 */
const val ALERT_PATH_TAG = "AlertPath"

/**
 * Process-scoped bridge from mesh traffic to the alert stack (siren,
 * notification, full-screen takeover).
 *
 * This deliberately does NOT live in MeshViewModel. A ViewModel is cleared the
 * moment its Activity goes away, so while the collectors lived there an
 * incoming CRITICAL flood alert or SOS beacon was still relayed by the
 * process-wide [MeshManager] but never surfaced to the user whenever no UI was
 * on screen - precisely the situation the alert stack exists for. The
 * ViewModel now only mirrors mesh traffic into UI state.
 *
 * TODO(design-decision-3): this covers the case where the process is still
 * alive (the app was swiped away but SosBeaconService, or a recently destroyed
 * Activity, kept it up). Surviving full process death requires a foreground
 * service that owns the mesh, which is an open design decision - see AUDIT.md,
 * "Needs a decision from you" item 3. Until that is decided, alerts stop when
 * the process does.
 */
object MeshAlertDispatcher {

    @Volatile private var started = false

    /**
     * beaconIds already alerted on, so a repeating SOS fires the siren once per
     * device, not every 30s and not again after a restart.
     *
     * Replaced in [start] with a cache backed by a [SharedPrefsClaimLog]. The
     * memory-only instance here only covers the window before [start] runs.
     */
    @Volatile
    private var alertedBeaconIds = AlertDedupCache(ALERTED_BEACON_CAPACITY, SOS_STORE_TTL_MS)

    /**
     * messageIds already alerted on.
     *
     * SOS had this protection and flood alerts did not. In P2P_CLUSTER every
     * device is connected to every other, so the same alert commonly arrives
     * from several peers at once - and each copy fired the takeover again.
     */
    @Volatile
    private var alertedMessageIds = AlertDedupCache(ALERTED_BEACON_CAPACITY, MESSAGE_STORE_TTL_MS)

    /**
     * Sensor event ids already alerted on.
     *
     * Additional to [alertedMessageIds], not a replacement. Two devices that can
     * both reach the same probe each detect the crossing and each broadcast,
     * producing two distinct messageIds that messageId dedup cannot collapse -
     * so every phone in range would siren twice. Dedup on the event identity
     * carried in the payload collapses them to one.
     */
    @Volatile
    private var alertedSensorEvents = AlertDedupCache(ALERTED_BEACON_CAPACITY, MESSAGE_STORE_TTL_MS)

    private fun claimFirstSighting(beaconId: String): Boolean = alertedBeaconIds.claim(beaconId)

    private fun claimFirstMessage(messageId: String): Boolean = alertedMessageIds.claim(messageId)

    private fun claimFirstSensorEvent(eventId: String): Boolean = alertedSensorEvents.claim(eventId)

    /**
     * Starts the collectors on [scope], which must be process-scoped (owned by
     * MeshSession), never a viewModelScope. Idempotent.
     */
    @Synchronized
    fun start(context: Context, meshManager: MeshManager, myDeviceId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        // Rebuilt over persistent claim logs now that there is a Context. Each
        // TTL matches the store TTL for that traffic: past it nothing can be
        // replayed, so the claim has nothing left to protect.
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

        scope.launch {
            meshManager.incomingMessages.collect { message ->
                // Same shape as the SOS collector below: dedup first, then hand
                // to the one shared trigger. The trigger does its own type and
                // isMine filtering; anything else is a no-op.
                if (message.type != MessageType.FLOOD_ALERT) return@collect
                if (!claimFirstMessage(message.messageId)) {
                    Log.i(ALERT_PATH_TAG, "flood alert ${message.messageId} already alerted - dropping duplicate")
                    return@collect
                }
                // The alert's own timestamp, not the moment it arrived: a
                // store-and-forward replay reaches us long after it was sent,
                // and it is the sending time that says whether an alarm is still
                // an honest claim.
                val freshness = freshnessOf(message.timestamp, ESCALATION_FRESHNESS_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "flood alert received: id=${message.messageId} severity=${message.severity} " +
                        "isMine=${message.isMine} from=${message.senderName} ${freshness.describe}",
                )
                if (!freshness.shouldEscalate) {
                    Log.i(
                        ALERT_PATH_TAG,
                        "flood alert ${message.messageId} is older than the " +
                            "${ESCALATION_FRESHNESS_MS / 60_000}m escalation window - delivering " +
                            "silently: notification and alert list yes, siren and takeover no",
                    )
                }
                CriticalAlertTrigger.onFloodAlertReceived(
                    appContext,
                    message,
                    escalationAllowed = freshness.shouldEscalate,
                )
            }
        }

        // Water-level sensor alerts, relayed from a device that can reach a
        // probe. Same shape as the flood-alert branch, with one extra dedup
        // layer for the multi-bridge case.
        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.SENSOR_ALERT) return@collect
                // Our own broadcast, echoed back locally by sendMessage. The
                // detecting device already escalated directly.
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
                // detectedAtMs, not message.timestamp: the crossing is the
                // event, and a relayed alert carries the moment the probe saw
                // water rather than the moment some hop rebroadcast it.
                val freshness = freshnessOf(sensor.detectedAtMs, ESCALATION_FRESHNESS_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "sensor alert received: event=${sensor.eventId} sensor=${sensor.sensorHost} " +
                        "bridge=${sensor.bridgeDeviceName} percent=${sensor.percent} ${freshness.describe}",
                )
                if (!freshness.shouldEscalate) {
                    Log.i(
                        ALERT_PATH_TAG,
                        "sensor event ${sensor.eventId} is older than the " +
                            "${ESCALATION_FRESHNESS_MS / 60_000}m escalation window - delivering " +
                            "silently: notification and alert list yes, siren and takeover no",
                    )
                }
                CriticalAlertTrigger.onRelayedSensorAlert(
                    context = appContext,
                    sensorHost = sensor.sensorHost,
                    bridgeDeviceName = sensor.bridgeDeviceName,
                    detectedAtMs = sensor.detectedAtMs,
                    escalationAllowed = freshness.shouldEscalate,
                )
            }
        }

        scope.launch {
            meshManager.incomingSosBeacons.collect { beacon ->
                if (beacon.senderId == myDeviceId) {
                    Log.i(ALERT_PATH_TAG, "sos beacon ${beacon.beaconId} is our own - suppressed")
                    return@collect
                }
                if (!claimFirstSighting(beacon.beaconId)) return@collect
                // Deliberately the 6h SOS store TTL, not the 15m window the
                // broadcast alerts use. A live SOS rebroadcasts every 30
                // seconds, so a stale sentAt already means "this is a replay,
                // not a transmission" - and the persistent claim above means a
                // given beaconId escalates at most once on this device however
                // often it is replayed. What is left inside the TTL is a person
                // whose device stopped broadcasting and who has not been marked
                // safe: that still warrants waking someone.
                val freshness = freshnessOf(beacon.sentAt, SOS_STORE_TTL_MS)
                Log.i(
                    ALERT_PATH_TAG,
                    "sos beacon received: id=${beacon.beaconId} from=${beacon.senderName} " +
                        freshness.describe,
                )
                if (!freshness.shouldEscalate) {
                    Log.i(
                        ALERT_PATH_TAG,
                        "sos beacon ${beacon.beaconId} is past the SOS store TTL - delivering " +
                            "silently: notification and beacon list yes, siren and takeover no",
                    )
                }
                // One presenter for every alert type. This used to also post
                // SosNotifications.notifyNewSos, which produced a second
                // notification on a channel with its own alarm ringtone - two
                // sounds for one event, only one of which any dismissal stopped.
                CriticalAlertTrigger.onSosBeaconReceived(
                    appContext,
                    beacon,
                    escalationAllowed = freshness.shouldEscalate,
                )
            }
        }
    }
}
