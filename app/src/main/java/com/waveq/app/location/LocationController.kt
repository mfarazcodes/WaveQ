package com.waveq.app.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.LocationServices
import com.waveq.app.mesh.awaitCurrentLocation
import com.waveq.app.mesh.awaitLastLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private val Context.locationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "waveq_location_settings")

private val KEY_VIEWING_OVERRIDE = stringPreferencesKey("viewing_override")
private val KEY_RECENT_PLACES = stringPreferencesKey("recent_places")
private val KEY_SAVED_PLACES = stringPreferencesKey("saved_places")

/** Recents are a convenience, not a history - five is what fits on screen without scrolling. */
private const val MAX_RECENT_PLACES = 5

/**
 * Distance the device must move before its name is looked up again. Reverse
 * geocoding costs a network round trip and the answer does not change over a
 * couple of kilometres.
 */
private const val RENAME_THRESHOLD_METERS = 2_000f

/**
 * The single source of truth for "where is this device" and "what place is the
 * user looking at" - which are deliberately two different things.
 *
 * ## The separation, and why it is absolute
 *
 * [deviceLocation] is the real GPS fix. It is the only thing that may ever
 * drive a life-safety decision:
 *  - SOS beacons broadcast it and nothing else, so a person in an emergency can
 *    never transmit a position they are not at (that path does not even read
 *    this class - [com.waveq.app.mesh.SosBeaconService] goes straight to
 *    FusedLocationProvider, and must stay that way);
 *  - the siren and full-screen takeover fire from the risk computed for it;
 *  - the 25 km mesh adoption rule measures against it.
 *
 * [viewingOverride] is a display preference and nothing more. Setting it
 * changes which place the risk UI describes. It changes no alert behaviour, no
 * broadcast, and no adoption decision anywhere in the app.
 *
 * If a future change ever needs "the location", the question to ask first is
 * which of these two it means. Anything that could wake someone up at night, or
 * that another person will act on, means [deviceLocation].
 */
object LocationController {

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationLock = Mutex()

    private val _deviceLocation = MutableStateFlow<Location?>(null)
    /** The device's real GPS position. Never affected by a manual override. */
    val deviceLocation: StateFlow<Location?> = _deviceLocation

    private val _devicePlace = MutableStateFlow<PlaceRef?>(null)
    /** Reverse-geocoded name for [deviceLocation], for captions only. */
    val devicePlace: StateFlow<PlaceRef?> = _devicePlace

    private val _viewingOverride = MutableStateFlow<PlaceRef?>(null)
    /** The manually chosen place, or null meaning "follow the device". */
    val viewingOverride: StateFlow<PlaceRef?> = _viewingOverride

    private val _wasOverrideRestored = MutableStateFlow(false)
    /**
     * True when the current override was restored from a previous session
     * rather than chosen just now.
     *
     * An override that silently survives a restart is a trap: the user opens the
     * app expecting their own area and reads someone else's risk score as their
     * own. Rather than dropping the setting (which would lose a deliberate
     * choice), the banner says explicitly that this is left over from last time,
     * until the user next picks or resets a location.
     */
    val wasOverrideRestored: StateFlow<Boolean> = _wasOverrideRestored

    private val _recentPlaces = MutableStateFlow<List<PlaceRef>>(emptyList())
    val recentPlaces: StateFlow<List<PlaceRef>> = _recentPlaces

    private val _savedPlaces = MutableStateFlow<List<PlaceRef>>(emptyList())
    val savedPlaces: StateFlow<List<PlaceRef>> = _savedPlaces

    /** The place the risk UI is describing: the override if set, otherwise the device's own place. */
    val viewingPlace: StateFlow<PlaceRef?> by lazy {
        combine(_viewingOverride, _devicePlace) { override, device -> override ?: device }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** Whether the user is looking at somewhere other than where they are. */
    val isOverridden: StateFlow<Boolean> by lazy {
        _viewingOverride.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)
    }

    @Synchronized
    fun init(application: Application) {
        if (initialized) return
        initialized = true
        appContext = application.applicationContext

        scope.launch {
            val prefs = appContext.locationDataStore.data.first()
            _recentPlaces.value = PlaceRef.listFromJson(prefs[KEY_RECENT_PLACES])
            _savedPlaces.value = PlaceRef.listFromJson(prefs[KEY_SAVED_PLACES])
            prefs[KEY_VIEWING_OVERRIDE]
                ?.let { runCatching { PlaceRef.fromJson(JSONObject(it)) }.getOrNull() }
                ?.let { restored ->
                    _viewingOverride.value = restored
                    _wasOverrideRestored.value = true
                }
        }
    }

