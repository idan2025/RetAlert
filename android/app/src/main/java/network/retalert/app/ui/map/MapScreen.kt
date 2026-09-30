@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.map

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private val RADIUS_OPTIONS = listOf(5, 10, 20, 50, 100)
private const val OWN_COLOR = 0xFF1565C0.toInt()      // blue
private const val PEER_COLOR = 0xFFC62828.toInt()    // red
private const val MAX_TILES = 20_000
private val LOCATION_PERMS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Bounding box of roughly [radiusKm] around [c]. */
private fun radiusBox(c: GeoPoint, radiusKm: Double): BoundingBox {
    val dLat = radiusKm / 111.32
    val dLon = radiusKm / (111.32 * cos(Math.toRadians(c.latitude)).coerceAtLeast(0.01))
    return BoundingBox(
        (c.latitude + dLat).coerceAtMost(85.0), (c.longitude + dLon).coerceAtMost(180.0),
        (c.latitude - dLat).coerceAtLeast(-85.0), (c.longitude - dLon).coerceAtLeast(-180.0),
    )
}

/** Build a small colored dot drawable for a map marker. */
private fun coloredDot(ctx: android.content.Context, color: Int): Drawable {
    val d = (28f * ctx.resources.displayMetrics.density).toInt()
    val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    c.drawCircle(d / 2f, d / 2f, d / 2f, p)
    return BitmapDrawable(ctx.resources, bmp)
}

