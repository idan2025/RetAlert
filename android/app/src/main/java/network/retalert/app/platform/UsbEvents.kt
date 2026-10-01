package network.retalert.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import dagger.hilt.android.AndroidEntryPoint
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject
import kotlin.concurrent.thread

/** USB access granted (or a device plugged in): bring USB interfaces up now
 *  instead of waiting for the next retry. */
@AndroidEntryPoint
class UsbPermissionReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ReticulumEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
        val pending = goAsync()
        thread(name = "retalert-usb-reload", isDaemon = true) {
            try { runCatching { engine.reloadInterfaces() } } finally { pending.finish() }
        }
    }
}
