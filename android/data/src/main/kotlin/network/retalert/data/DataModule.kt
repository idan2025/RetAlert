package network.retalert.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.ContactRepository
import network.retalert.domain.GroupRepository
import network.retalert.domain.InboxRepository
import network.retalert.domain.KeyComboRepository
import network.retalert.domain.OutboxRepository
import network.retalert.domain.PresetRepository
import network.retalert.domain.SettingsRepository
import network.retalert.domain.StarredRepository
import javax.inject.Singleton

/** Hilt module providing the Room database, DAOs, and repository bindings. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): RetAlertDatabase =
        Room.databaseBuilder(ctx, RetAlertDatabase::class.java, "retalert.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideContactDao(db: RetAlertDatabase): ContactDao = db.contactDao()
    @Provides fun provideGroupDao(db: RetAlertDatabase): GroupDao = db.groupDao()
    @Provides fun providePresetDao(db: RetAlertDatabase): PresetDao = db.presetDao()
    @Provides fun provideInboxDao(db: RetAlertDatabase): InboxDao = db.inboxDao()
    @Provides fun provideOutboxDao(db: RetAlertDatabase): OutboxDao = db.outboxDao()
    @Provides fun provideSettingsDao(db: RetAlertDatabase): SettingsDao = db.settingsDao()
    @Provides fun provideStarredDao(db: RetAlertDatabase): StarredDao = db.starredDao()
    @Provides fun provideKeyComboDao(db: RetAlertDatabase): KeyComboDao = db.keyComboDao()

    @Provides @Singleton
    fun provideContactRepository(dao: ContactDao): ContactRepository = RoomContactRepository(dao)

    @Provides @Singleton
    fun provideGroupRepository(dao: GroupDao): GroupRepository = RoomGroupRepository(dao)

    @Provides @Singleton
    fun providePresetRepository(dao: PresetDao): PresetRepository = RoomPresetRepository(dao)

    @Provides @Singleton
    fun provideInboxRepository(dao: InboxDao): InboxRepository = RoomInboxRepository(dao)

    @Provides @Singleton
    fun provideOutboxRepository(dao: OutboxDao): OutboxRepository = RoomOutboxRepository(dao)

    @Provides @Singleton
    fun provideSettingsRepository(dao: SettingsDao): SettingsRepository = RoomSettingsRepository(dao)

    @Provides @Singleton
    fun provideStarredRepository(dao: StarredDao): StarredRepository = RoomStarredRepository(dao)

    @Provides @Singleton
    fun provideKeyComboRepository(dao: KeyComboDao): KeyComboRepository = RoomKeyComboRepository(dao)
}