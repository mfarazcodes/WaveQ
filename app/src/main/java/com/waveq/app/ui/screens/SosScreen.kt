package com.waveq.app.ui.screens

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.waveq.app.mesh.MeshViewModel
import com.waveq.app.mesh.PermissionUtils
import com.waveq.app.mesh.SosBeacon
import com.waveq.app.mesh.SosBeaconRecord
import com.waveq.app.mesh.SosBeaconService
import com.waveq.app.mesh.SosTriage
import com.waveq.app.mesh.awaitCurrentLocation
import com.waveq.app.ui.components.*
import org.osmdroid.util.GeoPoint
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.delay

private const val HOLD_DURATION_MS = 3000L
private const val HOLD_STEP_MS = 16L
private const val STALE_THRESHOLD_MS = 120_000L
private const val LOCATION_REFRESH_MS = 20_000L
private const val CLOCK_TICK_MS = 1000L

@Composable
fun SosScreen(viewModel: MeshViewModel, initialNote: String = "") {
    val context = LocalContext.current
    val sosBeaconsMap by viewModel.sosBeacons.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val meshRunning by viewModel.isRunning.collectAsState()
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var note by remember { mutableStateOf(initialNote) }
    var myLocation by remember { mutableStateOf<Location?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var activationRequestedAt by remember { mutableStateOf<Long?>(null) }
    var showLocationDenied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { SosTriage.init(context) }
    val pinnedIds by SosTriage.pinned.collectAsState()
    val dismissedIds by SosTriage.dismissed.collectAsState()

    val myRecord = sosBeaconsMap.values.filter { it.isMine }.maxByOrNull { it.beacon.sequence }
    // Pinned first, dismissed hidden. Both are this device's private view - see
    // SosTriage; nothing here is transmitted or affects the sender.
    val otherRecords = sosBeaconsMap.values
        .filter { !it.isMine }
        .filterNot { it.beacon.beaconId in dismissedIds }
        .sortedByDescending { it.beacon.beaconId in pinnedIds }
    val hiddenCount = sosBeaconsMap.values.count { !it.isMine && it.beacon.beaconId in dismissedIds }
    val sosActive = myRecord != null || activationRequestedAt != null

    // Location is the only permission an SOS cannot run without. Notifications
    // are requested alongside it but never block activation: the foreground
    // service posts its notice regardless, and treating a declined notification
    // prompt as a hard failure left the SOS button dead with no explanation.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val locationGranted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            PermissionUtils.hasRequiredSosPermissions(context)
        if (locationGranted) {
            showLocationDenied = false
            activationRequestedAt = System.currentTimeMillis()
            startSosService(context, note)
        } else {
            showLocationDenied = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }

    // While we're not broadcasting our own SOS, we still need a location fix
    // to show distance-to-others in the received beacons list.
    LaunchedEffect(sosActive) {
        if (sosActive) return@LaunchedEffect
        while (true) {
            if (PermissionUtils.hasRequiredSosPermissions(context)) {
                myLocation = fusedLocationClient.awaitCurrentLocation() ?: myLocation
            }
            delay(LOCATION_REFRESH_MS)
        }
    }

    val referenceLocation: Location? = myRecord?.beacon?.toLocationOrNull() ?: myLocation

    if (sosActive) {
        ActiveSosContent(
            myRecord = myRecord,
            activationRequestedAt = activationRequestedAt ?: myRecord!!.beacon.startedAt,
            now = now,
            peerCount = peerCount,
            meshRunning = meshRunning,
            otherRecords = otherRecords,
            referenceLocation = referenceLocation,
            pinnedIds = pinnedIds,
            hiddenCount = hiddenCount,
            onCancelRequested = { showCancelConfirm = true },
        )
    } else {
        IdleSosContent(
            note = note,
            onNoteChange = { note = it },
            otherRecords = otherRecords,
            referenceLocation = referenceLocation,
            now = now,
            peerCount = peerCount,
            meshRunning = meshRunning,
            pinnedIds = pinnedIds,
            hiddenCount = hiddenCount,
            onHoldComplete = {
                if (PermissionUtils.hasRequiredSosPermissions(context)) {
                    activationRequestedAt = System.currentTimeMillis()
                    startSosService(context, note)
                } else {
                    permissionLauncher.launch(PermissionUtils.requiredSosPermissions())
                }
            },
        )
    }

    if (showLocationDenied) {
        AlertDialog(
            onDismissRequest = { showLocationDenied = false },
            icon = {
                Icon(
                    Icons.Filled.LocationOff,
                    contentDescription = null,
                    tint = MaterialTheme.appExtraColors.severityCritical,
                )
            },
            title = { Text("Location permission required") },
            text = {
                Text(
                    "An SOS beacon broadcasts your position, so it cannot be activated without " +
                        "location access. Grant it in system settings, then hold the SOS button again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDenied = false
                    context.startActivity(appSettingsIntent(context))
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDenied = false }) { Text("Not now") }
            },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel SOS?") },
            text = { Text("This stops broadcasting your location to nearby devices.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startService(
                        Intent(context, SosBeaconService::class.java).setAction(SosBeaconService.ACTION_CANCEL),
                    )
                    activationRequestedAt = null
                    viewModel.clearMySosBeacon()
                    showCancelConfirm = false
                }) { Text("Cancel SOS", color = MaterialTheme.appExtraColors.severityCritical) }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirm = false }) { Text("Keep Broadcasting") } },
        )
    }
}

