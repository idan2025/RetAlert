package network.retalert.app.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.app.platform.AlertDispatcher
import network.retalert.domain.ContactRepository
import network.retalert.domain.GroupRepository
import network.retalert.domain.Severity
import network.retalert.domain.normalizeHash
import javax.inject.Inject

data class SendUiState(
    val text: String = "",
    val severity: String = Severity.HELP,
    val target: String = "",
    val flash: String = "",
    val sending: Boolean = false,
    /** Quick-pick targets: contact names then group names. */
    val suggestions: List<String> = emptyList(),
)

/** Send screen: compose an alert (text + severity + recipient) and dispatch it.
 *  Recipient resolves a contact name, a group name, or a raw hex hash.
 *
 *  Photo/audio capture exists in `platform/media`, but media needs a live RNS
 *  Link to the peer, which nothing establishes yet ([network.retalert.reticulum.RnsMediaLink]),
 *  so it is not offered here until that path can actually deliver. */
@HiltViewModel
class SendViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val groups: GroupRepository,
    private val dispatcher: AlertDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(SendUiState())
    val state: StateFlow<SendUiState> = _state.asStateFlow()

    init { refreshSuggestions() }

    fun refreshSuggestions() = viewModelScope.launch(Dispatchers.IO) {
        val names = runCatching {
            contacts.list().map { it.name } + groups.list().map { it.name }
        }.getOrDefault(emptyList())
        _state.update { it.copy(suggestions = names.filter { n -> n.isNotBlank() }.distinct()) }
    }

    fun onText(v: String) = _state.update { it.copy(text = v) }
    fun onSeverity(v: String) = _state.update { it.copy(severity = v) }
    fun onTarget(v: String) = _state.update { it.copy(target = v) }

    /** Resolve [target] to a list of destination hashes.
     *  Contact name → its hash; group name → members; else raw hex. */
    private fun resolve(target: String): List<String> {
        val t = target.trim()
        if (t.isEmpty()) return emptyList()
        contacts.list().firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return listOf(it.hash) }
        groups.list().firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it.members }
        return listOfNotNull(normalizeHash(t))
    }

    fun send() {
        val s = _state.value
        if (s.target.isBlank() || s.text.isBlank()) {
            _state.update { it.copy(flash = "need a recipient and a message") }
            return
        }
        _state.update { it.copy(sending = true, flash = "sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val recipients = resolve(s.target)
                require(recipients.isNotEmpty()) {
                    "unknown recipient: use a contact or group name, or a 32-character hash"
                }
                dispatcher.send(s.severity, s.text, recipients)
            }
            _state.update {
                result.fold(
                    onSuccess = { a ->
                        it.copy(sending = false, text = "", flash = "queued ${a.alertId.take(8)}… for ${a.recipients.size} recipient(s) — see Outbox")
                    },
                    onFailure = { e -> it.copy(sending = false, flash = "not sent: ${e.message}") },
                )
            }
        }
    }
}
