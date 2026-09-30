package network.retalert.domain

/** In-memory queue of alerts awaiting ack/delivery. Mirrors RetryQueue core logic.
 *  Thread-safe: the engine's flusher and UI-triggered sends call in concurrently.
 *  Persistence moves to :data (Room); this is the pure, unit-testable core.
 *
 *  Fast retry: while an alert is unacked, re-send to unacked recipients every
 *  ``alert.retryInterval`` seconds (default 3s) — no long backoff mid-emergency.
 *  ``maxAttempts`` caps retries per recipient (0 = unlimited). Done alerts
 *  (all recipients acked/failed) are dropped. */
class RetryQueue(
    private val ack: AckTracker,
    private val sendFn: ((Alert, String) -> Unit)? = null,
    private val clock: Clock = RealClock,
    /** True while the transport still owns an earlier send to (alertId, recipient)
     *  — e.g. an LXMF message that is mid link-establishment. Such recipients are
     *  skipped (no attempt counted) so a slow link isn't flooded with duplicates. */
    private val inFlight: ((alertId: String, recipient: String) -> Boolean)? = null,
) {
    private data class Pending(val alert: Alert, var nextAttempt: Double)

    private val pending = LinkedHashMap<String, Pending>()

    /** Add an alert (tracked in AckTracker) and send immediately. */
    @Synchronized
    fun enqueue(alert: Alert) {
        ack.track(alert)
        pending[alert.alertId] = Pending(alert, clock.nowEpoch())
        sendToUnacked(alert)
    }

    @Synchronized
    fun remove(alertId: String) { pending.remove(alertId) }

    @Synchronized
    fun pending(): List<Alert> = pending.values.map { it.alert }

    /** Retry due alerts. Returns number of send attempts made. */
    @Synchronized
    fun flush(): Int {
        if (pending.isEmpty()) return 0
        val now = clock.nowEpoch()
        var attempts = 0
        val doneIds = ArrayList<String>()
        for ((alertId, p) in pending.toList()) {
            val alert = p.alert
            if (ack.isDone(alertId)) { doneIds.add(alertId); continue }
            if (now < p.nextAttempt) continue
            val unacked = ack.unackedRecipients(alertId)
            if (unacked.isEmpty()) { doneIds.add(alertId); continue }
            // Respect maxAttempts (per recipient, tracked in AckTracker).
            for (r in unacked) {
                if (inFlight?.invoke(alertId, r) == true) continue
                val rs = ack.internalGet(alertId, r)
                if (alert.maxAttempts != 0 && rs != null && rs.attempts >= alert.maxAttempts) {
                    ack.onFailed(alertId, r, "max attempts reached")
                    continue
                }
                sendOne(alert, r)
                attempts += 1
            }
            p.nextAttempt = now + alert.retryInterval
        }
        doneIds.forEach { pending.remove(it) }
        return attempts
    }

    private fun sendToUnacked(alert: Alert) {
        for (r in ack.unackedRecipients(alert.alertId)) {
            if (inFlight?.invoke(alert.alertId, r) == true) continue
            sendOne(alert, r)
        }
    }

    /** Hand one recipient to the transport. [RetryableTransportException] →
     *  "no path/announce yet", retryable: leave state SENT (attempt counted),
     *  next flush retries. Other exceptions mark failed. */
    private fun sendOne(alert: Alert, recipient: String) {
        ack.onSent(alert.alertId, recipient)
        val fn = sendFn ?: return
        try {
            fn(alert, recipient)
        } catch (_: RetryableTransportException) {
            return // retryable; state stays SENT
        } catch (e: Exception) {
            ack.onFailed(alert.alertId, recipient, e.message ?: e::class.simpleName ?: "error")
        }
    }
}

/** Thrown by the transport seam when no path/announce to a recipient exists yet
 *  — a retryable condition (parity with Python's LookupError in RetryQueue). */
class RetryableTransportException(message: String? = null) : Exception(message)