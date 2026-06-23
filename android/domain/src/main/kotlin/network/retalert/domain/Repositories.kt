package network.retalert.domain

/** Repository contracts. Defined in :domain so :data (Room impls), :reticulum
 *  (service), and :app (UI/ViewModels) all build against the same seam in
 *  parallel. :data provides the concrete Room-backed implementations.
 *
 *  Synchronous signatures mirror the in-memory stores; :data wraps Room calls
 *  on a background dispatcher and :app ViewModels call them from viewModelScope.
 *  Live-track state (LiveTrackStore) and Discover cache stay in-memory (ephemeral),
 *  so they have no repository here — only their *persisted* subsets do (starred,
 *  key combos). */

interface ContactRepository {
    fun list(): List<Contact>
    fun get(hash: String): Contact?
    fun add(hash: String, name: String)
    fun remove(hash: String): Boolean
}

interface GroupRepository {
    fun list(): List<Group>
    fun get(name: String): Group?
    fun members(name: String): List<String>
    fun create(name: String, members: List<String>): Boolean
    fun remove(name: String): Boolean
    fun addMember(name: String, hash: String): Boolean
    fun removeMember(name: String, hash: String): Boolean
}

interface PresetRepository {
    fun list(): List<Preset>
    fun get(id: String): Preset?
    fun byName(name: String): Preset?
    fun put(preset: Preset): Preset
    fun remove(id: String): Boolean
}

interface InboxRepository {
    fun list(): List<InboxEntry>
    fun get(alertId: String): InboxEntry?
    fun record(alertId: String, sourceHash: String, severity: String, text: String, receivedAt: Double? = null): InboxEntry
    fun remove(alertId: String): Boolean
    fun clear(): Int
    fun prune(now: Double? = null): Int
}

/** Settings is a single live snapshot; repo loads/saves it. Mutations go through
 *  the returned Settings instance then [save]. */
interface SettingsRepository {
    fun load(): Settings
    fun save(settings: Settings)
}

/** Outgoing alerts awaiting ack/delivery (the outbox). Backed by Room; the live
 *  AckTracker/RetryQueue run in-memory in :reticulum and persist via this repo. */
interface OutboxRepository {
    fun enqueue(alert: Alert)
    fun pending(): List<Alert>
    fun remove(alertId: String)
    /** Per-recipient ack states for one alert (alertId -> recipientHex -> state). */
    fun ackStates(alertId: String): Map<String, String>
    fun setAckState(alertId: String, recipient: String, state: String)
}

/** Persisted subset of Discover: starred peer hashes (cache itself is ephemeral). */
interface StarredRepository {
    fun starred(): List<String>
    fun star(hash: String): Boolean
    fun unstar(hash: String): Boolean
}

/** Persisted hardware-key combos. */
interface KeyComboRepository {
    fun list(): List<KeyCombo>
    fun save(combos: List<KeyCombo>)
}