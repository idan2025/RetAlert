package network.retalert.reticulum

import android.util.Log
import network.retalert.domain.MediaChannel
import network.retalert.domain.TransportIntelligence
import network.reticulum.link.Link

/**
 * Adapts [MediaChannel.linkSendFn] to a reticulum-kt [Link]. A live Link must be
 * established per recipient before media can flow; once one is handed in via
 * [setActiveLink], frames are transmitted with [Link.sendResourceData] (reliable,
 * chunked — the chunked wire format [MediaChunk.asBytes] is already byte-identical
 * to Python). With no Link, or one that is not yet active, the frame is dropped
 * gracefully so MediaChannel tier-gates correctly.
 *
 * Link establishment to a peer's media destination (the peer must announce one)
 * is build-step 13 work — it was a `NotImplementedError` stub in Python
 * `retalert/transport/rns_link.py` too. The transport path here is real; the
 * link-lifecycle / media-destination announcement subsystem is the remaining
 * piece (wired by the LXMF/announce layer once peers expose a media dest).
 */
class RnsMediaLink {

    /** Currently established outbound Link to a peer (or null). */
    @Volatile
    private var activeLink: Link? = null

    /** Hand in (or clear) the live Link frames should be sent over. */
    fun setActiveLink(link: Link?) { activeLink = link }

    /** A link-send function that transmits frames over the active Link, or drops
     *  gracefully when no Link is available (or it is not yet ready). */
    val linkSendFn: (ByteArray) -> Unit = { frame ->
        val link = activeLink
        if (link != null) {
            runCatching { link.sendResourceData(frame) }
                .onFailure { Log.d(TAG, "media frame (${frame.size}B) dropped — link not ready: ${it.message}") }
        } else {
            Log.d(TAG, "media frame (${frame.size}B) dropped — no RNS Link")
        }
    }

    /** Build a [MediaChannel] tier-gated by [ti] and routed through this link. */
    fun mediaChannel(ti: TransportIntelligence?): MediaChannel =
        MediaChannel(ti = ti, linkSendFn = linkSendFn)

    private companion object { const val TAG = "RetAlert/Media" }
}