/** App-details settings page, so a permanently-denied location permission has a way back. */
private fun appSettingsIntent(context: Context): Intent =
    Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:${context.packageName}"),
    )

private fun startSosService(context: Context, note: String) {
    val intent = Intent(context, SosBeaconService::class.java).apply {
        action = SosBeaconService.ACTION_START
        putExtra(SosBeaconService.EXTRA_NOTE, note)
    }
    ContextCompat.startForegroundService(context, intent)
}

// ---------------------------------------------------------------------------
// Idle state
// ---------------------------------------------------------------------------

@Composable
private fun IdleSosContent(
    note: String,
    onNoteChange: (String) -> Unit,
    otherRecords: List<SosBeaconRecord>,
    referenceLocation: Location?,
    now: Long,
    peerCount: Int,
    meshRunning: Boolean,
    pinnedIds: Set<String>,
    hiddenCount: Int,
    onHoldComplete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.screenPadding),
    ) {
        // The button is the whole screen. Everything else is a caption around it.
        Spacer(Modifier.height(Dimens.heroSpacing))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HoldToActivateButton(onHoldComplete = onHoldComplete)
        }
        Spacer(Modifier.height(Dimens.sectionSpacing))
        MicroLabel(
            "Hold 3 seconds to broadcast",
            modifier = Modifier.fillMaxWidth(),
        )

        // Honest up-front state: whether anything could actually receive a
        // beacon right now, before the user commits to activating one.
        Spacer(Modifier.height(Dimens.cardSpacing))
        Text(
            when {
                !meshRunning -> "Mesh is off - an SOS would not reach anyone yet"
                peerCount == 0 -> "No devices in range right now"
                else -> "$peerCount device(s) in range"
            },
            style = AppTypography.bodySmall,
            color = if (meshRunning && peerCount > 0) {
                MaterialTheme.appExtraColors.severityLow
            } else {
                MaterialTheme.appExtraColors.severityHigh
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Dimens.heroSpacing))
        LabeledField(
            label = "Note (optional)",
            value = note,
            onValueChange = onNoteChange,
            placeholder = "e.g. trapped on second floor",
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        SosBeaconMap(otherRecords, referenceLocation)

        Spacer(Modifier.height(Dimens.sectionSpacing))
        ReceivedBeaconsSection(
            otherRecords, referenceLocation, now, pinnedIds, hiddenCount,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Where the received beacons are, and where you are.
 *
 * Beacons sent before their device had a GPS fix carry null coordinates and are
 * deliberately not plotted - putting them at 0,0 would show a cluster of
 * emergencies in the Gulf of Guinea. The count of those is stated instead.
 */
@Composable
private fun SosBeaconMap(records: List<SosBeaconRecord>, myLocation: Location?) {
    val context = LocalContext.current
    val criticalColor = MaterialTheme.appExtraColors.severityCritical
    val darkTiles = isSystemInDarkTheme()

    val located = remember(records) { records.filter { it.beacon.hasFix } }
    val withoutFix = records.size - located.size
    if (located.isEmpty() && myLocation == null) return

    val markers = remember(located, criticalColor) {
        located.map { record ->
            val beacon = record.beacon
            MapMarker(
                id = beacon.beaconId,
                latitude = beacon.latitude!!,
                longitude = beacon.longitude!!,
                title = "SOS - ${beacon.senderName}",
                snippet = beacon.note?.takeIf { it.isNotBlank() }
                    ?: beacon.batteryPercent?.let { "Battery $it%" }
                    ?: "Battery unknown",
                tint = criticalColor,
                // Tapping a beacon hands its coordinates to a maps app, which is
                // the only action that actually helps someone reach it.
                onClick = { launchWalkingNavigation(context, beacon.latitude, beacon.longitude) },
            )
        }
    }
    val center = located.firstOrNull()?.beacon?.let { GeoPoint(it.latitude!!, it.longitude!!) }
        ?: myLocation?.let { GeoPoint(it.latitude, it.longitude) }

    Column(Modifier.fillMaxWidth()) {
        SectionHeading("Beacon locations")
        Spacer(Modifier.height(Dimens.cardSpacing))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius)),
        ) {
            OsmMapView(
                markers = markers,
                modifier = Modifier.fillMaxSize(),
                center = center,
                zoom = 13.0,
                showMyLocation = true,
                hasLocationPermission = PermissionUtils.hasRequiredSosPermissions(context),
                darkTiles = darkTiles,
            )
        }
        if (withoutFix > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "$withoutFix beacon(s) were sent before their device had a location fix and " +
                    "cannot be placed on the map.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.appExtraColors.textTertiary,
            )
        }
    }
}

