package network.retalert.app

import android.app.Application
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import network.retalert.app.platform.LocationSharer
import org.osmdroid.config.Configuration
import javax.inject.Inject

/** Application entrypoint. Bootstraps the Hilt graph and configures osmdroid
 *  before any MapView exists (tile servers reject requests without a proper
 *  user agent). The mesh service is started from [MainActivity]: Android 12+
 *  forbids starting a foreground service while the app is in the background,
 *  which `Application.onCreate` can be (e.g. on a sticky service restart). */
@HiltAndroidApp
class RetAlertApp : Application() {
    @Inject lateinit var locationSharer: LocationSharer

    override fun onCreate() {
        super.onCreate()
        // An emergency live share must survive the process being killed and
        // restarted (sticky mesh service). Not in the :phoenix trampoline.
        val mainProcess = Build.VERSION.SDK_INT < Build.VERSION_CODES.P || getProcessName() == packageName
        if (mainProcess) runCatching { locationSharer.resumeIfActive() }
        Configuration.getInstance().apply {
            load(this@RetAlertApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "RetAlert/${BuildConfig.VERSION_NAME} (+https://github.com/idan2025/RetAlert)"
        }
    }
}
