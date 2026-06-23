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

/**
 * Foreground service owning the RNS stack lifecycle. A thin shell around
 * [ReticulumEngine]; runs as `dataSync` foreground service so the mesh stays
 * alive in background. :app starts it with
 * `ContextCompat.startForegroundService(...)`; the manifest declares it.
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
        runCatching { engine.start() }
            .onFailure { Log.e(TAG, "engine start failed", it) }
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
        .setContentText("Mesh service active")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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