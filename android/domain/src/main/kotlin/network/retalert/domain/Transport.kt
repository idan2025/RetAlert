package network.retalert.domain

/** Transport-intelligence tiers, classification, policy, ranking, delivery plan.
 *  Mirrors retalert/core/transport_intel.py. Pure logic — no RNS dependency;
 *  operates on [IfaceDescriptor] (the :reticulum module adapts real RNS ifaces). */
object Tiers {
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"

    val RANK = mapOf(HIGH to 3, MEDIUM to 2, LOW to 1)

    fun rankOf(tier: String): Int = RANK[tier] ?: 0
}

/** RNS interface class name -> bandwidth tier. Parity with CLASS_TIER. */
val CLASS_TIER: Map<String, String> = mapOf(
    "LocalServerInterface" to Tiers.HIGH,
    "LocalClientInterface" to Tiers.HIGH,
    "TCPClientInterface" to Tiers.HIGH,
    "TCPServerInterface" to Tiers.HIGH,
    "I2PInterface" to Tiers.HIGH,
    "BackboneInterface" to Tiers.HIGH,
    "BackboneClientInterface" to Tiers.HIGH,
    "AutoInterface" to Tiers.MEDIUM,
    "UDPInterface" to Tiers.MEDIUM,
    "RNodeInterface" to Tiers.LOW,
    "KISSInterface" to Tiers.LOW,
    "AX25KISSInterface" to Tiers.LOW,
    "SerialInterface" to Tiers.LOW,
)

/** Payload class -> minimum tier required to carry it. Parity with PAYLOAD_MIN_TIER. */
val PAYLOAD_MIN_TIER: Map<String, String> = mapOf(
    "text" to Tiers.LOW,
    "ack" to Tiers.LOW,
    "gps_oneshot" to Tiers.LOW,
    "gps_live" to Tiers.MEDIUM,
    "photo" to Tiers.HIGH,
    "audio" to Tiers.HIGH,
)

val CRITICAL_SEVERITIES = setOf("critical", "danger", "medical")

const val FAN_OUT_OFF = "off"
const val FAN_OUT_CRITICAL = "critical"
const val FAN_OUT_ALL = "all"

/** Seam: describes an RNS interface without depending on RNS. :reticulum adapts. */
data class IfaceDescriptor(
    val name: String,
    val cls: String,
    val tier: String,
    val online: Boolean,
    val outCapable: Boolean,
    val hasPath: Boolean = true,
    val userOverride: String? = null,
) {
    val rankValue: Int get() = Tiers.rankOf(tier)
}

/** A classified interface snapshot (post-classification). */
data class IfaceInfo(
    val descriptor: IfaceDescriptor,
    val tier: String,
    val hasPath: Boolean = true,
    val userOverride: String? = null,
) {
    val name: String get() = descriptor.name
    val cls: String get() = descriptor.cls
    val online: Boolean get() = descriptor.online
    val outCapable: Boolean get() = descriptor.outCapable
    val rankValue: Int get() = Tiers.rankOf(tier)
}

/** How an alert should be sent. Parity with DeliveryPlan. */
data class DeliveryPlan(
    val mode: String,                       // "parallel" (Hail Mary) | "sequential"
    val interfaces: List<IfaceInfo> = emptyList(),
    val payloadClasses: List<String> = emptyList(),
    val minTier: String = Tiers.LOW,
    val queued: Boolean = false,
    val reason: String = "",
)

/** Pure transport brain. [ifaces] supplied by :reticulum; [overrides] by name or class. */
class TransportIntelligence(
    private val overrides: Map<String, String> = emptyMap(),
    private val ifaces: () -> List<IfaceDescriptor> = { emptyList() },
    private val hasPath: (recipientHash: ByteArray) -> Boolean = { true },
) {
    // -- classification ---------------------------------------------------

    fun tierOf(d: IfaceDescriptor): String {
        d.name.takeIf { it in overrides }?.let { return overrides[it]!! }
        d.cls.takeIf { it in overrides }?.let { return overrides[it]!! }
        return CLASS_TIER[d.cls] ?: Tiers.LOW
    }

    fun classifyInterfaces(): List<IfaceInfo> =
        ifaces().map { d ->
            IfaceInfo(d, tierOf(d), userOverride = overrides[d.name])
        }

    fun upInterfaces(): List<IfaceInfo> =
        classifyInterfaces().filter { it.online && it.outCapable }

    // -- policy ----------------------------------------------------------

    fun minTierFor(payloadClass: String): String = PAYLOAD_MIN_TIER[payloadClass] ?: Tiers.LOW

    fun allowed(payloadClass: String, tier: String): Boolean =
        Tiers.rankOf(tier) >= Tiers.rankOf(minTierFor(payloadClass))

    fun requiredTier(payloadClasses: List<String>): String {
        if (payloadClasses.isEmpty()) return Tiers.LOW
        return payloadClasses.maxByOrNull { Tiers.rankOf(minTierFor(it)) }
            ?.let { minTierFor(it) } ?: Tiers.LOW
    }

    fun gatePayload(payloadClass: String): Pair<Boolean, String?> {
        val tiers = upInterfaces().map { it.tier }
        if (tiers.isEmpty()) return false to null
        val best = tiers.maxByOrNull { Tiers.rankOf(it) }!!
        return allowed(payloadClass, best) to best
    }

    // -- ranking --------------------------------------------------------

    fun rankFor(recipientHash: ByteArray?, payloadClass: String): List<IfaceInfo> {
        val minTier = minTierFor(payloadClass)
        var path = true
        if (recipientHash != null) {
            path = try { hasPath(recipientHash) } catch (_: Exception) { false }
        }
        val usable = upInterfaces()
            .filter { Tiers.rankOf(it.tier) >= Tiers.rankOf(minTier) }
            .map { it.copy(hasPath = path) }
        return usable.sortedWith(compareByDescending<IfaceInfo> { it.rankValue }.thenBy { it.name })
    }

    // -- delivery plan --------------------------------------------------

    fun deliveryPlan(alert: Alert, fanOut: String = FAN_OUT_CRITICAL): DeliveryPlan {
        val payloadClasses = alert.payload.keys.toList().ifEmpty { listOf("text") }
        val required = requiredTier(payloadClasses)
        val recipientHex = alert.recipients.firstOrNull()
        val recipientHash = recipientHex
            ?.replace(":", "")
            ?.let { runCatching { it.hexToByteArray() }.getOrNull() }

        val maxPayload = payloadClasses.maxByOrNull { Tiers.rankOf(minTierFor(it)) } ?: "text"
        val ranked = rankFor(recipientHash, maxPayload)

        val isCritical = alert.severity in CRITICAL_SEVERITIES
        val doFanout = fanOut == FAN_OUT_ALL || (fanOut == FAN_OUT_CRITICAL && isCritical)
        val minimal = payloadClasses.all { minTierFor(it) == Tiers.LOW }

        if (doFanout && minimal) {
            val ifaces = upInterfaces()
                .sortedWith(compareByDescending<IfaceInfo> { it.rankValue }.thenBy { it.name })
            return DeliveryPlan(
                mode = "parallel", interfaces = ifaces,
                payloadClasses = payloadClasses, minTier = Tiers.LOW,
            )
        }

        if (ranked.isEmpty()) {
            return DeliveryPlan(
                mode = "sequential", interfaces = emptyList(),
                payloadClasses = payloadClasses, minTier = required,
                queued = true, reason = "no interface up at tier $required",
            )
        }
        return DeliveryPlan(
            mode = "sequential", interfaces = ranked,
            payloadClasses = payloadClasses, minTier = required,
        )
    }
}