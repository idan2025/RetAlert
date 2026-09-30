@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import network.retalert.domain.Fix
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos

private val RADIUS_OPTIONS = listOf(5, 10, 20, 50, 100)
private val SHARE_MINUTES = listOf(15, 30, 60, 120)
private const val PEER_COLOR = 0xFFC62828.toInt()   // red
private const val STALE_COLOR = 0xFF8E8E8E.toInt()  // grey
private const val MAX_TILES = 20_000
private val LOCATION_PERMS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasLocationPermission(ctx: Context) = LOCATION_PERMS.any {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

/** A dot with the peer's name under it. */
private fun labelledDot(ctx: Context, label: String, color: Int): Drawable {
    val d = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = 12f * d; typeface = Typeface.DEFAULT_BOLD
    }
    val halo = Paint(text).apply { this.color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f * d }
    val dot = 16f * d
    val w = maxOf(dot, text.measureText(label) + 8f * d).toInt()
    val h = (dot + text.textSize + 8f * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    c.drawCircle(cx, dot / 2, dot / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() })
    c.drawCircle(cx, dot / 2, dot / 2 - 2.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    val tx = cx - text.measureText(label) / 2
    val ty = dot + text.textSize + 2f * d
    c.drawText(label, tx, ty, halo)
    c.drawText(label, tx, ty, text)
    return BitmapDrawable(ctx.resources, bmp)
}

/** Own position: blue dot with a white ring. */
private fun ownDot(ctx: Context): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = (22f * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    c.drawCircle(r, r, r - 3f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() })
    return bmp
}

/** Bounding box of roughly [radiusKm] around [c]. */
private fun radiusBox(c: GeoPoint, radiusKm: Double): BoundingBox {
    val dLat = radiusKm / 111.32
    val dLon = radiusKm / (111.32 * cos(Math.toRadians(c.latitude)).coerceAtLeast(0.01))
    return BoundingBox(
        (c.latitude + dLat).coerceAtMost(85.0), (c.longitude + dLon).coerceAtMost(180.0),
        (c.latitude - dLat).coerceAtLeast(-85.0), (c.longitude - dLon).coerceAtLeast(-180.0),
    )
}

