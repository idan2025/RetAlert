package network.retalert.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.KeyCombo
import network.retalert.domain.KeyComboRepository
import network.retalert.domain.Settings
import network.retalert.domain.SettingsRepository
import network.retalert.domain.clampAnnounceInterval
import network.retalert.domain.ANNOUNCE_PRESET_VALUES
import network.retalert.domain.ANNOUNCE_PRESETS
import javax.inject.Inject

data class SettingsUiState(
    val receiveOnly: Boolean = true,
    val units: String = "km",
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val autoAnnounce: Boolean = false,
    val announceInterval: Double = 1800.0,
    val tierOverride: String = "auto",
    val shareInstanceHost: String = "",
    val includePrereleases: Boolean = false,
    val keyCombos: List<KeyCombo> = emptyList(),
    val flash: String = "",
)

/** Settings screen: receive-only-from-contacts toggle, per-sender allow/deny,
 *  distance units, auto-announce (1h/2h/3h + custom clamp), interface-tier
 *  override, share-instance host attach/detach, include-prereleases, and
 *  key-combo registration. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val keyCombos: KeyComboRepository,
) : ViewModel() {

    private val settings: Settings = settingsRepo.load()

    private val _state = MutableStateFlow(
        SettingsUiState(
            receiveOnly = settings.receiveOnlyFromContacts,
            units = settings.distanceUnits,
            allow = settings.allowlist.toList(),
            deny = settings.denylist.toList(),
            keyCombos = keyCombos.list(),
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun persist() = viewModelScope.launch { settingsRepo.save(settings) }

    fun setReceiveOnly(v: Boolean) = viewModelScope.launch {
        settings.receiveOnlyFromContacts = v
        persist()
        _state.update { it.copy(receiveOnly = v) }
    }

    fun setUnits(mi: Boolean) = viewModelScope.launch {
        settings.applyDistanceUnits(if (mi) "mi" else "km")
        persist()
        _state.update { it.copy(units = settings.distanceUnits) }
    }

    fun allow(hash: String) = viewModelScope.launch {
        val h = hash.replace(":", "").trim().lowercase()
        if (h.isBlank()) return@launch
        settings.allow(h)
        persist()
        _state.update { it.copy(allow = settings.allowlist.toList()) }
    }

    fun deny(hash: String) = viewModelScope.launch {
        val h = hash.replace(":", "").trim().lowercase()
        if (h.isBlank()) return@launch
        settings.deny(h)
        persist()
        _state.update { it.copy(deny = settings.denylist.toList()) }
    }

    fun setAutoAnnounce(enabled: Boolean, interval: Double? = null) = viewModelScope.launch {
        val clamped = clampAnnounceInterval(interval ?: _state.value.announceInterval)
        persist()
        _state.update { it.copy(autoAnnounce = enabled, announceInterval = clamped) }
    }

    fun setAnnounceInterval(seconds: Double) = viewModelScope.launch {
        val clamped = clampAnnounceInterval(seconds)
        _state.update { it.copy(announceInterval = clamped) }
    }

    fun setTierOverride(tier: String) = viewModelScope.launch {
        _state.update { it.copy(tierOverride = tier) }
    }

    fun setShareInstanceHost(host: String) = viewModelScope.launch {
        _state.update { it.copy(shareInstanceHost = host) }
    }

    fun detachShareInstance() = viewModelScope.launch {
        _state.update { it.copy(shareInstanceHost = "") }
    }

    fun setIncludePrereleases(v: Boolean) = viewModelScope.launch {
        _state.update { it.copy(includePrereleases = v) }
    }

    fun addKeyCombo(combo: String, trigger: String, arm: Boolean) = viewModelScope.launch {
        val tokens = combo.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || trigger.isBlank()) return@launch
        val list = _state.value.keyCombos.toMutableList().apply {
            removeAll { it.combo == tokens }
            add(KeyCombo(tokens, trigger, arm))
        }
        keyCombos.save(list)
        _state.update { it.copy(keyCombos = list, flash = "registered combo") }
    }

    /** Removes a registered combo by its token sequence. */
    fun removeKeyCombo(combo: List<String>) = viewModelScope.launch {
        val list = _state.value.keyCombos.filterNot { it.combo == combo }
        keyCombos.save(list)
        _state.update { it.copy(keyCombos = list) }
    }

    /** Clears the one-shot flash message after the UI has shown it. */
    fun clearFlash() = _state.update { it.copy(flash = "") }

    @Suppress("unused") fun announcePresets() = ANNOUNCE_PRESET_VALUES
    @Suppress("unused") fun announceLabels() = ANNOUNCE_PRESETS
}