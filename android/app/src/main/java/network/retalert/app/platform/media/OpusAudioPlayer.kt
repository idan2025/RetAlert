package network.retalert.app.platform.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opus audio player. Decodes a sequence of opus-encoded [ByteArray] chunks with a
 *  MediaCodec `audio/opus` decoder and plays back the resulting 16-bit PCM 48 kHz mono
 *  stream via [AudioTrack]. Suspends until playback drains.
 *
 *  High-tier media playback (received audio chunks). Constructed and bound as a
 *  singleton via [MediaModule]. */
class OpusAudioPlayer(
    @ApplicationContext private val ctx: Context,
) {
    /** Decode + play [chunks]. Runs on [Dispatchers.IO]; suspends until the stream
     *  finishes. No-op for an empty list. */
    suspend fun play(chunks: List<ByteArray>) {
        if (chunks.isEmpty()) return
        withContext(Dispatchers.IO) {
            val decoder = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            } catch (_: Exception) {
                return@withContext
            }
            val pcmSampleRate = 48_000
            val pcmChannels = 1

            // The decoder needs an input format hint (mime + sample rate + channels).
            val inputFormat = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, pcmSampleRate)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, pcmChannels)
            }

            val minBuf = AudioTrack.getMinBufferSize(
                pcmSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(pcmSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(1))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                track.play()

                val info = MediaCodec.BufferInfo()
                var eosSignalled = false
                var eosSeen = false
                var idx = 0

                while (!eosSeen) {
                    // Feed input.
                    if (!eosSignalled) {
                        val inIndex = decoder.dequeueInputBuffer(5_000L)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)
                            if (buf != null) {
                                buf.clear()
                                if (idx < chunks.size) {
                                    val data = chunks[idx++]
                                    buf.put(data)
                                    decoder.queueInputBuffer(
                                        inIndex, 0, data.size, 0L, 0,
                                    )
                                } else {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    eosSignalled = true
                                }
                            }
                        }
                    }

                    // Drain output.
                    val outIndex = decoder.dequeueOutputBuffer(info, 5_000L)
                    if (outIndex >= 0) {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(chunk, 0, info.size)
                            track.write(chunk, 0, chunk.size)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosSeen = true
                        }
                    }
                }

                track.stop()
            } finally {
                try { decoder.stop() } catch (_: Exception) {}
                decoder.release()
                track.release()
            }
        }
    }
}