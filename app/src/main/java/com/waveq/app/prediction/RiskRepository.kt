package com.waveq.app.prediction

import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import com.waveq.app.alerts.CriticalAlertTrigger
import com.waveq.app.location.LocationController
import com.waveq.app.location.PlaceRef
import com.waveq.app.mesh.MeshManager
import com.waveq.app.mesh.MeshPayload
import com.waveq.app.mesh.MeshSession
import com.waveq.app.mesh.MessageType
import com.waveq.app.mesh.RISK_UPDATE_MAX_HOPS
import com.waveq.app.mesh.SYSTEM_SENDER_NAME
import com.waveq.app.ui.components.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RiskRepository"

/**
 * How often the repository wakes up. Shorter than the refresh interval on
 * purpose: most ticks do no network work at all, they just re-age the existing
 * assessment so displayed confidence and "fetched N minutes ago" stay truthful
 * without the user having to reopen the screen.
 */
private const val TICK_INTERVAL_MS = 15 * 60 * 1000L

/**
 * Distance the device must move before its position counts as a different place
 * for risk purposes.
 *
 * 5 km is chosen against the terrain grid: the terrain cache cell is ~11 km, so
 * anything below this is unlikely to change the catchment class, and
 * re-fetching for every few hundred metres of movement would burn battery and
 * request quota recomputing an identical answer.
 */
private const val SIGNIFICANT_MOVE_KM = 5.0

/**
 * Maximum distance between where an assessment was computed and where this
 * device is, for it to be adopted as this device's own risk.
 *
 * 25 km is a compromise. Flash flood risk is genuinely catchment-local and
 * arguably this should be tighter, but the alternative for an offline device is
 * no assessment at all - and the meteorological drivers (a storm cell, a
 * saturated soil column) really do correlate over tens of kilometres. Beyond
 * this radius an update is still shown, but as clearly-labelled context about a
 * nearby area rather than as this device's own risk.
 */
const val RISK_ADOPT_MAX_DISTANCE_KM = 25.0

/**
 * Maximum age of the SOURCE DATA for an incoming assessment to be adopted.
 *
 * Measured from when the originating device fetched its inputs, not from when
 * the message arrived, so relaying cannot launder a stale assessment into a
 * fresh-looking one. Three hours is set against the longest terrain response
 * time that still leaves useful warning: past it, the forecast the assessment
 * was built on has largely been overtaken by events.
 */
const val RISK_ADOPT_MAX_AGE_MS = 3 * 60 * 60 * 1000L

/** How long another device counts as "currently sharing risk data" after we last heard from it. */
private const val SHARING_DEVICE_TTL_MS = 30 * 60 * 1000L

/** Cap on remembered nearby-area assessments, newest kept. */
private const val MAX_NEARBY_AREAS = 5

/** Bound on the remembered alert ids, so the siren dedup set cannot grow forever. */
private const val ALERTED_ID_CAPACITY = 100

/**
 * What the UI renders.
 *
 * Two assessments, and the distinction between them is the whole point:
 *
 * [device] is the risk where the phone physically is. It is computed from the
 * real GPS fix, it is what gets broadcast over the mesh, it is what mesh
 * adoption compares against, and it is the *only* one that can sound the siren.
 * It keeps being computed in the background no matter what the user is looking
 * at.
 *
 * [viewing] is what the risk screens display. Normally it is the very same
 * assessment as [device]; when the user has picked another place it is a
 * separate computation for that place, and it drives nothing but pixels.
 *
 * [nearbyAreas] are relayed assessments that failed the adoption rules, shown
 * as context so the user is never silently handed a distant device's score.
 */
data class RiskState(
    val device: RiskAssessment? = null,
    val viewing: RiskAssessment? = null,
    /** True when [viewing] is a manually chosen place rather than the device's own position. */
    val isViewingOverridden: Boolean = false,
    val nearbyAreas: List<RiskAssessment> = emptyList(),
    /** Distinct devices that have shared a risk assessment with us recently. */
    val sharingDeviceCount: Int = 0,
    val isRefreshing: Boolean = false,
    /** Set when there is nothing to show and the user needs to know why. */
    val statusMessage: String? = null,
)

