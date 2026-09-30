package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsAndParsingTest {

    @Test fun `normalizeHash accepts common spellings`() {
        val h = "0123456789abcdef0123456789abcdef"
        assertEquals(h, normalizeHash(h))
        assertEquals(h, normalizeHash("<0123456789ABCDEF0123456789ABCDEF>"))
        assertEquals(h, normalizeHash(" 01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef "))
    }

    @Test fun `normalizeHash rejects wrong length or non-hex`() {
        assertNull(normalizeHash("abc"))
        assertNull(normalizeHash("0123456789abcdef0123456789abcdeg"))
        assertNull(normalizeHash("Alice"))
    }

    @Test fun `tcp specs parse and normalise`() {
        assertEquals("10.0.0.5" to 4242, parseTcpSpec("10.0.0.5:4242"))
        assertEquals("rns.example.org" to 4965, parseTcpSpec(" rns.example.org:4965 "))
        assertEquals("fe80::1" to 4242, parseTcpSpec("[fe80::1]:4242"))
        assertEquals("[fe80::1]:4242", normalizeTcpSpec("[fe80::1]:4242"))
        assertNull(parseTcpSpec("host"))
        assertNull(parseTcpSpec("host:0"))
        assertNull(parseTcpSpec("host:99999"))
        assertNull(parseTcpSpec(":4242"))
    }

    @Test fun `settings default to AutoInterface on and dedupe TCP interfaces`() {
        val s = Settings()
        assertTrue(s.autoInterface)
        assertEquals("a.b:1", s.addTcpInterface("a.b:1"))
        s.addTcpInterface("a.b:1")
        assertEquals(listOf("a.b:1"), s.tcpInterfaces)
        assertNull(s.addTcpInterface("nope"))
        assertTrue(s.removeTcpInterface("a.b:1"))
        assertTrue(s.tcpInterfaces.isEmpty())
    }

    @Test fun `geo body round-trips through the parser`() {
        val body = encodeGeoBody(Fix(32.0853, 34.781768, accuracy = 7.4, altitude = 35.0, source = "android"))
        assertEquals("geo:32.085300,34.781768 acc=7 alt=35 src=android", body)
        val f = parseGeoBody(body)!!
        assertEquals(32.0853, f.lat, 1e-9)
        assertEquals(34.781768, f.lon, 1e-9)
        assertEquals(7.0, f.accuracy)
        assertEquals("android", f.source)
        // Without optional fields.
        assertEquals("geo:-1.500000,2.000000 src=manual", encodeGeoBody(Fix(-1.5, 2.0)))
    }

    @Test fun `live share interval respects the LoRa throttle`() {
        assertEquals(15.0, liveShareInterval(15.0, loraOnly = false, loraThrottleS = null))
        assertEquals(LORA_THROTTLE_DEFAULT, liveShareInterval(15.0, loraOnly = true, loraThrottleS = null))
        assertEquals(120.0, liveShareInterval(15.0, loraOnly = true, loraThrottleS = 120.0))
        assertEquals(5.0, liveShareInterval(1.0, loraOnly = false, loraThrottleS = null))
    }

    @Test fun `ack tracker reports each state transition once`() {
        val ack = AckTracker(FakeClock(epoch = 1000.0, mono = 0.0))
        val seen = mutableListOf<String>()
        ack.onStateChange = { _, r, st -> seen += "$r=$st" }
        ack.track(Alert(alertId = "a1", recipients = listOf("aa")))
        ack.onDelivered("a1", "aa")
        ack.onDelivered("a1", "aa")        // no change -> no event
        ack.onAck("a1", "aa", "on my way")
        ack.onFailed("a1", "aa")           // replied is terminal -> no event
        assertEquals(listOf("aa=delivered", "aa=replied"), seen)
    }
}

class LiveTrackTrailTest {
    @Test fun `store keeps a bounded trail of distinct fixes`() {
        val store = LiveTrackStore(historySize = 3)
        store.update("AA", Fix(1.0, 1.0))
        store.update("aa", Fix(1.0, 1.0))      // same position: not a new trail point
        store.update("aa", Fix(2.0, 2.0))
        store.update("aa", Fix(3.0, 3.0))
        store.update("aa", Fix(4.0, 4.0))      // oldest drops out
        assertEquals(listOf(2.0, 3.0, 4.0), store.trail("aa").map { it.lat })
        store.remove("aa")
        assertTrue(store.trail("aa").isEmpty())
    }
}
