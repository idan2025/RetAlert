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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
    /** Chat to open once the UI is up (notification tap, pop-up button). */
    private val pendingChat = androidx.compose.runtime.mutableStateOf<String?>(null)
    @Inject lateinit var engine: network.retalert.reticulum.ReticulumEngine
    @Inject lateinit var replier: network.retalert.app.platform.QuickReplier
    @Inject lateinit var settingsRepo: network.retalert.domain.SettingsRepository

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
            // The UI is English-only: on a right-to-left system (Hebrew, Arabic)
            // mirroring it only scrambles the text ("min 3", periods at the
            // start). Lay it out left-to-right until there are RTL translations.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                RetAlertTheme {
                    Surface(modifier = Modifier.fillMaxSize()) { RetAlertApp(openChat = pendingChat.value, onChatOpened = { pendingChat.value = null }) }
                    val shown by alarm.shown.collectAsState()
                    val replies by produceState(network.retalert.domain.DEFAULT_QUICK_REPLIES, shown) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { settingsRepo.load().quickReplies.toList() }
                                .getOrDefault(network.retalert.domain.DEFAULT_QUICK_REPLIES)
                        }
                    }
                    AlarmOverlay(
                        alarm, replies,
                        onReply = { msg, text -> replier.reply(msg, text) },
                        onOpenChat = { msg ->
                            alarm.stop()
                            if (!network.retalert.app.platform.QuickReplier.isTest(msg)) pendingChat.value = msg.alertId
                        },
                    )
                }
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
        intent.getStringExtra(EXTRA_OPEN_CHAT)?.let { pendingChat.value = it }
        // A USB node was plugged in and the user let RetAlert handle it:
        // permission is granted now, so connect without waiting for a retry.
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            kotlin.concurrent.thread(name = "retalert-usb-attach", isDaemon = true) { runCatching { engine.reloadInterfaces() } }
        }
    }

    companion object {
        const val EXTRA_SHOW_ALARM = "network.retalert.extra.SHOW_ALARM"
        const val EXTRA_STOP_ALARM = "network.retalert.extra.STOP_ALARM"
        /** Open this alert's chat (reply / chat notifications). */
        const val EXTRA_OPEN_CHAT = "network.retalert.extra.OPEN_CHAT"
        /** Pop-up over other apps, but not over the lock screen. */
        const val EXTRA_POPUP = "network.retalert.extra.POPUP"
    }
}