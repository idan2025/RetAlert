package network.retalert.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Per-recipient delivery states (ordered severity). Mirrors retalert/core/alert.py. */
object AckState {
    const val SENT = "sent"          // handed to transport, no confirmation yet
    const val DELIVERED = "delivered" // transport confirmed delivery (LXMF DELIVERED)
    const val ACKED = "acked"         // app-level ack from recipient
    const val REPLIED = "replied"    // recipient sent a canned reply
    const val FAILED = "failed"      // transport gave up / no path

    val ACTIVE = setOf(SENT)
    val DONE = setOf(DELIVERED, ACKED, REPLIED, FAILED)

    val ALL = listOf(SENT, DELIVERED, ACKED, REPLIED, FAILED)
}

object Severity {
    const val HELP = "help"
    const val MEDICAL = "medical"
    const val DANGER = "danger"
    const val CRITICAL = "critical"
    val ALL = listOf(HELP, MEDICAL, DANGER, CRITICAL)
    fun normalize(s: String?): String =
        if (s != null && s in ALL) s else HELP
}

object FanOut {
    const val OFF = "off"
    const val CRITICAL = "critical"   // default
    const val ALL = "all"
    fun normalize(s: String?): String =
        if (s == OFF || s == ALL) s!! else CRITICAL
}

/** One outgoing emergency event, fanned out to one or more recipients.
 *  Mirrors `Alert` in retalert/core/alert.py. */
@Serializable
data class Alert(
    val alertId: String,
    val severity: String = Severity.HELP,
    val text: String = "",
    val recipients: List<String> = emptyList(),   // destination hash hex
    val createdAt: Double = 0.0,
    val payload: Map<String, Boolean> = emptyMap(),
    val retryInterval: Double = 3.0,
    val maxAttempts: Int = 0,                     // 0 = unlimited
    val fanOut: String = FanOut.CRITICAL,
) {
    companion object {
        /** Create with auto alert_id + createdAt (Python __post_init__ parity). */
        fun new(
            clock: Clock,
            severity: String = Severity.HELP,
            text: String = "",
            recipients: List<String> = emptyList(),
            payload: Map<String, Boolean> = emptyMap(),
            retryInterval: Double = 3.0,
            maxAttempts: Int = 0,
            fanOut: String = FanOut.CRITICAL,
        ): Alert = Alert(
            alertId = randomHex(16),
            severity = Severity.normalize(severity),
            text = text,
            recipients = recipients,
            createdAt = if (clock.nowEpoch() == 0.0) RealClock.nowEpoch() else clock.nowEpoch(),
            payload = payload,
            retryInterval = retryInterval,
            maxAttempts = maxAttempts,
            fanOut = FanOut.normalize(fanOut),
        )
    }

    fun asJsonObject(): JsonObject = buildJsonObject {
        put("alert_id", alertId)
        put("severity", severity)
        put("text", text)
        put("recipients", JsonPrimitive(recipients.joinToString(","))) // legacy shape handled in data layer
        put("created_at", createdAt)
        put("retry_interval", retryInterval)
        put("max_attempts", maxAttempts)
        put("fan_out", fanOut)
    }
}