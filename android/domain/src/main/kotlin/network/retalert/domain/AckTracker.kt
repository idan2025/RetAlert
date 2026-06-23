package network.retalert.domain

/** Per-recipient alert delivery/ack state. Mirrors retalert/core/ack_tracker.py. */
data class RecipientState(
    var state: String = AckState.SENT,
    var attempts: Int = 0,
    var lastAttempt: Double = 0.0,
    var lastError: String = "",
    var reply: String = "",
)

/** In-memory per-recipient state for outgoing alerts. Thread-safe. */
class AckTracker(private val clock: Clock = RealClock) {
    // alertId -> (recipientHex -> RecipientState)
    private val state: MutableMap<String, MutableMap<String, RecipientState>> = LinkedHashMap()

    @Synchronized
    fun track(alert: Alert) {
        val per = state.getOrPut(alert.alertId) { LinkedHashMap() }
        alert.recipients.forEach { r -> per.putIfAbsent(r, RecipientState()) }
    }

    @Synchronized
    fun forget(alertId: String) { state.remove(alertId) }

    private fun get(alertId: String, recipient: String): RecipientState? =
        state[alertId]?.get(recipient)

    @Synchronized
    fun onSent(alertId: String, recipient: String) {
        get(alertId, recipient)?.let { rs ->
            rs.state = AckState.SENT
            rs.lastAttempt = clock.nowEpoch()
            rs.attempts += 1
        }
    }

    @Synchronized
    fun onDelivered(alertId: String, recipient: String) {
        get(alertId, recipient)?.let { rs ->
            if (rs.state != AckState.ACKED && rs.state != AckState.REPLIED) rs.state = AckState.DELIVERED
        }
    }

    @Synchronized
    fun onFailed(alertId: String, recipient: String, error: String = "") {
        get(alertId, recipient)?.let { rs ->
            if (rs.state != AckState.ACKED && rs.state != AckState.REPLIED) {
                rs.state = AckState.FAILED
                rs.lastError = error
            }
        }
    }

    @Synchronized
    fun onAck(alertId: String, recipient: String, reply: String = "") {
        val rs = get(alertId, recipient) ?: return
        rs.state = if (reply.isNotEmpty()) AckState.REPLIED else AckState.ACKED
        if (reply.isNotEmpty()) rs.reply = reply
    }

    @Synchronized
    fun stateOf(alertId: String, recipient: String): String? = get(alertId, recipient)?.state

    @Synchronized
    fun unackedRecipients(alertId: String): List<String> =
        state[alertId]?.entries
            ?.filter { it.value.state in AckState.ACTIVE }
            ?.map { it.key }
            ?: emptyList()

    @Synchronized
    fun failedRecipients(alertId: String): List<String> =
        state[alertId]?.entries
            ?.filter { it.value.state == AckState.FAILED }
            ?.map { it.key }
            ?: emptyList()

    @Synchronized
    fun isDone(alertId: String): Boolean {
        val per = state[alertId] ?: return true
        return per.values.all { it.state in AckState.DONE }
    }

    @Synchronized
    fun summary(alertId: String): Map<String, String> =
        state[alertId]?.mapValues { it.value.state } ?: emptyMap()

    /** Internal: direct access for RetryQueue coordination (parity with Python _get). */
    @Synchronized
    fun internalGet(alertId: String, recipient: String): RecipientState? = get(alertId, recipient)
}