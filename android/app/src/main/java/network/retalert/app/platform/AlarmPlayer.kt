package network.retalert.app.platform

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.retalert.domain.IncomingMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings an incoming alert like an alarm clock: a looping sound on the ALARM
 * stream at full volume plus an alarm-class vibration. Ringer mode (silent /
 * vibrate) does not mute the alarm stream. With Do Not Disturb access, a DND
 * mode that would block alarms is lowered to "alarms only" while ringing.
 * Everything changed is restored on [stop]. Rings until stopped, at most
 * [MAX_RING_MS].
 */
@Singleton
class AlarmPlayer @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume: Int? = null
    private var savedFilter: Int? = null

    private val _ringing = MutableStateFlow<IncomingMessage?>(null)
    /** The alert currently ringing, or null. */
    val ringing: StateFlow<IncomingMessage?> = _ringing.asStateFlow()

    /** Called on the main thread with the alert that stopped ringing. */
    var onStopped: ((IncomingMessage) -> Unit)? = null

    private val timeout = Runnable { silence() }

    fun start(msg: IncomingMessage) {
        main.post { ring(msg) }
    }

    fun stop() {
        main.post { silence() }
    }

    private fun ring(msg: IncomingMessage) {
        _ringing.value = msg
        if (player == null) {
            runCatching { begin() }.onFailure { Log.w(TAG, "alarm start failed", it) }
        }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, MAX_RING_MS)
    }

    private fun silence() {
        main.removeCallbacks(timeout)
        val stopped = _ringing.value
        _ringing.value = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator()?.cancel() }
        savedAlarmVolume?.let { v -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
        savedFilter?.let { f -> runCatching { nm?.setInterruptionFilter(f) } }
        savedFilter = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        stopped?.let { m -> runCatching { onStopped?.invoke(m) } }
    }

    private fun begin() {
        wakeLock = ctx.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retalert:alarm")
            ?.apply { acquire(MAX_RING_MS + 5_000) }
        liftDnd()
        audio?.let { a ->
            savedAlarmVolume = a.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching { a.setStreamVolume(AudioManager.STREAM_ALARM, a.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = MediaPlayer().apply {
            setAudioAttributes(ALARM_AUDIO)
            setDataSource(ctx, uri)
            isLooping = true
            prepare()
            start()
        }
        vibrate()
    }

    /** DND "total silence", or a priority mode that excludes alarms, would
     *  silence the alarm stream. Needs notification-policy access. */
    private fun liftDnd() {
        val n = nm ?: return
        if (!n.isNotificationPolicyAccessGranted) return
        val f = n.currentInterruptionFilter
        val blocksAlarms = f == NotificationManager.INTERRUPTION_FILTER_NONE ||
            (f == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                n.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0)
        if (blocksAlarms) {
            savedFilter = f
            runCatching { n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
        }
    }

    private fun vibrate() {
        val v = vibrator() ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 400, 1600, 800), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, ALARM_AUDIO)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    private companion object {
        const val TAG = "RetAlert/Alarm"
        const val MAX_RING_MS = 3 * 60_000L
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
