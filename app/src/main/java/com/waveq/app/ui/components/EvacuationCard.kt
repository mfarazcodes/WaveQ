package com.waveq.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveq.app.model.SafeZone
import com.waveq.app.ui.theme.*

@Composable
fun NearestSafeZoneCard(
    safeZone: SafeZone,
    userLat: Double?,
    userLng: Double?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val distanceMeters = if (userLat != null && userLng != null) {
        safeZone.distanceTo(userLat, userLng)
    } else null

    val distanceText = when {
        distanceMeters == null -> "Calculating distance…"
        distanceMeters < 1000 -> "${distanceMeters.toInt()} m away"
        else -> String.format("%.1f km away", distanceMeters / 1000)
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "NEAREST SAFE SHELTER",
                        style = AppTypography.labelSmall,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = safeZone.name,
                        style = AppTypography.titleMedium,
                        color = TextPrimary
                    )
                }
                StatusPill(
                    text = distanceText,
                    bg = AccentGreen,
                    fg = Color.White
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = safeZone.address,
                style = AppTypography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(12.dp))

            // Badges for supplies and capacity
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val availableSpots = safeZone.capacity - safeZone.currentOccupancy
                Text(
                    text = "$availableSpots spots available",
                    style = AppTypography.labelSmall,
                    color = if (availableSpots > 50) TextSecondary else SeverityCritical,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                if (safeZone.hasMedicalSupport) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceMuted)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.LocalHospital, contentDescription = null, tint = BrandRed, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Medical", style = AppTypography.labelSmall, color = TextSecondary)
                    }
                }

                if (safeZone.hasFoodSupplies) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceMuted)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Restaurant, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Food & Water", style = AppTypography.labelSmall, color = TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Navigation CTA
            Button(
                onClick = {
                    val gmmIntentUri = Uri.parse("google.navigation:q=${safeZone.latitude},${safeZone.longitude}&mode=w")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    if (mapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mapIntent)
                    } else {
                        // Fallback browser intent
                        val browserUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${safeZone.latitude},${safeZone.longitude}&travelmode=walking")
                        context.startActivity(Intent(Intent.ACTION_VIEW, browserUri))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ActionBlue),
                shape = RoundedCornerShape(Dimens.cardRadius),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Evacuation Route", style = AppTypography.titleSmall)
            }
        }
    }
}