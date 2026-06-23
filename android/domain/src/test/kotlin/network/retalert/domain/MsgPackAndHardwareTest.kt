package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MsgPackTest {
    @Test fun `round trips media chunk 6-tuple byte-identical`() {
        val payload = ByteArray(100) { (it and 0xff).toByte() }
        val chunk = MediaChunk("alertid", "photo", 0, 3, "deadbeef", payload)
        val bytes = chunk.asBytes()
        val back = MediaChunk.fromBytes(bytes)
        assertEquals("alertid", back.alertId)
        assertEquals("photo", back.kind)
        assertEquals(0, back.seq)
        assertEquals(3, back.total)
        assertEquals("deadbeef", back.sha256)
        assertArrayEquals(payload, back.payload)
    }

    @Test fun `pack unpack int edge values`() {
        // unpack returns the smallest boxed type (Int for fixint); compare as Number.
        fun n(v: Int) = (MsgPack.unpack(MsgPack.pack(v)) as Number).toLong()
        assertEquals(0L, n(0))
        assertEquals(127L, n(127))
        assertEquals(128L, n(128))
        assertEquals(300L, n(300))
        assertEquals(70_000L, n(70_000))
    }

    @Test fun `pack unpack long string`() {
        val s = "x".repeat(300)
        assertEquals(s, MsgPack.unpack(MsgPack.pack(s)))
    }

    @Test fun `pack unpack large bin`() {
        val b = ByteArray(500) { 42 }
        val out = MsgPack.unpack(MsgPack.pack(b)) as ByteArray
        assertArrayEquals(b, out)
    }
}

class MediaChannelTest {
    private val ti = TransportIntelligence(
        ifaces = { listOf(IfaceDescriptor("tcp", "TCPClientInterface", Tiers.HIGH, true, true)) },
    )

    @Test fun `send photo chunks and reassemble`() {
        val frames = mutableListOf<ByteArray>()
        val ch = MediaChannel(ti = ti, linkSendFn = { frames.add(it) })
        val data = ByteArray(10_000) { (it and 0xff).toByte() }
        val n = ch.sendPhoto("aid", data)
        assertEquals(3, n)  // ceil(10000/4096)=3
        // receiver side
        val rx = MediaChannel()
        var completed: Media? = null
        val rxCh = MediaChannel(onComplete = { completed = it })
        frames.forEach { rxCh.receive(it) }
        assertNotNull(completed)
        assertArrayEquals(data, completed!!.data)
        assertEquals(true, completed!!.complete)
    }

    @Test fun `photo gated off when no high tier`() {
        val lowTi = TransportIntelligence(
            ifaces = { listOf(IfaceDescriptor("rnode", "RNodeInterface", Tiers.LOW, true, true)) },
        )
        val ch = MediaChannel(ti = lowTi, linkSendFn = {})
        assertEquals(0, ch.sendPhoto("aid", ByteArray(10)))
    }

    @Test fun `audio chunk fires on_audio_chunk`() {
        var got: Media? = null
        val ch = MediaChannel(onAudioChunk = { got = it })
        val frame = MediaChunk("aid", "audio", 0, 0, "sha", ByteArray(5) { 9 }).asBytes()
        val m = ch.receive(frame)
        assertNotNull(m)
        assertEquals("audio", m?.kind)
        assertEquals("audio", got?.kind)
    }
}

class HardwareKeysTest {
    // Start mono past the cooldown window so a first fire isn't blocked by the
    // default lastFire=0 (parity with Python, where monotonic is > cooldown by first use).
    private val clock = FakeClock(mono = 100.0)

    private fun manager() = HardwareKeyManager(
        fireFn = { it }, armWindowS = 5.0, cooldownS = 5.0, clock = clock,
    )

    @Test fun `direct fire when no arm combos`() {
        val m = manager()
        m.register("vol_down", "vol_down", "vol_down", trigger = "panic")
        assertEquals("panic", m.feedSequence(listOf("vol_down", "vol_down", "vol_down")))
    }

    @Test fun `arm then fire`() {
        val m = manager()
        m.register("power", "power", trigger = "arm", arm = true)
        m.register("vol_up", "vol_up", "vol_up", trigger = "panic")
        // fire without arm -> ignored
        assertNull(m.feedSequence(listOf("vol_up", "vol_up", "vol_up")))
        // arm then fire
        assertNull(m.feedSequence(listOf("power", "power")))
        assertEquals("panic", m.feedSequence(listOf("vol_up", "vol_up", "vol_up")))
    }

    @Test fun `cooldown dedup`() {
        val m = manager()
        m.register("a", "a", trigger = "t")
        assertEquals("t", m.feedSequence(listOf("a", "a")))
        clock.advanceMono(1.0) // within 5s cooldown
        assertNull(m.feedSequence(listOf("a", "a")))
        clock.advanceMono(5.0) // past cooldown
        assertEquals("t", m.feedSequence(listOf("a", "a")))
    }

    @Test fun `register replaces same combo`() {
        val m = manager()
        m.register("a", trigger = "t1")
        m.register("a", trigger = "t2")
        assertEquals(1, m.listCombos().size)
        assertEquals("t2", m.listCombos().first().trigger)
    }
}

class DiscoverTest {
    private val clock = FakeClock(epoch = 1000.0)
    private val starred = mutableSetOf("starredpeer")
    private val discover = Discover(clock = clock, starred = starred)

    @Test fun `heard inserts and refreshes`() {
        val appData = MsgPack.pack(listOf("Alice".toByteArray(), 0, listOf("lxmf")))
        val p = discover.heard("aabbcc", appData, ASPECT_LXMF_DELIVERY)
        assertEquals("Alice", p.displayName)
        assertEquals("lxmf", p.appName)
        clock.epoch = 2000.0
        val p2 = discover.heard("aabbcc", appData, ASPECT_LXMF_DELIVERY)
        assertEquals(2000.0, p2.lastHeard)
    }

    @Test fun `non-LXMF announce leaves name blank`() {
        val p = discover.heard("xx", ByteArray(5), "other.aspect")
        assertEquals("", p.displayName)
        assertEquals("other", p.appName)
    }

    @Test fun `starred persists flag on heard`() {
        val p = discover.heard("starredpeer", null, ASPECT_LXMF_DELIVERY)
        assertEquals(true, p.starred)
    }

    @Test fun `list freshest first`() {
        clock.epoch = 1000.0; discover.heard("a", null)
        clock.epoch = 2000.0; discover.heard("b", null)
        assertEquals(listOf("b", "a"), discover.list().map { it.hash })
    }

    @Test fun `star unstar`() {
        discover.heard("peer1", null)
        assertEquals(true, discover.star("peer1"))
        assertEquals(true, discover.get("peer1")?.starred)
        assertEquals(true, discover.unstar("peer1"))
        assertEquals(false, discover.get("peer1")?.starred)
    }
}