@Composable
fun MapScreen(vm: MapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var shareDialog by remember { mutableStateOf(false) }
    var offlineDialog by remember { mutableStateOf(false) }
    var setLocDialog by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(hasLocationPermission(ctx)) }

    // One MapView + own-location overlay for this screen's lifetime; paused and
    // detached with it (osmdroid tile threads otherwise leak on every visit).
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    val myLocation = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(ctx), mapView).apply {
            // osmdroid's default arrow is pale and easy to lose; use a clear blue dot.
            val dot = ownDot(ctx)
            setPersonIcon(dot)
            setDirectionIcon(dot)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
        }
    }
    DisposableEffect(mapView) {
        mapView.overlays.add(myLocation)
        mapView.onResume()
        onDispose {
            myLocation.disableMyLocation()
            mapView.onPause()
            mapView.onDetach()
        }
    }
    LaunchedEffect(permitted) { if (permitted) myLocation.enableMyLocation() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permitted = grants.values.any { it }
        if (!permitted) vm.showMessage("location permission denied — your position can't be shown or shared")
    }
    LaunchedEffect(Unit) { if (!permitted) permLauncher.launch(LOCATION_PERMS) }

    // Feed own position to the VM (distance readouts) and center once on first fix.
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(permitted) {
        while (true) {
            myLocation.myLocation?.let { p ->
                vm.onOwnLocation(Fix(p.latitude, p.longitude, accuracy = myLocation.lastFix?.accuracy?.toDouble(), source = "gps"))
                if (!centered && state.followed == null) {
                    mapView.controller.setZoom(15.0)
                    mapView.controller.animateTo(p)
                    centered = true
                }
            }
            delay(2000)
        }
    }
    LaunchedEffect(state.peers.isNotEmpty()) {
        val first = state.peers.firstOrNull()
        if (!centered && first != null) {
            mapView.controller.setZoom(14.0)
            mapView.controller.setCenter(GeoPoint(first.fix.lat, first.fix.lon))
            centered = true
        }
    }
    LaunchedEffect(state.flash) {
        if (state.flash.isNotEmpty()) {
            snackbar.showSnackbar(state.flash)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { map ->
                map.overlays.removeAll { it is Marker || it is Polyline }
                state.peers.forEach { p ->
                    val color = if (p.stale(state.now)) STALE_COLOR else PEER_COLOR
                    if (p.trail.size > 1) {
                        map.overlays.add(
                            Polyline(map).apply {
                                setPoints(p.trail.map { GeoPoint(it.lat, it.lon) })
                                outlinePaint.color = color
                                outlinePaint.strokeWidth = 6f
                                outlinePaint.alpha = 160
                            },
                        )
                    }
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(p.fix.lat, p.fix.lon)
                            title = p.name
                            snippet = ago(p.ageS(state.now))
                            icon = labelledDot(map.context, p.name, color)
                            setAnchor(Marker.ANCHOR_CENTER, 0.2f)
                            setOnMarkerClickListener { _, _ -> vm.follow(p.hash); true }
                        },
                    )
                }
                // Follow mode keeps the followed peer centered (zoom untouched).
                state.followed?.let { h ->
                    state.peers.firstOrNull { it.hash == h }?.let { p ->
                        map.controller.animateTo(GeoPoint(p.fix.lat, p.fix.lon))
                    }
                }
                map.invalidate()
            },
        )

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.share.active) ShareBanner(state, onStop = vm::stopShare)
            state.downloadProgress?.let { (done, total) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Downloading offline map $done / $total", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // Right-hand actions.
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                SmallFloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More map options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Offline maps") },
                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                        onClick = { menu = false; offlineDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Set my location manually") },
                        leadingIcon = { Icon(Icons.Filled.EditLocationAlt, null) },
                        onClick = { menu = false; setLocDialog = true },
                    )
                }
            }
            SmallFloatingActionButton(onClick = {
                if (!permitted) permLauncher.launch(LOCATION_PERMS)
                else {
                    vm.unfollow()
                    val p = myLocation.myLocation ?: state.ownFix?.let { GeoPoint(it.lat, it.lon) }
                    if (p == null) vm.showMessage("waiting for a GPS fix…")
                    else { mapView.controller.setZoom(16.0); mapView.controller.animateTo(p) }
                }
            }) { Icon(Icons.Filled.MyLocation, "Center on my location") }
            SmallFloatingActionButton(
                onClick = { if (!permitted) permLauncher.launch(LOCATION_PERMS) else shareDialog = true },
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) { Icon(Icons.Filled.ShareLocation, "Share my live location") }
        }

        PeersPanel(
            state = state,
            distanceTo = vm::distanceTo,
            onSelect = { p ->
                vm.follow(p.hash)
                mapView.controller.animateTo(GeoPoint(p.fix.lat, p.fix.lon))
            },
            onUnfollow = vm::unfollow,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (shareDialog) {
        ShareDialog(
            state = state,
            onDismiss = { shareDialog = false },
            onShare = { hashes, minutes -> shareDialog = false; vm.startShare(hashes, minutes) },
        )
    }
    if (offlineDialog) {
        OfflineDialog(
            state = state,
            onDismiss = { offlineDialog = false },
            onDownload = { radius ->
                offlineDialog = false
                val center = state.ownFix?.let { GeoPoint(it.lat, it.lon) }
                    ?: GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
                val bbox = radiusBox(center, radius.toDouble())
                val cm = CacheManager(mapView)
                val zoomMin = mapView.zoomLevelDouble.toInt().coerceIn(8, 14)
                val zoomMax = (zoomMin + 3).coerceAtMost(16)
                val total = cm.possibleTilesInArea(bbox, zoomMin, zoomMax)
                if (total > MAX_TILES) {
                    vm.onDownloadFailed("$total tiles is too many — zoom out or pick a smaller radius")
                } else {
                    val name = "$radius km around %.3f, %.3f".format(center.latitude, center.longitude)
                    vm.beginDownload(radius, total)
                    cm.downloadAreaAsync(ctx.applicationContext, bbox, zoomMin, zoomMax,
                        object : CacheManager.CacheManagerCallback {
                            override fun downloadStarted() {}
                            override fun setPossibleTilesInArea(possible: Int) {}
                            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                vm.onDownloadProgress(progress)
                            }
                            override fun onTaskComplete() { vm.onDownloadComplete(name, radius) }
                            override fun onTaskFailed(err: Int) { vm.onDownloadFailed("$err tiles failed") }
                        },
                    )
                }
            },
        )
    }
    if (setLocDialog) SetLocationDialog(onDismiss = { setLocDialog = false }, onSet = { la, lo -> setLocDialog = false; vm.setOwnLocation(la, lo) })
}

