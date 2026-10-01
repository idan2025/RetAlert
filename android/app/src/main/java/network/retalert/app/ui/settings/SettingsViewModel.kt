package network.retalert.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import network.retalert.app.platform.AlertNotifier
import network.retalert.app.platform.AppRestarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.ANNOUNCE_MIN_INTERVAL
import network.retalert.domain.ANNOUNCE_PRESETS
import network.retalert.domain.ANNOUNCE_PRESET_VALUES
import network.retalert.domain.DEFAULT_SHARED_INSTANCE_PORT
import network.retalert.domain.HardwareKeyManager
import network.retalert.domain.IncomingMessage
import network.retalert.domain.KeyCombo
import network.retalert.domain.KeyComboRepository
import network.retalert.domain.PresetRepository
import network.retalert.domain.Settings
import network.retalert.domain.SettingsRepository
import network.retalert.domain.clampAnnounceInterval
import network.retalert.domain.normalizeHash
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject

data class SettingsUiState(
    val receiveOnly: Boolean = true,
    val units: String = "km",
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    /** "3 of 4 interfaces on" style summary for the Connection section. */
    val interfaceSummary: String = "",
    val useSharedInstance: Boolean = true,
    val sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
    /** Currently attached as a client to another app's instance. */
    val sharedInstance: Boolean = false,
    val meshRunning: Boolean = false,
    val restarting: Boolean = false,
    val panicShareLocation: Boolean = true,
    val liveShareIntervalS: Double = 15.0,
    val liveShareMinutes: Int = 60,
    val presetNames: List<String> = emptyList(),
    val ownHash: String = "",
    val version: String = "",
    val autoAnnounce: Boolean = false,
    val announceInterval: Double = ANNOUNCE_MIN_INTERVAL,
    val keyCombos: List<KeyCombo> = emptyList(),
    val alarmOverrideSilent: Boolean = true,
    val flash: String = "",
)

