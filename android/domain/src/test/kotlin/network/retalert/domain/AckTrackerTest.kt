package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AckTrackerTest {
    private val clock = FakeClock(epoch = 1000.0)
    private val ack = AckTracker(clock)

    private fun alert(vararg r: String) =
        Alert(alertId = "a1", recipients = r.toList(), createdAt = 1000.0)

    @Test fun `tracks all recipients as SENT`() {
        ack.track(alert("aa", "bb"))
        assertEquals("sent", ack.stateOf("a1", "aa"))
        assertEquals("sent", ack.stateOf("a1", "bb"))
        assertFalse(ack.isDone("a1"))
    }

    @Test fun `delivered then acked transitions`() {
        ack.track(alert("aa"))
        ack.onDelivered("a1", "aa")
        assertEquals("delivered", ack.stateOf("a1", "aa"))
        ack.onAck("a1", "aa")
        assertEquals("acked", ack.stateOf("a1", "aa"))
        assertTrue(ack.isDone("a1"))
    }

    @Test fun `ack after delivered does not regress to delivered`() {
        ack.track(alert("aa"))
        ack.onAck("a1", "aa")
        ack.onDelivered("a1", "aa")   // late delivery must not regress ACKED
        assertEquals("acked", ack.stateOf("a1", "aa"))
    }

    @Test fun `failed does not override acked`() {
        ack.track(alert("aa"))
        ack.onAck("a1", "aa")
        ack.onFailed("a1", "aa", "x")
        assertEquals("acked", ack.stateOf("a1", "aa"))
    }

    @Test fun `reply overrides ack and stores text`() {
        ack.track(alert("aa"))
        ack.onAck("a1", "aa", reply = "on my way")
        assertEquals("replied", ack.stateOf("a1", "aa"))
        assertEquals("on my way", ack.internalGet("a1", "aa")?.reply)
    }

    @Test fun `unacked and failed recipients`() {
        ack.track(alert("aa", "bb", "cc"))
        ack.onDelivered("a1", "bb")
        ack.onFailed("a1", "cc", "no path")
        assertEquals(listOf("aa"), ack.unackedRecipients("a1"))
        assertEquals(listOf("cc"), ack.failedRecipients("a1"))
    }

    @Test fun `summary lists per-recipient state`() {
        ack.track(alert("aa", "bb"))
        ack.onAck("a1", "bb")
        assertEquals(mapOf("aa" to "sent", "bb" to "acked"), ack.summary("a1"))
    }

    @Test fun `on_sent counts attempts and stamps time`() {
        ack.track(alert("aa"))
        clock.epoch = 2000.0
        ack.onSent("a1", "aa")
        assertEquals(1, ack.internalGet("a1", "aa")?.attempts)
        assertEquals(2000.0, ack.internalGet("a1", "aa")?.lastAttempt)
    }

    @Test fun `forget removes tracking`() {
        ack.track(alert("aa"))
        ack.forget("a1")
        assertEquals(null, ack.stateOf("a1", "aa"))
    }

    @Test fun `is_done true when no tracking`() {
        assertTrue(ack.isDone("unknown"))
    }
}