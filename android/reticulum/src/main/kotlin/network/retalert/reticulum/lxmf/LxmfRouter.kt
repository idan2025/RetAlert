package network.retalert.reticulum.lxmf

import android.util.Log

/**
 * Minimal LXMF transport seam. The real implementation is [LxmfRouterImpl],
 * backed by LXMF-kt (`network.reticulum.lxmf.LXMRouter`) consumed via the
 * Gradle composite build at `../lxmf-kt` (JitPack publishes no LXMF-kt
 * artifacts). [StubLxmfRouter] is retained as a compile-safe fallback for
 * environments where the composite build is unavailable; the production Hilt
 * binding in `ReticulumModule` uses [LxmfRouterImpl].
 *
 * Parity with `retalert/transport/lxmf_transport.py` (LXMFTransport):
 *  - [register] / [start] bring up the LXMF router + inbound callback.
 *  - [announce] sends an LXMF delivery announce.
 *  - [sendMessage] delivers a text body to a destination hash, wiring
 *    [onDelivered]/[onFailed] back into AckTracker (caller side).
 *  - [setIncomingCallback] routes parsed inbound LXMF (sourceHex, text, ts).
 *  - [deliveryHashHex] exposes the local LXMF delivery destination hash.
 */
interface LxmfRouter {
    val deliveryHashHex: String

    fun register()
    fun start()
    fun stop()
    fun announce()
    fun sendMessage(
        recipientHex: String,
        body: String,
        onDelivered: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    )

    fun setIncomingCallback(cb: ((sourceHex: String, text: String, timestamp: Double) -> Unit)?)
}

/**
 * Default LXMF binding: a compiling no-op. Every method logs a warning so the
 * missing wiring is visible at runtime. Replace once LXMF-kt is integrated.
 */
class StubLxmfRouter(
    override val deliveryHashHex: String = "00000000000000000000000000000000",
) : LxmfRouter {

    override fun register() { warn("register") }
    override fun start() { warn("start") }
    override fun stop() { warn("stop") }
    override fun announce() { warn("announce") }

    override fun sendMessage(
        recipientHex: String,
        body: String,
        onDelivered: (() -> Unit)?,
        onFailed: (() -> Unit)?,
    ) {
        warn("sendMessage -> $recipientHex (${body.length} bytes); LXMF-kt not wired")
        // No transport available: report failure so AckTracker marks the
        // recipient failed rather than hanging in SENT forever.
        onFailed?.invoke()
    }

    override fun setIncomingCallback(cb: ((String, String, Double) -> Unit)?) { warn("setIncomingCallback") }

    private fun warn(what: String) {
        Log.w(TAG, "LXMF-kt not wired: $what (StubLxmfRouter)")
    }

    private companion object { const val TAG = "RetAlert/Lxmf" }
}