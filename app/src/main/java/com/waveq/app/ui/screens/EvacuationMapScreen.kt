package com.waveq.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.waveq.app.model.SafeZone
import com.waveq.app.model.defaultSafeZones
import com.waveq.app.ui.components.PrimaryButton
import com.waveq.app.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

@Composable
fun EvacuationMapScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var locationStatusText by remember { mutableStateOf("Locating...") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            locationStatusText = "GPS Permission Denied"
        }
    }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val nearestShelter: SafeZone? = remember(userLocation) {
        userLocation?.let { loc ->
            defaultSafeZones.minByOrNull { it.distanceTo(loc.latitude, loc.longitude) }
        } ?: defaultSafeZones.firstOrNull()
    }

    Scaffold(
        containerColor = AppBackground,
        bottomBar = {
            nearestShelter?.let { shelter ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Filled.DirectionsRun,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Recommended Shelter",
                                style = AppTypography.titleMedium,
                                color = TextPrimary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = shelter.name,
                            style = AppTypography.headlineSmall,
                            color = AccentGreen
                        )
                        Text(
                            text = "Capacity: ${shelter.capacity} people • Emergency Medical Available",
                            style = AppTypography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            text = "Navigate To Shelter",
                            onClick = {
                                val navUri = Uri.parse("google.navigation:q=${shelter.latitude},${shelter.longitude}&mode=w")
                                val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                                    setPackage("com.google.android.apps.maps")
                                }
                                try {
                                    context.startActivity(mapIntent)
                                } catch (_: Exception) {
                                    val fallbackIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:${shelter.latitude},${shelter.longitude}?q=${shelter.latitude},${shelter.longitude}")
                                    )
                                    try {
                                        context.startActivity(fallbackIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            leadingIcon = Icons.Filled.Navigation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    val basePath = File(ctx.cacheDir, "osmdroid")
                    basePath.mkdirs()
                    Configuration.getInstance().apply {
                        userAgentValue = ctx.packageName
                        osmdroidBasePath = basePath
                        osmdroidTileCache = File(basePath, "tiles")
                    }

                    MapView(ctx).apply {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        controller.setZoom(15.0)

                        val startCenter = GeoPoint(28.6139, 77.2090)
                        controller.setCenter(startCenter)

                        // Robust native GPS overlay
                        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this).apply {
                            enableMyLocation()
                            enableFollowLocation()
                            runOnFirstFix {
                                val fix = myLocation
                                if (fix != null) {
                                    userLocation = fix
                                    locationStatusText = "GPS Active"
                                    post {
                                        controller.animateTo(fix)
                                    }
                                }
                            }
                        }
                        overlays.add(locationOverlay)

                        // Add safe zone route line
                        nearestShelter?.let { shelter ->
                            val routeLine = Polyline(this).apply {
                                addPoint(startCenter)
                                addPoint(GeoPoint(shelter.latitude, shelter.longitude))
                                outlinePaint.color = Color.rgb(16, 185, 129)
                                outlinePaint.strokeWidth = 10f
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            overlays.add(routeLine)
                        }
                    }
                },
                update = { mapView ->
                    userLocation?.let { loc ->
                        mapView.controller.setCenter(loc)
                    }
                },
                onRelease = { mapView ->
                    mapView.onPause()
                    mapView.onDetach()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (locationStatusText.contains("Active")) AccentGreen else AccentAmber,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = locationStatusText,
                            style = AppTypography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}