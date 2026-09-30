package network.retalert.reticulum

import android.util.Log
import network.retalert.domain.Alert
import network.retalert.domain.IfaceDescriptor
import network.retalert.domain.RetryableTransportException
import network.retalert.domain.TransportIntelligence
import network.retalert.domain.encodeAlert
import network.reticulum.transport.InterfaceRef
import network.reticulum.transport.Transport
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts reticulum-kt's live [InterfaceRef]s to the pure [IfaceDescriptor]
 * seam, and owns the transport send path used by RetryQueue.
 *
 * Parity with `retalert/core/transport_intel.py` (live interface feed) and
 * `retalert/daemon.py` `_send_to_recipient`.
 */

/**
 * RNS interface class for tier classification. Every live ref is an
 * `InterfaceAdapter`, so the class is recovered from the names the engine gives
 * its interfaces; spawned children (AutoInterface peers, shared-instance links)
 * inherit their parent's class.
 */
internal fun InterfaceRef.rnsClass(): String {
    parentInterface?.let { return it.rnsClass() }
    return when {
        name.startsWith(ReticulumEngine.AUTO_NAME) -> "AutoInterface"
        name.startsWith(ReticulumEngine.TCP_PREFIX) -> "TCPClientInterface"
        name.startsWith(ReticulumEngine.SHARED_NAME) -> "LocalClientInterface"
        else -> name.substringBefore('[').substringBefore(' ')
    }
}

/** Map a live RNS interface to a tier-classifiable descriptor. */
fun InterfaceRef.toIfaceDescriptor(): IfaceDescriptor = IfaceDescriptor(
    name = name,
    cls = rnsClass(),
    tier = "",                 // classified by TransportIntelligence.tierOf
    online = online,
    outCapable = canSend,
    hasPath = true,
)

/**
 * Transport send seam: routes one alert to one recipient over LXMF.
 * Throws [RetryableTransportException] when no path to the recipient exists
 * yet (after asking the network for one) — RetryQueue leaves state SENT and
 * retries on the next flush.
 *
 * LXMF itself retries a message until it is delivered or fails, so while a
 * send is in flight [isInFlight] reports true and RetryQueue does not stack
 * duplicates on top of it. An LXMF failure only clears the in-flight flag: the
 * recipient stays SENT and the next retry interval sends again (bounded by the
 * alert's maxAttempts).
 */
class RnsTransport(
    private val ackTracker: network.retalert.domain.AckTracker,
    private val lxmf: network.retalert.reticulum.lxmf.LxmfRouter,
) {
    /** (alertId|recipient) -> when the LXMF send started (ms). */
    private val inFlight = ConcurrentHashMap<String, Long>()

    private fun key(alertId: String, recipient: String) = "$alertId|$recipient"

    /** In flight until LXMF reports back — or, as a safety net against a lost
     *  callback, until [IN_FLIGHT_TIMEOUT_MS] passes. */
    fun isInFlight(alertId: String, recipient: String): Boolean {
        val k = key(alertId, recipient)
        val started = inFlight[k] ?: return false
        if (System.currentTimeMillis() - started < IN_FLIGHT_TIMEOUT_MS) return true
        inFlight.remove(k, started)
        return false
    }

    /** Send [alert] to [recipientHex] (destination hash hex, with or without colons). */
    fun send(alert: Alert, recipientHex: String) {
        val hash = recipientHex.replace(":", "").lowercase()
        val hashBytes = runCatching { hash.hexToByteArray() }.getOrNull()
            ?: throw IllegalArgumentException("invalid destination hash $recipientHex")
        if (!Transport.hasPath(hashBytes)) {
            runCatching { Transport.requestPath(hashBytes) }
            throw RetryableTransportException("no path/announce yet to $recipientHex")
        }
        val k = key(alert.alertId, recipientHex)
        if (isInFlight(alert.alertId, recipientHex)) return
        inFlight[k] = System.currentTimeMillis()
        val body = encodeAlert(alert.severity, alert.text, alert.alertId)
        try {
            sendLxmf(k, alert, recipientHex, hash, body)
        } catch (e: Exception) {
            inFlight.remove(k)
            throw e
        }
    }

    private fun sendLxmf(k: String, alert: Alert, recipientHex: String, hash: String, body: String) {
        lxmf.sendMessage(
            recipientHex = hash,
            body = body,
            onDelivered = {
                inFlight.remove(k)
                ackTracker.onDelivered(alert.alertId, recipientHex)
            },
            onFailed = {
                inFlight.remove(k)
                Log.w(TAG, "LXMF delivery of ${alert.alertId} to $recipientHex failed; will retry")
            },
        )
    }

    private companion object {
        const val TAG = "RetAlert/Transport"
        const val IN_FLIGHT_TIMEOUT_MS = 5 * 60_000L
    }
}

/** Build a [TransportIntelligence] fed by the live RNS interface table + path table. */
fun rnsTransportIntelligence(): TransportIntelligence = TransportIntelligence(
    ifaces = { Transport.getInterfaces().map { it.toIfaceDescriptor() } },
    hasPath = { recipientHash -> Transport.hasPath(recipientHash) },
)
