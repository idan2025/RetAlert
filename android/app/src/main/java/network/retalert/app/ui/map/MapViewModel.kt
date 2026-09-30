package network.retalert.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val liveGps: Boolean = false,
    val flash: String = "",
)

/** Map screen: osmdroid + peer markers from LiveTrackStore (fed by inbound
 *  `geo:` messages), tap-to-follow, km/mi distance, own position distinct from
 *  peers, FusedLocation one-shot + continuous GPS, manual set-location
 *  fallback, and offline-area download (osmdroid CacheManager from the screen). */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val tracks: LiveTrackStore,
    private val settingsRepo: SettingsRepository,
    private val fixSource: FixSource,
) : ViewModel() {

    /** Continuous own-position updates. Runs its own daemon thread, so the
     *  blocking `FixSource.getFix` calls are safe there. */
    private val geoTracker = GeoTracker(fixSource, onFix = ::onLiveFix)

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val units = runCatching { settingsRepo.load().distanceUnits }.getOrDefault(_state.value.units)
                _state.update { it.copy(tracks = tracks.list(), followed = tracks.followed(), units = units) }
                delay(2000)
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(tracks = tracks.list(), followed = tracks.followed()) }
    }

    /** Follow a peer by source hash (tap-to-follow). */
    fun follow(hash: String) {
        tracks.follow(hash)
        refresh()
    }

    fun unfollow() {
        tracks.unfollow()
        refresh()
    }

    /** Manual set-location (no-GPS fallback). */
    fun setOwnLocation(lat: Double, lon: Double) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            _state.update { it.copy(flash = "invalid coordinates") }
            return
        }
        _state.update { it.copy(ownFix = Fix(lat, lon, source = "manual"), flash = "own location set") }
    }

    /** One-shot GPS fix from [fixSource] (blocking call, dispatched off the main thread). */
    fun useMyLocation() = viewModelScope.launch(Dispatchers.IO) {
        val fix = runCatching { fixSource.getFix() }.getOrNull()
        _state.update {
            it.copy(
                ownFix = fix ?: it.ownFix,
                flash = if (fix == null) "location unavailable (permission or GPS off?)" else "own location from ${fix.source}",
            )
        }
    }

    /** Start/stop continuous own-position updates. */
    fun toggleLiveGps(intervalS: Double = 5.0) = viewModelScope.launch(Dispatchers.IO) {
        if (geoTracker.isSharing) {
            geoTracker.stopLiveShare()
            _state.update { it.copy(liveGps = false, flash = "live GPS stopped") }
        } else {
            geoTracker.startLiveShare(intervalS)
            _state.update { it.copy(liveGps = true, flash = "live GPS every ${geoTracker.interval.toInt()}s") }
        }
    }

    private fun onLiveFix(f: Fix) {
        _state.update { it.copy(ownFix = f) }
    }

    fun showMessage(msg: String) = _state.update { it.copy(flash = msg) }

    /** Distance from own position to a fix, formatted per settings. */
    fun distanceTo(fix: Fix): String? {
        val own = _state.value.ownFix ?: return null
        val km = haversineM(own.lat, own.lon, fix.lat, fix.lon) / 1000.0
        return if (_state.value.units == "mi") "%.1f mi".format(km / 1.609344) else "%.1f km".format(km)
    }

    // -- offline tile download (driven by osmdroid CacheManager from the screen) --

    fun beginDownload(radiusKm: Int, total: Int, name: String) {
        _state.update { it.copy(downloadProgress = 0 to total, flash = "caching ~$total tiles (${radiusKm} km radius)") }
    }

    fun onDownloadProgress(done: Int) {
        val total = _state.value.downloadProgress?.second ?: return
        _state.update { it.copy(downloadProgress = done.coerceAtMost(total) to total) }
    }

    fun onDownloadComplete(name: String, radiusKm: Int) {
        val total = _state.value.downloadProgress?.second ?: 0
        _state.update {
            it.copy(
                downloadProgress = null,
                offlineAreas = it.offlineAreas + OfflineArea(name, radiusKm),
                flash = "saved $total tiles offline",
            )
        }
    }

    fun onDownloadFailed(msg: String) {
        _state.update { it.copy(downloadProgress = null, flash = "download failed: $msg") }
    }

    override fun onCleared() {
        if (geoTracker.isSharing) Thread { geoTracker.stopLiveShare() }.start()
    }
}
