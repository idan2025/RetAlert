package network.retalert.domain

/** Wire codec + receive-side filtering/parsing. Mirrors retalert/core/incoming.py.
 *  Markers are byte-identical to the Python app so Sideband/Columba read them too. */

/** Marker distinguishing RetAlert app-to-app alerts from casual LXMF text. */
const val RETALERT_MARKER = "!RETALERT!"
/** v1 alert_id segment prefix (disambiguates from a v0 severity segment). */
private const val ALERT_ID_PREFIX = "id:"
/** Ack sub-marker. */
private const val ACK_SEG = "ack"
/** Reply sub-marker. */
private const val REPLY_SEG = "reply"

private val GEO_RE = Regex("""geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

/** Wrap an outgoing alert body with the RetAlert marker.
 *  v1 (with alertId): `!RETALERT!id:<alert_id>!<severity>!<text>`.
 *  v0 legacy (no id): `!RETALERT!<severity>!<text>`. */
fun encodeAlert(severity: String, text: String, alertId: String = ""): String =
    if (alertId.isNotEmpty()) "$RETALERT_MARKER$ALERT_ID_PREFIX$alertId!$severity!$text"
    else "$RETALERT_MARKER$severity!$text"

/** If [body] carries a RetAlert alert marker, return (alertId, severity, text)
 *  (alertId "" for v0 legacy); else null. Ack messages are NOT alerts. */
fun decodeAlert(body: String): Triple<String, String, String>? {
    if (!body.startsWith(RETALERT_MARKER)) return null
    val rest = body.substring(RETALERT_MARKER.length)
    val firstSep = rest.indexOf('!')
    if (firstSep < 0) return null
    val first = rest.substring(0, firstSep)
    val tail = rest.substring(firstSep + 1)
    if (first.isEmpty()) return null
    // v1: first segment is the alert_id.
    if (first.startsWith(ALERT_ID_PREFIX)) {
        val alertId = first.substring(ALERT_ID_PREFIX.length)
        val sevSep = tail.indexOf('!')
        if (sevSep < 0) return null
        val sev = tail.substring(0, sevSep)
        if (sev.isEmpty()) return null
        val text = tail.substring(sevSep + 1)
        return Triple(alertId, sev, text)
    }
    // v0 legacy: first segment is the severity.
    return Triple("", first, tail)
}

/** Wire an app-level ack back to the alert's sender. */
fun encodeAck(alertId: String): String = "$RETALERT_MARKER$ACK_SEG!$alertId"

/** If [body] is an ack, return its alertId; else null. */
fun decodeAck(body: String): String? {
    if (!body.startsWith(RETALERT_MARKER)) return null
    val rest = body.substring(RETALERT_MARKER.length)
    val sep = rest.indexOf('!')
    if (sep < 0) return null
    val seg = rest.substring(0, sep)
    val alertId = rest.substring(sep + 1)
    return if (seg != ACK_SEG || alertId.isEmpty()) null else alertId
}

/** Wire an app-level reply (ack + optional text) back to the sender. */
fun encodeReply(alertId: String, text: String = ""): String =
    "$RETALERT_MARKER$REPLY_SEG!$alertId!$text"

/** If [body] is a reply, return (alertId, text); else null. */
fun decodeReply(body: String): Pair<String, String>? {
    if (!body.startsWith(RETALERT_MARKER)) return null
    val rest = body.substring(RETALERT_MARKER.length)
    val sep = rest.indexOf('!')
    if (sep < 0) return null
    val seg = rest.substring(0, sep)
    if (seg != REPLY_SEG) return null
    val tail = rest.substring(sep + 1)
    val idSep = tail.indexOf('!')
    val alertId = if (idSep < 0) tail else tail.substring(0, idSep)
    if (alertId.isEmpty()) return null
    val text = if (idSep < 0) "" else tail.substring(idSep + 1)
    return alertId to text
}

private val SUFFIX_RE = { key: String -> Regex("""\b$key=([^\s]+)""") }

/** Parse a live-share message body into a Fix, or null if not geo.
 *  Daemon sends: `geo:lat,lon acc=.. alt=.. src=..`. */
fun parseGeoBody(body: String): Fix? {
    val m = GEO_RE.find(body) ?: return null
    val lat = m.groupValues[1].toDouble()
    val lon = m.groupValues[2].toDouble()
    fun suffix(key: String): String? = SUFFIX_RE(key).find(body)?.groupValues?.get(1)
    val acc = suffix("acc")
    val alt = suffix("alt")
    val src = suffix("src") ?: "lxmf"
    return Fix(
        lat = lat, lon = lon,
        accuracy = acc?.toDoubleOrNull(),
        altitude = alt?.toDoubleOrNull(),
        source = src,
    )
}

/** A received, filtered, parsed inbound message. Mirrors IncomingMessage. */
data class IncomingMessage(
    val sourceHash: String,
    val text: String,
    val timestamp: Double,
    val kind: String = "text",       // text | geo | alert | ack | reply
    val severity: String = "",       // set for kind == "alert"
    val alertId: String = "",        // set for "alert" (v1) and "ack"/"reply"
    val fix: Fix? = null,            // set for "geo" / alert with location
)

/** Minimal settings seam the dispatcher needs. */
interface ReceiveSettings {
    val receiveOnlyFromContacts: Boolean
    val allowlist: Set<String>
    val denylist: Set<String>
}

/** Settings as-is is a class; adapter to ReceiveSettings. */
class SettingsReceiveSettings(private val s: Settings) : ReceiveSettings {
    override val receiveOnlyFromContacts: Boolean get() = s.receiveOnlyFromContacts
    override val allowlist: Set<String> get() = s.allowlist
    override val denylist: Set<String> get() = s.denylist
}

/** Resolves a sender to a display name (announced LXMF dests). [DiscoveredPeer]
 *  is defined in Discover.kt (the full announce cache model). */
interface PeerDiscover {
    fun get(sourceHash: String): DiscoveredPeer?
}

/** Filter + parse inbound LXMF messages. Mirrors IncomingDispatcher.
 *  Precedence: deny > allow > receive-only-from-contacts toggle > open-accept. */
class IncomingDispatcher(
    private val settings: ReceiveSettings,
    private val contacts: Contacts,
    private val discover: PeerDiscover? = null,
    private val tracks: LiveTrackStore = LiveTrackStore(),
    private val onMessage: ((IncomingMessage) -> Unit)? = null,
    private val sendAckFn: ((alertId: String, sourceHex: String) -> Unit)? = null,
    private val ackCb: ((alertId: String, sourceHex: String) -> Unit)? = null,
    private val replyCb: ((alertId: String, sourceHex: String, reply: String) -> Unit)? = null,
) {
    /** Platform hook: called with the IncomingMessage for app-to-app alerts so the
     *  receiver can bypass silent/DND. :app wires the real notification. */
    var bypassSilentCb: ((IncomingMessage) -> Unit)? = null

    /** Filter + parse one inbound message. Returns the parsed message, or null if dropped. */
    fun handle(sourceHex: String, text: String, timestamp: Double): IncomingMessage? {
        val src = sourceHex.lowercase().trim()
        if (!isAllowed(src)) return null

        val msg = parse(src, text, timestamp)

        when (msg.kind) {
            "ack" -> ackCb?.let { runCatching { it(msg.alertId, src) } }
            "reply" -> replyCb?.let { runCatching { it(msg.alertId, src, msg.text) } }
            else -> {
                // Geo updates the live-track store regardless of alert/text kind.
                if (msg.fix != null) {
                    val name = discover?.get(src)?.displayName ?: ""
                    tracks.update(src, msg.fix, name)
                }
                if (msg.kind == "alert") {
                    fireBypassSilent(msg)
                    if (msg.alertId.isNotEmpty()) {
                        sendAckFn?.let { runCatching { it(msg.alertId, src) } }
                    }
                }
            }
        }

        onMessage?.let { runCatching { it(msg) } }
        return msg
    }

    private fun isAllowed(sourceHash: String): Boolean {
        val src = sourceHash.lowercase().trim()
        if (src in settings.denylist) return false
        if (src in settings.allowlist) return true
        if (settings.receiveOnlyFromContacts) return contacts.get(src) != null
        return true // open mode: accept any announced sender
    }

    private fun parse(src: String, text: String, timestamp: Double): IncomingMessage {
        // App-level ack? (check before alert — ack also carries the marker)
        decodeAck(text)?.let { ackId ->
            return IncomingMessage(src, text, timestamp, kind = "ack", alertId = ackId)
        }
        // App-level reply? (check before alert)
        decodeReply(text)?.let { (rid, rtext) ->
            return IncomingMessage(src, rtext, timestamp, kind = "reply", alertId = rid)
        }
        // App-to-app alert marker?
        decodeAlert(text)?.let { (alertId, severity, body) ->
            val fix = parseGeoBody(body)   // alerts may carry a location too
            return IncomingMessage(src, body, timestamp, kind = "alert",
                severity = severity, alertId = alertId, fix = fix)
        }
        // Live-share geo fix?
        parseGeoBody(text)?.let { fix ->
            return IncomingMessage(src, text, timestamp, kind = "geo", fix = fix)
        }
        // Plain casual message.
        return IncomingMessage(src, text, timestamp, kind = "text")
    }

    private fun fireBypassSilent(msg: IncomingMessage) {
        bypassSilentCb?.let { runCatching { it(msg) } }
    }
}