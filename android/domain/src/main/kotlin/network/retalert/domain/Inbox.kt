package network.retalert.domain

/** One received app-to-app alert, replyable/ackable by alert_id. Mirrors InboxEntry. */
data class InboxEntry(
    val alertId: String,
    val sourceHash: String,
    val severity: String,
    val text: String,
    val receivedAt: Double,
)

/** In-memory registry of received alerts. :data provides Room persistence.
 *  Parity with InboxRegistry (7-day prune). */
class InboxRegistry(
    private val maxAgeS: Double = DEFAULT_MAX_AGE_S,
    private val clock: Clock = RealClock,
) {
    private val entries = LinkedHashMap<String, InboxEntry>()

    /** Remember a received alert (v1 only — needs alertId). */
    @Throws(IllegalArgumentException::class)
    fun record(
        alertId: String,
        sourceHash: String,
        severity: String,
        text: String,
        receivedAt: Double? = null,
    ): InboxEntry {
        require(alertId.isNotBlank()) { "alert_id required" }
        val entry = InboxEntry(
            alertId = alertId,
            sourceHash = sourceHash.lowercase(),
            severity = severity,
            text = text,
            receivedAt = receivedAt ?: clock.nowEpoch(),
        )
        entries[alertId] = entry
        return entry
    }

    fun get(alertId: String): InboxEntry? = entries[alertId]

    fun list(): List<InboxEntry> = entries.values.sortedByDescending { it.receivedAt }

    fun remove(alertId: String): Boolean = entries.remove(alertId) != null

    fun clear(): Int { val n = entries.size; entries.clear(); return n }

    /** Drop entries older than maxAgeS. Returns count removed. */
    fun prune(now: Double? = null): Int {
        val t = now ?: clock.nowEpoch()
        val stale = entries.entries.filter { t - it.value.receivedAt > maxAgeS }.map { it.key }
        stale.forEach { entries.remove(it) }
        return stale.size
    }

    companion object { const val DEFAULT_MAX_AGE_S = 7 * 24 * 3600.0 }
}