    // -- Device location ---------------------------------------------------

    /** Whether this device currently holds a location permission at all. */
    fun hasLocationPermission(): Boolean =
        initialized && (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            )

    /**
     * Refreshes the real GPS fix and returns it.
     *
     * Returns null without throwing when location permission has not been
     * granted - the caller degrades to "no assessment yet" rather than failing.
     *
     * Both providers are tried, in this order, because either can legitimately
     * come back empty: `lastLocation` is null on a device that has not had a fix
     * since boot (a fresh install, or an emulator), and an active request can
     * time out indoors. Falling back only one way was enough to leave
     * deviceLocation permanently null, which is what made "use current location"
     * appear to do nothing - the override cleared, but there was never a device
     * position to compute a risk score for.
     */
    suspend fun refreshDeviceLocation(): Location? = locationLock.withLock {
        if (!hasLocationPermission()) return@withLock null

        val client = LocationServices.getFusedLocationProviderClient(appContext)
        val location = client.awaitLastLocation()
            ?: client.awaitCurrentLocation()
            ?: return@withLock null

        val previous = _deviceLocation.value
        _deviceLocation.value = location

        val needsName = _devicePlace.value == null ||
            previous == null ||
            previous.distanceTo(location) > RENAME_THRESHOLD_METERS
        if (needsName) {
            _devicePlace.value = PlaceSearch.describeCoordinates(appContext, location.latitude, location.longitude)
        }
        location
    }

    // -- Viewing location --------------------------------------------------

    /** Switches the risk UI to [place]. Alerts, SOS and mesh adoption are unaffected. */
    fun setViewingLocation(place: PlaceRef) {
        _viewingOverride.value = place
        _wasOverrideRestored.value = false
        addRecent(place)
        persist(KEY_VIEWING_OVERRIDE, place.toJson().toString())
    }

    /**
     * Drops the override so the risk UI follows the device again, and acquires a
     * fix straight away rather than waiting for the next 15-minute tick.
     *
     * RiskRepository observes [viewingOverride] and recomputes when this changes,
     * so clearing it is what triggers the device assessment to be published -
     * but only if there is a device location to compute one from, hence the
     * immediate refresh here.
     */
    fun resetToCurrentLocation() {
        _viewingOverride.value = null
        _wasOverrideRestored.value = false
        scope.launch {
            appContext.locationDataStore.edit { it.remove(KEY_VIEWING_OVERRIDE) }
        }
        scope.launch { refreshDeviceLocation() }
    }

    /**
     * Called when a location permission has just been granted, so the first fix
     * is acquired immediately instead of on the next scheduled tick.
     */
    fun onPermissionGranted() {
        scope.launch { refreshDeviceLocation() }
    }

    // -- Recents and saved places -----------------------------------------

    private fun addRecent(place: PlaceRef) {
        val key = place.coordinateKey()
        val updated = (listOf(place.copy(label = null, addedAt = System.currentTimeMillis())) +
            _recentPlaces.value.filterNot { it.coordinateKey() == key })
            .take(MAX_RECENT_PLACES)
        _recentPlaces.value = updated
        persist(KEY_RECENT_PLACES, PlaceRef.listToJson(updated))
    }

    /** Pins [place] under a user label ("Home", "Parents", "Village"). Re-pinning replaces the label. */
    fun savePlace(place: PlaceRef, label: String) {
        val cleanLabel = label.trim().ifBlank { place.name }
        val key = place.coordinateKey()
        val updated = _savedPlaces.value.filterNot { it.coordinateKey() == key } +
            place.copy(label = cleanLabel, addedAt = System.currentTimeMillis())
        _savedPlaces.value = updated
        persist(KEY_SAVED_PLACES, PlaceRef.listToJson(updated))
    }

    fun removeSavedPlace(place: PlaceRef) {
        val key = place.coordinateKey()
        val updated = _savedPlaces.value.filterNot { it.coordinateKey() == key }
        _savedPlaces.value = updated
        persist(KEY_SAVED_PLACES, PlaceRef.listToJson(updated))
    }

    fun isSaved(place: PlaceRef): Boolean =
        _savedPlaces.value.any { it.coordinateKey() == place.coordinateKey() }

    private fun persist(key: Preferences.Key<String>, value: String) {
        scope.launch { appContext.locationDataStore.edit { prefs -> prefs[key] = value } }
    }
}
