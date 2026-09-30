package network.retalert.app.platform

import network.retalert.domain.Alert
import network.retalert.domain.ContactRepository
import network.retalert.domain.FanOut
import network.retalert.domain.GroupRepository
import network.retalert.domain.Preset
import network.retalert.domain.PresetRepository
import network.retalert.domain.RealClock
import network.retalert.domain.Severity
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
        return engine.sendAlert(
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
        return alert to "all ${all.size} contacts"
    }

    /** Free-form alert (send screen). */
    fun send(severity: String, text: String, recipients: List<String>): Alert =
        engine.sendAlert(
            Alert.new(
                clock = RealClock,
                severity = Severity.normalize(severity),
                text = text,
                recipients = recipients,
                payload = mapOf("text" to true),
                fanOut = FanOut.CRITICAL,
            ),
        )

    companion object {
        const val DEFAULT_PRESET = "default"
        const val PANIC_TEXT = "PANIC - I need help"
    }
}
