package com.waveq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.waveq.app.location.LocationController
import com.waveq.app.location.PlaceRef
import com.waveq.app.location.PlaceSearch
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*
import kotlinx.coroutines.delay

/** Typing pause before a search fires, so a five-letter town is one request rather than four. */
private const val SEARCH_DEBOUNCE_MS = 350L

private const val MIN_QUERY_LENGTH = 2

/**
 * Location picker, in the shape people already know from delivery apps: current
 * location pinned at the top, a search box, then saved and recent places.
 *
 * The one thing this screen must never blur is what it is actually changing.
 * Picking a place here changes which location the **risk display** describes.
 * It does not move the device, does not change what an SOS beacon transmits,
 * and does not change which risk can sound the siren - all of which stay bound
 * to the real GPS fix. The screen says so in as many words at the bottom.
 */
@Composable
fun LocationPickerScreen(onDone: () -> Unit) {
    val devicePlace by LocationController.devicePlace.collectAsState()
    val override by LocationController.viewingOverride.collectAsState()
    val recents by LocationController.recentPlaces.collectAsState()
    val saved by LocationController.savedPlaces.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceRef>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var placeToSave by remember { mutableStateOf<PlaceRef?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            LocationController.onPermissionGranted()
            LocationController.resetToCurrentLocation()
            onDone()
        }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            results = emptyList()
            isSearching = false
            searchFailed = false
            return@LaunchedEffect
        }
        isSearching = true
        searchFailed = false
        delay(SEARCH_DEBOUNCE_MS)
        PlaceSearch.search(trimmed)
            .onSuccess { results = it }
            .onFailure {
                results = emptyList()
                searchFailed = true
            }
        isSearching = false
    }

    val select: (PlaceRef) -> Unit = { place ->
        LocationController.setViewingLocation(place)
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
    ) {
        Spacer(Modifier.height(Dimens.cardSpacing))
        Text("Choose location", style = AppTypography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "See flood risk for anywhere in India.",
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        LabeledField(
            label = "",
            value = query,
            onValueChange = { query = it },
            placeholder = "Search for a city, town or village",
            trailingIcon = {
                if (query.isEmpty()) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.appExtraColors.textTertiary)
                } else {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { query = "" },
                    )
                }
            },
        )

        if (query.trim().length >= MIN_QUERY_LENGTH) {
            Spacer(Modifier.height(Dimens.sectionSpacing))
            SearchResults(
                results = results,
                isSearching = isSearching,
                searchFailed = searchFailed,
                activeKey = override?.coordinateKey(),
                onSelect = select,
                onSave = { placeToSave = it },
            )
        } else {
            // -- Current location, always first ----------------------------
            Spacer(Modifier.height(Dimens.sectionSpacing))
            CurrentLocationRow(
                devicePlace = devicePlace,
                isActive = override == null,
                onClick = {
                    // Nothing in the app ever asked for location, so this row
                    // silently did nothing on a device that had never granted
                    // it: the override cleared, but no fix was ever acquired and
                    // no risk could be computed for the device position.
                    if (LocationController.hasLocationPermission()) {
                        LocationController.resetToCurrentLocation()
                        onDone()
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
            )

            if (saved.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionSpacing))
                SectionHeading("Saved")
                Spacer(Modifier.height(Dimens.cardSpacing))
                saved.sortedBy { it.label?.lowercase() }.forEach { place ->
                    PlaceRow(
                        place = place,
                        icon = Icons.Filled.Bookmark,
                        isActive = override?.coordinateKey() == place.coordinateKey(),
                        onClick = { select(place) },
                        trailing = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${place.label ?: place.name}",
                                tint = MaterialTheme.appExtraColors.textTertiary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { LocationController.removeSavedPlace(place) },
                            )
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.sectionSpacing))
                SectionHeading("Recent")
                Spacer(Modifier.height(Dimens.cardSpacing))
                recents.forEach { place ->
                    PlaceRow(
                        place = place,
                        icon = Icons.Filled.History,
                        isActive = override?.coordinateKey() == place.coordinateKey(),
                        onClick = { select(place) },
                        trailing = {
                            if (!LocationController.isSaved(place)) {
                                Icon(
                                    Icons.Filled.StarBorder,
                                    contentDescription = "Save ${place.name}",
                                    tint = MaterialTheme.appExtraColors.textTertiary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { placeToSave = place },
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        NoticeCard(
            icon = Icons.Filled.MyLocation,
            title = "Alerts always follow your real location",
            body = "Choosing another place changes only what the risk screens show you. " +
                "Emergency alerts, the siren and your SOS beacon all keep using your actual " +
                "GPS position, wherever you are looking.",
        )
        Spacer(Modifier.height(Dimens.sectionSpacing))
    }

    placeToSave?.let { place ->
        SavePlaceDialog(
            place = place,
            onDismiss = { placeToSave = null },
            onSave = { label ->
                LocationController.savePlace(place, label)
                placeToSave = null
            },
        )
    }
}

@Composable
private fun SearchResults(
    results: List<PlaceRef>,
    isSearching: Boolean,
    searchFailed: Boolean,
    activeKey: String?,
    onSelect: (PlaceRef) -> Unit,
    onSave: (PlaceRef) -> Unit,
) {
    when {
        isSearching -> Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Searching...", style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Place search is the one part of this app that genuinely needs a
        // network - say so plainly instead of showing "no results", which would
        // read as "this place does not exist".
        searchFailed -> Text(
            "Could not search right now. Place search needs an internet connection - " +
                "your saved and recent locations still work offline.",
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )

        results.isEmpty() -> Text(
            "No matching places in India.",
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )

        else -> Column {
            SectionHeading("Results")
            Spacer(Modifier.height(Dimens.cardSpacing))
            results.forEach { place ->
                PlaceRow(
                    place = place,
                    icon = Icons.Filled.Place,
                    isActive = activeKey == place.coordinateKey(),
                    onClick = { onSelect(place) },
                    trailing = {
                        Icon(
                            Icons.Filled.StarBorder,
                            contentDescription = "Save ${place.name}",
                            tint = MaterialTheme.appExtraColors.textTertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onSave(place) },
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** "Use current location" - the top option, always, whatever else is on screen. */
@Composable
private fun CurrentLocationRow(devicePlace: PlaceRef?, isActive: Boolean, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        onClick = onClick,
    ) {
        Row(Modifier.padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Use current location",
                    style = AppTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    devicePlace?.fullLabel() ?: "Detecting your location...",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) {
                Spacer(Modifier.width(10.dp))
                StatusPill("Current", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * One place. Name on top, district and state beneath - the subtitle is doing
 * real work here, since a bare "Rampur" or "Bilaspur" is genuinely ambiguous
 * across Indian states.
 */
@Composable
private fun PlaceRow(
    place: PlaceRef,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = if (isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        onClick = onClick,
    ) {
        Row(Modifier.padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    place.label?.let { label ->
                        StatusPill(
                            label,
                            MaterialTheme.colorScheme.surfaceVariant,
                            fg = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        place.name,
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    place.subtitle(),
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun SavePlaceDialog(place: PlaceRef, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var label by remember { mutableStateOf(place.label ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Save ${place.name}", style = AppTypography.titleMedium) },
        text = {
            Column {
                Text(
                    place.subtitle(),
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.cardSpacing))
                LabeledField(
                    label = "Label",
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "Home, Parents, Village",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label) }) {
                Text("Save", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = AppTypography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * The persistent reminder shown app-wide while a manual override is active.
 *
 * Persistent, not a snackbar. The whole risk of this feature is a user reading a
 * score for somewhere else and believing it describes where they are standing,
 * and that risk lasts exactly as long as the override does.
 */
@Composable
fun ViewingLocationBanner(
    placeName: String,
    wasRestored: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val background = MaterialTheme.appExtraColors.severityMediumBg
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.appExtraColors.severityMediumFg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            // A restored override gets stronger wording: the user did not choose
            // this in the session they are currently in, so it has to announce
            // itself rather than blend in.
            if (wasRestored) {
                "Still viewing $placeName from last time - alerts stay active for your current location."
            } else {
                "Viewing $placeName - alerts still active for your current location."
            },
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "RESET",
            style = MicroLabelStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(Dimens.badgeRadius))
                .clickable(onClick = onReset)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The tappable location name for the Home header - the picker's main entry
 * point, in the shape people already expect from delivery apps.
 */
@Composable
fun LocationHeaderButton(
    place: PlaceRef?,
    isOverridden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isOverridden) Icons.Filled.Place else Icons.Filled.MyLocation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            MicroLabel(if (isOverridden) "Viewing" else "Your location")
            Spacer(Modifier.height(3.dp))
            Text(
                place?.name ?: "Set location",
                style = AppTypography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "Change location",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
