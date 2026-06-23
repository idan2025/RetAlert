package network.retalert.app.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.Alert
import network.retalert.domain.FanOut
import network.retalert.domain.GroupRepository
import network.retalert.domain.LORA_THROTTLE_PRESETS
import network.retalert.domain.OutboxRepository
import network.retalert.domain.PAYLOAD_CLASSES
import network.retalert.domain.Preset
import network.retalert.domain.PresetRepository
import network.retalert.domain.RealClock
import network.retalert.domain.Severity
import network.retalert.domain.clampLoraThrottle
import javax.inject.Inject

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

/** Presets screen: list + one-tap fire (builds + dispatches an Alert), group
 *  expand, payload toggles, LoRa throttle presets. */
@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val presets: PresetRepository,
    private val groups: GroupRepository,
    private val outbox: OutboxRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PresetsUiState())
    val state: StateFlow<PresetsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(rows = presets.list().map { p -> p.toRow() }) }
    }

    fun toggleExpand(id: String) = _state.update {
        it.copy(expandedId = if (it.expandedId == id) null else id)
    }

    /** Toggle a payload class on a preset and persist it. */
    fun togglePayload(id: String, key: String) = viewModelScope.launch {
        val p = presets.get(id) ?: return@launch
        val newPayload = p.payload.toMutableMap().apply {
            this[key] = !(this[key] ?: false)
            if (values.none { it }) this["text"] = true
        }
        presets.put(p.copy(payload = newPayload))
        refresh()
    }

    /** Set the LoRa throttle on a preset (clamped to bounds). */
    fun setLoraThrottle(id: String, seconds: Double?) = viewModelScope.launch {
        val p = presets.get(id) ?: return@launch
        val clamped = clampLoraThrottle(seconds)
        presets.put(p.copy(loraThrottle = clamped))
        refresh()
    }

    /** One-tap fire: resolve the preset, expand its group, build + dispatch an Alert. */
    fun fire(name: String) = viewModelScope.launch {
        val preset = presets.byName(name) ?: return@launch
        val g = preset.group
        val recipients = if (g != null && g.isNotBlank()) groups.members(g) else preset.recipients
        if (recipients.isEmpty()) {
            _state.update { it.copy(flash = "$name: nothing dispatched") }
            return@launch
        }
        val alert = Alert.new(
            clock = RealClock,
            severity = Severity.normalize(preset.severity),
            text = preset.text,
            recipients = recipients,
            payload = preset.payload,
            retryInterval = preset.retryInterval,
            maxAttempts = preset.maxAttempts,
            fanOut = FanOut.normalize(preset.fanOut),
        )
        outbox.enqueue(alert)
        _state.update { it.copy(flash = "$name: sent ${alert.alertId.take(8)}…") }
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