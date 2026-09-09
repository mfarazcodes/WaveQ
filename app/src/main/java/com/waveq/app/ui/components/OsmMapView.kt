package com.waveq.app.ui.components

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * One-time osmdroid setup. Called from [com.waveq.app.WaveQApplication], never
 * from a screen.
 */
object OsmConfig {

    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext

        // filesDir rather than cacheDir: the OS may evict cacheDir under storage
        // pressure, and re-downloading the whole tile cache is exactly what a
        // device in a flood cannot do.
        val basePath = File(appContext.filesDir, "osmdroid")
        val tileCache = File(basePath, "tiles")
        basePath.mkdirs()
        tileCache.mkdirs()

        val prefs = appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().apply {
            // load() first: it reads every value out of prefs, so anything set
            // before it would be overwritten.
            load(appContext, prefs)
            // OpenStreetMap's tile servers return 403 for osmdroid's default
            // "osmdroid" user agent. This is the single most common cause of a
            // blank grey map.
            userAgentValue = appContext.packageName
            osmdroidBasePath = basePath
            osmdroidTileCache = tileCache
        }
    }
}

/** A point to plot. [onClick] fires on tap; a null tint uses osmdroid's default pin. */
data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String? = null,
    val tint: Color? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * The only place in the app that touches [MapView].
 *
 * Everything that was wrong with the ad-hoc map in EvacuationMapScreen is
 * handled once, here:
 *
 *  - **Lifecycle.** `onResume`/`onPause` are forwarded from the composition's
 *    lifecycle owner, and the location overlay is disabled when the app is
 *    backgrounded. Previously nothing paused the map until the composable left
 *    composition permanently, so GPS ran on in the background.
 *  - **Overlays by identity.** Markers, the route line and the location overlay
 *    are held in `remember` and removed by reference. The previous code deleted
 *    everything past index 0 and trusted the location overlay to be first -
 *    correct only by accident, and silently destructive if anything else were
 *    ever added in the factory.
 *  - **Markers rebuilt only on change.** Keyed on the marker list, not on every
 *    recomposition.
 *  - **Live location after the grant.** [hasLocationPermission] is a parameter,
 *    so enabling happens when the permission actually arrives. The old code
 *    called `enableMyLocation()` in the factory - before the prompt could
 *    possibly have been answered - and never retried, so granting location did
 *    nothing until the screen was recreated.
 *  - **Touch.** The map asks its parent not to intercept, so it can be panned
 *    inside a vertically scrolling column instead of the scroll stealing every
 *    vertical drag.
 *  - **Dark mode.** A colour matrix on the tile overlay, so a 320dp near-white
 *    map does not glare at night.
 */
@Composable
fun OsmMapView(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    center: GeoPoint? = null,
    zoom: Double = 14.0,
    routeFrom: GeoPoint? = null,
    routeTo: GeoPoint? = null,
    routeColor: Color = Color(0xFF22C55E),
    showMyLocation: Boolean = false,
    hasLocationPermission: Boolean = false,
    darkTiles: Boolean = false,
    onMyLocationFix: ((GeoPoint) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val markerOverlays = remember { mutableListOf<Marker>() }
    var routeOverlay by remember { mutableStateOf<Polyline?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    // Centre only once from the caller's value, so a recomposition does not yank
    // the map back while the user is panning.
    var didInitialCenter by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mapView) {
        val map = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    map?.onResume()
                    if (showMyLocation && hasLocationPermission) locationOverlay?.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    map?.onPause()
                    // Backgrounded: stop consuming GPS. Nothing did this before.
                    locationOverlay?.disableMyLocation()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Belt and braces: the Application already did this, but a MapView
            // must never be constructed against an unconfigured Configuration.
            OsmConfig.init(ctx)
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setUseDataConnection(true)
                isTilesScaledToDpi = true
                // Compose's verticalScroll is a pointer-input modifier, and
                // AndroidComposeView honours requestDisallowInterceptTouchEvent -
                // so this is what lets the map be panned inside a scrolling page.
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // never consume - the MapView still needs the event
                }
                mapView = this
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(
                if (darkTiles) TilesOverlay.INVERT_COLORS else null,
            )

            if (!didInitialCenter) {
                map.controller.setZoom(zoom)
                center?.let { map.controller.setCenter(it) }
                didInitialCenter = true
            }

            syncLocationOverlay(
                map = map,
                enabled = showMyLocation && hasLocationPermission,
                current = locationOverlay,
                onCreated = { locationOverlay = it },
                onRemoved = { locationOverlay = null },
                onFix = onMyLocationFix,
            )
            syncMarkers(map, markers, markerOverlays)
            routeOverlay = syncRoute(map, routeFrom, routeTo, routeColor.toArgb(), routeOverlay)

            map.invalidate()
        },
        onRelease = { map ->
            locationOverlay?.disableMyLocation()
            map.onPause()
            map.onDetach()
            markerOverlays.clear()
            mapView = null
        },
    )
}