/**
 * Hold for [HOLD_DURATION_MS] to activate.
 *
 * The timer, the progress value and the release check all live in one gesture
 * coroutine, so a release is structurally able to win the race against the
 * timer. The previous version launched the countdown on `rememberCoroutineScope`
 * and cancelled it from a separate `tryAwaitRelease()`; two problems followed
 * from that split. A cancelled gesture (`tryAwaitRelease()` returning false -
 * which happens whenever a parent steals the pointer, and on some quick taps)
 * did not cancel the job at all, so the countdown ran to completion and fired
 * an SOS from what the user experienced as a single tap. And even on a clean
 * release, the countdown could already have called back before the cancel
 * landed.
 *
 * Activation is driven by elapsed time, never by the progress value reaching
 * 1f: progress is a rendering detail here, not the trigger.
 */
@Composable
private fun HoldToActivateButton(onHoldComplete: () -> Unit) {
    var holdProgress by remember { mutableFloatStateOf(0f) }
    // So a recomposition mid-hold cannot fire a stale callback.
    val currentOnHoldComplete by rememberUpdatedState(onHoldComplete)

    Box(
        modifier = Modifier
            .size(280.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startMs = System.currentTimeMillis()
                    var activated = false

                    try {
                        while (true) {
                            // Poll for pointer changes BEFORE re-checking the
                            // clock, so a release that arrived inside the last
                            // frame ends the hold instead of losing to the
                            // deadline by a millisecond.
                            val event = withTimeoutOrNull(HOLD_STEP_MS) { awaitPointerEvent() }
                            if (event != null) {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                // Lifted, cancelled, or the pointer was claimed
                                // by a parent (a scroll, say) - abandon the hold.
                                if (change == null || !change.pressed || change.isConsumed) break
                                change.consume()
                            }

                            val elapsed = System.currentTimeMillis() - startMs
                            holdProgress = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
                            if (elapsed >= HOLD_DURATION_MS) {
                                activated = true
                                break
                            }
                        }
                    } finally {
                        // Runs on cancellation too, so an interrupted hold never
                        // leaves a half-filled ring on screen.
                        if (!activated) holdProgress = 0f
                    }

                    // Outside the try: if the gesture was cancelled, the
                    // exception propagates from the loop and this is never
                    // reached, so a cancel can never activate.
                    if (activated) currentOnHoldComplete()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { holdProgress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 6.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(236.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Sos, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(10.dp))
                Text("SOS", style = AppTypography.displayMedium, color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Active state
// ---------------------------------------------------------------------------

@Composable
private fun ActiveSosContent(
    myRecord: SosBeaconRecord?,
    activationRequestedAt: Long,
    now: Long,
    peerCount: Int,
    meshRunning: Boolean,
    otherRecords: List<SosBeaconRecord>,
    referenceLocation: Location?,
    pinnedIds: Set<String>,
    hiddenCount: Int,
    onCancelRequested: () -> Unit,
) {
    val beacon = myRecord?.beacon
    val elapsedMs = (now - activationRequestedAt).coerceAtLeast(0)
    // Plain volatiles rather than Compose state: this whole composable is
    // already re-invoked every second by the `now` tick, which is what makes
    // them refresh.
    val lastReachedAt = SosBeaconService.lastReachedAt
    val lastReachedPeerCount = SosBeaconService.lastReachedPeerCount
    val hasEverReachedAnyone = lastReachedAt > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary)
            .padding(Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Filled.Sos, contentDescription = null, tint = Color.White,
            modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "SOS ACTIVE", style = AppTypography.headlineSmall, color = Color.White,
            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Text(
            when {
                !meshRunning -> "Mesh is off - nothing is being sent"
                peerCount > 0 -> "Broadcasting your location to nearby devices"
                else -> "Searching for nearby devices"
            },
            style = AppTypography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))

        // The beacon loop keeps running regardless, so the user has to be told
        // plainly when those beacons are reaching nobody.
        if (!meshRunning || peerCount == 0) {
            SosReachWarning(
                text = if (!meshRunning) {
                    "The mesh transport is not running, so your beacon is not leaving this device. " +
                        "Check nearby-devices and location permissions."
                } else if (hasEverReachedAnyone) {
                    "No devices in range right now. Last reached $lastReachedPeerCount device(s) " +
                        "${formatTimeAgo(now - lastReachedAt)}."
                } else {
                    "No devices in range yet - nothing has received your beacon so far. " +
                        "Keep the phone on and move toward others if you safely can."
                },
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(Dimens.cardPadding),
        ) {
            ActiveStatRow(Icons.Filled.AccessTime, "Elapsed", formatElapsed(elapsedMs))
            ActiveStatRow(
                Icons.Filled.LocationOn, "Coordinates",
                // A beacon with no fix says so. It is still being broadcast -
                // battery, note and identity are useful on their own - but it
                // must not look like a position has been sent.
                beacon?.let { b ->
                    if (b.latitude != null && b.longitude != null) {
                        formatCoords(b.latitude, b.longitude)
                    } else {
                        "Location not yet acquired"
                    }
                } ?: "Acquiring…",
            )
            ActiveStatRow(
                if (beacon?.isCharging == true) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                "Battery",
                // "—" for both no-beacon-yet and unreadable gauge: nothing is
                // known either way, and a number here would be invented.
                beacon?.let { b ->
                    b.batteryPercent?.let { "$it%${if (b.isCharging) " (charging)" else ""}" }
                } ?: "—",
            )
            ActiveStatRow(
                if (meshRunning) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                "Mesh",
                if (meshRunning) "Running" else "Not running",
            )
            ActiveStatRow(Icons.Filled.Groups, "Peers in range", peerCount.toString())
            // Reports reach, not attempts: a beacon that went nowhere is not a broadcast.
            ActiveStatRow(
                Icons.Filled.Bolt,
                "Last delivery",
                if (hasEverReachedAnyone) {
                    "$lastReachedPeerCount device(s), ${formatTimeAgo(now - lastReachedAt)}"
                } else {
                    "Not yet delivered"
                },
            )
        }

        Spacer(Modifier.height(Dimens.cardSpacing))
        Button(
            onClick = onCancelRequested,
            modifier = Modifier.fillMaxWidth().height(Dimens.primaryButtonHeight),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Cancel SOS", style = AppTypography.titleMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        if (otherRecords.isNotEmpty()) {
            Text(
                "Other active SOS nearby", style = AppTypography.titleMedium, color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
        ReceivedBeaconsSection(
            otherRecords, referenceLocation, now, pinnedIds, hiddenCount,
            modifier = Modifier.weight(1f),
            onLight = true,
        )
    }
}

/** Explicit "your SOS is not reaching anyone" banner, on the red active-SOS background. */
@Composable
private fun SosReachWarning(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius))
            .background(Color.White)
            .padding(Dimens.cardPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = BrandRed,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = AppTypography.bodySmall, color = OnLightChip, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActiveStatRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = AppTypography.bodyMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        Text(value, style = AppTypography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Received beacons
// ---------------------------------------------------------------------------

@Composable
private fun ReceivedBeaconsSection(
    records: List<SosBeaconRecord>,
    referenceLocation: Location?,
    now: Long,
    pinnedIds: Set<String>,
    hiddenCount: Int,
    modifier: Modifier = Modifier,
    onLight: Boolean = false,
) {
    val context = LocalContext.current
    val textColor = if (onLight) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (onLight) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant

    // Pinned beacons stay at the top regardless of distance - a responder needs
    // the one they committed to first, not the nearest.
    val sorted = remember(records, referenceLocation, pinnedIds) {
        records.sortedWith(
            compareByDescending<SosBeaconRecord> { it.beacon.beaconId in pinnedIds }
                .thenBy { distanceMeters(referenceLocation, it.beacon) ?: Float.MAX_VALUE },
        )
    }

    Column(modifier = modifier) {
        if (!onLight) {
            Text("Received SOS Beacons", style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
        if (hiddenCount > 0) {
            Text(
                "$hiddenCount dismissed on this device. They are still broadcasting and still " +
                    "visible to everyone else.",
                style = AppTypography.bodySmall,
                color = if (onLight) secondaryColor else MaterialTheme.appExtraColors.textTertiary,
            )
            Spacer(Modifier.height(Dimens.cardSpacing))
        }
        if (sorted.isEmpty()) {
            if (onLight) {
                Text("No other active SOS beacons nearby.", style = AppTypography.bodySmall, color = secondaryColor)
            } else {
                NoticeCard(
                    icon = Icons.Filled.Sensors,
                    title = "No SOS beacons received",
                    body = "Any emergency beacon broadcast by a nearby device will appear here.",
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                items(sorted, key = { it.beacon.beaconId }) { record ->
                    ReceivedBeaconRow(
                        record = record,
                        distance = distanceMeters(referenceLocation, record.beacon),
                        now = now,
                        onLight = onLight,
                        isPinned = record.beacon.beaconId in pinnedIds,
                        onTogglePin = {
                            SosTriage.setPinned(
                                context,
                                record.beacon.beaconId,
                                record.beacon.beaconId !in pinnedIds,
                            )
                        },
                        onDismiss = { SosTriage.setDismissed(context, record.beacon.beaconId, true) },
                        onOpenLocation = {
                            val lat = record.beacon.latitude
                            val lon = record.beacon.longitude
                            if (lat != null && lon != null) launchWalkingNavigation(context, lat, lon)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One received beacon.
 *
 * Leads with what a responder needs - who, how far, how long since their last
 * update, and how much battery they have left before the beacon stops. The
 * coordinates are a tappable link rather than text, because reading numbers off
 * a screen is not how anyone gets to a person.
 *
 * The actions are "Pin" and "Dismiss", not "I'm safe" / "I need help": this is
 * someone else's emergency, and the only useful decisions are whether you are
 * responding to it and whether it should stay in your list.
 */
@Composable
private fun ReceivedBeaconRow(
    record: SosBeaconRecord,
    distance: Float?,
    now: Long,
    onLight: Boolean,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLocation: () -> Unit,
) {
    val extra = MaterialTheme.appExtraColors
    val beacon = record.beacon
    val stale = now - record.receivedAt > STALE_THRESHOLD_MS
    val background = when {
        onLight -> Color.White.copy(alpha = 0.15f)
        isPinned -> extra.severityCriticalBg
        stale -> MaterialTheme.colorScheme.surfaceVariant
        else -> extra.severityCriticalBg
    }
    val primaryColor = if (onLight) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (onLight) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val actionColor = if (onLight) Color.White else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.cardRadius))
            .background(background)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Sos,
                contentDescription = null,
                tint = if (onLight) Color.White else extra.severityCritical,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                beacon.senderName,
                style = AppTypography.titleSmall,
                color = primaryColor,
                modifier = Modifier.weight(1f),
            )
            if (isPinned) {
                StatusPill("Responding", if (onLight) Color.White.copy(alpha = 0.3f) else extra.severityCritical)
                Spacer(Modifier.width(6.dp))
            }
            if (stale) {
                StatusPill("Stale", if (onLight) Color.White.copy(alpha = 0.3f) else extra.textTertiary)
            } else {
                StatusPill("Live", extra.severityLow)
            }
        }

        Spacer(Modifier.height(8.dp))
        BeaconFact("Distance", distanceLabel(distance, beacon), secondaryColor, primaryColor)
        BeaconFact("Last update", formatTimeAgo(now - record.receivedAt), secondaryColor, primaryColor)
        BeaconFact(
            "Battery",
            beacon.batteryPercent?.let { "$it%${if (beacon.isCharging) " (charging)" else ""}" } ?: "Unknown",
            secondaryColor,
            primaryColor,
        )

        if (beacon.hasFix) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dimens.badgeRadius))
                    .clickable(onClick = onOpenLocation)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Directions,
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatCoords(beacon.latitude!!, beacon.longitude!!) + "  ·  Open in maps",
                    style = AppTypography.bodySmall,
                    color = actionColor,
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "No location in this beacon yet",
                style = AppTypography.bodySmall,
                color = secondaryColor,
            )
        }

        if (!beacon.note.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("\"${beacon.note}\"", style = AppTypography.bodySmall, color = primaryColor)
        }

        Spacer(Modifier.height(Dimens.cardSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTogglePin, modifier = Modifier.weight(1f)) {
                Icon(
                    if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isPinned) "Unpin" else "Pin",
                    style = AppTypography.titleSmall,
                    color = actionColor,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss", style = AppTypography.titleSmall, color = secondaryColor)
            }
        }
        Text(
            "Dismissing only hides this from your list. The beacon keeps broadcasting and other " +
                "devices still see it.",
            style = AppTypography.bodySmall,
            color = secondaryColor,
        )
    }
}

@Composable
private fun BeaconFact(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = AppTypography.bodySmall, color = labelColor, modifier = Modifier.weight(1f))
        Text(value, style = AppTypography.bodySmall, color = valueColor)
    }
}

private fun distanceLabel(distance: Float?, beacon: SosBeacon): String = when {
    distance != null -> formatDistance(distance)
    !beacon.hasFix -> "No location in beacon"
    else -> "Unknown - no fix on this device"
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** The beacon's position as a [Location], or null when it was sent without a fix. */
internal fun SosBeacon.toLocationOrNull(): Location? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return Location("mesh").apply { latitude = lat; longitude = lon }
}

private fun distanceMeters(from: Location?, beacon: SosBeacon): Float? {
    if (from == null) return null
    val lat = beacon.latitude ?: return null
    val lon = beacon.longitude ?: return null
    val results = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, lat, lon, results)
    return results[0]
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatDistance(meters: Float): String =
    if (meters < 1000) "${meters.toInt()} m away" else "%.1f km away".format(meters / 1000f)

private fun formatTimeAgo(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

private fun formatCoords(lat: Double, lon: Double): String = "%.5f, %.5f".format(lat, lon)
