package network.retalert.reticulum

import network.retalert.domain.LxmfOut
import network.retalert.domain.Wire

import network.retalert.domain.AckTracker
import network.retalert.domain.ChatMessage
import network.retalert.domain.ChatRepository
import network.retalert.domain.ChatState
import network.retalert.domain.OutboxRepository
import network.retalert.domain.Contacts
import network.retalert.domain.Discover
import network.retalert.domain.IncomingDispatcher
import network.retalert.domain.IncomingMessage
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.ReceiveSettings
import network.retalert.domain.SettingsReceiveSettings
import network.retalert.domain.encodeAck
import network.retalert.domain.encodeReply
import network.retalert.reticulum.lxmf.LxmfRouter

/**
 * App-facing callback for parsed inbound messages that should bypass silent
 * mode (app-to-app alerts). Implemented in :app by [network.retalert.app.platform.AlertNotifier]
 * (high-importance, bypass-DnD full-screen-intent notification), bound via
 * [network.retalert.app.platform.PlatformModule].
 */
interface IncomingNotifier {
    fun onAlert(msg: IncomingMessage)
    /** A recipient of one of our alerts replied ("On my way"…). */
    fun onReply(alertId: String, sourceHex: String, senderName: String, text: String) {}
    /** A chat message arrived in an alert's thread. */
    fun onChat(alertId: String, sourceHex: String, senderName: String, text: String) {}
}

/**
 * Builds the [IncomingDispatcher] wired to the live stores + transport.
 * Parity with `retalert/daemon.py` `_route_incoming` / `_on_parsed_incoming` /
 * `_send_ack` / `_on_inbound_ack` / `_on_inbound_reply`.
 */
