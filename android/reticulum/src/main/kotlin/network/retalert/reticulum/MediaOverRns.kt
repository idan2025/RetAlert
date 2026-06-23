package network.retalert.reticulum

import android.util.Log
import network.retalert.domain.MediaChannel
import network.retalert.domain.TransportIntelligence

/**
 * Adapts [MediaChannel.linkSendFn] to a reticulum-kt Link/Resource. A live Link
 * must be established per recipient before media can flow; the real wiring uses
 * `network.reticulum.link.Link.sendResourceData` + `Resource` for chunked
 * transfer. Until the Link lifecycle is wired, this provides a logging no-op
 * so MediaChannel compiles and tier-gates correctly.
 *
 * TODO(link): establish a Link to the recipient (via the LXMF delivery
 *  destination) and route frames through `Link.send`/`Resource`. The chunked
 *  wire format ([MediaChunk.asBytes]) is already byte-identical to Python.
 */
class RnsMediaLink {

    /** A link-send function that logs frames without transmitting. */
    val linkSendFn: (ByteArray) -> Unit = { frame ->
        Log.w(TAG, "media frame (${frame.size} bytes) dropped — RNS Link not wired")
    }

    /** Build a [MediaChannel] tier-gated by [ti] and routed through this link. */
    fun mediaChannel(ti: TransportIntelligence?): MediaChannel =
        MediaChannel(ti = ti, linkSendFn = linkSendFn)

    private companion object { const val TAG = "RetAlert/Media" }
}