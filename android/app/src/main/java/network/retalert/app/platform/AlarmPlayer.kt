package network.retalert.app.platform

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
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
import network.retalert.domain.AlarmSound
import network.retalert.domain.IncomingMessage
import network.retalert.domain.ToneSynth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings an incoming alert like an alarm clock: the chosen [AlarmSound] looping
 * on the ALARM stream at the chosen volume plus an alarm-class vibration. A sound
 * that can't be played falls back to the built-in siren — never silence. Ringer mode (silent /
 * vibrate) does not mute the alarm stream. With Do Not Disturb access, a DND
 * mode that would block alarms is lowered to "alarms only" while ringing.
 * Everything changed is restored on [stop]. Rings until stopped, at most
 * [RingOptions.minutes].
 */
@Singleton
class AlarmPlayer @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    /** A sound that is playing; [stop] releases it. */
    private fun interface Playing { fun stop() }

    private var playing: Playing? = null
    private var preview: Playing? = null
    private val pcmCache = HashMap<String, ShortArray>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume: Int? = null
    private var savedFilter: Int? = null

    private val _ringing = MutableStateFlow<IncomingMessage?>(null)
    /** The alert currently ringing, or null. */
    val ringing: StateFlow<IncomingMessage?> = _ringing.asStateFlow()

    private val _shown = MutableStateFlow<IncomingMessage?>(null)
    /** The alert the full-screen pop-up shows. Outlives the ringing when it
     *  times out unattended; cleared when the user stops or dismisses it. */
    val shown: StateFlow<IncomingMessage?> = _shown.asStateFlow()

    /** Called on the main thread with the alert that stopped ringing. */
    var onStopped: ((IncomingMessage) -> Unit)? = null

    private val timeout = Runnable { silence() }

    /** Show [msg] in the pop-up without ringing (ringing switched off). */
    fun show(msg: IncomingMessage) { _shown.value = msg }

    /** How one alert rings. */
    data class RingOptions(
        val sound: String = AlarmSound.DEFAULT,
        val volumePercent: Int = AlarmSound.DEFAULT_VOLUME_PERCENT,
        val vibrate: Boolean = true,
        val minutes: Int = AlarmSound.DEFAULT_RING_MINUTES,
    )

    /** Ring [msg] as [options] say. */
    fun start(msg: IncomingMessage, options: RingOptions) {
        _shown.value = msg
        main.post { ring(msg, options) }
    }

    /** Play [sound] for a few seconds at [volumePercent], as an alert would
     *  sound (Settings preview); no DND change, volume restored afterwards.
     *  Ignored while an alert rings. */
    fun preview(sound: String, volumePercent: Int) {
        main.post {
            stopPreviewNow()
            if (playing != null) return@post
            previewSavedVolume = setAlarmVolume(volumePercent)
            preview = runCatching { play(sound) }.getOrNull()
            main.postDelayed(previewTimeout, PREVIEW_MS)
        }
    }

    private var previewSavedVolume: Int? = null

    /** Set the alarm stream to [percent]; returns the previous index. */
    private fun setAlarmVolume(percent: Int): Int? {
        val a = audio ?: return null
        val before = a.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = AlarmSound.volumeIndex(a.getStreamMaxVolume(AudioManager.STREAM_ALARM), percent)
        runCatching { a.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
        return before
    }

    fun stopPreview() {
        main.post { stopPreviewNow() }
    }

    private val previewTimeout = Runnable { stopPreviewNow() }

    private fun stopPreviewNow() {
        main.removeCallbacks(previewTimeout)
        preview?.let { runCatching { it.stop() } }
        preview = null
        previewSavedVolume?.let { v -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        previewSavedVolume = null
    }

    /** User stopped or dismissed the alert: silence it and close the pop-up. */
    fun stop() {
        _shown.value = null
        main.post { silence() }
    }

    private fun ring(msg: IncomingMessage, options: RingOptions) {
        _ringing.value = msg
        if (playing == null) {
            stopPreviewNow()
            runCatching { begin(options) }.onFailure { Log.w(TAG, "alarm start failed", it) }
        }
        val ms = options.minutes.coerceIn(1, 60) * 60_000L
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, ms)
        wakeLock?.let { runCatching { it.acquire(ms + 5_000) } }
    }

    private fun silence() {
        main.removeCallbacks(timeout)
        val stopped = _ringing.value
        _ringing.value = null
        playing?.let { runCatching { it.stop() } }
        playing = null
        runCatching { vibrator()?.cancel() }
        savedAlarmVolume?.let { v -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
        savedFilter?.let { f -> runCatching { nm?.setInterruptionFilter(f) } }
        savedFilter = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        stopped?.let { m -> runCatching { onStopped?.invoke(m) } }
    }

    private fun begin(options: RingOptions) {
        wakeLock = ctx.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retalert:alarm")
            ?.apply { setReferenceCounted(false) }
        liftDnd()
        savedAlarmVolume = setAlarmVolume(options.volumePercent)
        playing = play(options.sound)
        if (options.vibrate) vibrate()
    }

    /** Start [sound] looping on the alarm stream; the built-in siren if it can't play. */
    private fun play(sound: String): Playing {
        val value = AlarmSound.normalize(sound)
        AlarmSound.builtinId(value)?.let { return playBuiltin(it) }
        val uri = when (value) {
            AlarmSound.SYSTEM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            else -> AlarmSound.uriOf(value)?.let(Uri::parse)
        }
        return runCatching { playUri(uri ?: error("no sound")) }.getOrElse {
            Log.w(TAG, "sound $value unavailable, using the built-in siren", it)
            playBuiltin(FALLBACK)
        }
    }

    private fun playUri(uri: Uri): Playing {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(ALARM_AUDIO)
            mp.setDataSource(ctx, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            throw e
        }
        return Playing { runCatching { mp.stop() }; mp.release() }
    }

    /** A synthesized tone, looped forever from a static buffer. */
    private fun playBuiltin(id: String): Playing {
        val pcm = pcmCache.getOrPut(id) { ToneSynth.render(id) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(ALARM_AUDIO)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, pcm.size, -1)
        track.play()
        return Playing { runCatching { track.stop() }; track.release() }
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
        const val PREVIEW_MS = 4_000L
        const val FALLBACK = "siren"
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
