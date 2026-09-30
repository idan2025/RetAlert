package network.retalert.app.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.app.platform.AlertDispatcher
import network.retalert.domain.ContactRepository
import network.retalert.domain.GroupRepository
import network.retalert.domain.LORA_THROTTLE_PRESETS
import network.retalert.domain.PAYLOAD_CLASSES
import network.retalert.domain.Preset
import network.retalert.domain.PresetRepository
import network.retalert.domain.Severity
import network.retalert.domain.clampLoraThrottle
import network.retalert.domain.normalizeHash
import javax.inject.Inject

const val LOCATION_NONE = "none"
const val LOCATION_ONCE = "once"
const val LOCATION_LIVE = "live"

data class PresetRow(
    val id: String,
    val name: String,
    val severity: String,
    val fanOut: String,
    val recipientsLabel: String,
    val payload: Map<String, Boolean>,
    val loraThrottle: Double?,
)

data class PresetsUiState(
    val rows: List<PresetRow> = emptyList(),
    val flash: String = "",
    val expandedId: String? = null,
)

/** Presets screen: create/delete, list + one-tap fire, group expand, payload
 *  toggles, LoRa throttle presets. A preset named 'default' is what the Home
 *  panic button fires. */
@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val presets: PresetRepository,
    private val groups: GroupRepository,
    private val contacts: ContactRepository,
    private val dispatcher: AlertDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(PresetsUiState())
    val state: StateFlow<PresetsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val rows = runCatching { presets.list().map { p -> p.toRow() } }.getOrDefault(emptyList())
        _state.update { it.copy(rows = rows) }
    }

    fun toggleExpand(id: String) = _state.update {
        it.copy(expandedId = if (it.expandedId == id) null else id)
    }

    /** Create (or replace, by name) a preset. [targets] is a comma-separated list of
     *  contact names and/or hashes, or a single group name. */
    fun create(name: String, severity: String, text: String, targets: String, location: String = LOCATION_NONE) = viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            val n = name.trim()
            require(n.isNotEmpty()) { "name required" }
            val parts = targets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.isNotEmpty()) { "at least one recipient required" }
            val group = parts.singleOrNull()?.let { t -> groups.list().firstOrNull { it.name.equals(t, true) }?.name }
            val recipients = if (group != null) emptyList() else parts.map { t ->
                contacts.list().firstOrNull { it.name.equals(t, true) }?.hash
                    ?: normalizeHash(t)
                    ?: error("unknown recipient '$t'")
            }
            val existing = presets.byName(n)
            presets.put(
                Preset.normalized(
                    id = existing?.id.orEmpty(),
                    name = n,
                    severity = Severity.normalize(severity),
                    text = text.trim(),
                    recipients = recipients,
                    group = group,
                    payload = mapOf(
                        "text" to true,
                        "gps_oneshot" to (location == LOCATION_ONCE),
                        "gps_live" to (location == LOCATION_LIVE),
                    ),
                    loraThrottle = existing?.loraThrottle,
                ),
            )
            n
        }
        _state.update { it.copy(flash = result.fold({ n -> "saved preset $n" }, { e -> "not saved: ${e.message}" })) }
        refresh()
    }

    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { presets.remove(id) }
        refresh()
    }

    /** Toggle a payload class on a preset and persist it. */
    fun togglePayload(id: String, key: String) = viewModelScope.launch(Dispatchers.IO) {
        val p = presets.get(id) ?: return@launch
        val newPayload = p.payload.toMutableMap().apply {
            this[key] = !(this[key] ?: false)
            if (values.none { it }) this["text"] = true
        }
        presets.put(p.copy(payload = newPayload))
        refresh()
    }

    /** Set the LoRa throttle on a preset (clamped to bounds). */
    fun setLoraThrottle(id: String, seconds: Double?) = viewModelScope.launch(Dispatchers.IO) {
        val p = presets.get(id) ?: return@launch
        presets.put(p.copy(loraThrottle = clampLoraThrottle(seconds)))
        refresh()
    }

    /** One-tap fire: resolve the preset, expand its group, build + dispatch an Alert. */
    fun fire(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val flash = runCatching { dispatcher.firePreset(name) }.fold(
            onSuccess = { "$name: sent ${it.alertId.take(8)}… to ${it.recipients.size} recipient(s)" },
            onFailure = { "$name: not sent — ${it.message}" },
        )
        _state.update { it.copy(flash = flash) }
    }

    private fun Preset.toRow(): PresetRow = PresetRow(
        id = id,
        name = name,
        severity = severity,
        fanOut = fanOut,
        recipientsLabel = group?.takeIf { it.isNotBlank() }?.let { "group:$it" }
            ?: recipients.joinToString(",") { it.take(8) },
        payload = payload,
        loraThrottle = loraThrottle,
    )

    @Suppress("unused") fun loraPresets() = LORA_THROTTLE_PRESETS
    @Suppress("unused") fun payloadClasses() = PAYLOAD_CLASSES
}