/**
 * Owns the flash-flood risk assessment for the whole process.
 *
 * Three things happen here and nowhere else:
 *  1. **Refresh** - on start, when the device moves significantly, and hourly
 *     when networked. Computation always runs, network or not; the data source
 *     falls back to its Room cache and the resulting data age shows up as
 *     reduced confidence rather than as a silent failure.
 *  2. **Propagation** - every fresh local assessment is broadcast over the mesh
 *     on the unencrypted City-Wide channel, and pushed to each newly connected
 *     peer, so devices that could never fetch a forecast still get one.
 *  3. **Adoption** - incoming assessments become this device's own risk only
 *     when close enough and fresh enough, and are otherwise kept as nearby-area
 *     context.
 *
 * Process-scoped, like [com.waveq.app.alerts.MeshAlertDispatcher] and for the
 * same reason: a CRITICAL risk update must still sound the siren when no
 * Activity is alive.
 */
object RiskRepository {

    @Volatile private var started = false
    private lateinit var appContext: Context
    private lateinit var dataSource: OpenMeteoDataSource
    private lateinit var terrainProvider: TerrainProfileProvider
    private var meshManager: MeshManager? = null
    private var myDeviceId: String = ""
    private var senderNameProvider: () -> String = { "A nearby device" }

    private val _state = MutableStateFlow(RiskState())
    val state: StateFlow<RiskState> = _state.asStateFlow()

    /** Serialises recomputation so a manual refresh and the tick loop cannot overlap. */
    private val computeLock = Mutex()

    /** Where the device assessment was last computed, for the significant-move check. */
    private var lastDeviceComputeLocation: Location? = null

    /**
     * The assessment for the manually chosen place, when there is one.
     *
     * Held separately from [RiskState.device] rather than replacing it, because
     * the device assessment must keep running underneath: the user browsing
     * another district must not stop their own phone from warning them.
     */
    @Volatile private var overrideAssessment: RiskAssessment? = null

    /** Coordinates [overrideAssessment] was computed for, to detect a change of place. */
    @Volatile private var overrideAssessmentKey: String? = null

    /** sourceDeviceId -> when we last received an assessment from it. */
    private val sharingDevices = mutableMapOf<String, Long>()

    /** assessmentIds already escalated to the siren, so one warning fires exactly once. */
    private val alertedAssessmentIds = LinkedHashSet<String>()

    @Synchronized
    fun init(
        application: Application,
        meshManager: MeshManager,
        myDeviceId: String,
        senderName: () -> String,
        scope: CoroutineScope,
    ) {
        if (started) return
        started = true

        appContext = application.applicationContext
        dataSource = OpenMeteoDataSource(appContext)
        terrainProvider = TerrainProfileProvider(dataSource)
        this.meshManager = meshManager
        this.myDeviceId = myDeviceId
        this.senderNameProvider = senderName

        // Incoming risk updates from the mesh.
        scope.launch {
            meshManager.incomingMessages.collect { message ->
                if (message.type != MessageType.RISK_UPDATE) return@collect
                if (message.isMine) return@collect
                val json = message.riskJson ?: return@collect
                runCatching { onRiskUpdateReceived(json, message.hopCount) }
                    .onFailure { Log.w(TAG, "failed to handle risk update", it) }
            }
        }

        // Hand a newly connected neighbour our current assessment at once rather
        // than making them wait for the next scheduled broadcast. This is the
        // case the whole feature exists for: they may have no connectivity at
        // all and no way to compute anything themselves.
        scope.launch {
            meshManager.peerConnections.collect { endpointId ->
                // The device assessment, never the viewed one: what we share
                // describes where this phone is, not what its user is browsing.
                val own = _state.value.device?.takeIf { it.provenance is RiskProvenance.Local } ?: return@collect
                sendAssessment(own, toEndpointId = endpointId)
            }
        }

        // React to the user picking a place. Recomputing here rather than
        // waiting for the next tick is what makes the picker feel immediate -
        // and it only ever touches the viewing assessment.
        // A full tick, not just the viewing half. Clearing the override means
        // "show me where I actually am", and that answer may never have been
        // computed - if no fix had ever been acquired the device assessment is
        // still null, so recomputing only the viewing slot published nothing and
        // the screen sat on "no assessment yet".
        scope.launch {
            LocationController.viewingOverride.collect {
                runCatching { tick(force = false) }
                    .onFailure { Log.w(TAG, "viewing recompute failed", it) }
            }
        }

        // Refresh on app start, then on every tick.
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.w(TAG, "risk tick failed", it) }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** Forces an immediate refresh - used by the refresh control on the detail screen. */
    fun refreshNow(scope: CoroutineScope) {
        scope.launch { runCatching { tick(force = true) }.onFailure { Log.w(TAG, "manual refresh failed", it) } }
    }

    // -- Refresh -----------------------------------------------------------

