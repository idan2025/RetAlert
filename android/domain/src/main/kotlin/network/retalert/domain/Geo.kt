package network.retalert.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/** User-configurable LoRa live-share throttle bounds (seconds). Parity. */
const val LORA_THROTTLE_MIN = 10.0
const val LORA_THROTTLE_MAX = 3600.0
const val LORA_THROTTLE_DEFAULT = 60.0
val LORA_THROTTLE_PRESETS = listOf(15, 30, 60, 120, 180)

/** Clamp a user LoRa throttle interval to [min, max]; default if null. */
fun clampLoraThrottle(seconds: Double?): Double =
    if (seconds == null) LORA_THROTTLE_DEFAULT
    else max(LORA_THROTTLE_MIN, min(LORA_THROTTLE_MAX, seconds))

/** One GPS fix. Mirrors `Fix` in geo_tracker.py. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Double? = null,
    val altitude: Double? = null,
    var timestamp: Double = 0.0,
    val source: String = "manual",
) {
    init {
        if (timestamp == 0.0) timestamp = RealClock.nowEpoch()
    }

    /** ``geo:lat,lon`` URI (RFC 5870-ish). Compact for low-bandwidth links. */
    val geoUri: String get() = "geo:$lat,$lon"
}

/** Base class. ``getFix`` returns a Fix or null if unavailable. */
interface FixSource {
    val name: String
    fun getFix(): Fix?
}

/** Returns a fixed/injected fix. Used for tests and manual coords. */
class ManualFixSource(
    private val lat: Double,
    private val lon: Double,
    private val accuracy: Double? = null,
    private val altitude: Double? = null,
) : FixSource {
    override val name = "manual"
    private val base = Fix(lat, lon, accuracy, altitude, source = "manual")
    override fun getFix(): Fix {
        // Refresh timestamp each call so live-share emits fresh fixes.
        base.timestamp = RealClock.nowEpoch()
        return base
    }
}

/** Wraps another source, caching the last successful fix (source = "last_known"). */
class LastKnownFixSource(private val inner: FixSource) : FixSource {
    override val name = "last_known"
    private var last: Fix? = null
    override fun getFix(): Fix? {
        val fix = inner.getFix()
        if (fix != null) { last = fix; return fix }
        val l = last ?: return null
        return Fix(l.lat, l.lon, l.accuracy, l.altitude, source = "last_known")
    }
}

/** Android GPS via platform API — implemented in the platform module (Phase 4).
 *  Domain holds only the seam; the concrete source lives where FusedLocation is. */
class AndroidFixSourceStub : FixSource {
    override val name = "android"
    override fun getFix(): Fix? = throw NotImplementedError("AndroidFixSource — platform build step")
}

class LinuxFixSourceStub : FixSource {
    override val name = "linux"
    override fun getFix(): Fix? = throw NotImplementedError("LinuxFixSource — desktop backend build step")
}

/** Produces GPS fixes: one-shot or live-share (periodic thread). Mirrors GeoTracker. */
class GeoTracker(
    private val fixSource: FixSource,
    private val onFix: ((Fix) -> Unit)? = null,
    private val clock: Clock = RealClock,
) {
    @Volatile private var sharing = false
    private var thread: Thread? = null
    private var intervalSec: Double = 5.0

    val isSharing: Boolean get() = sharing
    val interval: Double get() = intervalSec

    /** One-shot fix; invokes [onFix] if set. */
    fun oneShot(): Fix? {
        val fix = fixSource.getFix()
        if (fix != null) onFix?.invoke(fix)
        return fix
    }

    /** Emit a fix every [interval] seconds, calling [sendFn] and [onFix] each tick. */
    fun startLiveShare(interval: Double, sendFn: ((Fix) -> Unit)? = null) {
        if (sharing) return
        intervalSec = max(1.0, interval)
        sharing = true
        thread = Thread({ shareLoop(sendFn) }, "retalert-geoshare").apply { isDaemon = true; start() }
    }

    fun stopLiveShare() {
        sharing = false
        thread?.join((intervalSec + 1).toLongMillis())
        thread = null
    }

    private fun shareLoop(sendFn: ((Fix) -> Unit)?) {
        while (sharing) {
            val fix = fixSource.getFix()
            if (fix != null) {
                onFix?.let { runCatching { it(fix) } }
                sendFn?.let { runCatching { it(fix) } }
            }
            var slept = 0.0
            while (sharing && slept < intervalSec) {
                Thread.sleep(200)
                slept += 0.2
            }
        }
    }

    private fun Double.toLongMillis() = (this * 1000).toLong()
}

// --- geo helpers -------------------------------------------------------

const val EARTH_RADIUS_M = 6371000.0

/** Distance in metres between two lat/lon points. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dphi = Math.toRadians(lat2 - lat1)
    val dlmb = Math.toRadians(lon2 - lon1)
    val a = sin(dphi / 2) * sin(dphi / 2) + cos(p1) * cos(p2) * sin(dlmb / 2) * sin(dlmb / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}