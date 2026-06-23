package network.retalert.reticulum.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.AckTracker
import network.retalert.domain.AnnounceEngine
import network.retalert.domain.Discover
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.MediaChannel
import network.retalert.domain.RetryQueue
import network.retalert.domain.TransportIntelligence
import network.retalert.reticulum.IncomingNotifier
import network.retalert.reticulum.IncomingWiring
import network.retalert.reticulum.ReticulumEngine
import network.retalert.reticulum.RnsMediaLink
import network.retalert.reticulum.RnsTransport
import network.retalert.reticulum.ShareInstance
import network.retalert.reticulum.lxmf.LxmfRouter
import network.retalert.reticulum.lxmf.LxmfRouterImpl
import network.retalert.reticulum.lxmf.StubLxmfRouter
import javax.inject.Singleton

/**
 * Hilt graph for the :reticulum module. Repository bindings (Outbox, Starred,
 * Settings, Contact, Inbox) are provided by :data (`DataModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {

    @Provides @Singleton
    fun provideAckTracker(): AckTracker = AckTracker()

    @Provides @Singleton
    fun provideDiscover(): Discover = Discover()

    @Provides @Singleton
    fun provideLiveTrackStore(): LiveTrackStore = LiveTrackStore()

    @Provides @Singleton
    fun provideTransportIntelligence(): TransportIntelligence =
        network.retalert.reticulum.rnsTransportIntelligence()

    @Provides @Singleton
    fun provideLxmfRouter(
        @ApplicationContext context: Context,
    ): LxmfRouter =
        // Real LXMF-kt transport (composite build). StubLxmfRouter is retained
        // as the compile-safe fallback for builds without the composite build;
        // swap here if the composite build is ever unavailable.
        LxmfRouterImpl(context)
    // LxmfRouter fallback (unused): StubLxmfRouter()

    @Provides @Singleton
    fun provideRnsTransport(
        ackTracker: AckTracker,
        lxmf: LxmfRouter,
    ): RnsTransport = RnsTransport(ackTracker, lxmf)

    @Provides @Singleton
    fun provideRetryQueue(
        ackTracker: AckTracker,
        rnsTransport: RnsTransport,
    ): RetryQueue = RetryQueue(ackTracker, sendFn = rnsTransport::send)

    @Provides @Singleton
    fun provideAnnounceEngine(lxmf: LxmfRouter): AnnounceEngine =
        AnnounceEngine(announceFn = { lxmf.announce() })

    @Provides @Singleton
    fun provideRnsMediaLink(): RnsMediaLink = RnsMediaLink()

    @Provides @Singleton
    fun provideShareInstance(@ApplicationContext context: Context): ShareInstance =
        ShareInstance(context)

    @Provides @Singleton
    fun provideMediaChannel(
        ti: TransportIntelligence,
        link: RnsMediaLink,
    ): MediaChannel = link.mediaChannel(ti)

    // IncomingNotifier is provided by :app (PlatformModule → AlertNotifier),
    // which replaces the former no-op binding here.

    @Provides @Singleton
    fun provideIncomingWiring(
        ackTracker: AckTracker,
        lxmf: LxmfRouter,
        inbox: network.retalert.domain.InboxRepository,
        notifier: IncomingNotifier,
    ): IncomingWiring = IncomingWiring(ackTracker, lxmf, inbox, notifier)

    @Provides @Singleton
    fun provideReticulumEngine(
        @ApplicationContext context: Context,
        ackTracker: AckTracker,
        retryQueue: RetryQueue,
        discover: Discover,
        tracks: LiveTrackStore,
        ti: TransportIntelligence,
        lxmf: LxmfRouter,
        announceEngine: AnnounceEngine,
        mediaChannel: MediaChannel,
        rnsTransport: RnsTransport,
        incomingWiring: IncomingWiring,
        notifier: IncomingNotifier,
        outbox: network.retalert.domain.OutboxRepository,
        starred: network.retalert.domain.StarredRepository,
        settings: network.retalert.domain.SettingsRepository,
        contact: network.retalert.domain.ContactRepository,
        shareInstance: ShareInstance,
    ): ReticulumEngine = ReticulumEngine(
        context = context,
        ackTracker = ackTracker,
        retryQueue = retryQueue,
        discover = discover,
        tracks = tracks,
        transportIntelligence = ti,
        lxmf = lxmf,
        announceEngine = announceEngine,
        mediaChannel = mediaChannel,
        rnsTransport = rnsTransport,
        incomingWiring = incomingWiring,
        notifier = notifier,
        outboxRepo = outbox,
        starredRepo = starred,
        settingsRepo = settings,
        contactRepo = contact,
        shareInstance = shareInstance,
    )
}