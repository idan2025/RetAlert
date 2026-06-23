package network.retalert.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.HiltAndroidApp
import network.retalert.reticulum.ReticulumService

/** Application entrypoint. Bootstraps the Hilt graph and starts the foreground
 *  mesh service so the Reticulum stack (announce + inbound listen + retry queue)
 *  stays alive in the background. */
@HiltAndroidApp
class RetAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ReticulumService::class.java))
        }
    }
}