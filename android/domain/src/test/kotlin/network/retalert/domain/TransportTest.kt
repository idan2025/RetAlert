package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransportTest {
    private val ifaces = listOf(
        IfaceDescriptor("tcp1", "TCPClientInterface", Tiers.HIGH, online = true, outCapable = true),
        IfaceDescriptor("auto1", "AutoInterface", Tiers.MEDIUM, online = true, outCapable = true),
        IfaceDescriptor("rnode1", "RNodeInterface", Tiers.LOW, online = true, outCapable = true),
        IfaceDescriptor("down", "TCPClientInterface", Tiers.HIGH, online = false, outCapable = true),
    )
    private val ti = TransportIntelligence(
        overrides = emptyMap(),
        ifaces = { ifaces },
        hasPath = { true },
    )

    @Test fun `classify maps classes to tiers`() {
        val info = ti.classifyInterfaces().associateBy { it.name }
        assertEquals(Tiers.HIGH, info["tcp1"]?.tier)
        assertEquals(Tiers.MEDIUM, info["auto1"]?.tier)
        assertEquals(Tiers.LOW, info["rnode1"]?.tier)
    }

    @Test fun `unknown class defaults to LOW`() {
        val t = TransportIntelligence(ifaces = {
            listOf(IfaceDescriptor("x", "MysteryInterface", Tiers.LOW, online = true, outCapable = true))
        })
        assertEquals(Tiers.LOW, t.classifyInterfaces().first().tier)
    }

    @Test fun `override by name wins`() {
        val t = TransportIntelligence(overrides = mapOf("rnode1" to Tiers.HIGH), ifaces = { ifaces })
        assertEquals(Tiers.HIGH, t.tierOf(ifaces[2]))
    }

    @Test fun `up interfaces exclude offline`() {
        assertEquals(3, ti.upInterfaces().size)
        assertTrue(ti.upInterfaces().none { it.name == "down" })
    }

    @Test fun `min tier policy`() {
        assertEquals(Tiers.LOW, ti.minTierFor("text"))
        assertEquals(Tiers.MEDIUM, ti.minTierFor("gps_live"))
        assertEquals(Tiers.HIGH, ti.minTierFor("photo"))
    }

    @Test fun `gate_payload blocks photo when only low up`() {
        val t = TransportIntelligence(ifaces = {
            listOf(IfaceDescriptor("rnode1", "RNodeInterface", Tiers.LOW, online = true, outCapable = true))
        })
        val (ok, best) = t.gatePayload("photo")
        assertFalse(ok)
        assertEquals(Tiers.LOW, best)
    }

    @Test fun `rank_for filters by tier and sorts high first`() {
        val ranked = ti.rankFor(null, "gps_live")  // min MEDIUM
        assertEquals(listOf("tcp1", "auto1"), ranked.map { it.name })
    }

    @Test fun `rank_for low payload includes all up`() {
        val ranked = ti.rankFor(null, "text")
        assertEquals(listOf("tcp1", "auto1", "rnode1"), ranked.map { it.name })
    }

    @Test fun `delivery_plan hail mary for critical minimal`() {
        val alert = Alert(alertId = "a", severity = Severity.CRITICAL, recipients = listOf("aa"),
            payload = mapOf("text" to true))
        val plan = ti.deliveryPlan(alert, fanOut = FanOut.CRITICAL)
        assertEquals("parallel", plan.mode)
        assertEquals(3, plan.interfaces.size)
        assertEquals(Tiers.LOW, plan.minTier)
    }

    @Test fun `delivery_plan sequential for non-critical`() {
        val alert = Alert(alertId = "a", severity = Severity.HELP, recipients = listOf("aa"),
            payload = mapOf("text" to true))
        val plan = ti.deliveryPlan(alert, fanOut = FanOut.CRITICAL)
        assertEquals("sequential", plan.mode)
    }

    @Test fun `delivery_plan never fans out heavy payload`() {
        val alert = Alert(alertId = "a", severity = Severity.CRITICAL, recipients = listOf("aa"),
            payload = mapOf("photo" to true))
        val plan = ti.deliveryPlan(alert, fanOut = FanOut.ALL)
        assertEquals("sequential", plan.mode)  // photo is HIGH; not minimal -> sequential
    }

    @Test fun `delivery_plan queued when no compatible iface`() {
        val t = TransportIntelligence(ifaces = {
            listOf(IfaceDescriptor("rnode1", "RNodeInterface", Tiers.LOW, online = true, outCapable = true))
        })
        val alert = Alert(alertId = "a", severity = Severity.HELP, recipients = listOf("aa"),
            payload = mapOf("photo" to true))
        val plan = t.deliveryPlan(alert)
        assertTrue(plan.queued)
        assertEquals("no interface up at tier high", plan.reason)
    }
}