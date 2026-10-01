package network.retalert.app.platform

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.FixSource
import network.retalert.domain.HardwareKeyManager
import network.retalert.reticulum.IncomingNotifier
import javax.inject.Singleton
import kotlin.concurrent.thread

/** Phase-4 native platform Hilt bindings: location fix source, the real
 *  incoming-alert notifier (replaces :reticulum's no-op), and the hardware-key
 *  manager whose fire callback sends a preset alert through the engine. */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides @Singleton
    fun provideFixSource(@ApplicationContext ctx: Context): FixSource = FusedLocationSource(ctx)

    @Provides @Singleton
    fun provideIncomingNotifier(notifier: AlertNotifier): IncomingNotifier = notifier

    /** Key events arrive on the main thread; the fire (DB + mesh send) runs on
     *  a worker thread. */
    @Provides @Singleton
    fun provideHardwareKeyManager(dispatcher: AlertDispatcher): HardwareKeyManager {
        val fire: (String) -> Any? = { trigger ->
            thread(name = "retalert-hwkey-fire", isDaemon = true) {
                runCatching { dispatcher.firePreset(trigger) }
                    .onFailure { Log.w("RetAlert/HwKey", "combo fire '$trigger' failed: ${it.message}") }
            }
            trigger
        }
        return HardwareKeyManager(fireFn = fire)
    }
}
