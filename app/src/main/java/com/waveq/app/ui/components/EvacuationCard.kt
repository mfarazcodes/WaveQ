package com.waveq.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.waveq.app.model.SafeZone
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.appExtraColors

/** Formats a metre distance the way a person reads it. */
fun formatDistance(meters: Float?): String = when {
    meters == null -> "Calculating distance…"
    meters < 1000 -> "${meters.toInt()} m away"
    else -> String.format("%.1f km away", meters / 1000)
}

/**
 * Opens turn-by-turn walking navigation to a point, preferring Google Maps and
 * falling back to any installed maps app. Shared by the card and the
 * evacuation screen so the intent handling lives in one place.
 */
fun launchWalkingNavigation(context: android.content.Context, lat: Double, lng: Double) {
    val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=w")
    val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(mapIntent)
        return
    } catch (_: Exception) {
        // Google Maps not installed - fall through to the generic geo: handler.
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")),
        )
    } catch (_: Exception) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Summary of the nearest shelter: distance, occupancy, what is available there,
 * and a one-tap handoff to navigation.
 */
@Composable
fun NearestSafeZoneCard(
    safeZone: SafeZone,
    userLat: Double?,
    userLng: Double?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safeColor = MaterialTheme.appExtraColors.severityLow
    val distanceMeters = if (userLat != null && userLng != null) {
        safeZone.distanceTo(userLat, userLng)
    } else {
        null
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(safeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = safeColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MicroLabel("Nearest safe shelter")
                    Text(
                        text = safeZone.name,
                        style = AppTypography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(Dimens.cardSpacing))
            Text(
                text = formatDistance(distanceMeters),
                style = AppTypography.bodyMedium,
                color = safeColor,
            )
            Text(
                text = safeZone.address,
                style = AppTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Dimens.cardSpacing))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Occupancy",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${safeZone.currentOccupancy} / ${safeZone.capacity}",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { safeZone.occupancyFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (safeZone.occupancyFraction > 0.85f) {
                    MaterialTheme.appExtraColors.severityHigh
                } else {
                    safeColor
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(Modifier.height(Dimens.cardSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (safeZone.hasMedicalSupport) {
                    FacilityTag(Icons.Filled.LocalHospital, "Medical")
                }
                if (safeZone.hasFoodSupplies) {
                    FacilityTag(Icons.Filled.Restaurant, "Food & water")
                }
            }

            Spacer(Modifier.height(Dimens.sectionSpacing))
            PrimaryButton(
                text = "Navigate to shelter",
                onClick = {
                    launchWalkingNavigation(context, safeZone.latitude, safeZone.longitude)
                },
                leadingIcon = Icons.Filled.Directions,
            )
        }
    }
}

@Composable
private fun FacilityTag(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.appExtraColors.textTertiary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
