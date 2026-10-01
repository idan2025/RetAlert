package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireTest {
    private val fix = Fix(32.603172, 35.285351, accuracy = 13.0, altitude = 86.0, timestamp = 1790851727.0, source = "android")

    @Test fun `telemeter bytes match Python umsgpack (Sideband, Columba)`() {
        // Python: umsgpack.packb({1: t, 2: [struct.pack("!i", round(lat*1e6)), …, struct.pack("!H", round(acc*1e2)), t]})
        val python = "8201ce6abe3a8f0297c40401f17c24c404021a6967c40400002198c40400000000c40400000000c4020514ce6abe3a8f"
        assertEquals(python, Telemeter.pack(fix).toHexString())
        val back = Telemeter.unpack(Telemeter.pack(fix))!!
        assertEquals(fix.lat, back.lat, 1e-9)
        assertEquals(fix.lon, back.lon, 1e-9)
        assertEquals(13.0, back.accuracy!!, 1e-9)
        assertEquals(86.0, back.altitude!!, 1e-9)
        assertEquals(1790851727.0, back.timestamp, 0.0)
        assertNull(Telemeter.unpack(byteArrayOf(1, 2, 3)))
    }

    @Test fun `custom data matches Python umsgpack`() {
        val python = "83a16ba5616c657274a26964a6616263313233a3736576a8637269746963616c"
        val out = Wire.alert("critical", "x", "abc123", legacy = false)
        assertEquals(python, (out.fields[LxmfField.CUSTOM_DATA] as ByteArray).toHexString())
    }

    @Test fun `new alert is readable for other apps and decodes back for RetAlert`() {
        val out = Wire.alert("critical", "Fire on floor 2\nCome now", "id42", legacy = false)
        assertEquals("🚨 CRITICAL ALERT\nFire on floor 2\nCome now", out.content)
        assertEquals("retalert", out.fields[LxmfField.CUSTOM_TYPE])
        assertFalse(out.content.contains("!RETALERT!"))
        val internal = Wire.toInternal(out.content, out.fields)
        assertEquals(Triple("id42", "critical", "Fire on floor 2\nCome now"), decodeAlert(internal))
    }

    @Test fun `ack, reply and location round-trip, legacy is the old text`() {
        val ack = Wire.ack("id42", legacy = false)
        assertEquals("✓ Alert received", ack.content)
        assertEquals("id42", decodeAck(Wire.toInternal(ack.content, ack.fields)))

        val reply = Wire.reply("id42", "On my way", legacy = false)
        assertEquals("On my way", reply.content)
        assertEquals("id42" to "On my way", decodeReply(Wire.toInternal(reply.content, reply.fields)))

        val geo = Wire.geo(fix, legacy = false, expiresAtMs = 123L)
        assertEquals("", geo.content)          // Columba/Sideband: map only, no chat bubble
        assertTrue(LxmfField.TELEMETRY in geo.fields && LxmfField.CUSTOM_META in geo.fields)
        val parsed = parseGeoBody(Wire.toInternal(geo.content, geo.fields))!!
        assertEquals(fix.lat, parsed.lat, 1e-6)
        assertEquals(fix.lon, parsed.lon, 1e-6)

        assertEquals(encodeAlert("critical", "x", "id1"), Wire.alert("critical", "x", "id1", legacy = true).content)
        assertTrue(Wire.alert("critical", "x", "id1", legacy = true).fields.isEmpty())
        assertEquals(encodeGeoBody(fix), Wire.geo(fix, legacy = true).content)
    }

    @Test fun `old-format and plain messages pass through untouched`() {
        val old = encodeAlert("danger", "legacy", "id9")
        assertEquals(old, Wire.toInternal(old, emptyMap()))
        assertEquals("hello", Wire.toInternal("hello", emptyMap()))
        // A Columba user's shared location (empty text + Telemeter) becomes a geo fix.
        val columba = mapOf<Int, Any?>(LxmfField.TELEMETRY to Telemeter.pack(fix))
        assertTrue(Wire.toInternal("", columba).startsWith("geo:32.603172,35.285351"))
        // Someone else's custom type is not ours: content as-is.
        assertEquals("hi", Wire.toInternal("hi", mapOf(LxmfField.CUSTOM_TYPE to "other")))
    }
}
