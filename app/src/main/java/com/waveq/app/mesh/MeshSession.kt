package com.waveq.app.mesh

import android.app.Application
import android.content.Context
import com.waveq.app.alerts.MeshAlertDispatcher
import com.waveq.app.data.IncidentMeshSync
import com.waveq.app.location.LocationController
import com.waveq.app.prediction.RiskRepository
import com.waveq.app.sensor.SensorRepository
import com.waveq.app.settings.DisplayNameSettings
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEVICE_PREFS = "waveq_device_prefs"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_SENDER_NAME = "sender_name"
private const val PURGE_INTERVAL_MS = 60 * 60 * 1000L

/**
 * Process-wide mesh session: transport, relay manager, and channel storage.
 *
 * [MeshViewModel] and [SosBeaconService] both need to send/receive over the
 * same mesh - a Service can keep broadcasting SOS beacons with the screen off
 * long after the Activity/ViewModel is gone, but Nearby Connections only
 * tolerates one advertiser/discoverer per process. So both consumers route
 * through this single lazily-initialised instance instead of each owning
 * their own [NearbyTransport].
 */
object MeshSession {

    @Volatile private var initialized = false
    private lateinit var devicePrefs: android.content.SharedPreferences
    @Volatile private var appContext: Context? = null

    lateinit var channelRepository: ChannelRepository
        private set
    lateinit var messageStore: MessageStore
        private set
    lateinit var transport: NearbyTransport
        private set
    lateinit var meshManager: MeshManager
        private set
    lateinit var myDeviceId: String
        private set

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(application: Application) {
        if (initialized) return

        appContext = application.applicationContext
        // Before anything can send: the synchronous mirror is seeded here, so
        // the transport and the SOS service never read a default name.
        DisplayNameSettings.init(application)
        devicePrefs = application.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        myDeviceId = devicePrefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            devicePrefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

        channelRepository = ChannelRepository(application)
        // Needed here, not only in MeshViewModel: risk updates broadcast on the
        // City-Wide channel and must work before any UI has ever been created.
        channelRepository.ensureDefaultCityChannel()
        messageStore = MessageStore(application)
        val transport = NearbyTransport(application)
        this.transport = transport
        meshManager = MeshManager(
            transport = transport,
            channelRepository = channelRepository,
            messageStore = messageStore,
            myDeviceId = myDeviceId,
            cacheDir = application.cacheDir,
        )
        transport.onEnvelopeReceived = { endpointId, envelope -> meshManager.onEnvelopeReceived(endpointId, envelope) }
        transport.onPeerConnected = { endpointId -> meshManager.onPeerConnected(endpointId) }

        // Alert dispatch (siren / notification / full-screen takeover) is owned
        // here, on the process-scoped session, rather than by MeshViewModel -
        // otherwise incoming CRITICAL alerts are silently relayed but never
        // surfaced whenever no Activity is on screen. See MeshAlertDispatcher.
        MeshAlertDispatcher.start(application, meshManager, myDeviceId, sessionScope)

        // Operator confirmations arriving over the mesh update the local
        // incident store. Process-scoped for the same reason as alert dispatch:
        // a confirmation broadcast while this device's UI is closed must still
        // land.
        IncidentMeshSync.start(application, meshManager, sessionScope)

        // The water-level probe. Process-scoped for the same reason as alert
        // dispatch: a sensor crossing must escalate and propagate with no UI on
        // screen.
        SensorRepository.init(application, meshManager, myDeviceId, sessionScope)

        // Location state is process-scoped because the risk engine reads it on
        // every background tick, long before any UI exists. It owns the split
        // between the device's real GPS fix and whatever place the user has
        // chosen to view.
        LocationController.init(application)

        // The flash-flood risk engine is process-scoped for the same reason:
        // it refreshes hourly, propagates over the mesh and can escalate to the
        // siren, none of which may depend on an Activity being alive.
        RiskRepository.init(
            application = application,
            meshManager = meshManager,
            myDeviceId = myDeviceId,
            senderName = { senderName },
            scope = sessionScope,
        )

        // Purge expired store-and-forward rows on start, then once per hour for as long as the process lives.
        sessionScope.launch {
            while (true) {
                messageStore.purgeExpired()
                // Voice clips live in cacheDir and nothing deleted them - not
                // received ones, not the recorder's own outbound files.
                VoiceCache.purge(application)
                delay(PURGE_INTERVAL_MS)
            }
        }

        initialized = true
    }

    /**
     * The name stamped on every outgoing message and advertised to peers.
     *
     * Backed by [DisplayNameSettings] (DataStore, with a synchronous mirror) so
     * one value serves the UI, the transport and the SOS service. The
     * "Anonymous" fallback remains only as a last resort - the UI prompts for a
     * name before any screen that can send is reachable, so it should never be
     * what actually goes out.
     */
    var senderName: String
        get() = DisplayNameSettings.currentName()
            ?: devicePrefs.getString(KEY_SENDER_NAME, null)
            ?: "Anonymous"
        set(value) {
            val appContext = this.appContext ?: return
            DisplayNameSettings.setName(appContext, value)
        }
}
