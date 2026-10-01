package network.retalert.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTest {
    @Test fun `chat message round-trips through the wire and the dispatcher`() {
        val out = Wire.chat("id42", "Where are you?", legacy = false)
        assertEquals("Where are you?", out.content)           // what Columba/Sideband show
        val internal = Wire.toInternal(out.content, out.fields)
        assertEquals("id42" to "Where are you?", decodeChat(internal))

        var got: Triple<String, String, String>? = null
        val d = IncomingDispatcher(
            settings = object : ReceiveSettings {
                override val receiveOnlyFromContacts = false
                override val allowlist = emptySet<String>()
                override val denylist = emptySet<String>()
            },
            contacts = Contacts(),
            chatCb = { id, src, text -> got = Triple(id, src, text) },
        )
        val msg = d.handle("aa".repeat(16), internal, 1.0)!!
        assertEquals("chat", msg.kind)
        assertEquals(Triple("id42", "aa".repeat(16), "Where are you?"), got)
        // Text with "!" inside survives.
        assertEquals("id1" to "Help! now!", decodeChat(encodeChat("id1", "Help! now!")))
        assertEquals("Hi", Wire.chat("x", "Hi", legacy = true).content)
        assertTrue(Wire.chat("x", "Hi", legacy = true).fields.isEmpty())
    }

    @Test fun `outgoing batches collapse into one bubble with delivery counts`() {
        val rows = listOf(
            ChatMessage(1, "a", "r1", true, "Coming", 10.0, ChatState.DELIVERED, batch = 7),
            ChatMessage(2, "a", "r2", true, "Coming", 10.0, ChatState.FAILED, batch = 7),
            ChatMessage(3, "a", "r3", true, "Coming", 10.0, ChatState.SENDING, batch = 7),
            ChatMessage(4, "a", "r1", false, "Ok", 12.0, ChatState.RECEIVED),
        )
        val b = chatBubbles(rows)
        assertEquals(2, b.size)
        assertEquals(ChatBubble(true, "r1", "Coming", 10.0, delivered = 1, failed = 1, total = 3), b[0])
        assertEquals("Ok", b[1].text)
    }
}
