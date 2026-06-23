package network.retalert.data

import network.retalert.domain.Alert
import network.retalert.domain.Contact
import network.retalert.domain.Group
import network.retalert.domain.InboxEntry
import network.retalert.domain.InboxRegistry
import network.retalert.domain.KeyCombo
import network.retalert.domain.PAYLOAD_CLASSES
import network.retalert.domain.Preset
import network.retalert.domain.Settings
import network.retalert.domain.randomHex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/* ---------- mappers ---------- */

private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val STR_LIST = ListSerializer(String.serializer())
private val BOOL_MAP = MapSerializer(String.serializer(), Boolean.serializer())

private fun List<String>.toJson(): String = JSON.encodeToString(STR_LIST, this)
private fun String.toListOrNull(): List<String> =
    if (isBlank()) emptyList() else JSON.decodeFromString(STR_LIST, this)

private fun Map<String, Boolean>.toJson(): String = JSON.encodeToString(BOOL_MAP, this)
private fun String.toMapOrNull(): Map<String, Boolean> =
    if (isBlank()) emptyMap() else JSON.decodeFromString(BOOL_MAP, this)

private fun normalizePayload(p: Map<String, Boolean>): Map<String, Boolean> {
    val clean = LinkedHashMap<String, Boolean>()
    for (k in PAYLOAD_CLASSES) clean[k] = p[k] ?: false
    if (clean.values.none { it }) clean["text"] = true
    return clean
}

/* ---------- ContactRepository ---------- */

class RoomContactRepository(private val dao: ContactDao) : network.retalert.domain.ContactRepository {
    private fun norm(hash: String) = hash.lowercase().trim()

    override fun list(): List<Contact> = dao.list().map { Contact(it.hash, it.name) }
    override fun get(hash: String): Contact? = dao.get(norm(hash))?.let { Contact(it.hash, it.name) }

    override fun add(hash: String, name: String) = dao.upsert(ContactEntity(norm(hash), name))

    override fun remove(hash: String): Boolean = dao.delete(norm(hash)) > 0
}

/* ---------- GroupRepository ---------- */

class RoomGroupRepository(private val dao: GroupDao) : network.retalert.domain.GroupRepository {
    private fun normName(name: String) = name.trim()
    private fun normHash(hash: String) = hash.lowercase().trim()

    override fun list(): List<Group> = dao.list().map { Group(it.name, it.members.toListOrNull()) }
    override fun get(name: String): Group? =
        dao.get(normName(name))?.let { Group(it.name, it.members.toListOrNull()) }

    override fun members(name: String): List<String> =
        dao.get(normName(name))?.members?.toListOrNull() ?: emptyList()

    override fun create(name: String, members: List<String>): Boolean {
        val n = normName(name)
        if (dao.exists(n)) return false
        dao.upsert(GroupEntity(n, members.map(::normHash).toJson()))
        return true
    }

    override fun remove(name: String): Boolean = dao.delete(normName(name)) > 0

    override fun addMember(name: String, hash: String): Boolean {
        val n = normName(name)
        val g = dao.get(n) ?: return false
        val list = g.members.toListOrNull().toMutableList()
        val h = normHash(hash)
        if (h !in list) list.add(h)
        dao.upsert(GroupEntity(n, list.toJson()))
        return true
    }

    override fun removeMember(name: String, hash: String): Boolean {
        val n = normName(name)
        val g = dao.get(n) ?: return false
        val list = g.members.toListOrNull().toMutableList()
        val removed = list.remove(normHash(hash))
        if (removed) dao.upsert(GroupEntity(n, list.toJson()))
        return removed
    }
}

/* ---------- PresetRepository ---------- */

class RoomPresetRepository(private val dao: PresetDao) : network.retalert.domain.PresetRepository {
    private fun PresetEntity.toDomain(): Preset = Preset(
        id = id, name = name, severity = severity, text = text,
        recipients = recipients.toListOrNull(), group = group,
        payload = payload.toMapOrNull(), retryInterval = retryInterval,
        maxAttempts = maxAttempts, fanOut = fanOut, loraThrottle = loraThrottle,
    )

    private fun Preset.toEntity(): PresetEntity {
        val normId = id.ifBlank { randomHex(8) }
        return PresetEntity(
            id = normId, name = name, severity = severity, text = text,
            recipients = recipients.toJson(), group = group,
            payload = normalizePayload(payload).toJson(),
            retryInterval = retryInterval, maxAttempts = maxAttempts,
            fanOut = fanOut, loraThrottle = loraThrottle,
        )
    }

    override fun list(): List<Preset> = dao.list().map { it.toDomain() }
    override fun get(id: String): Preset? = dao.get(id)?.toDomain()
    override fun byName(name: String): Preset? = dao.byName(name.trim())?.toDomain()

    override fun put(preset: Preset): Preset {
        val entity = preset.toEntity()
        dao.upsert(entity)
        return Preset(
            id = entity.id, name = preset.name, severity = preset.severity, text = preset.text,
            recipients = preset.recipients, group = preset.group,
            payload = normalizePayload(preset.payload), retryInterval = preset.retryInterval,
            maxAttempts = preset.maxAttempts, fanOut = preset.fanOut, loraThrottle = preset.loraThrottle,
        )
    }

