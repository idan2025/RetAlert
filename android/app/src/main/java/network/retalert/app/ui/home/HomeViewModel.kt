package network.retalert.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.Alert
import network.retalert.domain.InboxRepository
import network.retalert.domain.OutboxRepository
import network.retalert.domain.PresetRepository
import network.retalert.domain.RealClock
import network.retalert.domain.randomHex
import network.retalert.domain.Severity
import network.retalert.domain.FanOut
import javax.inject.Inject

/** One interface chip on the Home status line. */
data class IfaceChip(val name: String, val tier: String)

/** One row in the Home status feed (recent sent + received). */
data class FeedItem(val id: String, val line: String, val kind: String)

data class HomeUiState(
    val ownHash: String = "",
    val interfaces: List<IfaceChip> = emptyList(),
    val feed: List<FeedItem> = emptyList(),
    val flash: String = "",
    val firing: Boolean = false,
)

/** Home screen: panic button (hold-to-confirm), live interface chips, own
 *  delivery hash, and a status feed of recent sent/received alerts. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val presets: PresetRepository,
    private val outbox: OutboxRepository,
    private val inbox: InboxRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeUiState(
            ownHash = randomHex(16),
            interfaces = listOf(
                IfaceChip("AutoInterface", "medium"),
                IfaceChip("RNode", "low"),
            ),
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { refresh() }

    /** Fire the "default" preset (panic). Resolves the preset, builds an Alert,
     *  enqueues it in the outbox, and surfaces the alert_id. */
    fun panic() {
        if (_state.value.firing) return
        _state.update { it.copy(firing = true, flash = "firing…") }
        viewModelScope.launch {
            val preset = presets.byName("default")
            if (preset == null) {
                _state.update { it.copy(firing = false, flash = "no preset to fire (configure one)") }
                return@launch
            }
            val recipients = preset.recipients.ifEmpty { outbox.pending().flatMap { it.recipients }.distinct() }
            val alert = Alert.new(
                clock = RealClock,
                severity = Severity.normalize(preset.severity),
                text = preset.text,
                recipients = preset.recipients,
                payload = preset.payload,
                retryInterval = preset.retryInterval,
                maxAttempts = preset.maxAttempts,
                fanOut = FanOut.normalize(preset.fanOut),
            )
            outbox.enqueue(alert)
            _state.update {
                it.copy(firing = false, flash = "sent alert ${alert.alertId.take(8)}…")
            }
            refresh()
        }
    }

    /** Refresh the status feed from the inbox + outbox. */
    fun refresh() {
        viewModelScope.launch {
            val sent = outbox.pending().map {
                FeedItem(it.alertId, "[${it.severity}] ${it.text.take(60)}", "sent")
            }
            val received = inbox.list().map {
                FeedItem(it.alertId, "[${it.severity}] ${it.text.take(60)}", "in")
            }
            _state.update { it.copy(feed = (sent + received).take(20)) }
        }
    }
}