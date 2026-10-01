package network.retalert.domain

/** One contact: destination hash + display name. Mirrors `Contact`. */
data class Contact(val hash: String, val name: String)

/** In-memory contact list keyed by destination hash. :data provides Room persistence.
 *  Adding an existing hash updates its name in place (idempotent). */
class Contacts {
    private val entries = LinkedHashMap<String, String>()

    fun add(hashHex: String, name: String) { entries[hashHex.lowercase().trim()] = name }
    fun remove(hashHex: String): Boolean = entries.remove(hashHex.lowercase().trim()) != null
    fun list(): List<Contact> = entries.map { (h, n) -> Contact(h, n) }
    fun get(hashHex: String): Contact? =
        entries[hashHex.lowercase().trim()]?.let { Contact(hashHex.lowercase().trim(), it) }
    fun contains(hashHex: String): Boolean = entries.containsKey(hashHex.lowercase().trim())
    fun clear() = entries.clear()
}

/** User receive-side settings. Mirrors `Settings`. Allowlist > denylist >
 *  receive-only-from-contacts toggle > open mode. */
class Settings(
    var receiveOnlyFromContacts: Boolean = true,
    var allowlist: MutableSet<String> = mutableSetOf(),
    var denylist: MutableSet<String> = mutableSetOf(),
    var distanceUnits: String = "km",   // "km" | "mi"
    /** Interfaces of RetAlert's own stack (Interfaces screen). AutoInterface
     *  on by default. Unused while attached to a shared instance. */
    var interfaces: MutableList<IfaceConfig> = defaultInterfaces(),
    /** Attach to another app's shared RNS instance (Columba, Sideband…) on
     *  127.0.0.1:[sharedInstancePort] when one is listening; else run standalone. */
    var useSharedInstance: Boolean = true,
    var sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
    /** Panic (no 'default' preset) also starts live location sharing. */
    var panicShareLocation: Boolean = true,
    /** Seconds between live location updates (over LoRa the throttle wins). */
    var liveShareIntervalS: Double = 15.0,
    /** How long an emergency live share runs before stopping by itself. */
    var liveShareMinutes: Int = 60,
    var autoAnnounce: Boolean = false,
    var announceInterval: Double = ANNOUNCE_MIN_INTERVAL,
    /** Incoming alerts ring as an alarm: alarm stream at full volume plus
     *  vibration, through silent / vibrate mode and Do Not Disturb. */
    var alarmOverrideSilent: Boolean = true,
) {
    /** Set distance units, normalising to km|mi. */
    fun applyDistanceUnits(units: String) { distanceUnits = if (units == "mi") "mi" else "km" }

    fun allow(hashHex: String) {
        val h = hashHex.lowercase().trim()
        allowlist.add(h); denylist.remove(h)
    }
    fun deny(hashHex: String) {
        val h = hashHex.lowercase().trim()
        denylist.add(h); allowlist.remove(h)
    }
    /** Add or replace (by id) an interface. Returns a user-facing error, or null. */
    fun upsertInterface(c: IfaceConfig): String? {
        validateIface(c)?.let { return it }
        if (c.type in IfaceType.SINGLETON && interfaces.any { it.type == c.type && it.id != c.id }) {
            return "only one ${IfaceType.label(c.type)} interface is supported"
        }
        if (interfaces.any { it.id != c.id && it.name.trim().equals(c.name.trim(), ignoreCase = true) }) {
            return "another interface is already called '${c.name.trim()}'"
        }
        val i = interfaces.indexOfFirst { it.id == c.id }
        if (i >= 0) interfaces[i] = c else interfaces.add(c)
        return null
    }

    fun removeInterface(id: String): Boolean = interfaces.removeAll { it.id == id }

    fun setInterfaceEnabled(id: String, enabled: Boolean): Boolean {
        val i = interfaces.indexOfFirst { it.id == id }
        if (i < 0) return false
        interfaces[i] = interfaces[i].copy(enabled = enabled)
        return true
    }

    fun forget(hashHex: String) {
        val h = hashHex.lowercase().trim()
        allowlist.remove(h); denylist.remove(h)
    }
}

/** An ad-hoc group: a named subset of contact destination hashes. */
data class Group(val name: String, val members: List<String> = emptyList())

/** In-memory named groups of destination hashes, keyed by group name. :data persists. */
class Groups {
    private val groups = LinkedHashMap<String, MutableList<String>>()

    /** Create a new group. Returns false if name exists. */
    fun create(name: String, members: List<String>): Boolean {
        val n = name.trim()
        if (n in groups) return false
        groups[n] = members.map { it.lowercase().trim() }.toMutableList()
        return true
    }

    fun remove(name: String): Boolean = groups.remove(name) != null

    /** Add a member to a group (idempotent). Returns false if group missing. */
    fun addMember(name: String, hashHex: String): Boolean {
        val n = name.trim()
        val list = groups[n] ?: return false
        val h = hashHex.lowercase().trim()
        if (h !in list) list.add(h)
        return true
    }

    fun removeMember(name: String, hashHex: String): Boolean {
        val n = name.trim()
        val list = groups[n] ?: return false
        return list.remove(hashHex.lowercase().trim())
    }

    fun list(): List<Group> = groups.map { (n, m) -> Group(n, m.toList()) }

    fun get(name: String): Group? = groups[name.trim()]?.let { Group(name.trim(), it.toList()) }

    fun members(name: String): List<String> = get(name)?.members ?: emptyList()

    fun clear() = groups.clear()
}
/** Default RNS shared-instance port (Reticulum `shared_instance_port`). */
const val DEFAULT_SHARED_INSTANCE_PORT = 37428

/** Parse "host:port" (IPv6 as "[addr]:port"). Returns the normalised spec or null. */
fun normalizeTcpSpec(spec: String): String? = parseTcpSpec(spec)?.let { (h, p) ->
    if (':' in h) "[$h]:$p" else "$h:$p"
}

/** Split a "host:port" spec into (host, port); null when malformed. */
fun parseTcpSpec(spec: String): Pair<String, Int>? {
    val t = spec.trim()
    val idx = t.lastIndexOf(':')
    if (idx <= 0 || idx == t.length - 1) return null
    val host = t.substring(0, idx).removePrefix("[").removeSuffix("]").trim()
    val port = t.substring(idx + 1).toIntOrNull() ?: return null
    if (host.isEmpty() || host.any { it.isWhitespace() } || port !in 1..65535) return null
    return host to port
}

private val HASH_RE = Regex("^[0-9a-f]{32}$")

/** Normalise a user-entered destination hash (strips `<>`, `:` and spaces). Null if not 16 bytes of hex. */
fun normalizeHash(input: String): String? =
    input.trim().removePrefix("<").removeSuffix(">").replace(":", "").replace(" ", "").lowercase()
        .takeIf { HASH_RE.matches(it) }
