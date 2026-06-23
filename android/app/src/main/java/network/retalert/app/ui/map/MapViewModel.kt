package network.retalert.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.retalert.domain.Discover
import network.retalert.domain.Fix
import network.retalert.domain.FixSource
import network.retalert.domain.GeoTracker
import network.retalert.domain.LiveTrack
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.SettingsRepository
import network.retalert.domain.haversineM
import javax.inject.Inject

/** One offline-area download entry (tiles cached into the osmdroid cache). */
data class OfflineArea(val name: String, val radiusKm: Int)

data class MapUiState(
    val tracks: List<LiveTrack> = emptyList(),
    val followed: String? = null,
    val ownFix: Fix? = null,
    val units: String = "km",
    val downloadProgress: Pair<Int, Int>? = null,
    val offlineAreas: List<OfflineArea> = emptyList(),
    val liveSharing: Boolean = false,
    val flash: String = "",
)

/** Map screen: osmdroid + peer markers from LiveTrackStore, tap-to-follow,
 *  km/mi distance, own-position distinct from peers, FusedLocation one-shot +
 *  live-share, manual set-location fallback, and offline-area download
 *  (real osmdroid CacheManager tile caching driven from the screen). */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val tracks: LiveTrackStore,
    private val settingsRepo: SettingsRepository,
    private val discover: Discover,
    private val fixSource: FixSource,
) : ViewModel() {

    private val settings = settingsRepo.load()

    /** Source hash under which our own live-share fixes are stored in [tracks]. */
    private val selfHash = "self"

    /** GeoTracker for live-share; built on first [toggleLiveShare]. Spawns its own
     *  daemon thread, so the blocking `FixSource.getFix` calls are safe there. */
    private val geoTracker = GeoTracker(fixSource, onFix = ::onLiveFix)

    private val _state = MutableStateFlow(
        MapUiState(
            ownFix = null,
            units = settings.distanceUnits,
            tracks = tracks.list(),
        )
    )
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        // Seed a demo peer so markers render in the standalone build.
        tracks.update("a1b2c3d4e5f6a1b2", Fix(0.01, 0.01), "Alice")
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(2000)
                _state.update { it.copy(tracks = tracks.list(), followed = tracks.followed(), units = settings.distanceUnits) }
            }
        }
    }

    fun refresh() {
        _state.update {
            it.copy(tracks = tracks.list(), followed = tracks.followed(), units = settings.distanceUnits)
        }
    }

    /** Follow a peer by source hash (tap-to-follow). */
    fun follow(hash: String) = viewModelScope.launch {
        tracks.follow(hash)
        refresh()
    }

    fun unfollow() = viewModelScope.launch {
        tracks.unfollow()
        refresh()
    }

    /** Manual set-location (desktop/no-GPS fallback). */
    fun setOwnLocation(lat: Double, lon: Double) = viewModelScope.launch {
        _state.update { it.copy(ownFix = Fix(lat, lon, source = "manual"), flash = "own location set") }
    }

    /** One-shot GPS fix from [fixSource]. Blocking call is dispatched off the main thread. */
    fun useMyLocation() = viewModelScope.launch {
        val fix = withContext(Dispatchers.IO) { fixSource.getFix() }
        _state.update {
            it.copy(
                ownFix = fix,
                flash = if (fix == null) "location unavailable" else "own location from ${fix.source}",
            )
        }
    }

    /** Start/stop live-share via [GeoTracker]. While sharing, each fix updates
     *  [ownFix] and is published to [tracks] under [selfHash]. */
    fun toggleLiveShare(intervalS: Double = 5.0) = viewModelScope.launch {
        if (geoTracker.isSharing) {
            geoTracker.stopLiveShare()
            _state.update { it.copy(liveSharing = false, flash = "live share stopped") }
        } else {
            geoTracker.startLiveShare(intervalS)
            _state.update { it.copy(liveSharing = true, flash = "live share @ ${geoTracker.interval}s") }
        }
    }

    /** GeoTracker callback: publish a fresh fix to own state + the track store. */
    private fun onLiveFix(f: Fix) {
        _state.update { it.copy(ownFix = f) }
        tracks.update(selfHash, f, "me")
    }

    /** Distance from own position to a fix, formatted per settings. */
    fun distanceTo(fix: Fix): String? {
        val own = _state.value.ownFix ?: return null
        val m = haversineM(own.lat, own.lon, fix.lat, fix.lon)
        val km = m / 1000.0
        return if (_state.value.units == "mi") "%.1f mi".format(km / 1.609344) else "%.1f km".format(km)
    }

    // -- offline tile download (driven by osmdroid CacheManager from the screen) --

    /** Begin a download: record the total tile count + area name. The screen's
     *  `CacheManager` calls [onDownloadProgress]/[onDownloadComplete]/[onDownloadFailed]. */
    fun beginDownload(radiusKm: Int, total: Int, name: String) {
        _state.update { it.copy(downloadProgress = 0 to total, offlineAreas = it.offlineAreas, flash = "caching ~$total tiles (${radiusKm}km area)") }
    }

    /** Per-tile progress from the CacheManager callback. */
    fun onDownloadProgress(done: Int) {
        val total = _state.value.downloadProgress?.second ?: return
        _state.update { it.copy(downloadProgress = done.coerceAtMost(total) to total) }
    }

    /** CacheManager finished successfully. */
    fun onDownloadComplete(name: String, radiusKm: Int) {
        val total = _state.value.downloadProgress?.second ?: 0
        _state.update {
            it.copy(
                downloadProgress = null,
                offlineAreas = it.offlineAreas + OfflineArea(name, radiusKm),
                flash = "saved $total tiles offline ($name)",
            )
        }
    }

    /** CacheManager failed. */
    fun onDownloadFailed(msg: String) {
        _state.update { it.copy(downloadProgress = null, flash = "download failed: $msg") }
    }

    fun deleteArea(name: String) = viewModelScope.launch {
        _state.update { it.copy(offlineAreas = it.offlineAreas.filterNot { a -> a.name == name }, flash = "deleted $name") }
    }
}