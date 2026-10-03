package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test fun `settings default to AutoInterface on and validate interfaces`() {
        val s = Settings()
        assertEquals(listOf(IfaceType.AUTO), s.interfaces.map { it.type })
        assertTrue(s.interfaces.single().enabled)
        val tcp = IfaceConfig("t1", IfaceType.TCP_CLIENT, "Hub", params = mapOf(IfaceParam.HOST to "a.b", IfaceParam.PORT to "4242"))
        assertNull(s.upsertInterface(tcp))
        assertNull(s.upsertInterface(tcp.copy(name = "Hub 2")))   // same id replaces
        assertEquals(2, s.interfaces.size)
        assertEquals("Hub 2", s.interfaces.last().name)
        assertEquals("Port must be a port number (1–65535)", s.upsertInterface(tcp.copy(id = "t2", params = mapOf(IfaceParam.HOST to "a.b"))))
        assertEquals("only one AutoInterface (LAN) interface is supported", s.upsertInterface(IfaceConfig("a2", IfaceType.AUTO, "x")))
        assertTrue(s.setInterfaceEnabled("t1", false))
        assertFalse(s.interfaces.last().enabled)
        assertTrue(s.removeInterface("t1"))
        assertEquals(1, s.interfaces.size)
    }

    @Test fun `quick replies are cleaned and capped`() {
        assertEquals(listOf("On my way", "Can't come", "Call me"), Settings().quickReplies)
        assertEquals(
            listOf("a", "b", "c", "d"),
            normalizeQuickReplies(listOf(" a ", "", "b", "a", "c", "d", "e")),
        )
        assertEquals(40, normalizeQuickReplies(listOf("x".repeat(99))).single().length)
    }

    @Test fun `rnode validation and legacy migration`() {
        val ok = IfaceConfig("r", IfaceType.RNODE, "LoRa", params = defaultParams(IfaceType.RNODE) + (IfaceParam.BT_ADDRESS to "AA:BB:CC:DD:EE:FF"))
        assertNull(validateIface(ok))
        assertEquals("pick a paired RNode", validateIface(ok.copy(params = defaultParams(IfaceType.RNODE))))
        assertEquals("spreading factor must be 5–12", validateIface(ok.copy(params = ok.params + (IfaceParam.SF to "13"))))
        // USB RNode: no Bluetooth address needed; device is "vid:pid" or empty (first found).
        val usb = ok.copy(params = defaultParams(IfaceType.RNODE) + (IfaceParam.LINK to MeshLink.USB))
        assertNull(validateIface(usb))
        assertNull(validateIface(usb.copy(params = usb.params + (IfaceParam.USB_DEVICE to "10c4:ea60"))))
        assertEquals("pick the USB device", validateIface(usb.copy(params = usb.params + (IfaceParam.USB_DEVICE to "junk"))))
        // A Meshtastic interface saved by 0.5.x stays listed but can't run, and says why.
        val mesh = IfaceConfig("m", "meshtastic", "Mesh", params = mapOf(IfaceParam.LINK to MeshLink.USB))
        assertEquals(IfaceType.REMOVED["meshtastic"], validateIface(mesh))
        assertFalse("meshtastic" in IfaceType.ALL)
        assertEquals("Meshtastic node (removed)", IfaceType.label("meshtastic"))
        val migrated = legacyInterfaces(autoInterface = false, tcpSpecs = listOf("rns.example.org:4965", "bad"))
        assertEquals(listOf(IfaceType.AUTO, IfaceType.TCP_CLIENT), migrated.map { it.type })
        assertFalse(migrated[0].enabled)
        assertEquals("rns.example.org", migrated[1].param(IfaceParam.HOST))
        assertEquals(4965, migrated[1].intParam(IfaceParam.PORT))
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
