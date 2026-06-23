package network.retalert.app.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.app.platform.media.OpusAudioRecorder
import network.retalert.domain.Alert
import network.retalert.domain.ContactRepository
import network.retalert.domain.FanOut
import network.retalert.domain.GroupRepository
import network.retalert.domain.MediaChannel
import network.retalert.domain.OutboxRepository
import network.retalert.domain.RealClock
import network.retalert.domain.Severity
import javax.inject.Inject

data class SendUiState(
    val text: String = "",
    val severity: String = Severity.HELP,
    val target: String = "",
    val flash: String = "",
    val sending: Boolean = false,
    val attachedPhoto: ByteArray? = null,
    val isRecording: Boolean = false,
    val audioChunks: Int = 0,
)

/** Send screen: compose an alert (text + severity + recipient + optional photo/audio)
 *  and dispatch it. Recipient resolves a contact name, a group name, or a raw hex hash.
 *
 *  Media (photo + audio) is High-tier-only and routed through [MediaChannel].
 *
 *  Audio approach: opus-encoded chunks are buffered in the VM as they are produced by
 *  [OpusAudioRecorder] (live capture emits on a background thread), then flushed to
 *  [MediaChannel.sendAudioChunk] under a fresh alertId on [send]. This avoids needing an
 *  alertId before recording starts and keeps the wire order deterministic (ascending seq). */
@HiltViewModel
class SendViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val groups: GroupRepository,
    private val outbox: OutboxRepository,
    private val mediaChannel: MediaChannel,
    private val recorder: OpusAudioRecorder,
) : ViewModel() {

    // Buffered encoded audio chunks awaiting flush on send.
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioLock = Any()

    private val _state = MutableStateFlow(SendUiState())
    val state: StateFlow<SendUiState> = _state.asStateFlow()

    init {
        // Route encoded chunks into the buffer; the VM flushes them on send().
        recorder.onChunk = { chunk ->
            synchronized(audioLock) { audioBuffer.add(chunk) }
            _state.update { it.copy(audioChunks = synchronized(audioLock) { audioBuffer.size }) }
        }
    }

    fun onText(v: String) = _state.update { it.copy(text = v) }
    fun onSeverity(v: String) = _state.update { it.copy(severity = v) }
    fun onTarget(v: String) = _state.update { it.copy(target = v) }

    /** Accept a captured JPEG from the camera surface. */
    fun onPhotoCaptured(bytes: ByteArray) = _state.update { it.copy(attachedPhoto = bytes) }

    /** Clear the attached photo. */
    fun clearPhoto() = _state.update { it.copy(attachedPhoto = null) }

    /** Start opus audio recording. No-op if the recorder refuses (permission/encoder). */
    fun startRecording() {
        recorder.start()
        if (recorder.isRecording) {
            synchronized(audioLock) { audioBuffer.clear() }
            _state.update { it.copy(isRecording = true, audioChunks = 0) }
        }
    }

    /** Stop opus audio recording. */
    fun stopRecording() {
        recorder.stop()
        _state.update { it.copy(isRecording = false) }
    }

    /** Resolve [target] to a list of destination hashes.
     *  Contact name → its hash; group name → members; else raw hex. */
    private fun resolve(target: String): List<String> {
        val t = target.trim()
        if (t.isEmpty()) return emptyList()
        contacts.list().firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return listOf(it.hash) }
        groups.list().firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it.members }
        return listOf(t.replace(":", "").lowercase())
    }

    fun send() {
        val s = _state.value
        if (s.target.isBlank() || s.text.isBlank()) {
            _state.update { it.copy(flash = "need a recipient and a message") }
            return
        }
        val recipients = resolve(s.target)
        if (recipients.isEmpty()) {
            _state.update { it.copy(flash = "could not resolve recipient") }
            return
        }
        _state.update { it.copy(sending = true, flash = "sending…") }
        viewModelScope.launch {
            val alert = Alert.new(
                clock = RealClock,
                severity = Severity.normalize(s.severity),
                text = s.text,
                recipients = recipients,
                payload = mapOf("text" to true),
                fanOut = FanOut.CRITICAL,
            )
            outbox.enqueue(alert)

            // Photo: chunk + send immediately (High-tier gate applied inside MediaChannel).
            val photo = s.attachedPhoto
            if (photo != null && photo.isNotEmpty()) {
                val n = mediaChannel.sendPhoto(alert.alertId, photo)
                _state.update { it.copy(attachedPhoto = null, flash = "sent ${alert.alertId.take(8)}… (photo $n chunks)") }
            } else {
                _state.update { it.copy(flash = "sent ${alert.alertId.take(8)}…") }
            }

            // Audio: flush buffered encoded chunks with ascending seq indices.
            val chunks = synchronized(audioLock) { audioBuffer.toList() }
            synchronized(audioLock) { audioBuffer.clear() }
            chunks.forEachIndexed { seq, chunk ->
                mediaChannel.sendAudioChunk(alert.alertId, chunk, seq)
            }
            if (chunks.isNotEmpty()) {
                _state.update { it.copy(flash = it.flash + " (audio ${chunks.size} chunks)") }
            }

            _state.update { it.copy(sending = false, text = "", audioChunks = 0) }
        }
    }

    override fun onCleared() {
        if (recorder.isRecording) recorder.stop()
    }
}