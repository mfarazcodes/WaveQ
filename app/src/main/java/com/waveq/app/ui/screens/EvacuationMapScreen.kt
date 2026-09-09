package com.waveq.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.waveq.app.model.SafeZone
import com.waveq.app.model.ShelterRegion
import com.waveq.app.model.demoSafeZones
import com.waveq.app.model.shelteredNear
import com.waveq.app.ui.components.AppCard
import com.waveq.app.ui.components.MapMarker
import com.waveq.app.ui.components.MicroLabel
import com.waveq.app.ui.components.NearestSafeZoneCard
import com.waveq.app.ui.components.OsmMapView
import com.waveq.app.ui.components.SectionHeading
import com.waveq.app.ui.components.formatDistance
import com.waveq.app.ui.components.launchWalkingNavigation
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors
import org.osmdroid.util.GeoPoint

/**
 * Evacuation routing: the user's live position, every shelter in range, and a
 * line to the nearest one, with a handoff to turn-by-turn navigation.
 *
 * All MapView handling now lives in [OsmMapView]; this screen supplies data.
 *
 * TODO: the line is straight-line, not a road route. A real routing engine
 * (OSRM/GraphHopper, or bundled offline routing) is needed before this can be
 * trusted in hill terrain where the direct line may cross a river or a ridge.
 */
@Composable
fun EvacuationMapScreen() {
    val context = LocalContext.current
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val safeColor = MaterialTheme.appExtraColors.severityLow
    val pendingColor = MaterialTheme.appExtraColors.severityMedium
    val darkTiles = isSystemInDarkTheme()

    // The grant result now actually does something: it flips the flag that
    // OsmMapView keys its location overlay on, so the blue dot appears as soon
    // as permission arrives instead of never.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    val locationStatusText = when {
        !hasLocationPermission -> "Location permission needed"
        userLocation == null -> "Locating…"
        else -> "GPS active"
    }

    // Only shelters actually near the user. Outside the covered regions this is
    // empty, and the screen says so rather than pointing at something 1,500 km
    // away that happened to be marginally closer than the alternative.
    val sheltersInRange: List<SafeZone> = remember(userLocation) {
        userLocation?.let { shelteredNear(it.latitude, it.longitude) } ?: emptyList()
    }
    val nearestShelter = sheltersInRange.firstOrNull()
    val mapCenter = userLocation
        ?: nearestShelter?.let { GeoPoint(it.latitude, it.longitude) }
        ?: GeoPoint(demoSafeZones.first().latitude, demoSafeZones.first().longitude)

    val markers = remember(sheltersInRange, safeColor) {
        sheltersInRange.take(MAX_MAP_SHELTERS).map { zone ->
            MapMarker(
                id = zone.id,
                latitude = zone.latitude,
                longitude = zone.longitude,
                title = zone.name,
                snippet = "Capacity ${zone.capacity}, ${zone.currentOccupancy} sheltering (demo data)",
                onClick = { launchWalkingNavigation(context, zone.latitude, zone.longitude) },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsRun,
                contentDescription = null,
                tint = safeColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Evacuation",
                style = AppTypography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Nearest relief shelters and the route to reach them.",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        DemoShelterDataNotice()

        Spacer(Modifier.height(Dimens.cardSpacing))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(Dimens.cardRadius)),
        ) {
            OsmMapView(
                markers = markers,
                modifier = Modifier.fillMaxSize(),
                center = mapCenter,
                zoom = 13.0,
                routeFrom = userLocation,
                routeTo = nearestShelter?.let { GeoPoint(it.latitude, it.longitude) },
                routeColor = safeColor,
                showMyLocation = true,
                hasLocationPermission = hasLocationPermission,
                darkTiles = darkTiles,
                onMyLocationFix = { userLocation = it },
            )

            Surface(
                shape = RoundedCornerShape(Dimens.badgeRadius),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (userLocation != null) safeColor else pendingColor),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = locationStatusText,
                        style = AppTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))

        when {
            userLocation == null -> {
                Text(
                    if (hasLocationPermission) {
                        "Waiting for a GPS fix before shelters can be ranked by distance."
                    } else {
                        "Grant location access to find the shelters nearest to you."
                    },
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
            nearestShelter == null -> NoSheltersHereCard()
            else -> {
                NearestSafeZoneCard(
                    safeZone = nearestShelter,
                    userLat = userLocation?.latitude,
                    userLng = userLocation?.longitude,
                )
                if (sheltersInRange.size > 1) {
                    Spacer(Modifier.height(Dimens.sectionSpacing))
                    SectionHeading("Other shelters nearby")
                    Spacer(Modifier.height(Dimens.cardSpacing))
                    sheltersInRange.drop(1).take(MAX_LISTED_SHELTERS).forEach { zone ->
                        OtherShelterCard(zone, userLocation)
                        Spacer(Modifier.height(Dimens.cardSpacing))
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

private const val MAX_MAP_SHELTERS = 40
private const val MAX_LISTED_SHELTERS = 15

/** Shown wherever shelters are listed - the bundled set is invented and must say so. */
@Composable
fun DemoShelterDataNotice(modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        background = MaterialTheme.appExtraColors.severityMediumBg,
    ) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                "Demo shelter data",
                style = AppTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "These shelters are sample data for Ghaziabad district and the Brahmaputra " +
                    "valley. The localities are real; the specific sites, capacities and " +
                    "occupancy figures are invented. Do not travel to one on the strength of " +
                    "this screen.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoSheltersHereCard() {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                "No shelters in this area",
                style = AppTypography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The bundled shelter set only covers " +
                    ShelterRegion.entries.joinToString(" and ") { it.label } +
                    ". Nothing is listed for where you are, and pointing you at the nearest " +
                    "entry anyway would send you hundreds of kilometres in the wrong direction.",
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OtherShelterCard(zone: SafeZone, userLocation: GeoPoint?) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Text(
                zone.name,
                style = AppTypography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                zone.address,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatDistance(userLocation?.let { zone.distanceTo(it.latitude, it.longitude) }),
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${zone.currentOccupancy} / ${zone.capacity}",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.appExtraColors.textTertiary,
                )
            }
        }
    }
}
