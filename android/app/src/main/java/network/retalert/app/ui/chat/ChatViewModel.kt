package network.retalert.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.ChatBubble
import network.retalert.domain.ChatRepository
import network.retalert.domain.ContactRepository
import network.retalert.domain.DEFAULT_QUICK_REPLIES
import network.retalert.domain.InboxRepository
import network.retalert.domain.OutboxRepository
import network.retalert.domain.SettingsRepository
import network.retalert.domain.chatBubbles
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject

data class ChatUiState(
    val alertId: String = "",
    val severity: String = "",
    val alertText: String = "",
    /** True: we sent the alert (thread with all its recipients). */
    val sentByUs: Boolean = false,
    /** How many people the thread is with (1 for a received alert). */
    val recipients: Int = 1,
    /** Who the thread is with, by name. */
    val with: String = "",
    val bubbles: List<ChatBubble> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    val flash: String = "",
)

/** One alert's chat thread: the alert, replies and free-text messages. */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val chat: ChatRepository,
    private val inbox: InboxRepository,
    private val outbox: OutboxRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val engine: ReticulumEngine,
) : ViewModel() {
    private val alertId: String = checkNotNull(savedState["alertId"])
    private val _state = MutableStateFlow(ChatUiState(alertId = alertId))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val subscription = chat.observe { id -> if (id == alertId) refresh() }

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val names = runCatching { contacts.list().associate { it.hash to it.name } }.getOrDefault(emptyMap())
        fun name(h: String) = names[h] ?: (h.take(8) + "…")
        val sent = runCatching { outbox.pending().firstOrNull { it.alertId == alertId } }.getOrNull()
        val received = if (sent == null) runCatching { inbox.get(alertId) }.getOrNull() else null
        val bubbles = runCatching { chatBubbles(chat.thread(alertId)) }.getOrDefault(emptyList())
        val quick = runCatching { settings.load().quickReplies.toList() }.getOrDefault(DEFAULT_QUICK_REPLIES)
        _state.update {
            it.copy(
                severity = sent?.severity ?: received?.severity.orEmpty(),
                alertText = sent?.text ?: received?.text.orEmpty(),
                sentByUs = sent != null,
                recipients = sent?.recipients?.size ?: 1,
                with = sent?.recipients?.joinToString(", ") { r -> name(r) } ?: received?.sourceHash?.let(::name).orEmpty(),
                bubbles = bubbles,
                names = names,
                quickReplies = quick,
            )
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching { engine.sendChat(alertId, t) }.getOrDefault(0)
            if (n == 0) _state.update { it.copy(flash = "Nobody to send to — the alert is gone") }
        }
    }

    fun clearFlash() = _state.update { it.copy(flash = "") }

    override fun onCleared() { subscription.close() }
}
