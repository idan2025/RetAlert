package network.retalert.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import network.retalert.app.MainActivity
import network.retalert.app.R
import network.retalert.domain.AlarmSound
import network.retalert.domain.IncomingMessage
import network.retalert.domain.SettingsRepository
import network.retalert.reticulum.IncomingNotifier
import javax.inject.Inject
import javax.inject.Singleton

/** Real [IncomingNotifier] binding: posts a high-importance notification with
 *  a full-screen intent for each inbound app-to-app alert (it opens over the
 *  lock screen), and — unless the user turned it off — rings it through
 *  [AlarmPlayer] so silent / vibrate mode and DND don't swallow it.
 *  Replaces the :reticulum no-op binding.
 *
 *  Parity with `retalert/daemon.py` `_on_parsed_incoming` → notification bypass. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val alarm: AlarmPlayer,
) : IncomingNotifier {

    init {
        createChannels()
        alarm.onStopped = { msg -> post(msg, ringing = false, updateOnly = true) }
    }

    override fun onAlert(msg: IncomingMessage) {
        val s = runCatching { settings.load() }.getOrNull()
        val override = s?.alarmOverrideSilent ?: true
        // Ring first: a phone with notifications blocked still gets the alarm.
        if (override) {
            alarm.start(msg, s?.alarmSound ?: AlarmSound.DEFAULT, s?.alarmVolumePercent ?: AlarmSound.DEFAULT_VOLUME_PERCENT)
        }
        post(msg, ringing = override, updateOnly = false)
    }

    /**
     * Post the alert's notification; with [updateOnly], quietly refresh it
     * instead. After the alarm stops, a notification still on screen keeps the
     * alert but loses its "Stop alarm" button; one the user already dismissed
     * or tapped stays gone.
     */
    private fun post(msg: IncomingMessage, ringing: Boolean, updateOnly: Boolean) {
        val mgr = NotificationManagerCompat.from(ctx)
        if (!mgr.areNotificationsEnabled()) return
        val id = notifId(msg.alertId)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val shown = nm?.activeNotifications?.firstOrNull { it.id == id }
        if (updateOnly && shown == null) return
        val channel = shown?.notification?.channelId
            ?: if (ringing) ALARM_CHANNEL_ID else CHANNEL_ID
        val req = msg.alertId.hashCode()
        val fullScreen = PendingIntent.getActivity(
            ctx, req, activityIntent().putExtra(MainActivity.EXTRA_SHOW_ALARM, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            ctx, req + 1, activityIntent().putExtra(MainActivity.EXTRA_STOP_ALARM, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("RetAlert · ${msg.severity}")
            .setContentText(msg.text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply {
                if (!updateOnly) setFullScreenIntent(fullScreen, true)
                if (ringing) {
                    setDeleteIntent(stop)
                    addAction(0, "Stop alarm", stop)
                }
                // The alarm channel is already silent; no setSilent(), which moves
                // the notification into a summary-less "silent" group that some
                // system UIs (seen on Nubia) drop.
                if (updateOnly) setOnlyAlertOnce(true)
            }
            .build()
        runCatching { mgr.notify(id, notif) }
    }

    private fun activityIntent() = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun notifId(alertId: String): Int = (alertId.hashCode() and 0x7fffffff) or 0x40000000

    private fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when \"Ring through silent mode\" is off (follows the phone's sound settings)"
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        // The alarm itself makes the sound and vibration; the channel stays
        // quiet so it doesn't play a second, ringer-mode-bound sound.
        nm.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL_ID, "Incoming alerts (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts that ring as an alarm, through silent and vibrate mode"
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "retalert-alerts"
        const val ALARM_CHANNEL_ID = "retalert-alarm"
    }
}

/** "Stop alarm" action / notification swiped away. */
@AndroidEntryPoint
class AlarmStopReceiver : BroadcastReceiver() {
    @Inject lateinit var alarm: AlarmPlayer
    override fun onReceive(context: Context, intent: Intent) { alarm.stop() }
}
