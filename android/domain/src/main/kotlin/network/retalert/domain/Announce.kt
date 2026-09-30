package network.retalert.domain

import kotlin.math.max
import kotlin.math.min

/** Auto-announce interval bounds (seconds). Min 30 min enforced. Parity. */
const val ANNOUNCE_MIN_INTERVAL = 1800.0      // 30 minutes
const val ANNOUNCE_MAX_INTERVAL = 43200.0    // 12 hours
val ANNOUNCE_PRESET_VALUES = listOf(3600.0, 7200.0, 10800.0)   // 1h, 2h, 3h
val ANNOUNCE_PRESETS = mapOf(3600.0 to "1h", 7200.0 to "2h", 10800.0 to "3h")

/** Clamp a user auto-announce interval to [30min, 12h]; default 30min. */
fun clampAnnounceInterval(seconds: Double?): Double =
    if (seconds == null) ANNOUNCE_MIN_INTERVAL
    else max(ANNOUNCE_MIN_INTERVAL, min(ANNOUNCE_MAX_INTERVAL, seconds))

/** Owns the auto-announce loop. [announceFn] called on each tick. Mirrors AnnounceEngine. */
class AnnounceEngine(
    private val announceFn: () -> Unit,
    private val clock: Clock = RealClock,
) {
    @Volatile private var auto = false
    private var intervalSec: Double = ANNOUNCE_MIN_INTERVAL
    private var thread: Thread? = null
    @Volatile private var running = false
    private var lastAnnounceMono: Double = 0.0

    val isAuto: Boolean get() = auto
    val interval: Double get() = intervalSec
    val lastAnnounceMonotonic: Double get() = lastAnnounceMono

    /** Fire one announce immediately (manual send). */
    fun announceNow() {
        announceFn()
        lastAnnounceMono = clock.monotonic()
    }

    /** Enable/disable auto-announce. Returns the effective interval. */
    fun setAuto(enabled: Boolean, interval: Double? = null): Double {
        if (interval != null) intervalSec = clampAnnounceInterval(interval)
        auto = enabled
        if (auto) start() else stop()
        return intervalSec
    }

    /** Stop the auto loop (daemon shutdown). */
    fun stop() { auto = false; stopLoop() }

    private fun start() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({ loop() }, "retalert-announce").apply { isDaemon = true; start() }
    }

    private fun stopLoop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    private fun loop() {
        // Announce once immediately, then every `interval` seconds.
        while (running && auto) {
            runCatching { announceNow() }   // never let the loop die on transport error
            var slept = 0.0
            while (running && auto && slept < intervalSec) {
                Thread.sleep(500)
                slept += 0.5
            }
        }
    }
}