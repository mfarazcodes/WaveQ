package com.waveq.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.waveq.app.ui.screens.Incident
import com.waveq.app.ui.theme.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@SuppressLint("MissingPermission")
@Composable
fun CrisisMapView(
    activeIncidentsCount: Int,
    incidents: List<Incident> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) userLocation = GeoPoint(loc.latitude, loc.longitude)
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) userLocation = GeoPoint(loc.latitude, loc.longitude)
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(28.6139, 77.2090))
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
                update = { map ->
                    map.overlays.clear()

                    // Plot Incidents with severity colors
                    incidents.forEach { incident ->
                        val geo = when (incident.id) {
                            "INC-001" -> GeoPoint(28.6150, 77.2100)
                            "INC-002" -> GeoPoint(28.6300, 77.2200)
                            "INC-003" -> GeoPoint(28.6050, 77.1950)
                            else -> GeoPoint(28.6250, 77.2050)
                        }
                        val marker = Marker(map).apply {
                            position = geo
                            title = "${incident.type}: ${incident.location}"
                            icon = createCircleDrawable(context, incident.severity.solid.toArgb(), 18)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        map.overlays.add(marker)
                    }

                    // Plot User Location Marker
                    userLocation?.let { userPt ->
                        val userMarker = Marker(map).apply {
                            position = userPt
                            title = "You are here"
                            icon = createCircleDrawable(context, AccentBlue.toArgb(), 22)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        }
                        map.overlays.add(userMarker)
                        map.controller.animateTo(userPt)
                    }

                    map.invalidate()
                }
            )
        }
    }
}

private fun createCircleDrawable(context: Context, color: Int, sizeDp: Int): ShapeDrawable {
    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
    return ShapeDrawable(OvalShape()).apply {
        intrinsicWidth = px
        intrinsicHeight = px
        paint.color = color
        paint.style = Paint.Style.FILL
    }
}