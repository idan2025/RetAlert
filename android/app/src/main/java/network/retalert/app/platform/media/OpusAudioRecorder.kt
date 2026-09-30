package network.retalert.app.platform.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer

/** Opus audio recorder. Captures 16-bit PCM mono at 48 kHz from the microphone and
 *  encodes it with a MediaCodec `audio/opus` encoder (32 kbps), emitting each encoded
 *  output buffer to [onChunk]. Owns its AudioRecord + MediaCodec lifecycle at use time;
 *  call [start] / [stop] in pairs.
 *
 *  High-tier media capture for the Send screen. Guarded by RECORD_AUDIO permission —
 *  [start] is a no-op if the permission is not granted. Constructed and bound as a
 *  singleton via [MediaModule]. */
class OpusAudioRecorder(
    @ApplicationContext private val ctx: Context,
) {
    private val sampleRate = 48_000
    private val channels = 1
    private val bitRate = 32_000

    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    /** Encoded-chunk callback. Set before [start]; invoked on the recorder thread. */
    @Volatile var onChunk: ((ByteArray) -> Unit)? = null

    val isRecording: Boolean get() = running

    /** Begin capturing + encoding. No-op if already running or permission missing.
     *  If the device has no opus encoder, [start] records nothing and [isRecording]
     *  stays false. */
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf <= 0) return
        val bufSize = minBuf * 2

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        val format = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (_: Exception) {
            record.release()
            return
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (_: Exception) {
            encoder.release()
            record.release()
            return
        }

        audioRecord = record
        codec = encoder
        running = true
        record.startRecording()
        thread = Thread({ encodeLoop() }, "opus-recorder").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop capturing + encoding, draining any remaining encoder output first. */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(500)
        thread = null
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        drain(endOfStream = true)
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release()
        codec = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    private fun encodeLoop() {
        // 20 ms frame at 48 kHz mono 16-bit = 960 samples * 2 bytes = 1920 bytes.
        val frameBytes = (sampleRate / 50) * 2 * channels
        val pcm = ByteArray(frameBytes)
        while (running) {
            val read = try { audioRecord?.read(pcm, 0, pcm.size) ?: -1 } catch (_: Exception) { -1 }
            if (read <= 0) continue
            val inputIndex = codec?.dequeueInputBuffer(5_000L) ?: -1
            if (inputIndex >= 0) {
                val buf: ByteBuffer = codec!!.getInputBuffer(inputIndex) ?: continue
                buf.clear()
                buf.put(pcm, 0, read)
                codec!!.queueInputBuffer(inputIndex, 0, read, 0L, 0)
            }
            drain(endOfStream = false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec?.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L) ?: break
            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (endOfStream) continue else break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> {
                    val buf: ByteBuffer? = codec?.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(data, 0, info.size)
                        onChunk?.invoke(data)
                    }
                    codec?.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }
}