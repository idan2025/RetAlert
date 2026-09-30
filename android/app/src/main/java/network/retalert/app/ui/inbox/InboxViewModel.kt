package network.retalert.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.ContactRepository
import network.retalert.domain.InboxRepository
import network.retalert.domain.LiveTrackStore
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject

data class InboxUiState(
    val entries: List<InboxRow> = emptyList(),
    val flash: String = "",
)

data class InboxRow(
    val alertId: String,
    val sourceHash: String,
    val sourceName: String,
    val severity: String,
    val text: String,
    /** The sender is sharing a location we can show on the map. */
    val hasLocation: Boolean = false,
)

/** Inbox screen: received alerts list, reply (canned/text) + manual ack by
 *  alert_id — both sent back to the alert's sender over LXMF. */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inbox: InboxRepository,
    private val contacts: ContactRepository,
    private val engine: ReticulumEngine,
    private val tracks: LiveTrackStore,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val rows = runCatching {
            val names = contacts.list().associate { it.hash to it.name }
            inbox.list().map { e ->
                InboxRow(
                    e.alertId, e.sourceHash, names[e.sourceHash].orEmpty(), e.severity, e.text,
                    hasLocation = tracks.get(e.sourceHash) != null,
                )
            }
        }.getOrDefault(emptyList())
        _state.update { it.copy(entries = rows) }
    }

    /** Re-send the app-level ack for an alert to its sender. */
    fun ackAlert(row: InboxRow) = viewModelScope.launch(Dispatchers.IO) {
        val flash = runCatching { engine.ack(row.alertId, row.sourceHash) }
            .fold({ "ack sent for ${row.alertId.take(8)}…" }, { "ack failed: ${it.message}" })
        _state.update { it.copy(flash = flash) }
    }

    /** Send a reply (ack + canned or free text) to the alert's sender. */
    fun reply(row: InboxRow, text: String) = viewModelScope.launch(Dispatchers.IO) {
        val flash = runCatching { engine.reply(row.alertId, row.sourceHash, text) }
            .fold({ if (text.isBlank()) "replied to ${row.alertId.take(8)}…" else "replied: $text" }, { "reply failed: ${it.message}" })
        _state.update { it.copy(flash = flash) }
    }

    /** Follow the sender on the map. Returns false if they share no location. */
    fun showOnMap(row: InboxRow): Boolean = tracks.follow(row.sourceHash)

    fun remove(row: InboxRow) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { inbox.remove(row.alertId) }
        refresh()
    }
}
