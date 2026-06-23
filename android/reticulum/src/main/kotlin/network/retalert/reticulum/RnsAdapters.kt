package network.retalert.reticulum

import network.retalert.domain.Alert
import network.retalert.domain.IfaceDescriptor
import network.retalert.domain.RetryableTransportException
import network.retalert.domain.TransportIntelligence
import network.retalert.domain.encodeAlert
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport

/**
 * Adapts reticulum-kt's live [InterfaceRef]s to the pure [IfaceDescriptor]
 * seam, and owns the transport send path used by RetryQueue.
 *
 * Parity with `retalert/core/transport_intel.py` (live interface feed) and
 * `retalert/daemon.py` `_send_to_recipient`.
 */

/** Map a live RNS interface to a tier-classifiable descriptor. */
fun InterfaceRef.toIfaceDescriptor(): IfaceDescriptor = IfaceDescriptor(
    name = name,
    cls = javaClass.simpleName,
    tier = "",                 // classified by TransportIntelligence.tierOf
    online = online,
    outCapable = canSend,
    hasPath = true,
)

/**
 * Transport send seam: routes one alert to one recipient via the delivery plan.
 * Throws [RetryableTransportException] when no path to the recipient exists
 * yet — RetryQueue leaves state SENT and retries on the next flush.
 */
class RnsTransport(
    private val ackTracker: network.retalert.domain.AckTracker,
    private val lxmf: network.retalert.reticulum.lxmf.LxmfRouter,
) {
    /** Send [alert] to [recipientHex] (destination hash hex, with or without colons). */
    fun send(alert: Alert, recipientHex: String) {
        val hash = recipientHex.replace(":", "").lowercase()
        val hashBytes = runCatching { hash.hexToByteArray() }.getOrNull()
        if (hashBytes == null || !Transport.hasPath(hashBytes)) {
            throw RetryableTransportException("no path/announce yet to $recipientHex")
        }
        val body = encodeAlert(alert.severity, alert.text, alert.alertId)
        lxmf.sendMessage(
            recipientHex = hash,
            body = body,
            onDelivered = { ackTracker.onDelivered(alert.alertId, recipientHex) },
            onFailed = { ackTracker.onFailed(alert.alertId, recipientHex, "lxmf failed") },
        )
    }
}

/** Build a [TransportIntelligence] fed by the live RNS interface table + path table. */
fun rnsTransportIntelligence(): TransportIntelligence = TransportIntelligence(
    ifaces = { Transport.getInterfaces().map { it.toIfaceDescriptor() } },
    hasPath = { recipientHash -> Transport.hasPath(recipientHash) },
)