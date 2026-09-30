package network.retalert.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

/** Application entrypoint. Bootstraps the Hilt graph and configures osmdroid
 *  before any MapView exists (tile servers reject requests without a proper
 *  user agent). The mesh service is started from [MainActivity]: Android 12+
 *  forbids starting a foreground service while the app is in the background,
 *  which `Application.onCreate` can be (e.g. on a sticky service restart). */
@HiltAndroidApp
class RetAlertApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            load(this@RetAlertApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "RetAlert/${BuildConfig.VERSION_NAME} (+https://github.com/idan2025/RetAlert)"
        }
    }
}
