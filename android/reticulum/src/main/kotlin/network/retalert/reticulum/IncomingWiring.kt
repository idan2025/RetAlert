package network.retalert.reticulum

import network.retalert.domain.AckTracker
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
) {

    fun build(
        settings: ReceiveSettings,
        contacts: Contacts,
        discover: Discover,
        tracks: LiveTrackStore,
    ): IncomingDispatcher {
        val sendAck: (alertId: String, sourceHex: String) -> Unit = { id, src ->
            sendControl(src, encodeAck(id))
        }
        val ackCb: (alertId: String, sourceHex: String) -> Unit = { id, src ->
            ackTracker.onAck(id, src)
        }
        val replyCb: (alertId: String, sourceHex: String, reply: String) -> Unit = { id, src, reply ->
            ackTracker.onAck(id, src, reply)
        }
        // Alerts are recorded in the inbox; the user-facing notification comes
        // only from the bypass-silent hook (alerts), not for acks/replies/geo.
        val onMessage: (IncomingMessage) -> Unit = { msg ->
            if (msg.kind == "alert" && msg.alertId.isNotEmpty()) {
                runCatching {
                    inbox.record(msg.alertId, msg.sourceHash, msg.severity, msg.text, msg.timestamp)
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
        ).also { it.bypassSilentCb = { msg -> notifier.onAlert(msg) } }
    }

    /** Receiver-side reply (ack + text) to an inbound alert. */
    fun sendReply(alertId: String, sourceHex: String, reply: String) {
        sendControl(sourceHex, encodeReply(alertId, reply))
    }

    /** Receiver-side manual ack for an inbound alert. */
    fun sendAck(alertId: String, sourceHex: String) {
        sendControl(sourceHex, encodeAck(alertId))
    }

    /**
     * Acks/replies are tiny, so they go opportunistically: one packet to the
     * sender's LXMF delivery destination. Not over the link the alert arrived
     * on — a Python LXMF sender only starts listening on that link a few
     * seconds after its delivery completes, so an immediate backchannel ack is
     * silently dropped. Falls back to a direct link if the packet fails.
     */
    private fun sendControl(destHex: String, body: String) {
        runCatching {
            lxmf.sendMessage(
                recipientHex = destHex,
                body = body,
                onFailed = { runCatching { lxmf.sendMessage(destHex, body) } },
                opportunistic = true,
            )
        }
    }
}