/** Settings screen: connection (shared instance; interfaces live on their
 *  own screen), alarm behaviour, announce (now / auto + interval), receive-only-from-contacts, per-sender
 *  allow/deny, distance units, and hardware key-combo registration.
 *
 *  All repository access runs on [Dispatchers.IO]; each change re-reads the
 *  stored settings so it never overwrites a field another writer changed. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val keyCombos: KeyComboRepository,
    private val hardwareKeys: HardwareKeyManager,
    private val engine: ReticulumEngine,
    @ApplicationContext private val appContext: Context,
    private val presets: PresetRepository,
    private val alertNotifier: AlertNotifier,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val s = settingsRepo.load()
            publish(s)
            _state.update {
                it.copy(
                    keyCombos = keyCombos.list(),
                    presetNames = runCatching { presets.list().map { p -> p.name } }.getOrDefault(emptyList()),
                    version = runCatching {
                        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                    }.getOrNull().orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            engine.status.collect { st ->
                _state.update { it.copy(sharedInstance = st.sharedInstance, meshRunning = st.running, ownHash = st.deliveryHash) }
            }
        }
    }

    private fun publish(s: Settings, flash: String? = null) = _state.update {
        it.copy(
            receiveOnly = s.receiveOnlyFromContacts,
            units = s.distanceUnits,
            allow = s.allowlist.sorted(),
            deny = s.denylist.sorted(),
            interfaceSummary = s.interfaces.let { l -> "${l.count { it.enabled }} of ${l.size} on" },
            alarmOverrideSilent = s.alarmOverrideSilent,
            useSharedInstance = s.useSharedInstance,
            panicShareLocation = s.panicShareLocation,
            liveShareIntervalS = s.liveShareIntervalS,
            liveShareMinutes = s.liveShareMinutes,
            sharedInstancePort = s.sharedInstancePort,
            autoAnnounce = s.autoAnnounce,
            announceInterval = s.announceInterval,
            flash = flash ?: it.flash,
        )
    }

    /** Load → modify → save → publish, on IO. [block] returns an optional flash message. */
    private fun mutate(block: (Settings) -> String?) = viewModelScope.launch(Dispatchers.IO) {
        val s = settingsRepo.load()
        val msg = block(s)
        settingsRepo.save(s)
        publish(s, msg)
    }

    fun setReceiveOnly(v: Boolean) = mutate { it.receiveOnlyFromContacts = v; null }

    fun setUnits(mi: Boolean) = mutate { it.applyDistanceUnits(if (mi) "mi" else "km"); null }

    fun allow(hash: String) = mutate { s ->
        val h = normalizeHash(hash) ?: return@mutate "invalid hash: needs 32 hex characters"
        s.allow(h); "allowing ${h.take(8)}…"
    }

    fun deny(hash: String) = mutate { s ->
        val h = normalizeHash(hash) ?: return@mutate "invalid hash: needs 32 hex characters"
        s.deny(h); "blocking ${h.take(8)}…"
    }

    fun forget(hash: String) = mutate { it.forget(hash); null }

    // -- emergency location sharing ------------------------------------------

    fun setPanicShareLocation(v: Boolean) = mutate { it.panicShareLocation = v; null }
    fun setLiveShareInterval(seconds: Double) = mutate { it.liveShareIntervalS = seconds; null }
    fun setLiveShareMinutes(minutes: Int) = mutate { it.liveShareMinutes = minutes; null }

    fun setAlarmOverrideSilent(v: Boolean) = mutate { it.alarmOverrideSilent = v; null }

    /** Ring a local fake alert exactly as a real one would. */
    fun testAlarm() = viewModelScope.launch(Dispatchers.IO) {
        alertNotifier.onAlert(
            IncomingMessage(
                sourceHash = "0".repeat(32), text = "Test alert — this is how an incoming alert rings.",
                timestamp = System.currentTimeMillis() / 1000.0, kind = "alert", severity = "test",
                alertId = "test-alarm",
            ),
        )
    }

    /** Re-read after returning from the Interfaces screen. */
    fun refresh() = viewModelScope.launch(Dispatchers.IO) { publish(settingsRepo.load()) }

    // -- shared instance (Columba, Sideband, MeshChat …) --------------------

    /** Use (or stop using) another app's shared instance; reconnects the stack. */
    fun setUseSharedInstance(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val s = settingsRepo.load()
        s.useSharedInstance = enabled
        settingsRepo.save(s)
        publish(s)
        restartMesh()
    }

    fun setSharedInstancePort(port: String) = viewModelScope.launch(Dispatchers.IO) {
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        if (p == null) {
            _state.update { it.copy(flash = "invalid port") }
            return@launch
        }
        val s = settingsRepo.load()
        s.sharedInstancePort = p
        settingsRepo.save(s)
        publish(s)
        restartMesh()
    }

    /** Reconnect: re-probes for a shared instance (e.g. Columba started after
     *  RetAlert) or falls back to standalone. */
    fun reconnect() = viewModelScope.launch(Dispatchers.IO) { restartMesh() }

    /** Connection-mode changes need a fresh process (see [AppRestarter]).
     *  Pending alerts are replayed from the outbox after the restart. */
    private fun restartMesh() {
        _state.update { it.copy(restarting = true, flash = "restarting RetAlert…") }
        AppRestarter.restart(appContext)
    }

    // -- announce ----------------------------------------------------------

    fun announceNow() = viewModelScope.launch(Dispatchers.IO) {
        val msg = if (!engine.isRunning) "mesh not running yet"
        else runCatching { engine.announceNow() }.fold({ "announced" }, { "announce failed: ${it.message}" })
        _state.update { it.copy(flash = msg) }
    }

    fun setAutoAnnounce(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val interval = runCatching { engine.setAutoAnnounce(enabled, _state.value.announceInterval) }
            .getOrElse { clampAnnounceInterval(_state.value.announceInterval) }
        _state.update { it.copy(autoAnnounce = enabled, announceInterval = interval) }
    }

    /** Commit a new interval (slider release or preset chip). */
    fun setAnnounceInterval(seconds: Double) = viewModelScope.launch(Dispatchers.IO) {
        val interval = runCatching { engine.setAutoAnnounce(_state.value.autoAnnounce, seconds) }
            .getOrElse { clampAnnounceInterval(seconds) }
        _state.update { it.copy(announceInterval = interval) }
    }

    // -- hardware keys -------------------------------------------------------

    fun addKeyCombo(combo: String, trigger: String, arm: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val tokens = combo.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || trigger.isBlank()) {
            _state.update { it.copy(flash = "combo and trigger are required") }
            return@launch
        }
        val list = keyCombos.list().toMutableList().apply {
            removeAll { it.combo == tokens }
            add(KeyCombo(tokens, trigger.trim(), arm))
        }
        saveCombos(list, "registered combo")
    }

    /** Removes a registered combo by its token sequence. */
    fun removeKeyCombo(combo: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        saveCombos(keyCombos.list().filterNot { it.combo == combo }, "removed combo")
    }

    /** Persist and push into the live manager the accessibility service uses. */
    private fun saveCombos(list: List<KeyCombo>, flash: String) {
        keyCombos.save(list)
        hardwareKeys.clear()
        list.forEach { hardwareKeys.register(it.combo, it.trigger, it.arm) }
        _state.update { it.copy(keyCombos = list, flash = flash) }
    }

    /** Clears the one-shot flash message after the UI has shown it. */
    fun clearFlash() = _state.update { it.copy(flash = "") }

    fun announcePresets() = ANNOUNCE_PRESET_VALUES
    fun announceLabels() = ANNOUNCE_PRESETS
}
