package network.retalert.reticulum

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Attaches to a host RNS instance (Sideband/Columba/MeshChat) so RetAlert shares
 * the host's transport instead of bringing up its own. reticulum-kt's programmatic
 * `Reticulum.start(..., connectToSharedInstance = true, sharedInstancePort = ...)`
 * covers the local (in-process / localhost) attach path that Python's
 * `share_instance.py` implemented via config + `share_instance = Yes`.
 *
 * Parity with `retalert/transport/share_instance.py` `attach_local` (local mode):
 * detect a shared-instance listener on [DEFAULT_PORT] and, when present, start
 * Reticulum as a client (`connectToSharedInstance = true`). When no host is
 * running the engine starts its own standalone stack exactly as before.
 *
 * Remote TCP-host attach (Python `attach_tcp`) + config persistence + host-app
 * selection are deferred to a settings UI pass; first release ships local attach,
 * which is what Sideband/Columba offer in-process on the same device.
 */
class ShareInstance(private val context: Context) {

    /** True if a shared RNS instance is listening on localhost:[port]. */
    fun isHostRunning(port: Int = DEFAULT_PORT): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS) }
        true
    }.getOrElse {
        Log.d(TAG, "share-instance probe port=$port failed: ${it.message}")
        false
    }

    /** Attach to the host instance (local mode). Returns true when a host is
     *  reachable and the engine should start as a shared-instance client. */
    fun attach(port: Int = DEFAULT_PORT): Boolean = isHostRunning(port)

    private companion object {
        const val TAG = "RetAlert/Share"
        // Mirrors Reticulum.DEFAULT_SHARED_INSTANCE_PORT (37428). Kept as a
        // const literal so it can serve as a default argument value; the engine
        // passes Reticulum.DEFAULT_SHARED_INSTANCE_PORT to Reticulum.start, so
        // the two stay in lockstep as long as the upstream default is unchanged.
        const val DEFAULT_PORT = 37428
        const val PROBE_TIMEOUT_MS = 400
    }
}