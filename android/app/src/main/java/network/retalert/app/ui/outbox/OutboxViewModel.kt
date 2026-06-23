package network.retalert.app.ui.outbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.retalert.domain.OutboxRepository
import javax.inject.Inject

data class OutboxRow(
    val alertId: String,
    val severity: String,
    val text: String,
    val states: List<RecipientState>,
)

data class RecipientState(val recipient: String, val state: String)

data class OutboxUiState(val rows: List<OutboxRow> = emptyList())

/** Outbox screen: per-recipient ack-state (SENT/DELIVERED/ACKED/REPLIED/FAILED)
 *  per sent alert. */
@HiltViewModel
class OutboxViewModel @Inject constructor(
    private val outbox: OutboxRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OutboxUiState())
    val state: StateFlow<OutboxUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = OutboxUiState(
            rows = outbox.pending().map { a ->
                val states = outbox.ackStates(a.alertId).map { (r, s) -> RecipientState(r, s) }
                OutboxRow(a.alertId, a.severity, a.text, states)
            },
        )
    }
}