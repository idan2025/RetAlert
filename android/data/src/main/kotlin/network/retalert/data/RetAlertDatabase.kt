package network.retalert.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Aggregate Room database for RetAlert. Version 1, exportSchema=false. */
@Database(
    entities = [
        ContactEntity::class,
        GroupEntity::class,
        PresetEntity::class,
        InboxEntryEntity::class,
        OutboxAlertEntity::class,
        AckStateEntity::class,
        SettingsEntity::class,
        StarredEntity::class,
        KeyComboEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class RetAlertDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun groupDao(): GroupDao
    abstract fun presetDao(): PresetDao
    abstract fun inboxDao(): InboxDao
    abstract fun outboxDao(): OutboxDao
    abstract fun settingsDao(): SettingsDao
    abstract fun starredDao(): StarredDao
    abstract fun keyComboDao(): KeyComboDao
}