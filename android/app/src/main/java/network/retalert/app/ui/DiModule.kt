package network.retalert.app.ui

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.Clock
import network.retalert.domain.RealClock
import javax.inject.Singleton

/**
 *  App-level Hilt module. The repository interfaces (Contact, Group, Preset,
 *  Inbox, Outbox, Settings, Starred, KeyCombo) and the RNS stores (AckTracker,
 *  Discover, LiveTrackStore, MediaChannel, TransportIntelligence, …) are now
 *  provided by :data (`DataModule`) and :reticulum (`ReticulumModule`). This
 *  module only owns app-only singletons not covered by those modules.
 *
 *  Phase 4 platform bindings (FusedLocationSource, AlertNotifier) live in
 *  `network.retalert.app.platform.PlatformModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiModule {

    @Provides @Singleton fun provideClock(): Clock = RealClock
}