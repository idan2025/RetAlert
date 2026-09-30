package network.retalert.app.ui.outbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.retalert.domain.AckState
import network.retalert.domain.ContactRepository
import network.retalert.domain.OutboxRepository
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject

data class OutboxRow(
    val alertId: String,
    val severity: String,
    val text: String,
    val states: List<RecipientState>,
) {
    /** Still retrying at least one recipient. */
    val active: Boolean get() = states.any { it.state == AckState.SENT }
}

data class RecipientState(val recipient: String, val state: String, val label: String = recipient.take(6) + "…")

data class OutboxUiState(val rows: List<OutboxRow> = emptyList())

/** Outbox screen: per-recipient ack-state (SENT/DELIVERED/ACKED/REPLIED/FAILED)
 *  per sent alert, newest first. */
@HiltViewModel
class OutboxViewModel @Inject constructor(
    private val outbox: OutboxRepository,
    private val contacts: ContactRepository,
    private val engine: ReticulumEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(OutboxUiState())
    val state: StateFlow<OutboxUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val rows = runCatching {
            val names = contacts.list().associate { it.hash to it.name }
            outbox.pending().sortedByDescending { it.createdAt }.map { a ->
                val states = outbox.ackStates(a.alertId).map { (r, s) ->
                    RecipientState(r, s, names[r] ?: (r.take(6) + "…"))
                }
                OutboxRow(a.alertId, a.severity, a.text, states)
            }
        }.getOrElse { return@launch }
        _state.value = OutboxUiState(rows)
    }

    /** Stop retrying (if still active) and drop the alert from the outbox. */
    fun remove(alertId: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { engine.cancelAlert(alertId) }
        refresh()
    }
}
