package network.retalert.app.ui.interfaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.IfaceConfig
import network.retalert.domain.IfaceType
import network.retalert.domain.SettingsRepository
import network.retalert.reticulum.EngineStatus
import network.retalert.reticulum.ReticulumEngine
import java.util.UUID
import javax.inject.Inject

/** One configured interface with its live state. */
data class IfaceRow(val config: IfaceConfig, val status: String, val online: Boolean, val error: Boolean)

data class InterfacesUiState(
    val rows: List<IfaceRow> = emptyList(),
    /** Attached to Columba/Sideband: these interfaces are not in use. */
    val sharedInstance: Boolean = false,
    val meshRunning: Boolean = false,
    val flash: String = "",
)

/** Interfaces screen: add / edit / enable / remove the interfaces of
 *  RetAlert's own stack. Every change is applied to the running stack at once. */
@HiltViewModel
class InterfacesViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val engine: ReticulumEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(InterfacesUiState())
    val state: StateFlow<InterfacesUiState> = _state.asStateFlow()
    private var configs: List<IfaceConfig> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            configs = settingsRepo.load().interfaces.toList()
            publish(engine.status.value)
        }
        viewModelScope.launch { engine.status.collect { publish(it) } }
    }

    private fun publish(st: EngineStatus) = _state.update {
        it.copy(
            rows = configs.map { c -> row(c, st) },
            sharedInstance = st.sharedInstance,
            meshRunning = st.running,
        )
    }

    private fun row(c: IfaceConfig, st: EngineStatus): IfaceRow {
        if (!c.enabled) return IfaceRow(c, "Off", online = false, error = false)
        if (st.sharedInstance) return IfaceRow(c, "Not used — on a shared instance", online = false, error = false)
        if (!st.running) return IfaceRow(c, "Mesh not running", online = false, error = false)
        st.ifaceErrors[c.id]?.let { return IfaceRow(c, "Failed: $it", online = false, error = true) }
        val name = engine.interfaceName(c)
        val live = st.interfaces.firstOrNull { it.name == name }
        return when {
            live == null -> IfaceRow(c, "Starting…", online = false, error = false)
            live.online -> IfaceRow(c, "Online · ${live.tier} bandwidth", online = true, error = false)
            else -> IfaceRow(c, "Offline — retrying", online = false, error = false)
        }
    }

    /** Load → modify → save → apply to the stack, on IO. [block] returns an error or null. */
    private fun change(ok: String, block: (network.retalert.domain.Settings) -> String?) =
        viewModelScope.launch(Dispatchers.IO) {
            val s = settingsRepo.load()
            val err = block(s)
            if (err != null) {
                _state.update { it.copy(flash = err) }
                return@launch
            }
            settingsRepo.save(s)
            configs = s.interfaces.toList()
            publish(engine.status.value)
            _state.update { it.copy(flash = ok) }
            runCatching { engine.reloadInterfaces() }
        }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun save(c: IfaceConfig) = change("${c.name} saved") { it.upsertInterface(c.copy(name = c.name.trim())) }

    fun setEnabled(id: String, enabled: Boolean) =
        change(if (enabled) "Interface on" else "Interface off") { s ->
            if (s.setInterfaceEnabled(id, enabled)) null else "interface not found"
        }

    fun remove(id: String) = change("Interface removed") { s ->
        if (s.removeInterface(id)) null else "interface not found"
    }

    /** Types that can still be added (singletons only once). */
    fun addableTypes(): List<String> =
        IfaceType.ALL.filter { t -> t !in IfaceType.SINGLETON || configs.none { it.type == t } }

    fun clearFlash() = _state.update { it.copy(flash = "") }
}
