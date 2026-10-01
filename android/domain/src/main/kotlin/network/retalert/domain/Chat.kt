package network.retalert.domain

/** One message in an alert's chat thread. Outgoing messages to several
 *  recipients are stored once per recipient and share a [batch]. */
data class ChatMessage(
    val id: Long = 0,
    val alertId: String,
    /** The other side: recipient (outgoing) or sender (incoming) hash. */
    val peer: String,
    val outgoing: Boolean,
    val text: String,
    val ts: Double,
    val state: String,
    val batch: Long = 0,
)

object ChatState {
    const val SENDING = "sending"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
    const val RECEIVED = "received"
}

/** Per-alert chat storage (kept in its own database by :data). */
interface ChatRepository {
    fun add(message: ChatMessage): Long
    fun setState(id: Long, state: String)
    fun thread(alertId: String): List<ChatMessage>
    fun deleteThread(alertId: String)
    /** Called with the alertId whenever a thread changes. Close to stop. */
    fun observe(listener: (alertId: String) -> Unit): AutoCloseable
}

/** One bubble in the chat UI: an outgoing batch collapsed into one entry. */
data class ChatBubble(
    val outgoing: Boolean,
    val peer: String,
    val text: String,
    val ts: Double,
    /** Incoming: [ChatState.RECEIVED]. Outgoing: delivered/total summary. */
    val delivered: Int = 0,
    val failed: Int = 0,
    val total: Int = 1,
)

/** Thread rows -> bubbles, oldest first; outgoing rows of one batch merge. */
fun chatBubbles(rows: List<ChatMessage>): List<ChatBubble> {
    val out = ArrayList<ChatBubble>()
    val seenBatch = HashSet<Long>()
    for (m in rows.sortedWith(compareBy({ it.ts }, { it.id }))) {
        if (!m.outgoing) {
            out += ChatBubble(false, m.peer, m.text, m.ts)
            continue
        }
        if (m.batch != 0L && !seenBatch.add(m.batch)) continue
        val group = if (m.batch == 0L) listOf(m) else rows.filter { it.outgoing && it.batch == m.batch }
        out += ChatBubble(
            outgoing = true, peer = m.peer, text = m.text, ts = m.ts,
            delivered = group.count { it.state == ChatState.DELIVERED },
            failed = group.count { it.state == ChatState.FAILED },
            total = group.size,
        )
    }
    return out
}
