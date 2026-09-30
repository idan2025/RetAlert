package network.retalert.domain

/** One peer's latest live-share fix. Mirrors LiveTrack. */
data class LiveTrack(
    val sourceHash: String,
    val fix: Fix,
    val displayName: String = "",
    val lastUpdated: Double = 0.0,
)

/** Thread-safe store of live-sharing peers + the followed source. Parity. */
class LiveTrackStore(
    private val clock: Clock = RealClock,
    /** Fixes kept per peer for drawing their trail. */
    private val historySize: Int = 200,
) {
    private val tracks = LinkedHashMap<String, LiveTrack>()
    private val history = HashMap<String, ArrayDeque<Fix>>()
    @Volatile private var followed: String? = null
    private val lock = Any()

    fun update(sourceHash: String, fix: Fix, displayName: String = ""): LiveTrack = synchronized(lock) {
        val h = sourceHash.lowercase().trim()
        val existing = tracks[h]
        val name = displayName.ifBlank { existing?.displayName ?: "" }
        val track = LiveTrack(h, fix, name, clock.nowEpoch())
        tracks[h] = track
        val trail = history.getOrPut(h) { ArrayDeque() }
        val last = trail.lastOrNull()
        if (last == null || last.lat != fix.lat || last.lon != fix.lon) {
            trail.addLast(fix)
            while (trail.size > historySize) trail.removeFirst()
        }
        track
    }

    fun get(sourceHash: String): LiveTrack? = synchronized(lock) {
        tracks[sourceHash.lowercase().trim()]
    }

    fun list(): List<LiveTrack> = synchronized(lock) { tracks.values.toList() }

    /** A peer's recent fixes, oldest first (for drawing a trail). */
    fun trail(sourceHash: String): List<Fix> = synchronized(lock) {
        history[sourceHash.lowercase().trim()]?.toList() ?: emptyList()
    }

    fun remove(sourceHash: String): Boolean = synchronized(lock) {
        val h = sourceHash.lowercase().trim()
        val existed = tracks.remove(h) != null
        history.remove(h)
        if (followed == h) followed = null
        existed
    }

    fun clear(): Int = synchronized(lock) {
        val n = tracks.size; tracks.clear(); history.clear(); followed = null; n
    }

    /** Follow a peer's live track. Returns false if peer not currently sharing. */
    fun follow(sourceHash: String): Boolean = synchronized(lock) {
        val h = sourceHash.lowercase().trim()
        if (h !in tracks) false else { followed = h; true }
    }

    fun unfollow() = synchronized(lock) { followed = null }

    fun followed(): String? = followed

    fun followedTrack(): LiveTrack? = synchronized(lock) {
        followed?.let { tracks[it] }
    }

    /** Drop tracks not updated within [maxAgeSeconds]. Returns count. */
    fun clearStale(maxAgeSeconds: Double): Int = synchronized(lock) {
        val cutoff = clock.nowEpoch() - maxAgeSeconds
        val stale = tracks.entries.filter { it.value.lastUpdated < cutoff }.map { it.key }
        for (h in stale) {
            tracks.remove(h)
            history.remove(h)
            if (followed == h) followed = null
        }
        stale.size
    }
}