class IncomingWiring(
    private val ackTracker: AckTracker,
    private val lxmf: LxmfRouter,
    private val inbox: network.retalert.domain.InboxRepository,
    private val notifier: IncomingNotifier,
    /** "Old message format" setting (see [network.retalert.domain.Wire]). */
    private val legacyWire: () -> Boolean = { false },
    private val chat: ChatRepository? = null,
    private val outbox: OutboxRepository? = null,
) {
    private fun now() = System.currentTimeMillis() / 1000.0

    private fun incomingChat(alertId: String, src: String, name: String, text: String, notify: Boolean) {
        val repo = chat ?: return
        runCatching { repo.add(ChatMessage(alertId = alertId, peer = src, outgoing = false, text = text, ts = now(), state = ChatState.RECEIVED)) }
        if (notify) runCatching { notifier.onChat(alertId, src, name, text) }
    }

    /**
     * Plain text from someone we share a recent alert with (e.g. a Columba user
     * answering in their chat) belongs in that alert's thread: the newest alert
     * they sent us or we sent them in the last [THREAD_WINDOW_S].
     */
    private fun threadFor(src: String): String? {
        val since = now() - THREAD_WINDOW_S
        val received = runCatching { inbox.list() }.getOrDefault(emptyList())
            .filter { it.sourceHash.equals(src, true) && it.receivedAt >= since }
            .maxByOrNull { it.receivedAt }?.let { it.alertId to it.receivedAt }
        val sent = runCatching { outbox?.pending().orEmpty() }.getOrDefault(emptyList())
            .filter { a -> a.createdAt >= since && a.recipients.any { it.equals(src, true) } }
            .maxByOrNull { it.createdAt }?.let { it.alertId to it.createdAt }
        return listOfNotNull(received, sent).maxByOrNull { it.second }?.first
    }

    fun build(
        settings: ReceiveSettings,
        contacts: Contacts,
        discover: Discover,
        tracks: LiveTrackStore,
    ): IncomingDispatcher {
        val sendAck: (alertId: String, sourceHex: String) -> Unit = { id, src ->
            sendControl(src, Wire.ack(id, legacyWire()))
        }
        val ackCb: (alertId: String, sourceHex: String) -> Unit = { id, src ->
            ackTracker.onAck(id, src)
        }
        val replyCb: (alertId: String, sourceHex: String, reply: String) -> Unit = { id, src, reply ->
            val ours = ackTracker.stateOf(id, src) != null
            ackTracker.onAck(id, src, reply)
            if (ours && reply.isNotBlank()) {
                val name = contacts.get(src)?.name ?: discover.get(src)?.displayName?.takeIf { it.isNotBlank() } ?: src.take(8)
                incomingChat(id, src, name, reply, notify = false) // the reply notification covers it
                runCatching { notifier.onReply(id, src, name, reply) }
            }
        }
        val chatCb: (alertId: String, sourceHex: String, text: String) -> Unit = { id, src, text ->
            val name = contacts.get(src)?.name ?: discover.get(src)?.displayName?.takeIf { it.isNotBlank() } ?: src.take(8)
            incomingChat(id, src, name, text, notify = true)
        }
        // Alerts are recorded in the inbox; the user-facing notification comes
        // only from the bypass-silent hook (alerts), not for acks/replies/geo.
        val onMessage: (IncomingMessage) -> Unit = { msg ->
            if (msg.kind == "alert" && msg.alertId.isNotEmpty()) {
                runCatching {
                    inbox.record(msg.alertId, msg.sourceHash, msg.severity, msg.text, msg.timestamp)
                }
            }
            if (msg.kind == "text" && msg.text.isNotBlank() && msg.fix == null) {
                threadFor(msg.sourceHash)?.let { id ->
                    val name = contacts.get(msg.sourceHash)?.name
                        ?: discover.get(msg.sourceHash)?.displayName?.takeIf { it.isNotBlank() } ?: msg.sourceHash.take(8)
                    incomingChat(id, msg.sourceHash, name, msg.text, notify = true)
                }
            }
        }

        return IncomingDispatcher(
            settings = settings,
            contacts = contacts,
            discover = discover,
            tracks = tracks,
            onMessage = onMessage,
            sendAckFn = sendAck,
            ackCb = ackCb,
            replyCb = replyCb,
            chatCb = chatCb,
        ).also { it.bypassSilentCb = { msg -> notifier.onAlert(msg) } }
    }

    /** Receiver-side reply (ack + text) to an inbound alert. */
    fun sendReply(alertId: String, sourceHex: String, reply: String) {
        if (reply.isBlank()) {
            sendControl(sourceHex, Wire.reply(alertId, reply, legacyWire()))
            return
        }
        // Replies show in the alert's chat thread too.
        val rowId = runCatching {
            chat?.add(ChatMessage(alertId = alertId, peer = sourceHex, outgoing = true, text = reply, ts = now(), state = ChatState.SENDING))
        }.getOrNull()
        sendControl(
            sourceHex, Wire.reply(alertId, reply, legacyWire()),
            onDelivered = { rowId?.let { runCatching { chat?.setState(it, ChatState.DELIVERED) } } },
            onFailed = { rowId?.let { runCatching { chat?.setState(it, ChatState.FAILED) } } },
        )
    }

    /**
     * Send [text] into [alertId]'s chat: to every recipient of an alert we
     * sent, or to the sender of one we received. Returns how many were queued.
     */
    fun sendChat(alertId: String, text: String): Int {
        val sentByUs = runCatching { outbox?.pending().orEmpty() }.getOrDefault(emptyList()).firstOrNull { it.alertId == alertId }
        val peers = sentByUs?.recipients ?: listOfNotNull(runCatching { inbox.get(alertId) }.getOrNull()?.sourceHash)
        if (peers.isEmpty()) return 0
        val out = Wire.chat(alertId, text, legacyWire())
        val batch = System.currentTimeMillis()
        val ts = now()
        for (p in peers) {
            val rowId = runCatching {
                chat?.add(ChatMessage(alertId = alertId, peer = p, outgoing = true, text = text, ts = ts, state = ChatState.SENDING, batch = batch))
            }.getOrNull()
            sendControl(
                p, out,
                onDelivered = { rowId?.let { runCatching { chat?.setState(it, ChatState.DELIVERED) } } },
                onFailed = { rowId?.let { runCatching { chat?.setState(it, ChatState.FAILED) } } },
            )
        }
        return peers.size
    }

    /** Receiver-side manual ack for an inbound alert. */
    fun sendAck(alertId: String, sourceHex: String) {
        sendControl(sourceHex, Wire.ack(alertId, legacyWire()))
    }

    /**
     * Acks/replies are tiny, so they go opportunistically: one packet to the
     * sender's LXMF delivery destination. Not over the link the alert arrived
     * on — a Python LXMF sender only starts listening on that link a few
     * seconds after its delivery completes, so an immediate backchannel ack is
     * silently dropped. Falls back to a direct link if the packet fails.
     */
    private fun sendControl(
        destHex: String,
        out: LxmfOut,
        onDelivered: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        runCatching {
            lxmf.sendMessage(
                recipientHex = destHex,
                body = out.content,
                fields = out.fields,
                onDelivered = onDelivered,
                // Fall back to a direct link; only that attempt's failure counts.
                onFailed = {
                    runCatching {
                        lxmf.sendMessage(destHex, out.content, onDelivered = onDelivered, onFailed = onFailed, fields = out.fields)
                    }.onFailure { onFailed?.invoke() }
                },
                opportunistic = out.content.toByteArray().size <= OPPORTUNISTIC_MAX_BYTES,
            )
        }.onFailure { onFailed?.invoke() }
    }

    private companion object {
        const val THREAD_WINDOW_S = 24 * 3600.0
        /** Bigger messages go over a link (one packet can't carry them). */
        const val OPPORTUNISTIC_MAX_BYTES = 200
    }
}