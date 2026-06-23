package network.retalert.reticulum

import android.content.Context
import android.util.Log

/**
 * Attaches to a host RNS instance (Sideband/Columba/MeshChat) via a Local
 * interface + config fragment, so RetAlert shares the host's transport instead
 * of bringing up its own. reticulum-kt exposes `Reticulum.start(..., shareInstance
 * = true, connectToSharedInstance = true, sharedInstancePort = ...)` for this.
 *
 * TODO(share): implement once the host-discovery / config-fragment handoff is
 *  specified. Until then this is a logging stub so the engine can call it
 *  without failing.
 */
class ShareInstance(private val context: Context) {

    /** Returns true if a shared RNS instance is reachable on [port]. */
    fun isHostRunning(port: Int = DEFAULT_PORT): Boolean {
        Log.w(TAG, "share-instance probe not wired (port=$port)")
        return false
    }

    /** Attach to the host instance. Stub: logs and returns false. */
    fun attach(port: Int = DEFAULT_PORT): Boolean {
        Log.w(TAG, "share-instance attach not wired (port=$port)")
        return false
    }

    private companion object { const val TAG = "RetAlert/Share"; const val DEFAULT_PORT = 37428 }
}