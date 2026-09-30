package network.retalert.domain

import kotlinx.serialization.Serializable

/** Payload classes a preset can request. */
val PAYLOAD_CLASSES = listOf("text", "gps_oneshot", "gps_live", "photo", "audio")

/** One saved emergency preset. Mirrors `Preset` in preset.py. */
@Serializable
data class Preset(
    val id: String = "",
    val name: String = "",
    val severity: String = "help",
    val text: String = "",
    val recipients: List<String> = emptyList(),
    val group: String? = null,
    val payload: Map<String, Boolean> = mapOf("text" to true),
    val retryInterval: Double = 3.0,
    val maxAttempts: Int = 0,
    val fanOut: String = "critical",
    val loraThrottle: Double? = null,
) {
    init {
        // Normalise payload keys to the known set, defaulting text on if empty.
        // (Done via companion factory `normalized`; data-class init can't reassign val,
        //  so callers must use Preset.normalized() to get the cleaned form.)
    }

    companion object {
        /** Build a preset with a normalised id + payload (parity with Python __post_init__). */
        fun normalized(
            id: String = "",
            name: String = "",
            severity: String = "help",
            text: String = "",
            recipients: List<String> = emptyList(),
            group: String? = null,
            payload: Map<String, Boolean> = emptyMap(),
            retryInterval: Double = 3.0,
            maxAttempts: Int = 0,
            fanOut: String = "critical",
            loraThrottle: Double? = null,
        ): Preset {
            val clean = LinkedHashMap<String, Boolean>()
            for (k in PAYLOAD_CLASSES) clean[k] = payload[k] ?: false
            if (clean.values.none { it }) clean["text"] = true
            return Preset(
                id = id.ifBlank { randomHex(8) },
                name = name, severity = severity, text = text,
                recipients = recipients, group = group,
                payload = clean, retryInterval = retryInterval,
                maxAttempts = maxAttempts, fanOut = fanOut, loraThrottle = loraThrottle,
            )
        }
    }
}

/** In-memory preset collection, keyed by preset id. :data provides Room-backed persistence. */
class PresetStore {
    private val presets = LinkedHashMap<String, Preset>()

    /** Insert or replace a preset (id assigned + payload normalised). */
    fun put(preset: Preset): Preset {
        val p = Preset.normalized(
            id = preset.id, name = preset.name, severity = preset.severity, text = preset.text,
            recipients = preset.recipients, group = preset.group, payload = preset.payload,
            retryInterval = preset.retryInterval, maxAttempts = preset.maxAttempts,
            fanOut = preset.fanOut, loraThrottle = preset.loraThrottle,
        )
        presets[p.id] = p
        return p
    }

    fun remove(presetId: String): Boolean = presets.remove(presetId) != null

    fun get(presetId: String): Preset? = presets[presetId]

    fun byName(name: String): Preset? {
        val n = name.trim()
        return presets.values.firstOrNull { it.name == n }
    }

    fun list(): List<Preset> = presets.values.toList()
}

/** Maps a trigger name to a Preset. Parity with PresetResolver. */
class PresetResolver(private val store: PresetStore) {
    fun resolve(trigger: String): Preset? {
        if (trigger.isBlank()) return store.byName(DEFAULT_NAME)
        store.byName(trigger)?.let { return it }
        return store.byName(DEFAULT_NAME)
    }

    companion object { const val DEFAULT_NAME = "default" }
}

/** Dispatches confirmed triggers as alerts. Mirrors PanicEngine. */
class PanicEngine(
    private val store: PresetStore,
    private val sendAlertFn: (Alert) -> Alert,
    private val expandGroupFn: (String) -> List<String>,
    private val startLiveShareFn: ((String, Double) -> Unit)? = null,
    private val getFixFn: (() -> Fix?)? = null,
    private val clock: Clock = RealClock,
) {
    /** Dedup window: re-fire of the same preset within this many seconds of an
     *  active (unacked) alert returns null. */
    private val active = LinkedHashMap<String, Pair<String, Double>>() // presetId -> (alertId, firedAt)

    fun fire(trigger: String = "default"): Alert? {
        val preset = store.let { PresetResolver(it).resolve(trigger) } ?: return null
        active[preset.id]?.let { (alertId, firedAt) ->
            if (clock.monotonic() - firedAt < DEDUP_WINDOW_S) return null
        }
        val recipients = recipientsFor(preset)
        if (recipients.isEmpty()) return null

        var text = preset.text
        var fix: Fix? = null
        if (preset.payload["gps_oneshot"] == true && getFixFn != null) {
            fix = runCatching { getFixFn() }.getOrNull()
        }
        if (fix != null) {
            text = (if (text.isNotEmpty()) "$text " else "") + fix.geoUri
        }

        val alert = Alert.new(
            clock = clock,
            severity = preset.severity,
            text = text,
            recipients = recipients,
            payload = preset.payload,
            retryInterval = preset.retryInterval,
            maxAttempts = preset.maxAttempts,
            fanOut = preset.fanOut,
        )
        sendAlertFn(alert)
        active[preset.id] = alert.alertId to clock.monotonic()

        if (preset.payload["gps_live"] == true && startLiveShareFn != null) {
            val interval = preset.loraThrottle ?: 5.0
            for (r in recipients) runCatching { startLiveShareFn(r, interval.toDouble()) }
        }
        return alert
    }

    private fun recipientsFor(preset: Preset): List<String> =
        if (!preset.group.isNullOrBlank()) expandGroupFn(preset.group)
        else preset.recipients.map { it.lowercase().trim() }

    /** Clear dedup state once an alert is fully acked/failed. */
    fun onAlertDone(alertId: String) {
        val stale = active.entries.filter { it.value.first == alertId }.map { it.key }
        stale.forEach { active.remove(it) }
    }

    companion object { const val DEDUP_WINDOW_S = 30.0 }
}