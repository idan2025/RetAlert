package network.retalert.domain

import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** LXMF field ids RetAlert reads or writes (LXMF.py / lxmf-kt LXMFConstants). */
object LxmfField {
    /** Sideband Telemeter blob — locations on Sideband / Columba / MeshChat maps. */
    const val TELEMETRY = 0x02
    /** Upstream extension point for app-specific messages: type tag + data. */
    const val CUSTOM_TYPE = 0xFB
    const val CUSTOM_DATA = 0xFC
    /** Columba location extras ({cease, expires, …}); Sideband ignores it. */
    const val CUSTOM_META = 0xFD
}

/** One outgoing LXMF message: what other apps display, plus machine fields. */
data class LxmfOut(val content: String, val fields: Map<Int, Any> = emptyMap())

/**
 * Sideband's Telemeter location encoding (sbapp/sideband/sense.py,
 * Location.pack): `{SID_TIME: t, SID_LOCATION: [lat, lon, alt, speed,
 * bearing, accuracy, last_update]}` with big-endian packed ints. Byte-for-byte
 * what Columba's TelemeterCodec writes, so RetAlert locations show on their maps.
 */
object Telemeter {
    private const val SID_TIME = 0x01
    private const val SID_LOCATION = 0x02

    fun pack(fix: Fix, speedMps: Double = 0.0, bearingDeg: Double = 0.0): ByteArray {
        val t = fix.timestamp.toLong()
        fun i32(v: Double) = ByteBuffer.allocate(4).putInt(v.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()).array()
        val acc = ((fix.accuracy ?: 0.0).coerceAtLeast(0.0) * 100).roundToInt().coerceAtMost(0xFFFF)
        val location = listOf(
            i32(fix.lat * 1e6),
            i32(fix.lon * 1e6),
            i32((fix.altitude ?: 0.0) * 100),
            i32(speedMps.coerceAtLeast(0.0) * 100),
            i32(bearingDeg * 100),
            ByteBuffer.allocate(2).putShort(acc.toShort()).array(),
            t,
        )
        return MsgPack.pack(mapOf(SID_TIME to t, SID_LOCATION to location))
    }

    /** The location in a Telemeter blob, or null if there is none. */
    fun unpack(blob: ByteArray, source: String = "telemeter"): Fix? = runCatching {
        val map = MsgPack.unpack(blob) as? Map<*, *> ?: return null
        val loc = map.entries.firstOrNull { (it.key as? Number)?.toInt() == SID_LOCATION }?.value as? List<*> ?: return null
        if (loc.size < 7) return null
        fun i32(v: Any?) = ByteBuffer.wrap(v as ByteArray).int
        val acc = ByteBuffer.wrap(loc[5] as ByteArray).short.toInt() and 0xFFFF
        val t = (loc[6] as Number).toDouble()
        Fix(
            lat = i32(loc[0]) / 1e6,
            lon = i32(loc[1]) / 1e6,
            accuracy = acc / 100.0,
            altitude = i32(loc[2]) / 100.0,
            timestamp = if (t > 0) t else RealClock.nowEpoch(),
            source = source,
        )
    }.getOrNull()
}

/**
 * RetAlert's message format on LXMF. Since 0.4 the text other apps show is
 * readable ("🚨 CRITICAL ALERT" + message) and RetAlert's own data rides in
 * LXMF's app fields (FIELD_CUSTOM_TYPE "retalert" + FIELD_CUSTOM_DATA);
 * live location goes in FIELD_TELEMETRY with no text, which Columba and
 * Sideband put on their map instead of the chat. [legacy] produces the
 * pre-0.4 `!RETALERT!` text, which older RetAlert versions and the Python
 * app need. [toInternal] reads both, so receiving never depends on the setting.
 */
object Wire {
    const val APP = "retalert"
    private const val ALERT_ICON = "🚨"

    fun alert(severity: String, text: String, alertId: String, legacy: Boolean): LxmfOut =
        if (legacy) LxmfOut(encodeAlert(severity, text, alertId))
        else LxmfOut(
            "$ALERT_ICON ${severity.uppercase()} ALERT\n$text",
            data(mapOf("k" to "alert", "id" to alertId, "sev" to severity)),
        )

    fun ack(alertId: String, legacy: Boolean): LxmfOut =
        if (legacy) LxmfOut(encodeAck(alertId))
        else LxmfOut("✓ Alert received", data(mapOf("k" to "ack", "id" to alertId)))

    fun reply(alertId: String, text: String, legacy: Boolean): LxmfOut =
        if (legacy) LxmfOut(encodeReply(alertId, text))
        else LxmfOut(text, data(mapOf("k" to "reply", "id" to alertId)))

    /** A message in an alert's chat thread: plain text for other apps,
     *  tagged with the alert for RetAlert. Legacy: just the text. */
    fun chat(alertId: String, text: String, legacy: Boolean): LxmfOut =
        if (legacy) LxmfOut(text)
        else LxmfOut(text, data(mapOf("k" to "chat", "id" to alertId)))

    /** Live / one-shot location. [expiresAtMs] tells Columba when sharing ends. */
    fun geo(fix: Fix, legacy: Boolean, expiresAtMs: Long? = null): LxmfOut {
        if (legacy) return LxmfOut(encodeGeoBody(fix))
        val fields = mutableMapOf<Int, Any>(LxmfField.TELEMETRY to Telemeter.pack(fix))
        expiresAtMs?.let { fields[LxmfField.CUSTOM_META] = MsgPack.pack(mapOf("expires" to it)) }
        return LxmfOut("", fields)
    }

    private fun data(d: Map<String, String>): Map<Int, Any> =
        mapOf(LxmfField.CUSTOM_TYPE to APP, LxmfField.CUSTOM_DATA to MsgPack.pack(d))

    /**
     * Map an incoming LXMF message to the text form RetAlert's dispatcher
     * parses (`!RETALERT!…` markers, `geo:` bodies). Legacy messages pass
     * through unchanged; plain chat stays plain chat; a location from
     * Sideband / Columba becomes a `geo:` fix so it shows on RetAlert's map.
     */
    fun toInternal(content: String, fields: Map<Int, Any?>): String {
        if (fields[LxmfField.CUSTOM_TYPE]?.let(::str) == APP) {
            val d = (fields[LxmfField.CUSTOM_DATA] as? ByteArray)?.let { runCatching { MsgPack.unpack(it) }.getOrNull() } as? Map<*, *>
            val kind = d?.get("k")?.let(::str)
            val id = d?.get("id")?.let(::str).orEmpty()
            when (kind) {
                "alert" -> {
                    val sev = d?.get("sev")?.let(::str).orEmpty().ifEmpty { "critical" }
                    // Drop the "🚨 CRITICAL ALERT" header line other apps display.
                    val text = if (content.startsWith(ALERT_ICON) && '\n' in content) content.substringAfter('\n') else content
                    return encodeAlert(sev, text, id)
                }
                "ack" -> if (id.isNotEmpty()) return encodeAck(id)
                "reply" -> if (id.isNotEmpty()) return encodeReply(id, content)
                "chat" -> if (id.isNotEmpty()) return encodeChat(id, content)
            }
        }
        if (content.isBlank()) {
            (fields[LxmfField.TELEMETRY] as? ByteArray)?.let(Telemeter::unpack)?.let { return encodeGeoBody(it) }
        }
        return content
    }

    private fun str(v: Any?): String? = when (v) {
        is String -> v
        is ByteArray -> v.decodeToString()
        else -> null
    }
}
