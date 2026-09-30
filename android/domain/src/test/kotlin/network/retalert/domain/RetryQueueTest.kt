package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryQueueTest {
    private val clock = FakeClock(epoch = 1000.0, mono = 0.0)
    private val ack = AckTracker(clock)
    private val sent = mutableListOf<Pair<Alert, String>>()
    private val queue = RetryQueue(ack, sendFn = { a, r -> sent.add(a to r) }, clock = clock)

    private fun alert(max: Int = 0, interval: Double = 3.0) =
        Alert(alertId = "a1", recipients = listOf("aa", "bb"),
            createdAt = 1000.0, retryInterval = interval, maxAttempts = max)

    @Test fun `enqueue sends to all recipients immediately`() {
        queue.enqueue(alert())
        assertEquals(2, sent.size)
        assertEquals(listOf("aa", "bb"), ack.unackedRecipients("a1").sorted())
    }

    @Test fun `flush skips when not yet due`() {
        queue.enqueue(alert(interval = 3.0))   // sends now, nextAttempt = 1000
        clock.epoch = 1000.0
        queue.flush()                            // sends again, sets nextAttempt = 1000 + 3 = 1003
        sent.clear()
        clock.epoch = 1001.0                     // 1001 < 1003 -> skip
        assertEquals(0, queue.flush())
    }

    @Test fun `flush resends unacked when due`() {
        queue.enqueue(alert(interval = 3.0))
        sent.clear()
        clock.epoch = 1004.0
        val n = queue.flush()
        assertEquals(2, n)
        assertEquals(2, sent.size)
    }

    @Test fun `flush drops done alerts`() {
        val a = alert()
        queue.enqueue(a)
        ack.onAck("a1", "aa"); ack.onAck("a1", "bb")
        assertEquals(0, queue.flush())
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun `max_attempts marks failed`() {
        val a = alert(max = 1)
        queue.enqueue(a)   // 1 attempt counted onSent for each
        clock.epoch = 1004.0
        queue.flush()       // attempts now 2 >= 1 -> failed
        assertEquals(listOf("aa", "bb").sorted(), ack.failedRecipients("a1").sorted())
    }

    @Test fun `retryable transport exception leaves SENT and retries`() {
        var calls = 0
        val q = RetryQueue(ack, sendFn = { _, _ ->
            calls++
            if (calls <= 2) throw RetryableTransportException("no path")
        }, clock = clock)
        q.enqueue(alert(interval = 3.0))
        // first send threw retryable; state stays SENT
        assertEquals("sent", ack.stateOf("a1", "aa"))
        clock.epoch = 1004.0
        q.flush()  // retries; still SENT (no path yet)
        assertEquals("sent", ack.stateOf("a1", "aa"))
    }

    @Test fun `in-flight recipients are skipped without counting an attempt`() {
        val busy = mutableSetOf("aa")
        val q = RetryQueue(ack, sendFn = { a, r -> sent.add(a to r) }, clock = clock,
            inFlight = { _, r -> r in busy })
        q.enqueue(alert(max = 1, interval = 3.0))
        assertEquals(listOf("bb"), sent.map { it.second })
        clock.epoch = 1004.0
        q.flush()
        // "aa" is still owned by the transport: no resend and not failed by maxAttempts.
        assertEquals("sent", ack.stateOf("a1", "aa"))
        assertEquals(0, ack.internalGet("a1", "aa")!!.attempts)
        busy.clear()
        sent.clear()
        clock.epoch = 1008.0
        q.flush()
        assertEquals(listOf("aa"), sent.map { it.second })
    }

    @Test fun `non-retryable exception marks failed`() {
        val q = RetryQueue(ack, sendFn = { _, _ -> throw IllegalStateException("boom") }, clock = clock)
        q.enqueue(alert())
        assertEquals("failed", ack.stateOf("a1", "aa"))
    }
}