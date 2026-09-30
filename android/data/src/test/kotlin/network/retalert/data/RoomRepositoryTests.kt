package network.retalert.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import network.retalert.domain.Alert
import network.retalert.domain.Preset
import network.retalert.domain.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomRepositoryTests {
    private lateinit var db: RetAlertDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), RetAlertDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun contacts_upsert_and_remove() {
        val repo = RoomContactRepository(db.contactDao())
        repo.add("  ABC123  ", "Alice")
        repo.add("abc123", "Alice Renamed")   // idempotent upsert, same key after trim/lowercase
        val got = repo.get("ABC123")
        assertNotNull(got)
        assertEquals("Alice Renamed", got!!.name)
        assertEquals(1, repo.list().size)
        assertTrue(repo.remove("ABC123"))
        assertNull(repo.get("ABC123"))
        assertFalse(repo.remove("missing"))
    }

    @Test fun groups_create_no_dup_and_add_member() {
        val repo = RoomGroupRepository(db.groupDao())
        assertTrue(repo.create("Team A", listOf("hash1", "HASH2")))
        assertFalse(repo.create("Team A", emptyList()))   // dup -> false
        assertEquals(2, repo.members("Team A").size)
        assertTrue(repo.addMember("Team A", "hash3"))
        assertEquals(3, repo.members("Team A").size)
        // idempotent add
        assertTrue(repo.addMember("Team A", "HASH3"))
        assertEquals(3, repo.members("Team A").size)
        assertTrue(repo.removeMember("Team A", "hash2"))
        assertFalse(repo.addMember("Nope", "x"))   // missing group
        assertTrue(repo.remove("Team A"))
        assertFalse(repo.remove("Team A"))
    }

    @Test fun presets_put_get_byName() {
        val repo = RoomPresetRepository(db.presetDao())
        val saved = repo.put(Preset(id = "p1", name = "Default", payload = mapOf("text" to true)))
        assertEquals("p1", saved.id)
        assertEquals("Default", repo.byName("Default")?.name)   // byName trims (case-sensitive, parity with domain)
        assertEquals("Default", repo.byName("  Default  ")?.name)
        val got = repo.get("p1")
        assertNotNull(got)
        assertEquals("Default", got!!.name)
        // empty payload normalises text=true
        val empty = repo.put(Preset(name = "Empty", payload = emptyMap()))
        assertEquals(true, empty.payload["text"])
        assertTrue(repo.remove("p1"))
        assertFalse(repo.remove("p1"))
    }

    @Test fun inbox_record_and_prune() {
        val repo = RoomInboxRepository(db.inboxDao())
        val now = 1_000_000.0
        val e = repo.record("a1", "SRC", "help", "hello", receivedAt = now - 100.0)
        assertEquals("a1", e.alertId)
        assertEquals("src", e.sourceHash)
        // older entry beyond 7-day window
        repo.record("a2", "src2", "help", "old", receivedAt = now - 8 * 24 * 3600.0)
        assertEquals(2, repo.list().size)
        val pruned = repo.prune(now = now)
        assertEquals(1, pruned)
        assertEquals(1, repo.list().size)
        assertEquals("a1", repo.list().first().alertId)
        assertEquals(1, repo.clear())
    }

    @Test fun outbox_enqueue_pending_ackStates() {
        val repo = RoomOutboxRepository(db.outboxDao())
        val alert = Alert(alertId = "x1", recipients = listOf("r1", "r2"), payload = mapOf("text" to true))
        repo.enqueue(alert)
        assertEquals(1, repo.pending().size)
        assertEquals("x1", repo.pending().first().alertId)
        repo.setAckState("x1", "R1", "sent")
        repo.setAckState("x1", "R2", "delivered")
        val acks = repo.ackStates("x1")
        assertEquals(2, acks.size)
        assertEquals("sent", acks["r1"])         // normalised recipient key
        assertEquals("delivered", acks["r2"])
        repo.setAckState("x1", "R1", "acked")   // upsert
        assertEquals("acked", repo.ackStates("x1")["r1"])
        repo.remove("x1")
        assertEquals(0, repo.pending().size)
        assertEquals(0, repo.ackStates("x1").size)
    }

    @Test fun settings_load_save_roundtrip() {
        val repo = RoomSettingsRepository(db.settingsDao())
        // defaults when empty
        val defaults = repo.load()
        assertEquals(true, defaults.receiveOnlyFromContacts)
        assertEquals("km", defaults.distanceUnits)
        defaults.allow("ABC")
        defaults.deny("def")
        defaults.applyDistanceUnits("mi")
        repo.save(defaults)
        val loaded = repo.load()
        assertEquals(true, loaded.receiveOnlyFromContacts)
        assertEquals("mi", loaded.distanceUnits)
        assertTrue(loaded.allowlist.contains("abc"))
        assertTrue(loaded.denylist.contains("def"))
    }

    @Test fun starred_star_unstar() {
        val repo = RoomStarredRepository(db.starredDao())
        assertTrue(repo.star("ABC"))
        assertEquals(1, repo.starred().size)
        assertFalse(repo.star("abc"))   // IGNORE conflict -> 0 rows inserted
        assertTrue(repo.unstar("abc"))
        assertEquals(0, repo.starred().size)
        assertFalse(repo.unstar("missing"))
    }

    @Test fun key_combos_save_list() {
        val repo = RoomKeyComboRepository(db.keyComboDao())
        repo.save(listOf(
            network.retalert.domain.KeyCombo(listOf("vol_up", "vol_down"), "default", arm = false),
            network.retalert.domain.KeyCombo(listOf("power", "power"), "arm", arm = true),
        ))
        assertEquals(2, repo.list().size)
        // save replaces (clear + insert)
        repo.save(listOf(network.retalert.domain.KeyCombo(listOf("a"), "x")))
        assertEquals(1, repo.list().size)
        repo.save(emptyList())
        assertEquals(0, repo.list().size)
    }
}