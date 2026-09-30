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
