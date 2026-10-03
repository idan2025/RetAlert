package network.retalert.domain

/** Interface kinds RetAlert can run on its own stack (when not attached to a
 *  shared instance). Values are persisted — never rename one. */
object IfaceType {
    const val AUTO = "auto"
    const val TCP_CLIENT = "tcp_client"
    const val TCP_SERVER = "tcp_server"
    const val UDP = "udp"
    const val RNODE = "rnode"
    const val BLE = "ble"
    const val I2P = "i2p"

    val ALL = listOf(AUTO, TCP_CLIENT, TCP_SERVER, UDP, RNODE, BLE, I2P)

    /** Types older versions could save, with why they no longer run. A saved one stays
     *  in the list, inactive, showing this reason until the user deletes it. */
    val REMOVED = mapOf(
        "meshtastic" to "Meshtastic support was removed: it was too slow and unreliable for alerts. " +
            "Delete this interface and use an RNode for LoRa.",
    )

    fun label(type: String): String = when (type) {
        AUTO -> "AutoInterface (LAN)"
        TCP_CLIENT -> "TCP client"
        TCP_SERVER -> "TCP server"
        UDP -> "UDP"
        RNODE -> "RNode LoRa"
        BLE -> "Bluetooth LE mesh"
        I2P -> "I2P"
        "meshtastic" -> "Meshtastic node (removed)"
        else -> type
    }

    /** Only one of these may exist at a time (they bind fixed ports / radios). */
    val SINGLETON = setOf(AUTO, BLE)
}

/** Parameter keys used in [IfaceConfig.params]. */
object IfaceParam {
    const val HOST = "host"
    const val PORT = "port"
    const val BIND = "bind"
    const val LISTEN_PORT = "listen_port"
    const val FORWARD_IP = "forward_ip"
    const val FORWARD_PORT = "forward_port"
    const val NETNAME = "ifac_netname"
    const val NETKEY = "ifac_netkey"
    const val BT_ADDRESS = "bt_address"
    const val FREQUENCY = "frequency"      // Hz
    const val BANDWIDTH = "bandwidth"      // Hz
    const val TX_POWER = "txpower"         // dBm
    const val SF = "spreading_factor"
    const val CR = "coding_rate"
    const val PEERS = "peers"              // I2P: comma-separated b32 addresses
    const val CONNECTABLE = "connectable"  // I2P: "true" | "false"
    const val GROUP_ID = "group_id"        // AutoInterface
    const val LINK = "link"                // RNode: "ble" (Bluetooth, LE or Classic by device; default) | "usb"
    const val USB_DEVICE = "usb_device"    // "vid:pid" in hex, or empty = first USB serial device
}

/** How an RNode is attached. Values are persisted. */
object MeshLink {
    const val BLE = "ble"
    const val USB = "usb"
}

private val USB_SPEC_RE = Regex("^[0-9a-f]{4}:[0-9a-f]{4}$")

/** True when [spec] is empty (first device) or a "vid:pid" hex pair. */
fun isUsbSpec(spec: String): Boolean = spec.isEmpty() || USB_SPEC_RE.matches(spec)

/** RNodes default to Bluetooth (configs saved before USB support have no link). */
fun rnodeLink(c: IfaceConfig): String = if (c.param(IfaceParam.LINK) == MeshLink.USB) MeshLink.USB else MeshLink.BLE


/**
 * One user-configured interface. [params] holds the type-specific fields as
 * strings (validated by [validateIface]) so the schema can grow without a
 * storage migration.
 */
data class IfaceConfig(
    val id: String,
    val type: String,
    val name: String,
    val enabled: Boolean = true,
    val params: Map<String, String> = emptyMap(),
) {
    fun param(key: String): String = params[key]?.trim().orEmpty()
    fun intParam(key: String): Int? = param(key).toIntOrNull()
    fun longParam(key: String): Long? = param(key).toLongOrNull()
}

