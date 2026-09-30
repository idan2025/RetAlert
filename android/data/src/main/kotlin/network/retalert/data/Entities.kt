package network.retalert.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One contact, keyed by lowercased-trimmed destination hash (idempotent upsert). */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val hash: String,
    val name: String,
)

/** Named group of destination hashes, keyed by group name. Members stored as JSON. */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val name: String,
    val members: String,    // List<String> as JSON
)

/** Saved emergency preset, keyed by id. */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val severity: String,
    val text: String,
    val recipients: String,         // List<String> as JSON
    val group: String?,            // nullable group name
    val payload: String,           // Map<String,Boolean> as JSON
    val retryInterval: Double,
    val maxAttempts: Int,
    val fanOut: String,
    val loraThrottle: Double?,
)

/** Received app-to-app alert, keyed by alertId. */
@Entity(tableName = "inbox")
data class InboxEntryEntity(
    @PrimaryKey val alertId: String,
    val sourceHash: String,
    val severity: String,
    val text: String,
    val receivedAt: Double,
)

/** Outgoing alert in the outbox, keyed by alertId. */
@Entity(tableName = "outbox")
data class OutboxAlertEntity(
    @PrimaryKey val alertId: String,
    val severity: String,
    val text: String,
    val recipients: String,         // List<String> as JSON
    val createdAt: Double,
    val payload: String,           // Map<String,Boolean> as JSON
    val retryInterval: Double,
    val maxAttempts: Int,
    val fanOut: String,
)

/** Per-recipient delivery state for one outbox alert. Composite key (alertId, recipient). */
@Entity(tableName = "ack_states", primaryKeys = ["alertId", "recipient"])
data class AckStateEntity(
    val alertId: String,
    val recipient: String,
    val state: String,
    val attempts: Int = 0,
    val lastAttempt: Double = 0.0,
    val lastError: String? = null,
    val reply: String? = null,
)

/** Single-row settings snapshot (id fixed at 1). Settings serialized to JSON. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val settings: String,
)

/** Persisted starred peer hash (one column). */
@Entity(tableName = "starred")
data class StarredEntity(
    @PrimaryKey val hash: String,
)

/** Persisted hardware-key combo, keyed by the combo sequence as JSON. */
@Entity(tableName = "key_combos")
data class KeyComboEntity(
    @PrimaryKey val combo: String,  // List<String> as JSON
    val trigger: String,
    val arm: Boolean,
)