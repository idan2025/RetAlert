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
            runCatching { lxmf.sendMessage(src, encodeAck(id)) }
        }
        val ackCb: (alertId: String, sourceHex: String) -> Unit = { id, src ->
            ackTracker.onAck(id, src)
        }
        val replyCb: (alertId: String, sourceHex: String, reply: String) -> Unit = { id, src, reply ->
            ackTracker.onAck(id, src, reply)
        }
        val onMessage: (IncomingMessage) -> Unit = { msg ->
            if (msg.kind == "alert" && msg.alertId.isNotEmpty()) {
                runCatching {
                    inbox.record(msg.alertId, msg.sourceHash, msg.severity, msg.text, msg.timestamp)
                }
            }
            runCatching { notifier.onAlert(msg) }
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
        runCatching { lxmf.sendMessage(sourceHex, encodeReply(alertId, reply)) }
    }
}