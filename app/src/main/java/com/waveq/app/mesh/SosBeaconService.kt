package com.waveq.app.mesh

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SosBeaconService"
private const val REPEAT_INTERVAL_MS = 30_000L

/**
 * Foreground service that keeps an SOS beacon repeating every 30s - including
 * with the screen off - until [ACTION_CANCEL] is received. Broadcasting goes
 * through the shared [MeshSession] so it works whether or not the app's UI
 * (and its [MeshViewModel]) is currently alive.
 */
class SosBeaconService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var beaconId: String = ""
    private var sequence: Int = 0
    private var startedAt: Long = 0L
    private var note: String? = null
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        MeshSession.init(application)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        SosNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us on its own, not that the
        // user asked for an SOS. Previously this fell through and started a
        // brand-new session with a fresh beaconId and sequence 0 - a phantom
        // emergency nobody triggered, broadcasting live GPS to strangers and
        // firing sirens on every device in range. Stop cleanly instead.
        //
        // TODO(design-decision-4): the cost of this is that an SOS killed by
        // the OS stays dead until the user re-arms it. Surviving that properly
        // means persisting and deliberately restoring the active session with
        // an explicit "SOS resumed" state - an open decision, see AUDIT.md
        // "Needs a decision from you" item 4.
        if (intent == null) {
            Log.w(TAG, "restarted with a null intent - stopping rather than resuming a phantom SOS")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL) {
            // Cancel the loop job synchronously here rather than waiting for
            // onDestroy() (which only runs after stopSelf()'s async teardown) -
            // otherwise an in-flight sendBeacon() whose location fix resolves
            // in that window still fires and re-adds our own beacon as "active"
            // via MeshManager's self-echo, right after the UI already cleared it.
            loopJob?.cancel()
            loopJob = null
            stopSelf()
            return START_NOT_STICKY
        }

        if (loopJob == null) {
            beaconId = UUID.randomUUID().toString()
            sequence = 0
            startedAt = System.currentTimeMillis()
            note = intent.getStringExtra(EXTRA_NOTE)?.takeIf { it.isNotBlank() }
            // A new SOS session must not inherit the previous one's delivery state.
            lastReachedPeerCount = 0
            lastReachedAt = 0L

            // Started before the notification is built (both GMS calls are
            // async and return immediately) so the very first notification
            // reports the real transport state rather than a stale "off".
            // The mesh may never have been enabled - the user may not have
            // opened Mesh Channels at all - in which case this stays false and
            // the notification says so instead of claiming to broadcast.
            if (PermissionUtils.hasAllMeshPermissions(this)) {
                MeshSession.transport.start(displayName = MeshSession.senderName)
            }

            val notification = SosNotifications.activeSosNotification(
                context = this,
                elapsedSeconds = 0,
                transportStarted = MeshSession.transport.isStarted,
                reachedPeers = 0,
                lastReachedPeerCount = 0,
                lastReachedAt = 0L,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, SOS_ACTIVE_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(SOS_ACTIVE_NOTIFICATION_ID, notification)
            }

            isActive = true
            loopJob = scope.launch { runLoop() }
        }
        // NOT sticky: see the null-intent guard above. An automatic restart
        // here cannot know what the user actually asked for.
        return START_NOT_STICKY
    }

    private suspend fun runLoop() {
        while (true) {
            sendBeacon()
            delay(REPEAT_INTERVAL_MS)
        }
    }

    private suspend fun sendBeacon() {
        sequence += 1
        val location = fusedLocationClient.awaitCurrentLocation()
        val (batteryPercent, isCharging) = readBattery()


        val beacon = SosBeacon(
            beaconId = beaconId,
            sequence = sequence,
            senderId = MeshSession.myDeviceId,
            senderName = MeshSession.senderName,
            // Null, never 0.0: a beacon without a fix says so rather than
            // broadcasting the origin as if it were the sender's position.
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = location?.accuracy,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            note = note,
            startedAt = startedAt,
            sentAt = System.currentTimeMillis(),
        )
        val reachedPeers = MeshSession.meshManager.sendSosBeacon(beacon)
        if (reachedPeers > 0) {
            lastReachedPeerCount = reachedPeers
            lastReachedAt = System.currentTimeMillis()
        }
        updateNotification(beacon, reachedPeers)
    }

    private fun readBattery(): Pair<Int?, Boolean> {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val raw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return batteryPercentOrNull(raw) to batteryManager.isCharging
    }

    private fun updateNotification(beacon: SosBeacon, reachedPeers: Int) {
        val elapsedSeconds = (beacon.sentAt - startedAt) / 1000
        val notification = SosNotifications.activeSosNotification(
            context = this,
            elapsedSeconds = elapsedSeconds,
            transportStarted = MeshSession.transport.isStarted,
            reachedPeers = reachedPeers,
            lastReachedPeerCount = lastReachedPeerCount,
            lastReachedAt = lastReachedAt,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(SOS_ACTIVE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.i(TAG, "SOS beacon stopped after ${sequence} broadcast(s)")
        isActive = false
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.waveq.app.action.SOS_START"
        const val ACTION_CANCEL = "com.waveq.app.action.SOS_CANCEL"
        const val EXTRA_NOTE = "note"

        /** True while this device's own SOS is broadcasting - critical alerts for others suppress while this is true. */
        var isActive: Boolean = false
            private set

        /**
         * Real delivery state, so the SOS UI never claims a broadcast that did
         * not happen. [lastReachedAt] is 0 while no beacon has yet reached any
         * peer - i.e. the SOS is running but nothing has heard it.
         */
        @Volatile
        var lastReachedPeerCount: Int = 0
            private set

        @Volatile
        var lastReachedAt: Long = 0L
            private set
    }
}

/**
 * A battery capacity reading, or null when the device could not supply one.
 *
 * `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` returns
 * `Integer.MIN_VALUE` when the property is unavailable - on an emulator, or on
 * hardware whose fuel gauge does not expose capacity. That sentinel used to be
 * copied straight into [SosBeacon.batteryPercent], and because
 * `MeshManager.sendSosBeacon` echoes the beacon object to this device's own UI
 * without a serialization round-trip, it bypassed the decode-boundary clamp
 * entirely and reached the active-SOS panel as `-2147483648%`.
 *
 * Clamping the sentinel into range would be worse, not better: `0%` on an SOS
 * screen reads as a phone about to die, and a responder triaging several
 * beacons would act on it. An unreadable gauge is not a low battery, so it is
 * reported as nothing at all.
 *
 * Top-level and pure so the sentinel handling is testable without a
 * `BatteryManager` or a running Service.
 */
fun batteryPercentOrNull(raw: Int): Int? = raw.takeIf { it in 0..100 }