/** Enables or removes the live-location overlay, reacting to a permission that arrives later. */
private fun syncLocationOverlay(
    map: MapView,
    enabled: Boolean,
    current: MyLocationNewOverlay?,
    onCreated: (MyLocationNewOverlay) -> Unit,
    onRemoved: () -> Unit,
    onFix: ((GeoPoint) -> Unit)?,
) {
    if (!enabled) {
        current?.let {
            it.disableMyLocation()
            map.overlays.remove(it)
            onRemoved()
        }
        return
    }
    if (current != null) {
        if (!current.isMyLocationEnabled) current.enableMyLocation()
        return
    }
    val overlay = MyLocationNewOverlay(GpsMyLocationProvider(map.context), map).apply {
        enableMyLocation()
        runOnFirstFix {
            val fix = myLocation ?: return@runOnFirstFix
            // runOnFirstFix runs on a background thread; hop to the main thread
            // before touching the map or Compose state.
            map.post { onFix?.invoke(fix) }
        }
    }
    map.overlays.add(overlay)
    onCreated(overlay)
}

/**
 * Rebuilds markers only when the set actually changed, and removes the previous
 * ones by reference rather than by list position.
 */
private fun syncMarkers(map: MapView, markers: List<MapMarker>, existing: MutableList<Marker>) {
    val signature = markers.map { "${it.id}:${it.latitude}:${it.longitude}:${it.title}" }
    val currentSignature = existing.map { it.id ?: "" }
    if (signature == currentSignature) return

    existing.forEach { marker ->
        marker.closeInfoWindow()
        map.overlays.remove(marker)
        marker.onDetach(map)
    }
    existing.clear()

    markers.forEachIndexed { index, data ->
        val marker = Marker(map).apply {
            id = signature[index]
            position = GeoPoint(data.latitude, data.longitude)
            title = data.title
            snippet = data.snippet
            data.tint?.let { tint ->
                // The default icon is a single Drawable shared by every marker
                // on this MapView (MapViewRepository.getDefaultMarkerIcon), so
                // tinting it in place would recolour all of them. Take a mutable
                // copy per marker.
                icon?.constantState?.newDrawable()?.mutate()?.let { copy ->
                    copy.setTint(tint.toArgb())
                    setIcon(copy)
                }
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            data.onClick?.let { handler ->
                setOnMarkerClickListener { _, _ ->
                    handler()
                    true
                }
            }
        }
        map.overlays.add(marker)
        existing.add(marker)
    }
}

/** Straight-line route overlay, replaced by reference. Returns the new overlay, or null. */
private fun syncRoute(
    map: MapView,
    from: GeoPoint?,
    to: GeoPoint?,
    argb: Int,
    current: Polyline?,
): Polyline? {
    current?.let { map.overlays.remove(it) }
    if (from == null || to == null) return null
    val line = Polyline(map).apply {
        addPoint(from)
        addPoint(to)
        outlinePaint.color = argb
        outlinePaint.strokeWidth = 10f
        outlinePaint.strokeCap = Paint.Cap.ROUND
    }
    map.overlays.add(line)
    return line
}
