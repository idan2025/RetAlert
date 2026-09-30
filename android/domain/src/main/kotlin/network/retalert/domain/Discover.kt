package network.retalert.domain

/** One announced destination heard on the mesh. Mirrors DiscoveredPeer. */
data class DiscoveredPeer(
    val hash: String,             // destination hash, hex, no colons
    val displayName: String = "",
    val aspect: String = "",
    val appName: String = "",     // first aspect segment, e.g. "lxmf"
    val lastHeard: Double = 0.0,
    val firstHeard: Double = 0.0,
    var starred: Boolean = false,
)

const val ASPECT_LXMF_DELIVERY = "lxmf.delivery"

/** Best-effort decode of an LXMF delivery announce's display name. Parity. */
fun decodeDisplayName(appData: ByteArray?, aspect: String): String {
    if (appData == null || appData.isEmpty()) return ""
    if (aspect != ASPECT_LXMF_DELIVERY) return "" // non-LXMF; app-defined format
    return try {
        val data = MsgPack.unpack(appData)
        if (data is List<*> && data.isNotEmpty()) {
            when (val nameBytes = data[0]) {
                is ByteArray -> String(nameBytes, Charsets.UTF_8)
                is String -> nameBytes
                else -> ""
            }
        } else ""
    } catch (_: Exception) { "" }
}

fun appNameFromAspect(aspect: String): String =
    if (aspect.isEmpty()) "" else aspect.substringBefore(".")

/** In-memory cache of heard announces + a starred set (starred persists via :data).
 *  Mirrors `Discover`. The RNS AnnounceHandler shim lives in :reticulum. */
class Discover(
    private val clock: Clock = RealClock,
    private val starred: MutableSet<String> = mutableSetOf(),
) : PeerDiscover {
    private val peers = LinkedHashMap<String, DiscoveredPeer>()
    private val lock = Any()

    /** Insert/refresh a heard announce. Returns the stored peer. */
    fun heard(hashHex: String, appData: ByteArray?, aspect: String = ASPECT_LXMF_DELIVERY): DiscoveredPeer {
        val h = hashHex.lowercase().trim()
        val name = decodeDisplayName(appData, aspect)
        val now = clock.nowEpoch()
        return synchronized(lock) {
            val existing = peers[h]
            if (existing == null) {
                val peer = DiscoveredPeer(
                    hash = h, displayName = name, aspect = aspect,
                    appName = appNameFromAspect(aspect),
                    lastHeard = now, firstHeard = now,
                    starred = h in starred,
                )
                peers[h] = peer
                peer
            } else {
                val updated = existing.copy(
                    displayName = name.ifBlank { existing.displayName },
                    aspect = aspect.ifBlank { existing.aspect },
                    appName = appNameFromAspect(aspect).ifBlank { existing.appName },
                    lastHeard = now,
                    starred = h in starred,
                )
                peers[h] = updated
                updated
            }
        }
    }

    /** Heard peers, freshest first. */
    fun list(): List<DiscoveredPeer> = synchronized(lock) {
        peers.values.sortedByDescending { it.lastHeard }
    }

    override fun get(sourceHash: String): DiscoveredPeer? = synchronized(lock) {
        peers[sourceHash.lowercase().trim()]
    }

    fun starredHashes(): List<String> = synchronized(lock) { starred.sorted() }

    fun star(hashHex: String): Boolean = synchronized(lock) {
        val h = hashHex.lowercase().trim()
        starred.add(h)
        peers[h]?.let { peers[h] = it.copy(starred = true); true } ?: false
    }

    fun unstar(hashHex: String): Boolean = synchronized(lock) {
        val h = hashHex.lowercase().trim()
        val existed = starred.remove(h)
        peers[h]?.let { peers[h] = it.copy(starred = false) }
        existed
    }

    fun clear(): Int = synchronized(lock) { val n = peers.size; peers.clear(); n }

    fun remove(hashHex: String): Boolean = synchronized(lock) {
        peers.remove(hashHex.lowercase().trim()) != null
    }
}