    private suspend fun tick(force: Boolean = false) = computeLock.withLock {
        pruneSharingDevices()
        _state.update { it.copy(isRefreshing = true) }
        try {
            refreshDeviceAssessment(force)
            refreshViewingAssessment(force)
        } finally {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Keeps [RiskState.device] current.
     *
     * This runs on every tick no matter which place the user happens to be
     * viewing. It is the assessment that broadcasts, that mesh adoption
     * compares against, and that can sound the siren - so a user browsing
     * another district must never cause it to stop.
     */
    private suspend fun refreshDeviceAssessment(force: Boolean) {
        val location = LocationController.refreshDeviceLocation()
        if (location == null) {
            _state.update { current ->
                current.copy(
                    // Re-age whatever we already have; its confidence keeps falling honestly.
                    device = current.device?.withRecomputedConfidence(),
                    sharingDeviceCount = sharingDevices.size,
                    statusMessage = if (current.device == null) {
                        "Waiting for a location fix to assess risk where you are."
                    } else {
                        null
                    },
                )
            }
            republish()
            return
        }

        val device = _state.value.device
        val movedKm = lastDeviceComputeLocation?.let {
            distanceKmBetween(it.latitude, it.longitude, location.latitude, location.longitude)
        }
        // Null age (no device assessment, or one built with no dated input) means
        // "as stale as possible", so it always triggers a recompute.
        val dataAgeMs = device?.dataAgeMs() ?: Long.MAX_VALUE
        val isLocallyComputed = device?.provenance is RiskProvenance.Local

        val shouldRecompute = force ||
            device == null ||
            // An adopted assessment is a stand-in until we can compute our own.
            !isLocallyComputed ||
            (movedKm != null && movedKm >= SIGNIFICANT_MOVE_KM) ||
            dataAgeMs >= FORECAST_REFRESH_INTERVAL_MS

        if (!shouldRecompute) {
            _state.update {
                it.copy(device = it.device?.withRecomputedConfidence(), sharingDeviceCount = sharingDevices.size)
            }
            republish()
            return
        }

        recomputeDevice(location, force)
    }

    private suspend fun recomputeDevice(location: Location, force: Boolean) {
        val lat = location.latitude
        val lon = location.longitude

        val terrain = terrainProvider.profileFor(lat, lon)
        val forecast = dataSource.forecast(lat, lon, forceRefresh = force)
        val flood = dataSource.flood(lat, lon, forceRefresh = force)

        if (forecast == null && flood == null && terrain == null) {
            // Nothing at all, live or cached. Say so rather than showing a zero
            // score, which would read as "no risk" when it means "no data".
            _state.update {
                it.copy(
                    sharingDeviceCount = sharingDevices.size,
                    statusMessage = if (it.device == null) {
                        "No weather data for this area yet. Connect to the internet once to download it - " +
                            "after that it keeps working offline."
                    } else {
                        null
                    },
                )
            }
            republish()
            return
        }

        val assessment = RiskEngine.assess(
            RiskInputs(forecast = forecast, flood = flood, terrain = terrain, latitude = lat, longitude = lon),
        )
        lastDeviceComputeLocation = location

        // A locally computed assessment replaces the current one unless what we
        // hold is a RELAYED assessment built on fresher source data than ours.
        // Our own data describes exactly where we are, so it wins ties - but a
        // neighbour who fetched twenty minutes ago genuinely knows more than our
        // hour-old cache, and silently overwriting them with staler numbers
        // would be the same mistake the adoption rules exist to prevent, just in
        // the other direction.
        _state.update { current ->
            val existing = current.device
            val existingFetchedAt = existing?.dataFetchedAt
            val keepRelayed = existing != null &&
                existing.provenance is RiskProvenance.Relayed &&
                existingFetchedAt != null &&
                // A dated relayed assessment also beats a local one built with
                // no dated input at all (terrain only).
                (assessment.dataFetchedAt == null || existingFetchedAt > assessment.dataFetchedAt)
            current.copy(
                device = if (keepRelayed) existing else assessment,
                sharingDeviceCount = sharingDevices.size,
                statusMessage = null,
            )
        }
        republish()

        // Only share something built on real data - broadcasting an assessment
        // whose inputs were all unavailable would spread an empty score dressed
        // up as a measurement.
        if (forecast != null) sendAssessment(assessment, toEndpointId = null)

        if (assessment.isCritical) escalateLocal(assessment)
    }

    /**
     * Keeps [RiskState.viewing] current when a manual override is set.
     *
     * Note what this function does NOT do: it never broadcasts, never escalates
     * to the siren, and never feeds mesh adoption. Viewing another place is a
     * display choice, so its assessment drives pixels and nothing else.
     */
    private suspend fun refreshViewingAssessment(force: Boolean) {
        val override = LocationController.viewingOverride.value
        if (override == null) {
            overrideAssessment = null
            overrideAssessmentKey = null
            republish()
            return
        }

        val key = override.coordinateKey()
        val existing = overrideAssessment
        val isStale = existing == null ||
            overrideAssessmentKey != key ||
            (existing.dataAgeMs() ?: Long.MAX_VALUE) >= FORECAST_REFRESH_INTERVAL_MS

        if (!force && !isStale) {
            overrideAssessment = existing?.withRecomputedConfidence()
            republish()
            return
        }

        recomputeViewing(override, force)
    }

    private suspend fun recomputeViewing(place: PlaceRef, force: Boolean) {
        val lat = place.latitude
        val lon = place.longitude

        // Terrain is cached permanently per ~11 km cell, so returning to a place
        // the user has looked at before costs no elevation requests at all.
        val terrain = terrainProvider.profileFor(lat, lon)
        val forecast = dataSource.forecast(lat, lon, forceRefresh = force)
        val flood = dataSource.flood(lat, lon, forceRefresh = force)

        if (forecast == null && flood == null && terrain == null) {
            overrideAssessment = null
            overrideAssessmentKey = null
            _state.update {
                it.copy(statusMessage = "No weather data for ${place.name} yet. Connect to the internet to download it.")
            }
            republish()
            return
        }

        overrideAssessment = RiskEngine.assess(
            RiskInputs(forecast = forecast, flood = flood, terrain = terrain, latitude = lat, longitude = lon),
        )
        overrideAssessmentKey = place.coordinateKey()
        _state.update { it.copy(statusMessage = null) }
        republish()
    }

    /**
     * Recomputes what the UI shows from the device assessment and the current
     * override. With no override the two are the same object; with one, the
     * viewing slot holds its own separate assessment.
     */
    private fun republish() {
        val override = LocationController.viewingOverride.value
        _state.update { current ->
            current.copy(
                isViewingOverridden = override != null,
                viewing = if (override == null) current.device else overrideAssessment,
            )
        }
    }

    // -- Mesh propagation --------------------------------------------------

    /**
     * Broadcasts (or unicasts) an assessment on the City-Wide channel.
     *
     * Always unencrypted and always on City-Wide, exactly like a flood alert:
     * every device has to be able to read a risk update whether or not it shares
     * a passphrase with us, so this is never routed through a private channel.
     * [RISK_UPDATE_MAX_HOPS] carries it 12 hops, and MessageStore's
     * store-and-forward carries it to devices met later.
     */
    private fun sendAssessment(assessment: RiskAssessment, toEndpointId: String?) {
        val mesh = meshManager ?: return
        val channelId = MeshSession.channelRepository.cityChannelId()
        val name = senderNameProvider()
        val payload = MeshPayload(
            senderId = myDeviceId,
            // NOT the user's display name: this is generated by the model on a
            // timer, and stamping a person's name on it made it read as
            // something they said. The human-readable device name still travels
            // inside riskJson for provenance.
            senderName = SYSTEM_SENDER_NAME,
            type = MessageType.RISK_UPDATE,
            text = assessment.headline,
            audioFileName = null,
            timestamp = System.currentTimeMillis(),
            severity = assessment.severity.name,
            riskJson = RiskSerialization.toJson(assessment, myDeviceId, name),
        )
        if (toEndpointId != null) {
            mesh.sendMessageTo(toEndpointId, channelId, payload, maxHops = RISK_UPDATE_MAX_HOPS)
        } else {
            mesh.sendMessage(channelId, payload, maxHops = RISK_UPDATE_MAX_HOPS)
        }
    }

    // -- Receiving ---------------------------------------------------------

    /**
     * Handles a risk update from another device.
     *
     * The rules, in order:
     *  - confidence is recomputed from the ORIGINAL fetch timestamp (done in
     *    [RiskSerialization.fromJson]), so relaying never refreshes it;
     *  - adopt as our own only if within [RISK_ADOPT_MAX_DISTANCE_KM] and the
     *    source data is under [RISK_ADOPT_MAX_AGE_MS] old;
     *  - if we already hold an equally fresh or fresher assessment, keep ours
     *    and ignore this one;
     *  - anything else is kept as nearby-area context, never as our own risk;
     *  - the siren fires only for an update that was actually adopted, so a
     *    stale or distant CRITICAL never wakes the phone.
     */
    private suspend fun onRiskUpdateReceived(json: String, hopCount: Int) {
        // Real GPS, never the viewing override: proximity here decides whether a
        // siren goes off, so it has to be measured from where the phone is.
        val myLocation = LocationController.deviceLocation.value ?: LocationController.refreshDeviceLocation()
        val received = RiskSerialization.fromJson(json, hops = hopCount, distanceKm = null) ?: return

        val distanceKm = myLocation?.let {
            distanceKmBetween(
                it.latitude,
                it.longitude,
                received.assessment.latitude,
                received.assessment.longitude,
            )
        }
        val incoming = received.assessment.let { assessment ->
            val provenance = assessment.provenance
            if (provenance is RiskProvenance.Relayed) {
                assessment.copy(provenance = provenance.copy(distanceKm = distanceKm))
            } else {
                assessment
            }
        }

        sharingDevices[received.sourceDeviceId] = System.currentTimeMillis()

        // With no location fix we cannot verify the 25 km rule, so we do not
        // adopt - an unverifiable distance is treated as failing the rule, not
        // as passing it.
        val isCloseEnough = distanceKm != null && distanceKm <= RISK_ADOPT_MAX_DISTANCE_KM
        // An undated assessment cannot be shown to be fresh, so it fails this
        // rule rather than passing it.
        val isFreshEnough = (incoming.dataAgeMs() ?: Long.MAX_VALUE) <= RISK_ADOPT_MAX_AGE_MS
        val device = _state.value.device
        // Ours wins ties: an equally fresh local assessment describes exactly
        // where we are, which a relayed one only approximates. But a dated
        // incoming one beats a local assessment that has no dated input at all.
        val incomingFetchedAt = incoming.dataFetchedAt
        val isNewerThanOurs = incomingFetchedAt != null &&
            (device?.dataFetchedAt == null || incomingFetchedAt > device.dataFetchedAt)

        if (isCloseEnough && isFreshEnough && isNewerThanOurs) {
            _state.update {
                it.copy(device = incoming, sharingDeviceCount = sharingDevices.size, statusMessage = null)
            }
            republish()
            if (incoming.isCritical) escalateRelayed(incoming, received.sourceDeviceName, distanceKm)
        } else {
            _state.update { current ->
                val others = current.nearbyAreas.filterNot { existing ->
                    (existing.provenance as? RiskProvenance.Relayed)?.sourceDeviceId == received.sourceDeviceId
                }
                current.copy(
                    nearbyAreas = (listOf(incoming) + others).take(MAX_NEARBY_AREAS),
                    sharingDeviceCount = sharingDevices.size,
                )
            }
        }
    }

    // -- Escalation --------------------------------------------------------

    /** Fires the existing local-risk hook: siren, notification and full-screen takeover. */
    private fun escalateLocal(assessment: RiskAssessment) {
        if (!claimFirstAlert(assessment.assessmentId)) return
        CriticalAlertTrigger.onLocalRiskCritical(
            appContext,
            description = "${assessment.headline} (risk score ${assessment.score}/100)",
            location = assessment.toLocation(),
        )
    }

    /** Same alert path, but honest that the assessment came from another device. */
    private fun escalateRelayed(assessment: RiskAssessment, sourceName: String, distanceKm: Double?) {
        if (!claimFirstAlert(assessment.assessmentId)) return
        CriticalAlertTrigger.onRelayedRiskCritical(
            appContext,
            sourceName = sourceName,
            description = buildString {
                append(assessment.headline)
                append(" (risk score ${assessment.score}/100")
                if (distanceKm != null) append(", computed %.0f km away".format(distanceKm))
                append(")")
            },
            // Only reached for an adopted assessment, which must have had a
            // dated input to pass the freshness rule; computedAt is a safe floor.
            dataFetchedAt = assessment.dataFetchedAt ?: assessment.computedAt,
            location = assessment.toLocation(),
        )
    }

    private fun RiskAssessment.toLocation(): Location = Location("risk-engine").apply {
        latitude = this@toLocation.latitude
        longitude = this@toLocation.longitude
    }

    @Synchronized
    private fun claimFirstAlert(assessmentId: String): Boolean {
        if (!alertedAssessmentIds.add(assessmentId)) return false
        if (alertedAssessmentIds.size > ALERTED_ID_CAPACITY) {
            val iterator = alertedAssessmentIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    private fun pruneSharingDevices() {
        val cutoff = System.currentTimeMillis() - SHARING_DEVICE_TTL_MS
        sharingDevices.entries.removeAll { it.value < cutoff }
    }

    /**
     * Severity where the device actually is - the alert-relevant one, not
     * whatever place the user happens to be viewing.
     */
    fun currentSeverity(): Severity = _state.value.device?.severity ?: Severity.LOW
}
