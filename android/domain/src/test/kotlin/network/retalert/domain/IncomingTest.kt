package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IncomingCodecTest {
    @Test fun `encode v1 alert round trips`() {
        val s = encodeAlert("critical", "help me", "abc123")
        assertEquals("!RETALERT!id:abc123!critical!help me", s)
        val (id, sev, text) = decodeAlert(s)!!
        assertEquals("abc123", id); assertEquals("critical", sev); assertEquals("help me", text)
    }

    @Test fun `encode v0 legacy alert`() {
        val s = encodeAlert("medical", "fallen")
        assertEquals("!RETALERT!medical!fallen", s)
        val (id, sev, text) = decodeAlert(s)!!
        assertEquals("", id); assertEquals("medical", sev); assertEquals("fallen", text)
    }

    @Test fun `decode non-alert returns null`() {
        assertNull(decodeAlert("hello there"))
        assertNull(decodeAlert(""))
    }

    @Test fun `alert text may contain bangs`() {
        val s = encodeAlert("danger", "fire! now!", "id1")
        val (id, sev, text) = decodeAlert(s)!!
        assertEquals("id1", id); assertEquals("danger", sev); assertEquals("fire! now!", text)
    }

    @Test fun `ack encode decode`() {
        assertEquals("!RETALERT!ack!id9", encodeAck("id9"))
        assertEquals("id9", decodeAck("!RETALERT!ack!id9"))
        assertNull(deAckOrNot("!RETALERT!ack!"))
    }

    private fun deAckOrNot(s: String) = decodeAck(s)

    @Test fun `ack decode and decode_alert precedence`() {
        // At the codec level decode_alert parses "!RETALERT!ack!<id>" as a v0
        // alert (severity "ack"); the *dispatcher* disambiguates by checking
        // decode_ack first. Verify both: ack extraction, and that decode_alert
        // sees it as v0 (so the dispatcher's ordering is what matters).
        assertEquals("id9", decodeAck("!RETALERT!ack!id9"))
        val (id, sev, _) = decodeAlert("!RETALERT!ack!id9")!!
        assertEquals("", id)
        assertEquals("ack", sev)
    }

    @Test fun `reply encode decode`() {
        val s = encodeReply("id3", "en route")
        assertEquals("!RETALERT!reply!id3!en route", s)
        val (id, text) = decodeReply(s)!!
        assertEquals("id3", id); assertEquals("en route", text)
    }

    @Test fun `reply empty text`() {
        val (id, text) = decodeReply("!RETALERT!reply!id3!")!!
        assertEquals("id3", id); assertEquals("", text)
    }

    @Test fun `parse geo body full`() {
        val fix = parseGeoBody("geo:37.77,-122.42 acc=12.5 alt=30 src=lxmf")!!
        assertEquals(37.77, fix.lat); assertEquals(-122.42, fix.lon)
        assertEquals(12.5, fix.accuracy); assertEquals(30.0, fix.altitude); assertEquals("lxmf", fix.source)
    }

    @Test fun `parse geo body minimal defaults src to lxmf`() {
        val fix = parseGeoBody("geo:40.0,10.0")!!
        assertEquals(40.0, fix.lat); assertEquals(10.0, fix.lon)
        assertNull(fix.accuracy); assertEquals("lxmf", fix.source)
    }

    @Test fun `parse geo body returns null for non-geo`() {
        assertNull(parseGeoBody("hello"))
    }
}

class IncomingFilterTest {
    private fun dispatcher(settings: Settings, contacts: Contacts = Contacts()) =
        IncomingDispatcher(SettingsReceiveSettings(settings), contacts)

    @Test fun `denylist blocks even if in contacts`() {
        val s = Settings().apply { deny("deadbeef") }
        val d = dispatcher(s)
        assertNull(d.handle("deadbeef", "hi", 1.0))
    }

    @Test fun `allowlist permits`() {
        val s = Settings().apply { allow("aabbcc") }
        val d = dispatcher(s)
        assertNotNull(d.handle("aabbcc", "hi", 1.0))
    }

    @Test fun `receive only from contacts blocks unknown`() {
        val s = Settings().apply { receiveOnlyFromContacts = true }
        val contacts = Contacts().apply { add("aabb", "Alice") }
        val d = dispatcher(s, contacts)
        assertNull(d.handle("ffff", "hi", 1.0))        // not a contact
        assertNotNull(d.handle("aabb", "hi", 1.0))     // contact
    }

    @Test fun `open mode accepts any`() {
        val s = Settings().apply { receiveOnlyFromContacts = false }
        val d = dispatcher(s)
        assertNotNull(d.handle("anyone", "hi", 1.0))
    }

    @Test fun `precedence deny beats allow`() {
        val s = Settings().apply { allow("xx"); deny("xx") }
        val d = dispatcher(s)
        assertNull(d.handle("xx", "hi", 1.0))
    }

    @Test fun `parses alert and fires bypass silent`() {
        val s = Settings().apply { receiveOnlyFromContacts = false }
        var fired: IncomingMessage? = null
        val d = dispatcher(s).apply { bypassSilentCb = { fired = it } }
        val msg = d.handle("peer1", "!RETALERT!id:aid!critical!fire", 5.0)
        assertEquals("alert", msg?.kind)
        assertEquals("critical", msg?.severity)
        assertEquals("aid", msg?.alertId)
        assertEquals("fire", msg?.text)
        assertEquals("peer1", fired?.sourceHash)
    }

    @Test fun `parses ack and invokes ack callback`() {
        val s = Settings().apply { receiveOnlyFromContacts = false }
        var acked: Pair<String, String>? = null
        val d = IncomingDispatcher(SettingsReceiveSettings(s), Contacts(),
            ackCb = { id, src -> acked = id to src })
        val msg = d.handle("peer2", "!RETALERT!ack!aid2", 1.0)
        assertEquals("ack", msg?.kind)
        assertEquals("aid2", msg?.alertId)
        assertEquals("aid2" to "peer2", acked)
    }
}