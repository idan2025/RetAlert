package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AlarmSoundTest {

    @Test fun `values parse, normalise and label`() {
        assertEquals("builtin:siren", AlarmSound.DEFAULT)
        assertEquals("klaxon", AlarmSound.builtinId("builtin:klaxon"))
        assertNull(AlarmSound.builtinId("builtin:nope"))
        assertEquals("content://media/1", AlarmSound.uriOf(AlarmSound.uri("content://media/1")))
        assertEquals(AlarmSound.DEFAULT, AlarmSound.normalize(null))
        assertEquals(AlarmSound.DEFAULT, AlarmSound.normalize("builtin:gone"))
        assertEquals(AlarmSound.DEFAULT, AlarmSound.normalize("uri:"))
        assertEquals(AlarmSound.SYSTEM, AlarmSound.normalize(AlarmSound.SYSTEM))
        assertEquals("Klaxon", AlarmSound.label("builtin:klaxon"))
        assertEquals("Phone's alarm sound", AlarmSound.label(AlarmSound.SYSTEM))
        assertNull(AlarmSound.label("uri:content://x"))
    }

    @Test fun `volume index rounds and never reaches zero`() {
        assertEquals(4, AlarmSound.volumeIndex(7, 50))
        assertEquals(7, AlarmSound.volumeIndex(7, 100))
        assertEquals(1, AlarmSound.volumeIndex(7, 0))
        assertEquals(8, AlarmSound.volumeIndex(15, 50))
        assertEquals(15, AlarmSound.volumeIndex(15, 150))
    }

    @Test fun `every built-in renders a loud, seamless loop`() {
        for ((id, _) in AlarmSound.BUILTINS) {
            val pcm = ToneSynth.render(id)
            val seconds = pcm.size.toDouble() / ToneSynth.SAMPLE_RATE
            assertTrue(seconds in 0.5..4.0, "$id loop is ${seconds}s")
            assertTrue(ToneSynth.peak(pcm) in 15_000..23_500, "$id peak ${ToneSynth.peak(pcm)}")
            // Loop seam: both ends fade to (near) zero, so repeating doesn't click.
            assertTrue(abs(pcm.first().toInt()) < 500 && abs(pcm.last().toInt()) < 500, "$id seam")
        }
        assertEquals(ToneSynth.render("siren").size, ToneSynth.render("unknown").size)
    }
}
