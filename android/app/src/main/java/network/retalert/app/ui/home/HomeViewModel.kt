package network.retalert.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.app.platform.AlertDispatcher
import network.retalert.app.platform.LocationSharer
import network.retalert.app.platform.ShareState
import network.retalert.domain.InboxRepository
import network.retalert.domain.OutboxRepository
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject

/** One interface chip on the Home status line. */
data class IfaceChip(val name: String, val tier: String, val online: Boolean = true)

/** One row in the Home status feed (recent sent + received). */
data class FeedItem(val id: String, val line: String, val kind: String, val time: Double = 0.0)

data class HomeUiState(
    val ownHash: String = "",
    val meshStatus: String = "starting…",
    val interfaces: List<IfaceChip> = emptyList(),
    val feed: List<FeedItem> = emptyList(),
    val flash: String = "",
    val firing: Boolean = false,
    val share: ShareState = ShareState(),
)

/** Home screen: panic button (hold-to-confirm), live interface chips, own
 *  delivery hash, and a status feed of recent sent/received alerts. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val engine: ReticulumEngine,
    private val dispatcher: AlertDispatcher,
    private val outbox: OutboxRepository,
    private val inbox: InboxRepository,
    private val sharer: LocationSharer,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            engine.status.collect { s ->
                _state.update {
                    it.copy(
                        ownHash = s.deliveryHash.takeIf { s.running }.orEmpty(),
                        meshStatus = when {
                            s.running && s.sharedInstance -> "attached to shared instance"
                            s.running -> "mesh running"
                            s.error.isNotEmpty() -> "mesh failed: ${s.error}"
                            else -> "starting…"
                        },
                        interfaces = s.interfaces.map { i -> IfaceChip(i.name, i.tier, i.online) },
                    )
                }
            }
        }
        viewModelScope.launch { sharer.state.collect { s -> _state.update { it.copy(share = s) } } }
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(FEED_REFRESH_MS)
            }
        }
    }

    /** Fire the panic alert ('default' preset, else every contact). */
    fun panic() {
        if (_state.value.firing) return
        _state.update { it.copy(firing = true, flash = "firing…") }
        viewModelScope.launch(Dispatchers.IO) {
            val flash = runCatching { dispatcher.panic() }.fold(
                onSuccess = { (alert, target) -> "sent ${alert.alertId.take(8)}… to $target" },
                onFailure = { "not sent: ${it.message}" },
            )
            _state.update { it.copy(firing = false, flash = flash) }
            refresh()
        }
    }

    fun stopSharing() = sharer.stop()

    /** Refresh the status feed from the inbox + outbox. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val feed = runCatching {
                val sent = outbox.pending().map {
                    FeedItem(it.alertId, "[${it.severity}] ${it.text.take(60)}", "sent", it.createdAt)
                }
                val received = inbox.list().map {
                    FeedItem(it.alertId, "[${it.severity}] ${it.text.take(60)}", "in", it.receivedAt)
                }
                (sent + received).sortedByDescending { it.time }.take(20)
            }.getOrDefault(emptyList())
            _state.update { it.copy(feed = feed) }
        }
    }

    private companion object { const val FEED_REFRESH_MS = 3000L }
}