/** Sensible starting values for a new interface of [type]. */
fun defaultParams(type: String): Map<String, String> = when (type) {
    IfaceType.TCP_CLIENT -> mapOf(IfaceParam.PORT to "4242")
    IfaceType.TCP_SERVER -> mapOf(IfaceParam.BIND to "0.0.0.0", IfaceParam.PORT to "4242")
    IfaceType.UDP -> mapOf(
        IfaceParam.LISTEN_PORT to "4242",
        IfaceParam.FORWARD_IP to "255.255.255.255",
        IfaceParam.FORWARD_PORT to "4242",
    )
    // EU 869.525 MHz / 125 kHz / SF8 / CR5 is the common community default.
    IfaceType.RNODE -> mapOf(
        IfaceParam.FREQUENCY to "869525000",
        IfaceParam.BANDWIDTH to "125000",
        IfaceParam.TX_POWER to "14",
        IfaceParam.SF to "8",
        IfaceParam.CR to "5",
    )
    IfaceType.I2P -> mapOf(IfaceParam.CONNECTABLE to "false")
    else -> emptyMap()
}

private val BT_MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
private val LORA_BANDWIDTHS = setOf(
    7_800L, 10_400L, 15_600L, 20_800L, 31_250L, 41_700L, 62_500L, 125_000L, 250_000L, 500_000L,
    203_125L, 406_250L, 812_500L, 1_625_000L,
)

/** Null when [c] is usable, else a user-facing reason. */
fun validateIface(c: IfaceConfig): String? {
    IfaceType.REMOVED[c.type]?.let { return it }
    if (c.type !in IfaceType.ALL) return "unknown interface type '${c.type}'"
    if (c.name.isBlank()) return "give the interface a name"
    fun port(key: String, what: String): String? =
        if (c.intParam(key) in 1..65535) null else "$what must be a port number (1–65535)"
    return when (c.type) {
        IfaceType.TCP_CLIENT -> when {
            c.param(IfaceParam.HOST).isEmpty() || c.param(IfaceParam.HOST).any(Char::isWhitespace) -> "enter the host name or IP"
            else -> port(IfaceParam.PORT, "Port")
        }
        IfaceType.TCP_SERVER -> port(IfaceParam.PORT, "Port")
        IfaceType.UDP -> port(IfaceParam.LISTEN_PORT, "Listen port")
            ?: port(IfaceParam.FORWARD_PORT, "Forward port")
            ?: if (c.param(IfaceParam.FORWARD_IP).isEmpty()) "enter the forward (broadcast) address" else null
        IfaceType.RNODE -> {
            val f = c.longParam(IfaceParam.FREQUENCY)
            when {
                rnodeLink(c) == MeshLink.BLE && !BT_MAC_RE.matches(c.param(IfaceParam.BT_ADDRESS)) -> "pick a paired RNode"
                rnodeLink(c) == MeshLink.USB && !isUsbSpec(c.param(IfaceParam.USB_DEVICE)) -> "pick the USB device"
                f == null || f !in 137_000_000L..3_000_000_000L -> "frequency must be 137–3000 MHz (in Hz)"
                c.longParam(IfaceParam.BANDWIDTH) !in LORA_BANDWIDTHS -> "unsupported LoRa bandwidth"
                c.intParam(IfaceParam.TX_POWER) !in 0..37 -> "TX power must be 0–37 dBm"
                c.intParam(IfaceParam.SF) !in 5..12 -> "spreading factor must be 5–12"
                c.intParam(IfaceParam.CR) !in 5..8 -> "coding rate must be 5–8"
                else -> null
            }
        }
        else -> null
    }
}

/** The interface list an install had before the Interfaces screen existed. */
fun legacyInterfaces(autoInterface: Boolean, tcpSpecs: List<String>): MutableList<IfaceConfig> =
    buildList {
        add(IfaceConfig(id = "auto", type = IfaceType.AUTO, name = "Local network", enabled = autoInterface))
        tcpSpecs.forEach { spec ->
            val (host, port) = parseTcpSpec(spec) ?: return@forEach
            add(
                IfaceConfig(
                    id = "tcp-$spec", type = IfaceType.TCP_CLIENT, name = spec,
                    params = mapOf(IfaceParam.HOST to host, IfaceParam.PORT to port.toString()),
                ),
            )
        }
    }.toMutableList()

fun defaultInterfaces(): MutableList<IfaceConfig> = legacyInterfaces(autoInterface = true, tcpSpecs = emptyList())