@Composable
fun MapScreen(vm: MapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // Location needs a runtime grant; request it right before use.
    var liveAfterPerm by remember { mutableStateOf(false) }
    val locationPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            if (liveAfterPerm) vm.toggleLiveGps() else vm.useMyLocation()
        } else {
            vm.showMessage("location permission denied")
        }
        liveAfterPerm = false
    }
    var radiusMenu by remember { mutableStateOf(false) }
    var selectedRadius by remember { mutableStateOf(10) }
    var setLoc by remember { mutableStateOf(false) }
    // One MapView for the lifetime of this screen, paused/detached with it
    // (osmdroid tile threads otherwise leak on every visit).
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    val peerDot = remember { coloredDot(ctx, PEER_COLOR) }
    val ownDot = remember { coloredDot(ctx, OWN_COLOR) }
    // Center once on the first known position (own fix, else first peer).
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(state.ownFix, state.tracks) {
        if (centered) return@LaunchedEffect
        val p = state.ownFix?.let { GeoPoint(it.lat, it.lon) }
            ?: state.tracks.firstOrNull()?.let { GeoPoint(it.fix.lat, it.fix.lon) }
            ?: return@LaunchedEffect
        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(p)
        centered = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Map") }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { mapView },
                    update = { map ->
                        map.overlays.removeAll { it is Marker }
                        // Peers (red); tap a marker to follow that peer.
                        state.tracks.forEach { t ->
                            val m = Marker(map).apply {
                                position = GeoPoint(t.fix.lat, t.fix.lon)
                                title = t.displayName.ifBlank { t.sourceHash.take(8) }
                                icon = peerDot
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { marker, _ ->
                                    vm.follow(t.sourceHash)
                                    marker.showInfoWindow()
                                    true
                                }
                            }
                            map.overlays.add(m)
                        }
                        // Own position (blue, distinct from peers).
                        state.ownFix?.let { fix ->
                            val m = Marker(map).apply {
                                position = GeoPoint(fix.lat, fix.lon)
                                title = "own (${fix.source})"
                                icon = ownDot
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            }
                            map.overlays.add(m)
                        }
                        // Follow: re-center on the followed peer (keep zoom).
                        state.followed?.let { h ->
                            state.tracks.firstOrNull { it.sourceHash == h }?.let { t ->
                                map.controller.setCenter(GeoPoint(t.fix.lat, t.fix.lon))
                            }
                        }
                        map.invalidate()
                    },
                )
            }

            Card(Modifier.fillMaxWidth().padding(6.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.followed != null) {
                            OutlinedButton(onClick = { vm.unfollow() }) { Text("Unfollow") }
                        }
                        OutlinedButton(onClick = { setLoc = true }) { Text("Set location") }
                        OutlinedButton(onClick = { locationPerm.launch(LOCATION_PERMS) }) { Text("My location") }
                        Button(onClick = {
                            if (state.liveGps) vm.toggleLiveGps() else { liveAfterPerm = true; locationPerm.launch(LOCATION_PERMS) }
                        }) { Text(if (state.liveGps) "Stop GPS" else "Live GPS") }
                    }
                    if (state.tracks.isEmpty()) {
                        Text(
                            "No peers sharing location yet — peers appear here when they send geo: updates.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (state.followed == null) {
                        Text("Tap a red marker to follow that peer.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        when (val f = state.ownFix) {
                            null -> "own: no fix"
                            else -> "own: %.5f, %.5f (%s)".format(f.lat, f.lon, f.source)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.followed?.let { h ->
                        val t = state.tracks.firstOrNull { it.sourceHash == h }
                        val dist = t?.let { vm.distanceTo(it.fix) }
                        Text(
                            "following ${t?.displayName?.ifBlank { h.take(8) } ?: h.take(8)} · ${dist ?: "distance unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("radius km", style = MaterialTheme.typography.labelMedium)
                        Box {
                            AssistChip(
                                onClick = { radiusMenu = true },
                                label = { Text("$selectedRadius") },
                                modifier = Modifier.padding(start = 6.dp),
                            )
                            DropdownMenu(expanded = radiusMenu, onDismissRequest = { radiusMenu = false }) {
                                RADIUS_OPTIONS.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text("$r km") },
                                        onClick = { selectedRadius = r; radiusMenu = false },
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val map = mapView
                                // Cache a [selectedRadius] km box around own position (else the
                                // map center) at the current zoom plus up to two detail levels.
                                // Bulk download must comply with the tile source's usage policy,
                                // hence the tile cap below.
                                val center = state.ownFix?.let { GeoPoint(it.lat, it.lon) }
                                    ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
                                val bbox = radiusBox(center, selectedRadius.toDouble())
                                val cm = CacheManager(map)
                                val zoomMin = map.zoomLevelDouble.toInt().coerceIn(1, 14)
                                val zoomMax = (zoomMin + 2).coerceAtMost(16)
                                val total = cm.possibleTilesInArea(bbox, zoomMin, zoomMax)
                                if (total > MAX_TILES) {
                                    vm.onDownloadFailed("$total tiles is too many — zoom out or pick a smaller radius")
                                    return@Button
                                }
                                val name = "${selectedRadius} km around %.3f, %.3f".format(center.latitude, center.longitude)
                                vm.beginDownload(selectedRadius, total, name)
                                cm.downloadAreaAsync(ctx.applicationContext, bbox, zoomMin, zoomMax,
                                    object : CacheManager.CacheManagerCallback {
                                        override fun downloadStarted() {}
                                        override fun setPossibleTilesInArea(possible: Int) {}
                                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                            vm.onDownloadProgress(progress)
                                        }
                                        override fun onTaskComplete() { vm.onDownloadComplete(name, selectedRadius) }
                                        override fun onTaskFailed(err: Int) { vm.onDownloadFailed("code $err") }
                                    },
                                )
                            },
                            enabled = state.downloadProgress == null,
                            modifier = Modifier.padding(start = 6.dp),
                        ) { Text("Download area") }
                    }
                    state.downloadProgress?.let { (done, total) ->
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("downloading $done/$total…", style = MaterialTheme.typography.bodySmall)
                    }
                    state.flash.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.offlineAreas.isNotEmpty()) {
                        Text("Offline areas:", style = MaterialTheme.typography.labelMedium)
                        state.offlineAreas.forEach { a ->
                            Text("• ${a.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (setLoc) {
        var lat by remember { mutableStateOf("") }
        var lon by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { setLoc = false },
            title = { Text("Set my location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lat, onValueChange = { lat = it },
                        label = { Text("latitude") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = lon, onValueChange = { lon = it },
                        label = { Text("longitude") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lat.toDoubleOrNull()?.let { la -> lon.toDoubleOrNull()?.let { lo -> vm.setOwnLocation(la, lo) } }
                    setLoc = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { setLoc = false }) { Text("Cancel") } },
        )
    }
}