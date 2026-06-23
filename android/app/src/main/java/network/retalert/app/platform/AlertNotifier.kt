package network.retalert.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import network.retalert.app.MainActivity
import network.retalert.app.R
import network.retalert.domain.IncomingMessage
import network.retalert.reticulum.IncomingNotifier
import javax.inject.Inject
import javax.inject.Singleton

/** Real [IncomingNotifier] binding: posts a high-importance, bypass-silent
 *  notification with a full-screen intent for each inbound app-to-app alert,
 *  bringing the app to the foreground even over Do Not Disturb. Replaces the
 *  :reticulum no-op binding.
 *
 *  Parity with `retalert/daemon.py` `_on_parsed_incoming` → notification bypass. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : IncomingNotifier {

    init { createChannel() }

    override fun onAlert(msg: IncomingMessage) {
        val mgr = NotificationManagerCompat.from(ctx)
        if (!mgr.areNotificationsEnabled()) return
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fullScreen = PendingIntent.getActivity(
            ctx, msg.alertId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("RetAlert · ${msg.severity}")
            .setContentText(msg.text.take(120))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .build()
        runCatching { mgr.notify(notifId(msg.alertId), notif) }
    }

    private fun notifId(alertId: String): Int = (alertId.hashCode() and 0x7fffffff) or 0x40000000

    private fun createChannel() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Bypass-silent alerts from app-to-app messages"
                setBypassDnd(true)
            },
        )
    }

    private companion object { const val CHANNEL_ID = "retalert-alerts" }
}