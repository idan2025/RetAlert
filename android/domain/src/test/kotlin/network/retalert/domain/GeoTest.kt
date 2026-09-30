package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GeoTest {
    @Test fun `haversine zero for same point`() {
        assertEquals(0.0, haversineM(10.0, 20.0, 10.0, 20.0), 1e-6)
    }

    @Test fun `haversine known distance`() {
        // London (51.5,-0.12) to Paris (48.85,2.35) ~ 343 km
        val d = haversineM(51.5, -0.12, 48.85, 2.35)
        assertTrue(abs(d - 343_555.0) < 2000.0, "got $d")
    }

    @Test fun `clamp lora throttle bounds`() {
        assertEquals(60.0, clampLoraThrottle(null))
        assertEquals(10.0, clampLoraThrottle(1.0))      // below min
        assertEquals(3600.0, clampLoraThrottle(99999.0)) // above max
        assertEquals(120.0, clampLoraThrottle(120.0))
    }

    @Test fun `fix geo uri`() {
        val f = Fix(1.5, 2.5)
        assertEquals("geo:1.5,2.5", f.geoUri)
    }

    @Test fun `manual fix source refreshes timestamp`() {
        val src = ManualFixSource(1.0, 2.0)
        val f1 = src.getFix()!!
        Thread.sleep(10)
        val f2 = src.getFix()!!
        assertTrue(f2.timestamp >= f1.timestamp)
    }

    @Test fun `last known falls back to cached`() {
        val inner = ManualFixSource(3.0, 4.0)
        val lk = LastKnownFixSource(inner)
        lk.getFix()  // cache it
        // simulate inner going dark by wrapping a source that returns null after first
        val oneShot = object : FixSource {
            override val name = "oneshot"
            private var emitted = false
            override fun getFix(): Fix? = if (emitted) null.also { } else { emitted = true; Fix(5.0, 6.0) }
        }
        val lk2 = LastKnownFixSource(oneShot)
        lk2.getFix()              // emits
        val cached = lk2.getFix() // inner null -> last known
        assertNotNull(cached)
        assertEquals(5.0, cached!!.lat)
        assertEquals("last_known", cached.source)
    }
}

class PresetAndPanicTest {
    @Test fun `preset normalized defaults text on when empty payload`() {
        val p = Preset.normalized(name = "p1", payload = emptyMap())
        assertEquals(mapOf("text" to true, "gps_oneshot" to false, "gps_live" to false, "photo" to false, "audio" to false), p.payload)
    }

    @Test fun `preset normalized keeps explicit payload keys`() {
        val p = Preset.normalized(payload = mapOf("gps_live" to true))
        assertEquals(true, p.payload["gps_live"])
        assertEquals(false, p.payload["text"])
    }

    @Test fun `preset store put get by name`() {
        val store = PresetStore()
        store.put(Preset.normalized(name = "default", severity = "critical"))
        assertNotNull(store.byName("default"))
        assertNull(store.byName("nope"))
    }

    @Test fun `resolver exact then default then null`() {
        val store = PresetStore().apply {
            put(Preset.normalized(name = "default", severity = "critical"))
            put(Preset.normalized(name = "fire", severity = "danger"))
        }
        val r = PresetResolver(store)
        assertEquals("fire", r.resolve("fire")?.name)
        assertEquals("default", r.resolve("unknown")?.name)   // falls back to default
        assertEquals("default", r.resolve("")?.name)          // blank -> default
    }

    @Test fun `resolver null when no default and no match`() {
        val store = PresetStore().apply { put(Preset.normalized(name = "fire")) }
        val r = PresetResolver(store)
        assertNull(r.resolve("unknown"))
    }

    @Test fun `panic engine dedup within window`() {
        val store = PresetStore().apply {
            put(Preset.normalized(name = "default", severity = "critical",
                recipients = listOf("aa"), payload = mapOf("text" to true)))
        }
        val clock = FakeClock(mono = 0.0)
        var sent = 0
        val engine = PanicEngine(
            store = store,
            sendAlertFn = { sent++; it },
            expandGroupFn = { emptyList() },
            clock = clock,
        )
        val a1 = engine.fire("default")
        assertNotNull(a1)
        clock.advanceMono(5.0) // within 30s window
        assertNull(engine.fire("default"))  // deduped
        assertEquals(1, sent)
    }

    @Test fun `panic engine no recipients returns null`() {
        val store = PresetStore().apply {
            put(Preset.normalized(name = "default", recipients = emptyList()))
        }
        val engine = PanicEngine(store, { it }, { emptyList() }, clock = FakeClock())
        assertNull(engine.fire("default"))
    }
}

class InboxTest {
    private val clock = FakeClock(epoch = 1000.0)
    private val inbox = InboxRegistry(clock = clock)

    @Test fun `record and list newest first`() {
        inbox.record("a1", "AA", "critical", "t1", 100.0)
        inbox.record("a2", "BB", "help", "t2", 200.0)
        val list = inbox.list()
        assertEquals("a2", list[0].alertId)
        assertEquals("a1", list[1].alertId)
    }

    @Test fun `record requires alert id`() {
        assertThrows(IllegalArgumentException::class.java) { inbox.record("", "AA", "x", "y") }
    }

    @Test fun `prune drops entries older than max age`() {
        val reg = InboxRegistry(maxAgeS = 100.0, clock = clock)
        reg.record("old", "AA", "x", "y", 800.0)   // 200s old
        reg.record("new", "BB", "x", "y", 950.0)   // 50s old
        clock.epoch = 1000.0
        assertEquals(1, reg.prune())
        assertNull(reg.get("old"))
        assertNotNull(reg.get("new"))
    }
}

class LiveTracksTest {
    private val clock = FakeClock(epoch = 1000.0)
    private val store = LiveTrackStore(clock)

    @Test fun `update and get`() {
        store.update("AABB", Fix(1.0, 2.0), "Alice")
        val t = store.get("aabb")!!   // lowercased
        assertEquals("Alice", t.displayName)
        assertEquals(1.0, t.fix.lat)
    }

    @Test fun `follow only known peer`() {
        store.update("aabb", Fix(1.0, 2.0))
        assertFalse(store.follow("ffff"))
        assertTrue(store.follow("aabb"))
        assertEquals("aabb", store.followed())
    }

    @Test fun `clear stale drops old tracks`() {
        store.update("old", Fix(0.0, 0.0))
        clock.epoch = 1000.0
        store.update("old", Fix(0.0, 0.0))  // lastUpdated=1000
        clock.epoch = 1100.0
        store.update("new", Fix(1.0, 1.0))  // lastUpdated=1100
        clock.epoch = 1200.0
        assertEquals(1, store.clearStale(150.0))  // old (200s) stale, new (100s) fresh
        assertNotNull(store.get("new"))
        assertNull(store.get("old"))
    }

    @Test fun `remove clears follow`() {
        store.update("aabb", Fix(1.0, 2.0))
        store.follow("aabb")
        store.remove("aabb")
        assertNull(store.followed())
    }
}