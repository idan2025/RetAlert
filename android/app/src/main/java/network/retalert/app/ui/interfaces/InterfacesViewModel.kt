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
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import network.retalert.app.platform.AppRestarter
import network.retalert.domain.DEFAULT_SHARED_INSTANCE_PORT
import network.retalert.domain.IfaceConfig
import network.retalert.reticulum.UsbSerial
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
    /** "Use shared instance" setting (Columba, Sideband, MeshChat on this phone). */
    val useSharedInstance: Boolean = true,
    val sharedInstancePort: Int = DEFAULT_SHARED_INSTANCE_PORT,
    val restarting: Boolean = false,
    /** USB serial devices plugged in right now. */
    val usbDevices: List<UsbOption> = emptyList(),
    val flash: String = "",
)

data class UsbOption(val spec: String, val label: String, val hasPermission: Boolean)

/** Interfaces screen: add / edit / enable / remove the interfaces of
 *  RetAlert's own stack. Every change is applied to the running stack at once. */
@HiltViewModel
class InterfacesViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val engine: ReticulumEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(InterfacesUiState())
    val state: StateFlow<InterfacesUiState> = _state.asStateFlow()
    private var configs: List<IfaceConfig> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val s = settingsRepo.load()
            configs = s.interfaces.toList()
            _state.update { it.copy(useSharedInstance = s.useSharedInstance, sharedInstancePort = s.sharedInstancePort) }
            publish(engine.status.value)
        }
        refreshUsb()
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

    // -- USB --------------------------------------------------------------

    fun refreshUsb() = viewModelScope.launch(Dispatchers.IO) {
        val list = runCatching { UsbSerial.list(appContext) }.getOrDefault(emptyList())
        _state.update { it.copy(usbDevices = list.map { d -> UsbOption(d.spec, d.label, d.hasPermission) }) }
    }

    /** Ask Android for access; [UsbPermissionReceiver] reconnects once granted. */
    fun requestUsb(spec: String) {
        val d = UsbSerial.list(appContext).firstOrNull { spec.isEmpty() || it.spec == spec } ?: run {
            _state.update { it.copy(flash = "plug the device in first") }
            return
        }
        UsbSerial.requestPermission(appContext, d.device)
    }

    /** Retry interfaces now (e.g. after USB access was granted). */
    fun reconnectInterfaces() = viewModelScope.launch(Dispatchers.IO) { runCatching { engine.reloadInterfaces() } }

    // -- shared instance ----------------------------------------------------

    /** Same setting as Settings → Connection; switching restarts RetAlert (see [AppRestarter]). */
    fun setUseSharedInstance(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val s = settingsRepo.load()
        s.useSharedInstance = enabled
        settingsRepo.save(s)
        restart()
    }

    fun setSharedInstancePort(port: String) = viewModelScope.launch(Dispatchers.IO) {
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return@launch _state.update { it.copy(flash = "invalid port") }
        val s = settingsRepo.load()
        s.sharedInstancePort = p
        settingsRepo.save(s)
        restart()
    }

    fun reconnectShared() = viewModelScope.launch(Dispatchers.IO) { restart() }

    private fun restart() {
        _state.update { it.copy(restarting = true, flash = "restarting RetAlert…") }
        AppRestarter.restart(appContext)
    }
}
