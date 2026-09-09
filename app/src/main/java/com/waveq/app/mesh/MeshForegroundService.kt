package com.waveq.app.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.waveq.app.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "MeshForegroundService"
private const val CHANNEL_ID = "mesh_active_channel"
private const val NOTIFICATION_ID = 4300

/** Discovery is suspended below this level; advertising continues so the device stays reachable. */
private const val LOW_BATTERY_PERCENT = 20

/**
 * Keeps the mesh alive with no Activity on screen.
 *
 * ## Why this exists
 *
 * Alert dispatch used to live in MeshViewModel's `viewModelScope`. That scope is
 * cleared the moment the Activity is finished - swipe the app away and the
 * collectors stop, while MeshManager and NearbyTransport keep relaying. An
 * incoming CRITICAL flood alert or SOS beacon was still forwarded to other
 * devices and still written to the store, but never reached SirenPlayer or
 * CriticalAlertActivity: the alert stack was wired but unreachable in precisely
 * the situation it exists for - phone in a pocket, app not open.
 *
 * Moving the collectors to a process-scoped coroutine ([MeshAlertDispatcher])
 * fixed the Activity-death case but not process death, because nothing was
 * keeping the process alive. This service is that missing piece: while it runs,
 * the process lives, the transport advertises and discovers, and the dispatcher's
 * collectors stay subscribed.
 *
 * ## Its cost, made visible
 *
 * A radio held open and a process kept alive are real costs, so they are
 * user-controlled rather than implicit: the service starts only when the user
 * enables the mesh, stops completely when they disable it, shows a persistent
 * low-priority notification for as long as it runs, and throttles discovery
 * below [LOW_BATTERY_PERCENT] percent battery.
 */
class MeshForegroundService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "mesh service coroutine failed", t) },
    )

    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        MeshSession.init(application)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested by user")
            MeshSession.transport.stop()
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Permissions can be revoked while the service is running; starting the
        // transport without them would advertise nothing and report success.
        if (!PermissionUtils.hasAllMeshPermissions(this)) {
            Log.w(TAG, "missing nearby permissions - not starting")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(buildNotification(peers = 0, statusLabel = "Starting…"))
        isRunning = true

        MeshSession.transport.start(displayName = MeshSession.senderName)
        registerBatteryReceiver()
        applyBatteryPolicy(currentBatteryPercent())

        // Keep the notification honest: it reports the transport's real state,
        // not just "running".
        scope.launch {
            MeshSession.transport.status.collectLatest { status ->
                notify(buildNotification(status.peerCount, status.label))
            }
        }

        // Restarted by the system after process death: the user did enable the
        // mesh, and the notification makes the resumed state obvious, so
        // resuming is the right call here (unlike SosBeaconService, where a
        // restart would invent an emergency nobody triggered).
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- Battery -----------------------------------------------------------

    private fun currentBatteryPercent(): Int {
        val manager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return 100
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // getIntProperty returns Integer.MIN_VALUE when unavailable.
        return if (percent in 0..100) percent else 100
    }

    private fun applyBatteryPolicy(percent: Int) {
        val isCharging = (getSystemService(BATTERY_SERVICE) as? BatteryManager)?.isCharging == true
        MeshSession.transport.setLowPowerMode(!isCharging && percent < LOW_BATTERY_PERCENT)
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                applyBatteryPolicy(currentBatteryPercent())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryReceiver = receiver
    }

    // -- Notification ------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mesh network", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the offline mesh is running in the background"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(peers: Int, statusLabel: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(
                when (peers) {
                    0 -> "WaveQ mesh active - no peers"
                    1 -> "WaveQ mesh active - 1 peer"
                    else -> "WaveQ mesh active - $peers peers"
                },
            )
            .setContentText(statusLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "Turn off",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun startForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_STOP = "com.waveq.app.action.MESH_STOP"

        /** True while the service is up. The mesh on/off switch reads this. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).setAction(ACTION_STOP)
            // startService, not startForegroundService: this asks a running
            // service to shut down, and must not create one if none exists.
            runCatching { context.startService(intent) }
        }
    }
}
