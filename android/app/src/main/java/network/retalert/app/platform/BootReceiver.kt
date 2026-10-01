package network.retalert.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import network.retalert.reticulum.ReticulumService

/** Restarts the mesh listener after a reboot or an app update, so alerts keep
 *  arriving without the user having to open RetAlert first. Boot is one of
 *  the cases Android still lets a background app start a foreground service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ReticulumService::class.java)) }
            .onFailure { Log.w("RetAlert/Boot", "could not start mesh service", it) }
    }
}
