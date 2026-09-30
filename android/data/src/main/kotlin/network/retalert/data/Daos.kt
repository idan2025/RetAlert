package network.retalert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY hash")
    fun list(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE hash = :hash LIMIT 1")
    fun get(hash: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE hash = :hash")
    fun delete(hash: String): Int

    @Query("SELECT COUNT(*) FROM contacts")
    fun count(): Int
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY name")
    fun list(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE name = :name LIMIT 1")
    fun get(name: String): GroupEntity?

    @Query("DELETE FROM groups WHERE name = :name")
    fun delete(name: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM groups WHERE name = :name)")
    fun exists(name: String): Boolean
}

@Dao
interface PresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PresetEntity)

    @Query("SELECT * FROM presets ORDER BY id")
    fun list(): List<PresetEntity>

    @Query("SELECT * FROM presets WHERE id = :id LIMIT 1")
    fun get(id: String): PresetEntity?

    @Query("SELECT * FROM presets WHERE name = :name LIMIT 1")
    fun byName(name: String): PresetEntity?

    @Query("DELETE FROM presets WHERE id = :id")
    fun delete(id: String): Int
}

@Dao
interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: InboxEntryEntity)

    @Query("SELECT * FROM inbox ORDER BY receivedAt DESC")
    fun list(): List<InboxEntryEntity>

    @Query("SELECT * FROM inbox WHERE alertId = :alertId LIMIT 1")
    fun get(alertId: String): InboxEntryEntity?

    @Query("DELETE FROM inbox WHERE alertId = :alertId")
    fun delete(alertId: String): Int

    @Query("DELETE FROM inbox")
    fun clear(): Int

    @Query("DELETE FROM inbox WHERE :now - receivedAt > :maxAgeS")
    fun prune(now: Double, maxAgeS: Double): Int
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAlert(entity: OutboxAlertEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    fun pendingAlerts(): List<OutboxAlertEntity>

    @Query("DELETE FROM outbox WHERE alertId = :alertId")
    fun deleteAlert(alertId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAck(entity: AckStateEntity)

    @Query("SELECT * FROM ack_states WHERE alertId = :alertId")
    fun ackStates(alertId: String): List<AckStateEntity>

    @Query("DELETE FROM ack_states WHERE alertId = :alertId")
    fun deleteAcks(alertId: String): Int
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SettingsEntity)

    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun get(): SettingsEntity?
}

@Dao
interface StarredDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: StarredEntity): Long

    @Query("SELECT * FROM starred ORDER BY hash")
    fun list(): List<StarredEntity>

    @Query("DELETE FROM starred WHERE hash = :hash")
    fun delete(hash: String): Int
}

@Dao
interface KeyComboDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entities: List<KeyComboEntity>)

    @Query("SELECT * FROM key_combos")
    fun list(): List<KeyComboEntity>

    @Query("DELETE FROM key_combos")
    fun clear()
}