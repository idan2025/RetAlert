package network.retalert.app.platform

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.retalert.domain.Alert
import network.retalert.domain.ContactRepository
import network.retalert.domain.FanOut
import network.retalert.domain.FixSource
import network.retalert.domain.GroupRepository
import network.retalert.domain.HardwareKeyManager
import network.retalert.domain.OutboxRepository
import network.retalert.domain.PresetRepository
import network.retalert.domain.RealClock
import network.retalert.domain.Severity
import network.retalert.reticulum.IncomingNotifier
import javax.inject.Singleton

/** Phase-4 native platform Hilt bindings: location fix source, the real
 *  incoming-alert notifier (replaces :reticulum's no-op), and the hardware-key
 *  manager whose fire callback resolves + enqueues a preset alert. */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides @Singleton
    fun provideFixSource(@ApplicationContext ctx: Context): FixSource = FusedLocationSource(ctx)

    @Provides @Singleton
    fun provideIncomingNotifier(@ApplicationContext ctx: Context): IncomingNotifier = AlertNotifier(ctx)

    /** The hardware-key fire callback resolves the preset by name and enqueues
     *  an alert to its recipients (preset.recipients, else the group's members). */
    @Provides @Singleton
    fun provideHardwareKeyManager(
        presets: PresetRepository,
        groups: GroupRepository,
        outbox: OutboxRepository,
    ): HardwareKeyManager {
        val fire: (String) -> Any? = { trigger ->
            runCatching {
                val preset = presets.byName(trigger) ?: return@runCatching
                val recipients = preset.recipients.ifEmpty {
                    preset.group?.let { groups.get(it)?.members } ?: emptyList()
                }
                if (recipients.isEmpty()) return@runCatching
                val alert = Alert.new(
                    clock = RealClock,
                    severity = Severity.normalize(preset.severity),
                    text = preset.text,
                    recipients = recipients,
                    payload = preset.payload,
                    fanOut = preset.fanOut,
                )
                outbox.enqueue(alert)
            }
            null
        }
        return HardwareKeyManager(fireFn = fire)
    }
}