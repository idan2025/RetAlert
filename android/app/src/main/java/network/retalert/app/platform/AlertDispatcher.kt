package network.retalert.app.platform

import network.retalert.domain.Alert
import network.retalert.domain.ContactRepository
import network.retalert.domain.FanOut
import network.retalert.domain.GroupRepository
import network.retalert.domain.Preset
import network.retalert.domain.PresetRepository
import network.retalert.domain.RealClock
import network.retalert.domain.Severity
import network.retalert.domain.SettingsRepository
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place alerts are built and handed to the mesh engine, shared by the
 * panic button, the presets screen, the send screen and the hardware-key
 * trigger. All methods block on the database: call them off the main thread.
 */
@Singleton
class AlertDispatcher @Inject constructor(
    private val engine: ReticulumEngine,
    private val presets: PresetRepository,
    private val groups: GroupRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val sharer: LocationSharer,
) {
    /** Recipients of a preset: its group's members when set, else its own list. */
    fun recipientsOf(preset: Preset): List<String> {
        val g = preset.group?.takeIf { it.isNotBlank() }
        return if (g != null) groups.members(g) else preset.recipients
    }

    /** Fire a preset by name. Throws [IllegalStateException] with a user-facing reason. */
    fun firePreset(name: String): Alert {
        val preset = presets.byName(name) ?: error("no preset named '$name'")
        val recipients = recipientsOf(preset)
        check(recipients.isNotEmpty()) { "preset '$name' has no recipients" }
        val alert = engine.sendAlert(
            Alert.new(
                clock = RealClock,
                severity = Severity.normalize(preset.severity),
                text = preset.text,
                recipients = recipients,
                payload = preset.payload,
                retryInterval = preset.retryInterval,
                maxAttempts = preset.maxAttempts,
                fanOut = FanOut.normalize(preset.fanOut),
            ),
        )
        // Location follows the alert (never delays it): live GPS if the preset
        // asks for it, else a single fix for gps_oneshot.
        when {
            preset.payload["gps_live"] == true -> startLiveShare(alert.recipients, "preset '$name'", preset.loraThrottle)
            preset.payload["gps_oneshot"] == true -> sharer.sendOnce(alert.recipients)
        }
        return alert
    }

    private fun startLiveShare(recipients: List<String>, reason: String, loraThrottleS: Double? = null) {
        val s = settings.load()
        sharer.start(recipients, s.liveShareMinutes, s.liveShareIntervalS, reason, loraThrottleS)
    }

    /** Panic: the 'default' preset if one exists and can fire, otherwise a
     *  critical alert to every contact — an emergency must never dead-end on a
     *  misconfigured preset. Returns the alert and a short description of what fired. */
    fun panic(): Pair<Alert, String> {
        if (presets.byName(DEFAULT_PRESET) != null) {
            runCatching { firePreset(DEFAULT_PRESET) }.getOrNull()
                ?.let { return it to "preset '$DEFAULT_PRESET'" }
        }
        val all = contacts.list().map { it.hash }
        check(all.isNotEmpty()) { "add a contact or a 'default' preset first" }
        val alert = engine.sendAlert(
            Alert.new(
                clock = RealClock,
                severity = Severity.CRITICAL,
                text = PANIC_TEXT,
                recipients = all,
                fanOut = FanOut.CRITICAL,
            ),
        )
        if (settings.load().panicShareLocation) startLiveShare(alert.recipients, "panic")
        return alert to "all ${all.size} contacts"
    }

    /** Free-form alert (send screen), optionally followed by live location. */
    fun send(severity: String, text: String, recipients: List<String>, shareLive: Boolean = false): Alert =
        engine.sendAlert(
            Alert.new(
                clock = RealClock,
                severity = Severity.normalize(severity),
                text = text,
                recipients = recipients,
                payload = mapOf("text" to true),
                fanOut = FanOut.CRITICAL,
            ),
        ).also { if (shareLive) startLiveShare(it.recipients, "alert") }

    companion object {
        const val DEFAULT_PRESET = "default"
        const val PANIC_TEXT = "PANIC - I need help"
    }
}
