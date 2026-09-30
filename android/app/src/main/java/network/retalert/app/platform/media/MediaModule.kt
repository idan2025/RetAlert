package network.retalert.app.platform.media

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the Phase-4 native media capture/playback components. The recorder
 *  and player own their AudioRecord/MediaCodec/AudioTrack lifecycles at use time, so
 *  construction is no-op here — they are injected as singletons and started/stopped by
 *  the Send screen. */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides @Singleton
    fun provideOpusAudioRecorder(@ApplicationContext ctx: Context): OpusAudioRecorder =
        OpusAudioRecorder(ctx)

    @Provides @Singleton
    fun provideOpusAudioPlayer(@ApplicationContext ctx: Context): OpusAudioPlayer =
        OpusAudioPlayer(ctx)
}