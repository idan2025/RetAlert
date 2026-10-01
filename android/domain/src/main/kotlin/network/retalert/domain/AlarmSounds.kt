package network.retalert.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Which sound an incoming alert rings with, persisted as one string:
 * `builtin:<id>` (synthesized in the app), `system` (the phone's default
 * alarm sound) or `uri:<content uri>` (a ringtone or audio file the user
 * picked). Anything unreadable falls back to [DEFAULT].
 */
object AlarmSound {
    const val SYSTEM = "system"
    private const val BUILTIN = "builtin:"
    private const val URI = "uri:"

    /** Built-in tones: id -> label. Ids are persisted — never rename one. */
    val BUILTINS: List<Pair<String, String>> = listOf(
        "siren" to "Siren",
        "hilo" to "Hi-lo",
        "yelp" to "Yelp",
        "klaxon" to "Klaxon",
        "beeps" to "Urgent beeps",
        "sos" to "SOS (Morse)",
    )

    /** An emergency should not sound like the morning alarm clock. */
    val DEFAULT = builtin("siren")

    fun builtin(id: String) = "$BUILTIN$id"
    fun uri(uri: String) = "$URI$uri"

    fun builtinId(value: String): String? =
        value.removePrefix(BUILTIN).takeIf { value.startsWith(BUILTIN) && BUILTINS.any { b -> b.first == it } }

    fun uriOf(value: String): String? = value.removePrefix(URI).takeIf { value.startsWith(URI) && it.isNotBlank() }

    /** [value] if it names a usable sound, else [DEFAULT]. */
    fun normalize(value: String?): String = when {
        value == null -> DEFAULT
        value == SYSTEM -> value
        builtinId(value) != null -> value
        uriOf(value) != null -> value
        else -> DEFAULT
    }

    const val DEFAULT_VOLUME_PERCENT = 50

    /** Alarm-stream index for [percent] of [max]: rounded, never below 1 so
     *  an alert can't be set to ring silently. */
    fun volumeIndex(max: Int, percent: Int): Int =
        ((max * percent.coerceIn(0, 100) + 50) / 100).coerceIn(1, maxOf(1, max))

    /** Label for a built-in or the system sound; null for a picked uri (its name is stored separately). */
    fun label(value: String): String? = when {
        value == SYSTEM -> "Phone's alarm sound"
        else -> builtinId(value)?.let { id -> BUILTINS.first { it.first == id }.second }
    }
}

/**
 * Synthesizes the built-in alarm tones as one loopable buffer of 16-bit mono
 * PCM. Pure math, so the app ships no audio files (and no licences).
 */
object ToneSynth {
    const val SAMPLE_RATE = 22_050
    /** Headroom below full scale; loudness is set by the alarm volume setting. */
    private const val PEAK = 0.7

    fun render(id: String): ShortArray {
        val b = Buf()
        when (id) {
            // Wail: slow rise and fall, the classic emergency siren.
            "siren" -> b.sweep(fromHz = 650.0, toHz = 1_450.0, seconds = 1.6, shape = Shape.SIREN)
                .sweep(fromHz = 1_450.0, toHz = 650.0, seconds = 1.6, shape = Shape.SIREN)
            // Two-tone European hi-lo.
            "hilo" -> repeat(2) { b.tone(960.0, 0.45, Shape.SIREN).tone(720.0, 0.45, Shape.SIREN) }
            // Yelp: fast repeated rise.
            "yelp" -> repeat(6) { b.sweep(700.0, 1_600.0, 0.16, Shape.SIREN).sweep(1_600.0, 700.0, 0.16, Shape.SIREN) }
            // Klaxon: harsh falling buzz, then a gap.
            "klaxon" -> b.sweep(420.0, 260.0, 0.55, Shape.BUZZ).silence(0.35)
            // Four sharp beeps, pause.
            "beeps" -> { repeat(4) { b.tone(2_100.0, 0.09, Shape.BEEP).silence(0.07) }; b.silence(0.45) }
            // ... --- ... at about 15 wpm, then a word gap.
            "sos" -> {
                val dot = 0.08
                fun mark(units: Int) = b.tone(880.0, dot * units, Shape.BEEP).silence(dot)
                repeat(3) { mark(1) }; b.silence(dot * 2)
                repeat(3) { mark(3) }; b.silence(dot * 2)
                repeat(3) { mark(1) }; b.silence(dot * 6)
            }
            else -> return render("siren")
        }
        return b.toArray()
    }

    enum class Shape { SIREN, BUZZ, BEEP }

    private class Buf {
        private var data = ShortArray(SAMPLE_RATE * 4)
        private var n = 0
        private var phase = 0.0

        private fun push(v: Double) {
            if (n == data.size) data = data.copyOf(data.size * 2)
            data[n++] = (v.coerceIn(-1.0, 1.0) * PEAK * Short.MAX_VALUE).toInt().toShort()
        }

        private fun wave(shape: Shape, p: Double): Double = when (shape) {
            // Sine with a little odd harmonic content, lightly saturated: carries
            // well without the near-square harshness that strains small speakers.
            Shape.SIREN -> tanh(0.9 * (sin(p) + 0.2 * sin(3 * p) + 0.08 * sin(5 * p))) / tanh(0.9 * 1.12)
            // Softened sawtooth buzz.
            Shape.BUZZ -> tanh(1.2 * (((p / (2 * PI)) % 1.0) * 2 - 1)) / tanh(1.2)
            Shape.BEEP -> sin(p)
        }

        fun sweep(fromHz: Double, toHz: Double, seconds: Double, shape: Shape): Buf {
            val count = (seconds * SAMPLE_RATE).toInt()
            for (i in 0 until count) {
                val hz = fromHz + (toHz - fromHz) * i / count
                phase = (phase + 2 * PI * hz / SAMPLE_RATE) % (2 * PI)
                push(wave(shape, phase))
            }
            return this
        }

        /** A steady tone with 5 ms edges so beeps start and stop without clicks. */
        fun tone(hz: Double, seconds: Double, shape: Shape): Buf {
            val count = (seconds * SAMPLE_RATE).toInt()
            val edge = min(count / 2, SAMPLE_RATE / 200)
            for (i in 0 until count) {
                phase = (phase + 2 * PI * hz / SAMPLE_RATE) % (2 * PI)
                val env = when {
                    shape == Shape.SIREN -> 1.0
                    i < edge -> i.toDouble() / edge
                    i >= count - edge -> (count - i).toDouble() / edge
                    else -> 1.0
                }
                push(wave(shape, phase) * env)
            }
            return this
        }

        fun silence(seconds: Double): Buf {
            repeat((seconds * SAMPLE_RATE).toInt()) { push(0.0) }
            phase = 0.0
            return this
        }

        /** The loop's start and end phases differ; a 5 ms fade at both ends
         *  hides the seam when the buffer repeats. */
        fun toArray(): ShortArray {
            val out = data.copyOf(n)
            val edge = min(n / 2, SAMPLE_RATE / 200)
            for (i in 0 until edge) {
                val g = i.toDouble() / edge
                out[i] = (out[i] * g).toInt().toShort()
                out[n - 1 - i] = (out[n - 1 - i] * g).toInt().toShort()
            }
            return out
        }
    }

    /** Peak absolute sample (for tests). */
    fun peak(pcm: ShortArray): Int = pcm.maxOf { abs(it.toInt()) }
}
