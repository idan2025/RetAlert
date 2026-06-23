@file:Suppress("unused")

package network.retalert.reticulum.lxmf

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.common.toHexString
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import network.reticulum.lxmf.MessageState
import java.io.File

/**
 * Real [LxmfRouter] backed by LXMF-kt (`network.reticulum.lxmf.LXMRouter`),
 * consumed via the Gradle composite build at `../lxmf-kt`.
 *
 * Parity with `retalert/transport/lxmf_transport.py` (LXMFTransport):
 *  - [register] loads the RetAlert identity (the same file the engine writes
 *    at `filesDir/retalert_identity` — the engine has already created it and
 *    called `Reticulum.start(...)` before [register], so the file exists and
 *    RNS is live), creates the [LXMRouter], registers an LXMF `lxmf.delivery`
 *    inbound destination, and wires the inbound delivery callback.
 *  - [start] runs the router's outbound processing loop (retries, path
 *    requests, link establishment).
 *  - [announce] sends an LXMF delivery announce so peers can discover us.
 *  - [sendMessage] builds an outbound [LXMessage] addressed to the recipient's
 *    `lxmf.delivery` destination (looked up via [Identity.recall]) and hands it
 *    to the router; LXMF-kt fires the message's `deliveryCallback`/
 *    `failedCallback` as it reaches [MessageState.DELIVERED] / [FAILED], which
 *    this impl forwards to [onDelivered] / [onFailed].
 *  - [deliveryHashHex] exposes our local LXMF delivery destination hash.
 *
 * Every public call is wrapped in `runCatching` so a transport hiccup never
 * propagates into [ReticulumEngine] and crashes the foreground service; a
 * failure to send reports back through [onFailed] rather than throwing.
 */
class LxmfRouterImpl(
    private val context: Context,
) : LxmfRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var router: LXMRouter? = null
    private var deliveryDestination: Destination? = null
    @Volatile private var incomingCb: ((sourceHex: String, text: String, timestamp: Double) -> Unit)? = null

    @Volatile
    override var deliveryHashHex: String =
        "00000000000000000000000000000000"
        private set

    override fun register() {
        runCatching {
            // The engine has already loadOrCreateIdentity() + Reticulum.start()
            // before calling register(), so the identity file exists and RNS is
            // live. Read the same identity so the LXMF delivery destination is
            // derived from the same keys as the RNS transport identity.
            val identityFile = File(context.filesDir, IDENTITY_FILE)
            val identity = Identity.fromFile(identityFile.absolutePath)
                ?: error("RetAlert identity not yet created; engine must start before LXMF register()")
            val storagePath = context.filesDir.absolutePath

            val r = LXMRouter(
                identity = identity,
                storagePath = storagePath,
                autopeer = true,
            )
            // Inbound delivery -> route parsed LXMF into the engine callback.
            r.registerDeliveryCallback { message ->
                runCatching {
                    val srcHex = message.sourceHash.toHexString()
                    val text = message.content
                    val ts = message.timestamp ?: (System.currentTimeMillis() / 1000.0)
                    incomingCb?.invoke(srcHex, text, ts)
                }.onFailure { Log.e(TAG, "inbound delivery handler failed", it) }
            }

            val dest = r.registerDeliveryIdentity(
                identity = identity,
                displayName = null,
                stampCost = null,
            )
            router = r
            deliveryDestination = dest
            deliveryHashHex = dest.hexHash
            Log.i(TAG, "LXMF router registered; deliveryHash=${dest.hexHash}")
        }.onFailure {
            Log.e(TAG, "LXMF register failed", it)
            // register() is called inside ReticulumEngine.start()'s try block;
            // rethrow so the engine observes the failure (parity: a transport
            // that can't come up should fail loudly at start, not silently).
            throw it
        }
    }

    override fun start() {
        runCatching { router?.start() }
            .onFailure { Log.e(TAG, "LXMF start failed", it) }
    }

    override fun stop() {
        runCatching { router?.stop() }
            .onFailure { Log.w(TAG, "LXMF stop failed", it) }
    }

    override fun announce() {
        runCatching { router?.announce(deliveryDestination ?: return) }
            .onFailure { Log.w(TAG, "LXMF announce failed", it) }
    }

    override fun sendMessage(
        recipientHex: String,
        body: String,
        onDelivered: (() -> Unit)?,
        onFailed: (() -> Unit)?,
    ) {
        val r = router
        val src = deliveryDestination
        if (r == null || src == null) {
            Log.w(TAG, "sendMessage before register; reporting failure")
            onFailed?.invoke()
            return
        }
        scope.launch {
            runCatching {
                val destHash = hexToBytes(recipientHex)
                // Recall the recipient's identity (announced lxmf.delivery
                // destinations are remembered by RNS Transport on announce so
                // this lookup succeeds for known/announced contacts). Without
                // an identity we cannot build an OUT destination, so fail the
                // send rather than dropping it silently.
                val recalled = Identity.recall(destHash)
                if (recalled == null) {
                    Log.w(TAG, "sendMessage: no known identity for $recipientHex; failing")
                    onFailed?.invoke()
                    return@runCatching
                }
                val outDest = Destination.create(
                    identity = recalled,
                    direction = DestinationDirection.OUT,
                    type = DestinationType.SINGLE,
                    appName = LXMRouter.APP_NAME,
                    LXMRouter.DELIVERY_ASPECT,
                )
                val message = LXMessage.create(
                    destination = outDest,
                    source = src,
                    content = body,
                    title = "",
                    desiredMethod = DeliveryMethod.DIRECT,
                )
                message.deliveryCallback = { onDelivered?.invoke() }
                message.failedCallback = { onFailed?.invoke() }
                r.handleOutbound(message)
            }.onFailure {
                Log.e(TAG, "sendMessage failed for $recipientHex", it)
                onFailed?.invoke()
            }
        }
    }

    override fun setIncomingCallback(cb: ((sourceHex: String, text: String, timestamp: Double) -> Unit)?) {
        incomingCb = cb
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val TAG = "RetAlert/Lxmf"
        const val IDENTITY_FILE = "retalert_identity"
    }
}