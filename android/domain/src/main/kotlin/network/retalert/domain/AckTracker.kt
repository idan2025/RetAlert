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
    /** Observer for per-recipient state transitions (e.g. persist to the outbox).
     *  Invoked outside the tracker's lock. */
    @Volatile var onStateChange: ((alertId: String, recipient: String, state: String) -> Unit)? = null

    private fun notify(alertId: String, recipient: String, before: String?, after: String?) {
        if (after != null && before != after) runCatching { onStateChange?.invoke(alertId, recipient, after) }
    }

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

    /** Apply [change] to one recipient under the lock; notify the observer
     *  (outside the lock) with the before/after states captured atomically. */
    private fun transition(alertId: String, recipient: String, change: (RecipientState) -> Unit) {
        val (before, after) = synchronized(this) {
            val rs = get(alertId, recipient) ?: return
            val b = rs.state
            change(rs)
            b to rs.state
        }
        notify(alertId, recipient, before, after)
    }

    fun onDelivered(alertId: String, recipient: String) = transition(alertId, recipient) { rs ->
        if (rs.state != AckState.ACKED && rs.state != AckState.REPLIED) rs.state = AckState.DELIVERED
    }

    fun onFailed(alertId: String, recipient: String, error: String = "") = transition(alertId, recipient) { rs ->
        if (rs.state != AckState.ACKED && rs.state != AckState.REPLIED) {
            rs.state = AckState.FAILED
            rs.lastError = error
        }
    }

    fun onAck(alertId: String, recipient: String, reply: String = "") = transition(alertId, recipient) { rs ->
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