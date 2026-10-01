package network.retalert.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import network.retalert.app.platform.AlarmPlayer
import network.retalert.app.ui.alarm.AlarmOverlay
import network.retalert.app.ui.nav.RetAlertApp
import network.retalert.app.ui.theme.RetAlertTheme
import network.retalert.reticulum.ReticulumService

/** Single-activity host for the Compose nav graph. Starts the mesh service
 *  (idempotent) and requests the runtime
 *  permissions the Phase-4 native features need (notifications, camera, mic,
 *  location) on first launch. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var alarm: AlarmPlayer
    @Inject lateinit var engine: network.retalert.reticulum.ReticulumEngine

    private fun missingPerms(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleAlarmIntent(intent)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ReticulumService::class.java))
        }
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }
        missingPerms().takeIf { it.isNotEmpty() }?.let { launcher.launch(it) }
        setContent {
            RetAlertTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RetAlertApp() }
                AlarmOverlay(alarm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlarmIntent(intent)
    }

    /** Full-screen intent: show over the lock screen and wake the display (the
     *  alarm keeps ringing until stopped). Tapping the notification: the user
     *  has seen it, so stop ringing. */
    private fun handleAlarmIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHOW_ALARM, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (intent.getBooleanExtra(EXTRA_STOP_ALARM, false)) alarm.stop()
        // A USB node was plugged in and the user let RetAlert handle it:
        // permission is granted now, so connect without waiting for a retry.
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            kotlin.concurrent.thread(name = "retalert-usb-attach", isDaemon = true) { runCatching { engine.reloadInterfaces() } }
        }
    }

    companion object {
        const val EXTRA_SHOW_ALARM = "network.retalert.extra.SHOW_ALARM"
        const val EXTRA_STOP_ALARM = "network.retalert.extra.STOP_ALARM"
    }
}