@Composable
private fun ShareBanner(state: MapUiState, onStop: () -> Unit) {
    val s = state.share
    val minsLeft = ((s.untilEpoch - state.now) / 60).toInt().coerceAtLeast(0)
    val names = s.recipients.map { h -> state.contacts.firstOrNull { it.hash == h }?.name ?: h.take(6) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sharing live location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "with ${names.joinToString()} · $minsLeft min left" +
                        if (s.lastSentEpoch > 0) " · updated ${ago(state.now - s.lastSentEpoch)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.problem.isNotEmpty()) {
                    Text(s.problem, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop") }
        }
    }
}

@Composable
private fun PeersPanel(
    state: MapUiState,
    distanceTo: (Fix) -> String?,
    onSelect: (PeerUi) -> Unit,
    onUnfollow: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.peers.isEmpty()) {
                Text("No one is sharing their location with you yet.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "People appear here when they send an alert with location or share it live.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Text("People (${state.peers.size})", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(state.peers, key = { it.hash }) { p ->
                    val followed = p.hash == state.followed
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(p) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            Modifier.size(12.dp),
                            shape = CircleShape,
                            color = androidx.compose.ui.graphics.Color(if (p.stale(state.now)) STALE_COLOR else PEER_COLOR),
                        ) {}
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (followed) FontWeight.Bold else null)
                            Text(
                                listOfNotNull(distanceTo(p.fix), ago(p.ageS(state.now))).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (followed) TextButton(onClick = onUnfollow) { Text("Unfollow") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareDialog(state: MapUiState, onDismiss: () -> Unit, onShare: (List<String>, Int) -> Unit) {
    val picked = remember { mutableStateListOf<String>().apply { addAll(state.share.recipients) } }
    var minutes by remember { mutableStateOf(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share live location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.contacts.isEmpty()) {
                    Text("Add contacts first — only contacts can receive your location.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(state.contacts, key = { it.hash }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable { if (c.hash in picked) picked.remove(c.hash) else picked.add(c.hash) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = c.hash in picked, onCheckedChange = null)
                                Text(c.name, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("For how long", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SHARE_MINUTES.forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(picked.toList(), minutes) }, enabled = picked.isNotEmpty()) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OfflineDialog(state: MapUiState, onDismiss: () -> Unit, onDownload: (Int) -> Unit) {
    var radius by remember { mutableStateOf(10) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline maps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Save map tiles around your position (or the map center) so the map still works without internet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RADIUS_OPTIONS.forEach { r ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("$r km") })
                    }
                }
                if (state.offlineAreas.isNotEmpty()) {
                    Text("Saved this session", style = MaterialTheme.typography.labelLarge)
                    state.offlineAreas.forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(radius) }, enabled = state.downloadProgress == null) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SetLocationDialog(onDismiss: () -> Unit, onSet: (Double, Double) -> Unit) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set my location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lon, { lon = it }, label = { Text("longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val la = lat.trim().toDoubleOrNull(); val lo = lon.trim().toDoubleOrNull()
                if (la != null && lo != null) onSet(la, lo) else onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

