package network.retalert.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.AckState
import network.retalert.domain.InboxRepository
import network.retalert.domain.OutboxRepository
import javax.inject.Inject

data class InboxUiState(
    val entries: List<InboxRow> = emptyList(),
    val flash: String = "",
)

data class InboxRow(
    val alertId: String,
    val sourceHash: String,
    val severity: String,
    val text: String,
)

/** Inbox screen: received alerts list, reply (canned/text) + manual ack by
 *  alert_id. */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inbox: InboxRepository,
    private val outbox: OutboxRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(entries = inbox.list().map { e ->
            InboxRow(e.alertId, e.sourceHash, e.severity, e.text)
        }) }
    }

    /** Manual ack by alert_id: marks the matching sent alert's recipient acked. */
    fun ackAlert(alertId: String) = viewModelScope.launch {
        outbox.ackStates(alertId).keys.forEach { r -> outbox.setAckState(alertId, r, AckState.ACKED) }
        _state.update { it.copy(flash = "acked $alertId") }
        refresh()
    }

    /** Send a reply (canned or free text) — records an ack+reply on the outbox. */
    fun reply(alertId: String, text: String) = viewModelScope.launch {
        outbox.ackStates(alertId).keys.forEach { r ->
            outbox.setAckState(alertId, r, AckState.REPLIED)
        }
        _state.update { it.copy(flash = if (text.isBlank()) "replied $alertId" else "replied: $text") }
    }
}