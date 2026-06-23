package network.retalert.reticulum

import network.retalert.domain.ASPECT_LXMF_DELIVERY
import network.retalert.domain.Discover
import network.reticulum.identity.Identity
import network.reticulum.transport.AnnounceHandler

/**
 * reticulum-kt announce handler shim feeding [Discover.heard]. Registered with
 * an `aspectFilter = "lxmf.delivery"` (see ReticulumEngine) so every call is an
 * LXMF delivery announce; appData is decoded into a display name by Discover.
 *
 * Parity with `retalert/core/discover.py` `AnnounceHandler`.
 */
class RetAlertAnnounceHandler(
    private val discover: Discover,
) : AnnounceHandler {

    /** Returns true to consume the announce (mirrors RNS handler convention). */
    override fun handleAnnounce(destinationHash: ByteArray, announcedIdentity: Identity, appData: ByteArray?): Boolean {
        val hex = destinationHash.joinToString("") { "%02x".format(it) }
        discover.heard(hex, appData, ASPECT_LXMF_DELIVERY)
        return true
    }
}