    override fun remove(id: String): Boolean = dao.delete(id) > 0
}

/* ---------- InboxRepository ---------- */

class RoomInboxRepository(private val dao: InboxDao) : network.retalert.domain.InboxRepository {
    private fun InboxEntryEntity.toDomain() = InboxEntry(alertId, sourceHash, severity, text, receivedAt)

    override fun list(): List<InboxEntry> = dao.list().map { it.toDomain() }
    override fun get(alertId: String): InboxEntry? = dao.get(alertId)?.toDomain()

    override fun record(
        alertId: String, sourceHash: String, severity: String, text: String, receivedAt: Double?,
    ): InboxEntry {
        require(alertId.isNotBlank()) { "alert_id required" }
        val t = receivedAt ?: System.currentTimeMillis() / 1000.0
        val entry = InboxEntry(alertId, sourceHash.lowercase(), severity, text, t)
        dao.upsert(
            InboxEntryEntity(
                alertId = alertId, sourceHash = entry.sourceHash,
                severity = severity, text = text, receivedAt = t,
            )
        )
        return entry
    }

    override fun remove(alertId: String): Boolean = dao.delete(alertId) > 0
    override fun clear(): Int = dao.clear()
    override fun prune(now: Double?): Int =
        dao.prune(now ?: (System.currentTimeMillis() / 1000.0), InboxRegistry.DEFAULT_MAX_AGE_S)
}

/* ---------- OutboxRepository ---------- */

class RoomOutboxRepository(
    private val outboxDao: OutboxDao,
) : network.retalert.domain.OutboxRepository {
    private fun Alert.toEntity() = OutboxAlertEntity(
        alertId = alertId, severity = severity, text = text,
        recipients = recipients.toJson(), createdAt = createdAt,
        payload = payload.toJson(), retryInterval = retryInterval,
        maxAttempts = maxAttempts, fanOut = fanOut,
    )

    private fun OutboxAlertEntity.toDomain() = Alert(
        alertId = alertId, severity = severity, text = text,
        recipients = recipients.toListOrNull(), createdAt = createdAt,
        payload = payload.toMapOrNull(), retryInterval = retryInterval,
        maxAttempts = maxAttempts, fanOut = fanOut,
    )

    override fun enqueue(alert: Alert) {
        outboxDao.upsertAlert(alert.toEntity())
    }

    override fun pending(): List<Alert> = outboxDao.pendingAlerts().map { it.toDomain() }

    override fun remove(alertId: String) {
        outboxDao.deleteAcks(alertId)
        outboxDao.deleteAlert(alertId)
    }

    override fun ackStates(alertId: String): Map<String, String> =
        outboxDao.ackStates(alertId).associate { it.recipient to it.state }

    override fun setAckState(alertId: String, recipient: String, state: String) {
        outboxDao.upsertAck(
            AckStateEntity(alertId = alertId, recipient = recipient.lowercase().trim(), state = state)
        )
    }
}

/* ---------- SettingsRepository ---------- */

class RoomSettingsRepository(
    private val dao: SettingsDao,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : network.retalert.domain.SettingsRepository {

    @kotlinx.serialization.Serializable
    private data class Snapshot(
        val receiveOnlyFromContacts: Boolean = true,
        val allowlist: List<String> = emptyList(),
        val denylist: List<String> = emptyList(),
        val distanceUnits: String = "km",
    )

    override fun load(): Settings {
        val raw = dao.get()?.settings ?: return Settings()
        val s = runCatching { json.decodeFromString<Snapshot>(raw) }.getOrNull() ?: return Settings()
        return Settings(
            receiveOnlyFromContacts = s.receiveOnlyFromContacts,
            allowlist = s.allowlist.toMutableSet(),
            denylist = s.denylist.toMutableSet(),
            distanceUnits = s.distanceUnits,
        )
    }

    override fun save(settings: Settings) {
        val s = Snapshot(
            receiveOnlyFromContacts = settings.receiveOnlyFromContacts,
            allowlist = settings.allowlist.toList(),
            denylist = settings.denylist.toList(),
            distanceUnits = settings.distanceUnits,
        )
        dao.upsert(SettingsEntity(1, json.encodeToString(Snapshot.serializer(), s)))
    }
}

/* ---------- StarredRepository ---------- */

class RoomStarredRepository(private val dao: StarredDao) : network.retalert.domain.StarredRepository {
    override fun starred(): List<String> = dao.list().map { it.hash }
    override fun star(hash: String): Boolean =
        dao.insert(StarredEntity(hash.lowercase().trim())) > 0
    override fun unstar(hash: String): Boolean = dao.delete(hash.lowercase().trim()) > 0
}

/* ---------- KeyComboRepository ---------- */

class RoomKeyComboRepository(private val dao: KeyComboDao) : network.retalert.domain.KeyComboRepository {
    private fun String.toComboList(): List<String> =
        if (isBlank()) emptyList() else JSON.decodeFromString(STR_LIST, this)

    override fun list(): List<KeyCombo> = dao.list().map { KeyCombo(it.combo.toComboList(), it.trigger, it.arm) }

    override fun save(combos: List<KeyCombo>) {
        dao.clear()
        if (combos.isEmpty()) return
        dao.upsertAll(combos.map { KeyComboEntity(it.combo.toJson(), it.trigger, it.arm) })
    }
}