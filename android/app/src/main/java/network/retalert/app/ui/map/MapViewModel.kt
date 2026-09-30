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
import network.retalert.app.platform.LocationSharer
import network.retalert.app.platform.ShareState
import network.retalert.domain.Contact
import network.retalert.domain.ContactRepository
import network.retalert.domain.Fix
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.RealClock
import network.retalert.domain.SettingsRepository
import network.retalert.domain.haversineM
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One peer on the map. */
data class PeerUi(
    val hash: String,
    val name: String,
    val fix: Fix,
    val lastUpdated: Double,
    val trail: List<Fix>,
) {
    fun ageS(now: Double) = (now - lastUpdated).coerceAtLeast(0.0)
    fun stale(now: Double) = ageS(now) > STALE_AFTER_S
}

/** One offline-area download entry (tiles cached into the osmdroid cache). */
data class OfflineArea(val name: String, val radiusKm: Int)

data class MapUiState(
    val peers: List<PeerUi> = emptyList(),
    val followed: String? = null,
    val ownFix: Fix? = null,
    val units: String = "km",
    val share: ShareState = ShareState(),
    val contacts: List<Contact> = emptyList(),
    val downloadProgress: Pair<Int, Int>? = null,
    val offlineAreas: List<OfflineArea> = emptyList(),
    val flash: String = "",
    val now: Double = RealClock.nowEpoch(),
)

const val STALE_AFTER_S = 5 * 60.0

/** Map screen: peers sharing live location (from inbound `geo:` messages),
 *  their trails, tap-to-follow with distance/bearing, own position, live
 *  location sharing (emergency or manual) and offline tile downloads. */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val tracks: LiveTrackStore,
    private val settingsRepo: SettingsRepository,
    private val contactRepo: ContactRepository,
    private val sharer: LocationSharer,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                poll()
                delay(2000)
            }
        }
        viewModelScope.launch {
            sharer.state.collect { s -> _state.update { it.copy(share = s, ownFix = s.lastFix ?: it.ownFix) } }
        }
    }

    private fun poll() {
        val contacts = runCatching { contactRepo.list() }.getOrDefault(emptyList())
        val names = contacts.associate { it.hash to it.name }
        val units = runCatching { settingsRepo.load().distanceUnits }.getOrDefault(_state.value.units)
        val peers = tracks.list().map { t ->
            PeerUi(
                hash = t.sourceHash,
                name = names[t.sourceHash] ?: t.displayName.ifBlank { t.sourceHash.take(8) },
                fix = t.fix,
                lastUpdated = t.lastUpdated,
                trail = tracks.trail(t.sourceHash),
            )
        }.sortedBy { it.name.lowercase() }
        _state.update {
            it.copy(
                peers = peers, followed = tracks.followed(), units = units,
                contacts = contacts, now = RealClock.nowEpoch(),
            )
        }
    }

    fun follow(hash: String) {
        tracks.follow(hash)
        _state.update { it.copy(followed = tracks.followed()) }
    }

    fun unfollow() {
        tracks.unfollow()
        _state.update { it.copy(followed = null) }
    }

    /** Own position from the map's location provider. */
    fun onOwnLocation(fix: Fix) = _state.update { it.copy(ownFix = fix) }

    /** Manual set-location (no-GPS fallback). */
    fun setOwnLocation(lat: Double, lon: Double) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            showMessage("invalid coordinates")
            return
        }
        _state.update { it.copy(ownFix = Fix(lat, lon, source = "manual"), flash = "own location set") }
    }

    /** Share live location with chosen contacts for [minutes]. */
    fun startShare(hashes: List<String>, minutes: Int) = viewModelScope.launch(Dispatchers.IO) {
        if (hashes.isEmpty()) {
            showMessage("pick at least one contact")
            return@launch
        }
        val interval = runCatching { settingsRepo.load().liveShareIntervalS }.getOrDefault(15.0)
        sharer.start(hashes, minutes, interval, "manual")
        showMessage("sharing live location with ${hashes.size} contact(s) for $minutes min")
    }

    fun stopShare() {
        sharer.stop()
        showMessage("stopped sharing location")
    }

    fun showMessage(msg: String) = _state.update { it.copy(flash = msg) }
    fun clearMessage() = _state.update { it.copy(flash = "") }

    /** "1.2 km · NE" from own position to [fix], or null without an own fix. */
    fun distanceTo(fix: Fix): String? {
        val own = _state.value.ownFix ?: return null
        val km = haversineM(own.lat, own.lon, fix.lat, fix.lon) / 1000.0
        val dist = when {
            _state.value.units == "mi" -> "%.1f mi".format(km / 1.609344)
            km < 1.0 -> "%.0f m".format(km * 1000)
            else -> "%.1f km".format(km)
        }
        return "$dist ${compass(bearing(own, fix))}"
    }

    // -- offline tile download (driven by osmdroid CacheManager from the screen) --

    fun beginDownload(radiusKm: Int, total: Int) {
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
                flash = "saved $total tiles for offline use",
            )
        }
    }

    fun onDownloadFailed(msg: String) {
        _state.update { it.copy(downloadProgress = null, flash = "download failed: $msg") }
    }
}

/** Initial bearing in degrees from [a] to [b]. */
internal fun bearing(a: Fix, b: Fix): Double {
    val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

internal fun compass(deg: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg + 22.5) / 45.0).toInt() % 8]

/** "just now", "40 s ago", "3 min ago", "2 h ago". */
internal fun ago(seconds: Double): String = when {
    seconds < 10 -> "just now"
    seconds < 60 -> "${seconds.toInt()} s ago"
    seconds < 3600 -> "${(seconds / 60).toInt()} min ago"
    else -> "${(seconds / 3600).toInt()} h ago"
}
