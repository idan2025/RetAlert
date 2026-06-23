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