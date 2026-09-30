package network.retalert.reticulum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * Foreground service owning the RNS stack lifecycle. A thin shell around
 * [ReticulumEngine]; runs as a `specialUse` foreground service (an always-on
 * mesh listener — `dataSync` is capped at 6h/day on Android 15+) so alerts
 * still arrive with the app in the background. :app starts it from
 * `MainActivity` with `ContextCompat.startForegroundService(...)`.
 *
 * Parity with `retalert/daemon.py` `EmergencyDaemon.start`/`stop`.
 */
@AndroidEntryPoint
class ReticulumService : Service() {

    @Inject lateinit var engine: ReticulumEngine

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Engine start does disk + socket I/O; never on the main thread.
        if (!engine.isRunning) {
            thread(name = "retalert-engine-start", isDaemon = true) {
                runCatching { engine.start() }
                    .onFailure { Log.e(TAG, "engine start failed", it) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { engine.stop() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RetAlert service", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Keeps the Reticulum mesh running in the background" }
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("RetAlert")
        .setContentText("Listening for alerts on the mesh")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private companion object {
        const val TAG = "RetAlert/Service"
        const val CHANNEL_ID = "retalert-service"
        const val NOTIFICATION_ID = 0x7E7
    }
}
